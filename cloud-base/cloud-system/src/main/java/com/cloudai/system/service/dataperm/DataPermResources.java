package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.vo.SysLeaveVo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据权限资源注册表（设计 D11 / 重构轮 D15）：resource 取值与每资源可配列清单的唯一真源——
 * 配置与求值前均经本注册表校验（3034），防自由字符串配错永不命中。
 * Class 化注册（D15）：内部 {@code LinkedHashMap<String, ResourceDef>} + static 块每资源一行
 * {@code register(resource, VoClass, List.of(...可配列))}；每条声明列受 D17 启动断言防护
 * （声明列 ⊆ VO 真实实例字段且 String 类型，violation → IllegalStateException → 启动失败）。
 * 新资源接入 = static 块加一行 + 读路径接求值器与列应用工具（接入模板设计 §7.3 三步 / 本轮 §5）。
 * 列 key 语义 = VO 字段名（camelCase），列表与详情共用同一列规则（一致治理）；
 * 列字面量只允许出现在 register 声明行（唯一声明点 = 常量定义的等价物，声明点之外禁散落）。
 */
public final class DataPermResources {

    /** 请假资源（试点）：可配列 title（隐藏示例）/ reason（脱敏示例） */
    public static final String LEAVE = "leave";

    /** 无效的资源或列标识（契约 §8 错误码 3034） */
    public static final int ERR_INVALID_RESOURCE = 3034;

    private static final Map<String, ResourceDef> REGISTRY = new LinkedHashMap<>();

    static {
        register(LEAVE, SysLeaveVo.class, List.of("title", "reason"));
    }

    /** 资源定义（D15）：可配列清单的 VO 类——D17 声明断言锚点；列 key 语义 = VO 字段名（D11） */
    private record ResourceDef(Class<?> voClass, List<String> columns) {
    }

    /**
     * 注册一行（D15，static 块专用）：前置校验（空白 resource / 空列清单 / 重复注册 →
     * IllegalStateException）+ D17 声明断言——violation 静态初始化失败即服务启动失败（fail-fast）。
     */
    private static void register(String resource, Class<?> voClass, List<String> columns) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalStateException("数据权限资源标识不能为空白");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("数据权限资源可配列清单不能为空: " + resource);
        }
        if (REGISTRY.containsKey(resource)) {
            throw new IllegalStateException("数据权限资源重复注册: " + resource);
        }
        assertDeclaredColumns(resource, voClass, columns);
        REGISTRY.put(resource, new ResourceDef(voClass, List.copyOf(columns)));
    }

    /**
     * D17 声明断言（fail-fast 断言体，register 内调用）：逐声明列校验两条——
     * ①是 voClass 的真实实例字段（排除 static 修饰符，serialVersionUID 不干扰）；
     * ②字段类型为 String（masked 整值替换 *** 的语义前提，D11）。
     * violation 抛 IllegalStateException（消息含 resource/列名/VO 类名/原因）。
     * 包可见供单测直调红绿两态（不触 REGISTRY，无需 reset 钩子）。
     */
    static void assertDeclaredColumns(String resource, Class<?> voClass, List<String> columns) {
        for (String column : columns) {
            Field field = findInstanceField(voClass, column);
            if (field == null) {
                throw new IllegalStateException(String.format(
                        "数据权限资源 %s 声明列 %s 不是 VO %s 的字段（D17 声明断言）",
                        resource, column, voClass.getSimpleName()));
            }
            if (field.getType() != String.class) {
                throw new IllegalStateException(String.format(
                        "数据权限资源 %s 声明列 %s 在 VO %s 中非 String（可配列必须 String，D17）",
                        resource, column, voClass.getSimpleName()));
            }
        }
    }

    /** 按名取非 static 实例字段，无则 null */
    private static Field findInstanceField(Class<?> voClass, String column) {
        for (Field field : voClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getName().equals(column)) {
                return field;
            }
        }
        return null;
    }

    /** 资源是否已注册 */
    public static boolean isRegistered(String resource) {
        return resource != null && REGISTRY.containsKey(resource);
    }

    /** 全部已注册资源标识（资源注册表端点数据源，契约 §3.5；注册序确定顺序） */
    public static List<String> listRegisteredResources() {
        return List.copyOf(REGISTRY.keySet());
    }

    /** 资源的可配列清单（未注册资源返回空列表——调用方先经 assertResource） */
    public static List<String> getConfigurableColumns(String resource) {
        ResourceDef def = resource == null ? null : REGISTRY.get(resource);
        return def == null ? List.of() : def.columns();
    }

    /** 校验资源已注册，未注册抛 3034 */
    public static void assertResource(String resource) {
        if (!isRegistered(resource)) {
            throw new BusinessException(ERR_INVALID_RESOURCE, "无效的资源或列标识: " + resource);
        }
    }

    /** 校验列在资源的可配清单内，不在抛 3034（调用方先经 assertResource） */
    public static void assertColumn(String resource, String columnKey) {
        if (!getConfigurableColumns(resource).contains(columnKey)) {
            throw new BusinessException(ERR_INVALID_RESOURCE, "无效的资源或列标识: " + columnKey);
        }
    }
}
