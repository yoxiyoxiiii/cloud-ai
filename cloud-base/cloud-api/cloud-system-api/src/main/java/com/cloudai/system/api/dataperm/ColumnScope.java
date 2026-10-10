package com.cloudai.system.api.dataperm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 列级终态（不可变；2026-10-10 组件化 D18 搬家 + D28 label 解耦——原 getSummary 引 system 实体
 * SysDataPermColumn.ActionEnum.label()，api 模块不可见实体，标签内联为私有常量，输出格式逐字不变）：
 * hidden 置 null / masked 整值替换 {@code ***}（单一策略 MVP，设计 D11）。
 * isEmpty=无任何列动作（读路径零处理，免遍历）。
 */
public final class ColumnScope {

    /** 脱敏替换值（单一策略：整值替换，多样化移交设计 §11.5） */
    public static final String MASK = "***";

    /**
     * D28 动作中文名内联常量（搬家伴生解耦）：输出格式与原实体枚举 label() 逐字一致。
     * 标签字面量三处冻结点=契约 §6.8 columnSummary 格式（任一处变更为契约变更）：
     * system 实体 ActionEnum.label / 本常量 / 前端 ACTION 常量文案。
     */
    private static final String LABEL_HIDDEN = "隐藏";
    private static final String LABEL_MASKED = "脱敏";

    private final Set<String> hiddenColumns;
    private final Set<String> maskedColumns;

    /** 工厂（不可变防御拷贝；私有构造——求值器/测试统一经 of 构造） */
    public static ColumnScope of(Set<String> hiddenColumns, Set<String> maskedColumns) {
        return new ColumnScope(hiddenColumns, maskedColumns);
    }

    private ColumnScope(Set<String> hiddenColumns, Set<String> maskedColumns) {
        this.hiddenColumns = Set.copyOf(hiddenColumns);
        this.maskedColumns = Set.copyOf(maskedColumns);
    }

    public boolean isHidden(String columnKey) {
        return hiddenColumns.contains(columnKey);
    }

    public boolean isMasked(String columnKey) {
        return maskedColumns.contains(columnKey);
    }

    /** 应用脱敏：null 直返 null（隐藏/无动作列不调用本方法） */
    public String mask(String value) {
        return value == null ? null : MASK;
    }

    /** 无任何列动作 */
    public boolean isEmpty() {
        return hiddenColumns.isEmpty() && maskedColumns.isEmpty();
    }

    /**
     * 列结论摘要（留痕/解释/自查共用口径）：如 {@code reason:脱敏;title:隐藏}——
     * hidden 在前 masked 在后、各自字典序，输出稳定；求值器与 my-scope 复用同一格式。
     */
    public String getSummary() {
        List<String> parts = new ArrayList<>();
        hiddenColumns.stream().sorted()
                .forEach(k -> parts.add(k + ":" + LABEL_HIDDEN));
        maskedColumns.stream().sorted()
                .forEach(k -> parts.add(k + ":" + LABEL_MASKED));
        return String.join(";", parts);
    }

    public Set<String> getHiddenColumns() {
        return hiddenColumns;
    }

    public Set<String> getMaskedColumns() {
        return maskedColumns;
    }
}
