package com.cloudai.common.rocketmq.tx;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本地事务注册中心
 * channel → TxLocalExecutor 路由表（自动装配收集全部 executor bean 构建）。
 * channel 重复视为装配错误（启动即败，防静默路由错乱）。
 */
public class TxExecutorRegistry {

    private final Map<String, TxLocalExecutor<?>> executors;

    public TxExecutorRegistry(List<TxLocalExecutor<?>> executorBeans) {
        this.executors = executorBeans.stream()
                .collect(Collectors.toMap(TxLocalExecutor::channel, Function.identity()));
    }

    /** 按通道取 executor；未注册返回 null（listener 记 error 后 ROLLBACK） */
    public TxLocalExecutor<?> find(String channel) {
        return executors.get(channel);
    }

    public int size() {
        return executors.size();
    }
}
