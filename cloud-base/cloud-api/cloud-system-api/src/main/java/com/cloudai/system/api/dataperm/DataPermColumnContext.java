package com.cloudai.system.api.dataperm;

/**
 * 列处理上下文（设计 D52）：Service 求值后 set——语义是「本请求列处理已接线」而非数据本身：
 * 空 ColumnScope（无列动作）也必须 set；advice 判定「树中白名单实例存在而上下文空」= 漏接
 * → fail-closed 3042。生命周期归 {@link DataPermColumnContextFilter}（双层清理，D54）。
 */
public final class DataPermColumnContext {

    private DataPermColumnContext() {
    }

    /**
     * 决策快照：resource 供 3042 日志与未来多资源演进排查（D52）；
     * columnScope 为 null 时消费短路零处理（防御，applier 的 null 短路分支）。
     */
    public record ColumnDecision(String resource, ColumnScope columnScope) {
    }

    private static final ThreadLocal<ColumnDecision> HOLDER = new ThreadLocal<>();

    /** 接线声明：无条件调用（空 scope 也 set）；同请求重复 set 后值覆盖前值（单资源语义，D52） */
    public static void set(String resource, ColumnScope columnScope) {
        HOLDER.set(new ColumnDecision(resource, columnScope));
    }

    public static ColumnDecision get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
