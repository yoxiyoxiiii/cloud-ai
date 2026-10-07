package com.cloudai.common.translate.core;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.annotation.TranslateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 响应级统一翻译：扫描收集 → 批量查询 → 回填译文字段（设计 §3.2/§3.3，D1）。
 *
 * <p>设计规则 0（红线）：只写 labelField 配对字段，被翻译的原字段（status/createBy/updateBy 等）
 * 绝不写——原值是前端编辑回填/行内判断/颜色映射的业务依据。</p>
 *
 * <p>降级总纲：beforeBodyWrite 全程 try/catch Throwable → log.error → 原样返回 body——
 * 翻译绝不拖垮业务响应；单字段未命中保持 null（前端降级链，契约 §3）。</p>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class TranslateAdvisor implements ResponseBodyAdvice<Object> {

    /** 对象树下钻深度上限：R→PageResult→VO 为常规 3 层，更深嵌套剪枝（防失控遍历） */
    static final int MAX_DEPTH = 3;

    private final TransFieldScanner scanner;
    private final TranslationCacheService cacheService;
    private final Map<Method, Boolean> supportsCache = new ConcurrentHashMap<>();

    /** 泛型链解析最终 VO 类是否标 @TranslateVO（结果按 Method 缓存；R<Void>/springdoc 等零成本跳过） */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        try {
            Method method = returnType.getMethod();
            if (method == null) {
                return false;
            }
            return supportsCache.computeIfAbsent(method, m -> findTranslateClass(returnType) != null);
        } catch (Exception e) {
            log.error("translate supports resolve failed", e);
            return false;
        }
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        try {
            Object data = body instanceof R<?> r ? r.getData() : body;
            translate(data);
        } catch (Throwable t) {
            // 降级总纲：任何翻译异常都不影响业务响应
            log.error("translate failed, body returned as-is", t);
        }
        return body;
    }

    private void translate(Object data) throws IllegalAccessException {
        if (data == null) {
            return;
        }
        List<PendingItem> pendings = new ArrayList<>();
        collect(data, 1, Collections.newSetFromMap(new IdentityHashMap<>()), pendings);
        if (pendings.isEmpty()) {
            return;
        }
        fillLabels(pendings);
    }

    /** 遍历收集：容器展开（PageResult.rows/Collection/数组），仅下钻 @TranslateVO 实例；防环 + 深度上限 */
    private void collect(Object node, int depth, Set<Object> visited, List<PendingItem> out) throws IllegalAccessException {
        if (node == null || depth > MAX_DEPTH || !visited.add(node)) {
            return;
        }
        if (node instanceof PageResult<?> page) {
            collect(page.getRows(), depth + 1, visited, out);
            return;
        }
        if (node instanceof Collection<?> coll) {
            for (Object item : coll) {
                collect(item, depth + 1, visited, out);
            }
            return;
        }
        if (node.getClass().isArray()) {
            for (int i = 0, len = Array.getLength(node); i < len; i++) {
                collect(Array.get(node, i), depth + 1, visited, out);
            }
            return;
        }
        if (!node.getClass().isAnnotationPresent(TranslateVO.class)) {
            return;
        }
        for (TransFieldScanner.TransFieldMeta meta : scanner.findTransFields(node.getClass())) {
            Object value = meta.getSourceField().get(node);
            if (value == null) {
                continue;
            }
            // 手翻优先：labelField 已非 null 时跳过回填（逃生门，设计 §3.3）
            if (meta.getLabelField().get(node) != null) {
                continue;
            }
            out.add(new PendingItem(node, meta, String.valueOf(value)));
        }
    }

    /** 批量查询 + 回填：按字典键/用户分组合批一次查询，未命中的条目保持 null */
    private void fillLabels(List<PendingItem> pendings) throws IllegalAccessException {
        Map<String, Set<String>> dictValues = new HashMap<>();
        Set<String> accounts = new HashSet<>();
        for (PendingItem pending : pendings) {
            if (pending.meta().getKind() == TransFieldScanner.TransKind.DICT) {
                dictValues.computeIfAbsent(pending.meta().getDictKey(), k -> new HashSet<>()).add(pending.rawValue());
            } else {
                accounts.add(pending.rawValue());
            }
        }
        Map<String, Map<String, String>> dictLabels = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dictValues.entrySet()) {
            dictLabels.put(entry.getKey(), cacheService.findDictLabels(entry.getKey(), entry.getValue()));
        }
        Map<String, String> userNames = accounts.isEmpty()
                ? Map.of() : cacheService.findUserNames(accounts);
        for (PendingItem pending : pendings) {
            String label = pending.meta().getKind() == TransFieldScanner.TransKind.DICT
                    ? dictLabels.get(pending.meta().getDictKey()).get(pending.rawValue())
                    : userNames.get(pending.rawValue());
            if (label == null) {
                continue;
            }
            // 红线：只写 labelField，原字段绝不写
            pending.meta().getLabelField().set(pending.instance(), label);
        }
    }

    /** 泛型链递归找 @TranslateVO 类（R&lt;PageResult&lt;XxxVo&gt;&gt; 三层内） */
    private Class<?> findTranslateClass(MethodParameter param) {
        return findAnnotated(param.getParameterType(), param.getGenericParameterType(), 0);
    }

    private Class<?> findAnnotated(Class<?> raw, Type generic, int depth) {
        if (raw == null || depth > MAX_DEPTH + 1) {
            return null;
        }
        if (raw.isAnnotationPresent(TranslateVO.class)) {
            return raw;
        }
        if (generic instanceof ParameterizedType pt) {
            for (Type arg : pt.getActualTypeArguments()) {
                Class<?> hit = findAnnotated(rawOf(arg), arg, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
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

    /** 待翻译条目：VO 实例 + 字段元数据 + 原字段字符串化值 */
    private record PendingItem(Object instance, TransFieldScanner.TransFieldMeta meta, String rawValue) {
    }
}
