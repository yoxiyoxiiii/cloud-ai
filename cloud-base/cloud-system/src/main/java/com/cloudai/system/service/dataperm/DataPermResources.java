package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.api.dataperm.DataPermColumns;
import com.cloudai.system.api.dataperm.FilterColumn;
import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import com.cloudai.system.api.dataperm.FilterColumns;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.SysUserVo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据权限资源注册表（设计 D11 / 重构轮 D15）：resource 取值与每资源列清单的唯一真源——
 * 配置与求值前均经本注册表校验（3034），防自由字符串配错永不命中。
 * Class 化注册（D15）：内部 {@code LinkedHashMap<String, ResourceDef>} + static 块每资源一行
 * {@code register(resource, VoClass, List.of(...可配列), EntityClass, List.of(...可过滤列))}；
 * 可配列受 D17 启动断言防护（声明列 ⊆ VO 真实实例字段且 String 类型），
 * 可过滤列受 D32 断言防护（FilterColumns.assertDeclared 锚 entity 走类层级 + 类型匹配，
 * createTime 在 BaseEntity 父类——与 Applier 仅 getDeclaredFields 的差异记档）。
 * 组件化轮（2026-10-10 D22）双层注册：本地资源 register（voClass/entityClass 可见，走双断言）；
 * 跨服务资源 registerRemote（纯字符串注册，不调类断言——消费方 VO/entity 类对 provider 不可见，
 * 「声明列 ⊆ 消费方字段」断言下沉消费方启动期（DataPermColumns/DataPermColumns.assertDeclared
 * 同构一行）；远程资源对 3034 校验链/配置台下拉/my-scope/DB 一致性检查全部自然工作）。
 * 谓词轮（2026-10-11 D32）双清单显式区分：columns（可配列——列级动作，VO 字段名 String，
 * 既有语义零变化）与 filterColumns（可过滤列——行级条件，entity 字段名 + 裸 DB 列名 + 类型 +
 * 中文名，条件配置 3035 校验与求值 enrichment 的数据源）。
 * 新资源接入 = static 块加一行 + 读路径接求值器与列应用工具（接入模板设计 §5.4 四步）。
 * 列字面量只允许出现在 register 声明行（唯一声明点 = 常量定义的等价物，声明点之外禁散落）。
 */
public final class DataPermResources {

    /** 请假资源（试点）：可配列 title（隐藏示例）/ reason（脱敏示例） */
    public static final String LEAVE = "leave";

    /** 审批单资源（组件化轮首个跨服务资源，消费方 cloud-bpmn）：可配列 title（标题快照） */
    public static final String BPMN_APPROVAL = "bpmn_approval";

    /** 用户资源（列级数据权限轮）：列级-only——可配列 mobile/email，行档位与谓词不参与求值 */
    public static final String USER = "user";

    /** 无效的资源或列标识（契约 §8 错误码 3034） */
    public static final int ERR_INVALID_RESOURCE = 3034;

    private static final Map<String, ResourceDef> REGISTRY = new LinkedHashMap<>();

    static {
        // 可过滤列 v2（操作符扩展轮 D42/D45）：+title（STRING，PREFIX 兑现后进清单）+ 字典列 dictKey
        register(LEAVE, SysLeaveVo.class, List.of("title", "reason"), SysLeave.class, List.of(
                new FilterColumn("id", "id", FilterColumnTypeEnum.NUMBER, "单号", null),
                new FilterColumn("leaveType", "leave_type", FilterColumnTypeEnum.NUMBER, "请假类型",
                        "system_leave_type"),
                new FilterColumn("startDate", "start_date", FilterColumnTypeEnum.DATE, "开始日期", null),
                new FilterColumn("endDate", "end_date", FilterColumnTypeEnum.DATE, "结束日期", null),
                new FilterColumn("title", "title", FilterColumnTypeEnum.STRING, "标题", null)));
        registerRemote(BPMN_APPROVAL, List.of("title"), List.of(
                new FilterColumn("id", "id", FilterColumnTypeEnum.NUMBER, "审批单号", null),
                new FilterColumn("status", "status", FilterColumnTypeEnum.NUMBER, "状态",
                        "bpmn_approval_status"),
                new FilterColumn("businessType", "business_type", FilterColumnTypeEnum.STRING, "业务类型", null),
                new FilterColumn("createTime", "create_time", FilterColumnTypeEnum.DATETIME, "发起时间", null),
                new FilterColumn("title", "title", FilterColumnTypeEnum.STRING, "标题", null)));
        // 列级-only 资源（列级数据权限轮 D60）：可过滤列恒空清单——行级不可配是注册态约束
        //（空清单使任何谓词 field 在既有 3035 校验链自然拒绝，零新校验码）
        registerColumnOnly(USER, SysUserVo.class, List.of("mobile", "email"));
    }

    /** 资源定义（D15 + 谓词轮 D32 + 列级轮 D60）：可配列 VO 类（D17 断言锚点，远程 null）+ 可过滤列清单（entity 锚）+ 列级-only 标记 */
    private record ResourceDef(Class<?> voClass, List<String> columns, List<FilterColumn> filterColumns,
            boolean columnOnly) {
    }

    /**
     * 注册一行（D15 + 谓词轮 D32，static 块专用）：前置校验（空白 resource / 空列清单 / 重复注册 →
     * IllegalStateException）+ 双断言——可配列 D17（VO 字段 + String），可过滤列 D32
     * （FilterColumns.assertDeclared 锚 entity 走类层级 + 类型匹配）——violation 静态初始化失败
     * 即服务启动失败（fail-fast）。
     */
    private static void register(String resource, Class<?> voClass, List<String> columns,
            Class<?> entityClass, List<FilterColumn> filterColumns) {
        validateRegistration(resource, columns);
        assertDeclaredColumns(resource, voClass, columns);
        FilterColumns.assertDeclared(entityClass, filterColumns);
        REGISTRY.put(resource, new ResourceDef(voClass, List.copyOf(columns), List.copyOf(filterColumns),
                false));
    }

    /**
     * 列级-only 资源注册一行（列级数据权限轮 D60，static 块专用）：共用 register 的前置校验与
     * D17 声明断言；可过滤列强制为空清单（无参数可传——行级不可配是注册态约束，谓词配置被
     * 既有 3035 校验链自然拒绝）。求值侧行段短路语义见 DataPermEvaluator 的 columnOnly 分支。
     */
    private static void registerColumnOnly(String resource, Class<?> voClass, List<String> columns) {
        validateRegistration(resource, columns);
        assertDeclaredColumns(resource, voClass, columns);
        REGISTRY.put(resource, new ResourceDef(voClass, List.copyOf(columns), List.of(), true));
    }

    /**
     * 跨服务资源注册一行（组件化 D22 + 谓词轮 D32，static 块专用）：纯字符串形态（无 voClass/
     * entityClass——消费方类对 provider 不可见），前置校验同 register（空白/空清单/重复 →
     * IllegalStateException）；不调类断言（D17/D32 仅对本地 register 生效），「声明 ⊆ 消费方字段」
     * 由消费方启动期 DataPermColumns/FilterColumns.assertDeclared 各一行声明兜底（双层漂移防护 D22）。
     */
    private static void registerRemote(String resource, List<String> columns,
            List<FilterColumn> filterColumns) {
        validateRegistration(resource, columns);
        REGISTRY.put(resource, new ResourceDef(null, List.copyOf(columns), List.copyOf(filterColumns),
                false));
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

    /** 资源的可过滤列清单（谓词轮 D32；未注册资源返回空列表——调用方先经 assertResource） */
    public static List<FilterColumn> getFilterColumns(String resource) {
        ResourceDef def = resource == null ? null : REGISTRY.get(resource);
        return def == null ? List.of() : def.filterColumns();
    }

    /** 资源是否列级-only（行档位与谓词不参与求值；未注册资源 false——调用方先经 assertResource） */
    public static boolean isColumnOnly(String resource) {
        ResourceDef def = resource == null ? null : REGISTRY.get(resource);
        return def != null && def.columnOnly();
    }

    /** 按 entity 字段名取可过滤列声明（求值 enrichment / 校验链定位用；不在清单返回 null） */
    public static FilterColumn findFilterColumn(String resource, String field) {
        return getFilterColumns(resource).stream()
                .filter(column -> column.field().equals(field))
                .findFirst()
                .orElse(null);
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
