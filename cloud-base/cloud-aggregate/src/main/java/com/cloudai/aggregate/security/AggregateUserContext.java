package com.cloudai.aggregate.security;

import java.util.Map;

/**
 * 聚合请求用户身份上下文（ThreadLocal）：主线程构建不可变五头 Map 后放入，
 * 段任务开头取用（并行线程不继承请求上下文，显式传递是唯一可控路径）、结束清理防线程池串号。
 * 值 = 头名 → 头值（头名引用 SecurityConstants 常量）；空值头在构建时已剔除。
 */
public final class AggregateUserContext {

    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    /** 存入当前线程上下文（Map.copyOf 固化不可变快照，段任务读取期间源 Map 不受扰动） */
    public static void set(Map<String, String> headers) {
        CONTEXT.set(Map.copyOf(headers));
    }

    /** 当前线程上下文；非聚合请求线程返回 null（拦截器据此跳过注入） */
    public static Map<String, String> get() {
        return CONTEXT.get();
    }

    /** 清理当前线程上下文（段任务 finally 与编排 finally 双兜底，防线程池复用串号） */
    public static void clear() {
        CONTEXT.remove();
    }

    private AggregateUserContext() {
    }
}
