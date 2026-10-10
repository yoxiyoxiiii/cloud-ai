package com.cloudai.system.service.dataperm;

import com.cloudai.system.entity.SysDataPermColumn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 列级终态（设计 §5.1，不可变）：hidden 置 null / masked 整值替换 {@code ***}（单一策略 MVP，设计 D11）。
 * isEmpty=无任何列动作（读路径零处理，免遍历）。
 */
public final class ColumnScope {

    /** 脱敏替换值（单一策略：整值替换，多样化移交设计 §11.5） */
    public static final String MASK = "***";

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

    /** 应用脱敏：null 直返 null（隐藏/无动作列不调用本方法；语义与设计 §5.1 mask 一致） */
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
                .forEach(k -> parts.add(k + ":" + SysDataPermColumn.ActionEnum.HIDDEN.label()));
        maskedColumns.stream().sorted()
                .forEach(k -> parts.add(k + ":" + SysDataPermColumn.ActionEnum.MASKED.label()));
        return String.join(";", parts);
    }

    public Set<String> getHiddenColumns() {
        return hiddenColumns;
    }

    public Set<String> getMaskedColumns() {
        return maskedColumns;
    }
}
