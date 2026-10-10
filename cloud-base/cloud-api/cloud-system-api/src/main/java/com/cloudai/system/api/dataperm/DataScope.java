package com.cloudai.system.api.dataperm;

import java.util.Set;

/**
 * 行级范围终态（不可变；2026-10-10 组件化设计 D18/D19 自 cloud-system service/dataperm 搬家至 api 模块，
 * 实现零变更——消费方引 jar 即得）：all=true 过滤豁免；否则 accounts 白名单
 * （空集=看不到任何行，Service 短路不进 mapper——XML 永不收到空集合，规避 IN () 非法 SQL）。
 * 语义按账号列（apply_user）过滤是试点资源的既定事实（数据权限设计 D4 记档）。
 */
public final class DataScope {

    private final boolean all;
    private final Set<String> accounts;

    private DataScope(boolean all, Set<String> accounts) {
        this.all = all;
        this.accounts = Set.copyOf(accounts);
    }

    /** 全部档：过滤豁免 */
    public static DataScope all() {
        return new DataScope(true, Set.of());
    }

    /** 账号白名单档（空集合法=空范围） */
    public static DataScope of(Set<String> accounts) {
        return new DataScope(false, accounts);
    }

    public boolean isAll() {
        return all;
    }

    public Set<String> getAccounts() {
        return accounts;
    }

    /** 空范围：看不到任何行（Service 短路判据） */
    public boolean isEmptyScope() {
        return !all && accounts.isEmpty();
    }

    /** 详情行级判定：该行的归属账号是否可见 */
    public boolean allows(String ownerAccount) {
        return all || accounts.contains(ownerAccount);
    }
}
