package com.cloudai.system.api.dataperm;

/**
 * 数据权限求值结果与线程内传递（切面 set / 方法体 current 读取）：resource 供排查日志、
 * columnScope 供列上下文接线（切面负责 set 到 {@link DataPermColumnContext}）、rowFilter 为
 * 行集判定唯一权威（list SQL 与 detail 判定读它）。
 *
 * <p>生命周期归 {@link DataPermEvaluateAspect}（try/finally + 嵌套前值恢复）；与列上下文
 * （请求级，归 servlet filter 双层清理）刻意分离——本 holder 只在标注方法执行窗口存活。
 * currentRowFilter 未接线即抛 IllegalStateException：自调用绕过代理、漏标注、直调误用
 * 三种姿势全部显性暴露，不静默退化为无过滤。</p>
 * @author jy-root
 */
public record DataPermEvaluation(String resource, ColumnScope columnScope, RowFilter rowFilter) {

    private static final ThreadLocal<DataPermEvaluation> HOLDER = new ThreadLocal<>();

    /** 切面接线（嵌套标注调用由切面恢复前值，业务代码不调用） */
    static void set(DataPermEvaluation evaluation) {
        HOLDER.set(evaluation);
    }

    /** 当前求值结果（未经切面接线的直调路径返回 null——判空义务在读方，见 currentRowFilter） */
    public static DataPermEvaluation current() {
        return HOLDER.get();
    }

    /** 行集判定权威：未接线（自调用绕过代理/漏标注）fail-fast，不静默无过滤返回 */
    public static RowFilter currentRowFilter() {
        DataPermEvaluation evaluation = HOLDER.get();
        if (evaluation == null) {
            throw new IllegalStateException(
                    "当前线程无数据权限求值结果——@DataPermEvaluate 未标注或方法未经代理调用（自调用绕过切面）");
        }
        return evaluation.rowFilter();
    }

    /** 切面 finally 收尾（恢复嵌套前值也经本方法）；public 供测试清理线程残留——业务代码不调用 */
    public static void clear() {
        HOLDER.remove();
    }
}
