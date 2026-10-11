package com.cloudai.system.api.dataperm;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 可过滤列声明断言（设计 D32，2026-10-11 谓词轮；D17 fail-fast 口径）：本地资源 register 行
 * 与消费方启动期声明（如 bpmn DataPermColumnDeclarationRunner）共用——violation →
 * IllegalStateException 启动失败（启动期防线不得静默降级）。
 *
 * <p>断言两维：①field ∈ entity 真实实例字段（<b>遍历类层级含父类</b>——createTime 等审计字段
 * 在 BaseEntity，与 {@link DataPermColumns} 仅 getDeclaredFields 的差异记档：可配列断言锚 VO
 * 无继承前提，可过滤列断言锚 entity 恒有 BaseEntity 父类）；②字段 Java 类型与声明 type 匹配
 * （{@link FilterColumnTypeEnum#matches}）。不与 D17 复用：可过滤列天然非 String
 * （leave VO 的 leaveType 是 String / entity 是 Integer——锚 entity 才类型可断言，D32）。
 */
public final class FilterColumns {

    private FilterColumns() {
    }

    /**
     * 声明列断言（调用点不 try 包——断言失败即启动失败）：逐列校验类层级实例字段存在 +
     * 类型匹配，violation 抛 IllegalStateException（消息含 field/实体类名/原因）。
     */
    public static void assertDeclared(Class<?> entityClass, List<FilterColumn> columns) {
        for (FilterColumn column : columns) {
            Field field = findInstanceField(entityClass, column.field());
            if (field == null) {
                throw new IllegalStateException(String.format(
                        "数据权限可过滤列 %s 不是实体 %s 的字段（含父类层级，D32 断言）",
                        column.field(), entityClass.getSimpleName()));
            }
            if (!column.type().matches(field.getType())) {
                throw new IllegalStateException(String.format(
                        "数据权限可过滤列 %s 在实体 %s 中类型 %s 与声明 %s 不匹配（D32 断言）",
                        column.field(), entityClass.getSimpleName(),
                        field.getType().getSimpleName(), column.type()));
            }
        }
    }

    /** 按名取非 static 实例字段：自 entityClass 向上遍历类层级（至 Object 前），无则 null */
    private static Field findInstanceField(Class<?> entityClass, String field) {
        for (Class<?> current = entityClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field declared : current.getDeclaredFields()) {
                if (!Modifier.isStatic(declared.getModifiers()) && declared.getName().equals(field)) {
                    return declared;
                }
            }
        }
        return null;
    }
}
