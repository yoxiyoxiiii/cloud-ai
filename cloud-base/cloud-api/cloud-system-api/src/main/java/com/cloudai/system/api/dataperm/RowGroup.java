package com.cloudai.system.api.dataperm;

import java.util.List;
import java.util.Set;

/**
 * 规则组终态（不可变，设计 D31，2026-10-11 谓词轮）：一条命中规则的行集表达——
 * 组语义 {@code (allAccounts || apply_user ∈ accounts) && predicates 全满足}。
 *
 * <p>allAccounts=true 时 accounts 恒空（ALL 档+条件组——owner 无关授权，「只能看 id=1~100」
 * 即此形态）；无条件规则 = 单组无谓词（退化为 {@code apply_user IN (...)}，与 v1 行集语义
 * 逐字等价，D40）；组内谓词恒 AND（行内 AND，D30）。
 */
public final class RowGroup {

    private final boolean allAccounts;
    private final Set<String> accounts;
    private final List<RowPredicate> predicates;

    private RowGroup(boolean allAccounts, Set<String> accounts, List<RowPredicate> predicates) {
        this.allAccounts = allAccounts;
        this.accounts = Set.copyOf(accounts);
        this.predicates = List.copyOf(predicates);
    }

    public static RowGroup of(boolean allAccounts, Set<String> accounts, List<RowPredicate> predicates) {
        return new RowGroup(allAccounts, accounts, predicates);
    }

    /** true=组不限归属账号（ALL+条件组；此时 accounts 空） */
    public boolean isAllAccounts() {
        return allAccounts;
    }

    public Set<String> getAccounts() {
        return accounts;
    }

    /** 组条件（组内 AND；无条件组空列表） */
    public List<RowPredicate> getPredicates() {
        return predicates;
    }
}
