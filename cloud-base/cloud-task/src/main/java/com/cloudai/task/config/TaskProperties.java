package com.cloudai.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务引擎节奏配置（设计 §5.2：DB 轮询秒级 pickup / 执行池 / 进度节流 / 保留期）。
 * 架构口径非密钥，仓库化默认值，Nacos 可覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloud.task")
public class TaskProperties {

    /** DB 轮询认领间隔（毫秒），默认 1s——秒级 pickup 主通道 */
    private long pollIntervalMs = 1000;

    /** 执行线程池（core=max） */
    private final Executor executor = new Executor();

    /** 进度写库节流（毫秒）：距上次写库不足该间隔且未达 100% 时跳过写库 */
    private long progressThrottleMs = 500;

    /** 终态行保留天数（清扫任务物理清理基准） */
    private int retentionDays = 30;

    /** 执行器池参数 */
    @Data
    public static class Executor {

        /** 并发上限（core=max=该值，有界队列=2 倍，拒绝策略 CallerRuns 兜底不丢任务） */
        private int concurrency = 8;
    }
}
