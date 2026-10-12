package com.cloudai.system.api.dataperm;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据权限列统一应用 advice（设计 D51-D55）：R.data 树遍历——白名单 VO（@DataPermVo）命中即经
 * {@link DataPermColumnApplier} 应用列动作（Service 只声明 {@link DataPermColumnContext}，不再逐行 mutate）。
 *
 * <p><b>fail-closed 总纲（D53，与翻译降级总纲方向相反）</b>：树中白名单实例而上下文空（Service 漏接线）、
 * 或处理链任何 Throwable → 一律改写 body 为 {@code R.fail(3042)}（不返回数据）+ log.error 记
 * URI/方法/VO 类——「原样返回」对列处理就是明文泄露，<b>无任何吞异常原样返回分支</b>。
 * advice 内部唯一合法的「跳过」是 applier 三层防御的脏列 warn（D16 既有，防御脏数据不是上下文）。</p>
 *
 * <p>树遍历策略（D55，与翻译「只下钻标注类」的差异——列处理没有逃生门）：PageResult.rows/
 * Collection/数组展开；白名单实例消费后<b>不再下钻其字段</b>（列 VO 字段是叶子，其翻译字段归
 * TranslateAdvisor）；其余 POJO 经 com.cloudai 剪枝下钻（JDK/枚举/String 不下钻，Map 不支持记档）；
 * MAX_DEPTH=5 + IdentitySet 防环。本工程 VO 无继承（applier 同口径），下钻只走 getDeclaredFields。</p>
 */
@Slf4j
@RestControllerAdvice
public class DataPermColumnAdvice implements ResponseBodyAdvice<Object> {

    /** 树深度上限：R→detail 包装→列 VO 为 3 层，留裕量取 5（防失控遍历，沿翻译先例） */
    static final int MAX_DEPTH = 5;

    /** 列处理上下文缺失/处理异常（契约 §2，system 段 3042——R9 已占用 3038-3041） */
    static final int ERR_COLUMN_CONTEXT_MISSING = 3042;

    /** 面向用户的 3042 文案（服务端缺陷显性化；排查靠 log.error，契约冻结点） */
    static final String MSG_COLUMN_CONTEXT_MISSING = "数据权限列处理异常，响应已拒绝";

    /** com.cloudai 包前缀（项目私有剪枝约定，D55） */
    private static final String CLOUDAI_PREFIX = "com.cloudai.";

    private final Map<Method, Boolean> supportsCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> scanNeedCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> drillNeedCache = new ConcurrentHashMap<>();

    /**
     * supports（沿 TranslateAdvisor 泛型链解析形态）：收集返回类型泛型链上的类，
     * 任一「是白名单类或其 com.cloudai 字段闭包递归可达白名单类」才介入（结果按 Method 缓存）；
     * R&lt;Void&gt;/springdoc 等零成本跳过；解析异常 catch log.error return false（沿翻译——
     * supports 误拒只损失列应用介入机会，真漏接在树遍历前不可知，此分支不返回数据不违 fail-closed）。
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        try {
            Method method = returnType.getMethod();
            if (method == null) {
                return false;
            }
            return supportsCache.computeIfAbsent(method, m -> scanType(
                    returnType.getParameterType(), returnType.getGenericParameterType(), 0, new HashSet<>()));
        } catch (Exception e) {
            log.error("数据权限列 advice supports 解析失败", e);
            return false;
        }
    }

    /** fail-closed 总纲（D53）：缺失/任何 Throwable → log.error + R.fail(3042)，无原样返回分支 */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        try {
            Object data = body instanceof R<?> r ? r.getData() : body;
            Class<?> missing = applyTree(data, 1, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (missing == null) {
                return body;
            }
            log.error("数据权限列上下文缺失：白名单 VO 出现但 Service 未接线（fail-closed 拒绝响应）: "
                    + "uri={}, method={}, voClass={}", request.getURI(), returnType.getMethod(), missing.getName());
        } catch (Throwable t) {
            log.error("数据权限列应用异常（fail-closed 拒绝响应）: uri={}, method={}",
                    request.getURI(), returnType.getMethod(), t);
        }
        return R.fail(ERR_COLUMN_CONTEXT_MISSING, MSG_COLUMN_CONTEXT_MISSING);
    }

    // ---- 树遍历（D55） ----

    /**
     * 树遍历：容器展开 + 白名单消费 + com.cloudai 剪枝下钻。
     *
     * @return null=正常完成；非 null=发现上下文缺失的白名单类（短路停止遍历，供 3042 日志定位）
     */
    private Class<?> applyTree(Object node, int depth, Set<Object> visited) throws IllegalAccessException {
        if (node == null || depth > MAX_DEPTH || !visited.add(node)) {
            return null;
        }
        Class<?> nodeClass = node.getClass();
        if (nodeClass.isAnnotationPresent(DataPermVo.class)) {
            return consume(node);
        }
        if (node instanceof PageResult<?> page) {
            return applyTree(page.getRows(), depth + 1, visited);
        }
        if (node instanceof Collection<?> coll) {
            for (Object item : coll) {
                Class<?> missing = applyTree(item, depth + 1, visited);
                if (missing != null) {
                    return missing;
                }
            }
            return null;
        }
        if (nodeClass.isArray()) {
            for (int i = 0, len = Array.getLength(node); i < len; i++) {
                Class<?> missing = applyTree(Array.get(node, i), depth + 1, visited);
                if (missing != null) {
                    return missing;
                }
            }
            return null;
        }
        if (drillNeedCache.computeIfAbsent(nodeClass, this::needsDrill)) {
            return drillFields(node, depth, visited);
        }
        return null;
    }

    /** 白名单实例消费：上下文在 → applier 应用（三层防御复用）；空 → 返回该类（缺失信号） */
    private Class<?> consume(Object vo) {
        DataPermColumnContext.ColumnDecision decision = DataPermColumnContext.get();
        if (decision == null) {
            return vo.getClass();
        }
        DataPermColumnApplier.apply(vo, decision.columnScope());
        return null;
    }

    /** 逐下钻字段 get 后递归（字段已由 needsDrill 同口径筛选：com.cloudai POJO / 容器承载 com.cloudai） */
    private Class<?> drillFields(Object node, int depth, Set<Object> visited) throws IllegalAccessException {
        for (Field field : node.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !isDrillField(field)) {
                continue;
            }
            field.setAccessible(true);
            Class<?> missing = applyTree(field.get(node), depth + 1, visited);
            if (missing != null) {
                return missing;
            }
        }
        return null;
    }

    // ---- supports 泛型链 + 字段闭包可达性（缓存口径） ----

    /** 泛型链递归（沿 TranslateAdvisor.findAnnotated 形态）：类自身可达或任一泛型实参类型可达 */
    private boolean scanType(Class<?> raw, Type generic, int depth, Set<Class<?>> seen) {
        if (raw == null || depth > MAX_DEPTH + 1 || !seen.add(raw)) {
            return false;
        }
        if (scanNeedCache.computeIfAbsent(raw, this::scanNeed)) {
            return true;
        }
        if (generic instanceof ParameterizedType pt) {
            for (Type arg : pt.getActualTypeArguments()) {
                if (scanType(rawOf(arg), arg, depth + 1, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 类是白名单、或其 com.cloudai 字段闭包递归可达白名单（scanNeedCache 的计算体） */
    private boolean scanNeed(Class<?> cls) {
        return scanReach(cls, new HashSet<>());
    }

    private boolean scanReach(Class<?> cls, Set<Class<?>> visited) {
        if (cls == null || !visited.add(cls)) {
            return false;
        }
        if (cls.isArray()) {
            // 数组 Class 不携带组件注解也无声明字段——解包到组件类型再判（R<Vo[]> 形态）
            return scanReach(cls.getComponentType(), visited);
        }
        if (cls.isAnnotationPresent(DataPermVo.class)) {
            return true;
        }
        for (Field field : cls.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !isDrillField(field)) {
                continue;
            }
            for (Class<?> candidate : fieldCandidates(field)) {
                if (scanReach(candidate, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否值得下钻该类的字段：存在 com.cloudai POJO 直列字段或容器承载 com.cloudai 的字段（drillNeedCache 计算体） */
    private boolean needsDrill(Class<?> cls) {
        for (Field field : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && isDrillField(field)) {
                return true;
            }
        }
        return false;
    }

    /** 下钻字段筛选：com.cloudai POJO 字段 / 元素为 com.cloudai 的 Collection 字段 / 组件为 com.cloudai 的数组字段 */
    private boolean isDrillField(Field field) {
        return !fieldCandidates(field).isEmpty();
    }

    /** 字段的候选下钻类：com.cloudai POJO 直列；Collection 泛型实参与数组组件中的 com.cloudai POJO */
    private Set<Class<?>> fieldCandidates(Field field) {
        Class<?> type = field.getType();
        Set<Class<?>> out = Collections.emptySet();
        if (type.isArray()) {
            return isCloudaiPojo(type.getComponentType()) ? Set.of(type.getComponentType()) : out;
        }
        if (isCloudaiPojo(type)) {
            return Set.of(type);
        }
        if (Collection.class.isAssignableFrom(type) && field.getGenericType() instanceof ParameterizedType pt) {
            for (Type arg : pt.getActualTypeArguments()) {
                Class<?> argRaw = rawOf(arg);
                if (isCloudaiPojo(argRaw)) {
                    return Set.of(argRaw);
                }
            }
        }
        return out;
    }

    /** com.cloudai 剪枝口径（D55）：本工程 POJO；注解/枚举不算（JDK 语义类型无列载体） */
    private boolean isCloudaiPojo(Class<?> type) {
        return type != null && type.getName().startsWith(CLOUDAI_PREFIX) && !type.isAnnotation() && !type.isEnum();
    }

    private Class<?> rawOf(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }
}
