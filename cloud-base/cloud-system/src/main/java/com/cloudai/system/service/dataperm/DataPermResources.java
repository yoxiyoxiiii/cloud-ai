package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.api.dataperm.DataPermColumns;
import com.cloudai.system.vo.SysLeaveVo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据权限资源注册表（设计 D11 / 重构轮 D15）：resource 取值与每资源可配列清单的唯一真源——
 * 配置与求值前均经本注册表校验（3034），防自由字符串配错永不命中。
 * Class 化注册（D15）：内部 {@code LinkedHashMap<String, ResourceDef>} + static 块每资源一行
 * {@code register(resource, VoClass, List.of(...可配列))}；每条声明列受 D17 启动断言防护
 * （声明列 ⊆ VO 真实实例字段且 String 类型，violation → IllegalStateException → 启动失败）。
 * 组件化轮（2026-10-10 D22）双层注册：本地资源 register（voClass 可见，走 D17 类断言）；
 * 跨服务资源 registerRemote（纯字符串注册，voClass=null 不调类断言——消费方 VO 类对 provider
 * 不可见，「声明列 ⊆ VO 字段」断言下沉消费方 DataPermColumns.assertDeclared 第三层防线；
 * 远程资源对 3034 校验链/配置台下拉/my-scope/DB 一致性检查全部自然工作，消费的只是字符串清单）。
 * 新资源接入 = static 块加一行 + 读路径接求值器与列应用工具（接入模板设计 §5.4 四步）。
 * 列 key 语义 = VO 字段名（camelCase），列表与详情共用同一列规则（一致治理）；
 * 列字面量只允许出现在 register 声明行（唯一声明点 = 常量定义的等价物，声明点之外禁散落）。
 */
public final class DataPermResources {

    /** 请假资源（试点）：可配列 title（隐藏示例）/ reason（脱敏示例） */
    public static final String LEAVE = "leave";

    /** 审批单资源（组件化轮首个跨服务资源，消费方 cloud-bpmn）：可配列 title（标题快照） */
    public static final String BPMN_APPROVAL = "bpmn_approval";

    /** 无效的资源或列标识（契约 §8 错误码 3034） */
    public static final int ERR_INVALID_RESOURCE = 3034;

    private static final Map<String, ResourceDef> REGISTRY = new LinkedHashMap<>();

    static {
        register(LEAVE, SysLeaveVo.class, List.of("title", "reason"));
        registerRemote(BPMN_APPROVAL, List.of("title"));
    }

    /** 资源定义（D15）：可配列清单的 VO 类——D17 声明断言锚点；跨服务资源为 null（D22 记档）；列 key 语义 = VO 字段名（D11） */
    private record ResourceDef(Class<?> voClass, List<String> columns) {
    }

    /**
     * 注册一行（D15，static 块专用）：前置校验（空白 resource / 空列清单 / 重复注册 →
     * IllegalStateException）+ D17 声明断言——violation 静态初始化失败即服务启动失败（fail-fast）。
     */
    private static void register(String resource, Class<?> voClass, List<String> columns) {
        validateRegistration(resource, columns);
        assertDeclaredColumns(resource, voClass, columns);
        REGISTRY.put(resource, new ResourceDef(voClass, List.copyOf(columns)));
    }

    /**
     * 跨服务资源注册一行（组件化 D22，static 块专用）：纯字符串形态（无 voClass——消费方 VO 类
     * 对 provider 不可见），前置校验同 register（空白/空清单/重复 → IllegalStateException）；
     * 不调类断言（D17 仅对本地 register 生效），「声明列 ⊆ 消费方 VO 字段」由消费方启动期
     * DataPermColumns.assertDeclared 一行声明兜底（双层漂移防护记档设计 D22）。
     */
    private static void registerRemote(String resource, List<String> columns) {
        validateRegistration(resource, columns);
        REGISTRY.put(resource, new ResourceDef(null, List.copyOf(columns)));
    }

    /** 注册前置校验（register/registerRemote 共用）：空白 resource / 空列清单 / 重复注册 */
    private static void validateRegistration(String resource, List<String> columns) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalStateException("数据权限资源标识不能为空白");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("数据权限资源可配列清单不能为空: " + resource);
        }
        if (REGISTRY.containsKey(resource)) {
            throw new IllegalStateException("数据权限资源重复注册: " + resource);
        }
    }

    /**
     * D17 声明断言（本地 register 内调用）：委托 api 包 {@link DataPermColumns#assertDeclared}
     * 单一实现（组件化 D22——语义与重构轮逐字同源：真实实例字段 + String 类型，
     * violation → IllegalStateException）。resource 参数保留供调用方上下文与单测直调（包可见）。
     */
    static void assertDeclaredColumns(String resource, Class<?> voClass, List<String> columns) {
        DataPermColumns.assertDeclared(voClass, columns);
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
