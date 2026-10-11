package com.cloudai.system.api.dataperm;

import java.util.List;
import java.util.function.Function;

/**
 * 行级过滤终态（不可变，设计 D31，2026-10-11 谓词轮）：{rowAll, groups}——
 * rowAll=true 过滤豁免（groups 空，与 v1 ALL 短路一致）；否则有效行集 = 组间 OR 并集
 * （跨规则并集，D3「加规则永不收紧」不变式的求值侧形态）。
 *
 * <p>组合不变式（求值器保证，D31）：①rowAll=true ⇔ 存在 ALL 无条件规则；②无条件规则的
 * 账号集合并成单组；③死组（!allAccounts && accounts 空）已被求值器丢弃——本类不二次防御
 * （收到即恒假组，无害）。{@link #isEmptyScope()} = groups 空（Service 短路不进 mapper，
 * 与 v1 口径一致——XML 永不收到空集合）。
 */
public final class RowFilter {

    private final boolean rowAll;
    private final List<RowGroup> groups;

    private RowFilter(boolean rowAll, List<RowGroup> groups) {
        this.rowAll = rowAll;
        this.groups = List.copyOf(groups);
    }

    /** 过滤豁免（ALL 无条件规则命中；groups 空） */
    public static RowFilter rowAll() {
        return new RowFilter(true, List.of());
    }

    /** 分组并集形态（非豁免；groups 空合法=空范围） */
    public static RowFilter of(List<RowGroup> groups) {
        return new RowFilter(false, groups);
    }

    public boolean isRowAll() {
        return rowAll;
    }

    public List<RowGroup> getGroups() {
        return groups;
    }

    /** 空范围：看不到任何行（Service 短路判据，不进 mapper） */
    public boolean isEmptyScope() {
        return !rowAll && groups.isEmpty();
    }

    /**
     * detail 行级判定（D34，替代 DataScope.allows 的判定面升级）：rowAll 短路直 true；
     * 否则任一组匹配即可见——组匹配 = (allAccounts || owner ∈ accounts) && 组谓词全过。
     * 取值器返回 null（未知字段/反射失败）→ 谓词不满足 → 组失败 → 拒绝（fail-closed 方向：
     * 配置漂移时宁可拒绝不放大授权）。
     */
    public boolean allowsDetail(String ownerAccount, Function<String, Object> columnValues) {
        if (rowAll) {
            return true;
        }
        for (RowGroup group : groups) {
            if (groupMatches(group, ownerAccount, columnValues)) {
                return true;
            }
        }
        return false;
    }

    private boolean groupMatches(RowGroup group, String ownerAccount,
            Function<String, Object> columnValues) {
        boolean accountDim = group.isAllAccounts() || group.getAccounts().contains(ownerAccount);
        if (!accountDim) {
            return false;
        }
        for (RowPredicate predicate : group.getPredicates()) {
            if (!predicate.test(columnValues.apply(predicate.getField()))) {
                return false;
            }
        }
        return true;
    }
}
