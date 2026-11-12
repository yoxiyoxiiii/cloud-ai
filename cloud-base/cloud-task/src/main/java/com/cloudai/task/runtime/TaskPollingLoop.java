package com.cloudai.task.runtime;

import com.cloudai.task.config.TaskProperties;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DB 轮询认领循环（设计 §5.2）：SmartLifecycle 自管单线程调度（秒级 pickup 是服务内建运行机制，
 * 不属 xxl-job CRON 治理域——治理域周期任务走 xxl-job 任务 13，Spring 计划任务注解仓规禁用）。
 * 启动先做孤儿恢复（重启前仍执行中的行重置回排队），再按 pollIntervalMs 周期认领：
 * 容量=并发上限-本机运行数 → 取排队候选 → CAS 条件写认领 → 回查本批行派发执行池；每轮顺带看门狗。
 */
@Slf4j
@Component
public class TaskPollingLoop implements SmartLifecycle {

    private final AsyncTaskMapper mapper;
    private final TaskEngine engine;
    private final TaskProperties properties;
    private final String executorId;

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public TaskPollingLoop(AsyncTaskMapper mapper, TaskEngine engine, TaskProperties properties,
                           @Value("${server.port}") int serverPort) {
        this.mapper = mapper;
        this.engine = engine;
        this.properties = properties;
        this.executorId = resolveExecutorId(serverPort);
    }

    /** 认领执行器标识 host:port（多实例演进预留；标识用途非回调地址，hostname 即可） */
    private String resolveExecutorId(int serverPort) {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + serverPort;
        } catch (Exception e) {
            return "unknown-host:" + serverPort;
        }
    }

    @Override
    public void start() {
        int recovered = mapper.requeueRunning();
        if (recovered > 0) {
            log.info("启动孤儿恢复: {} 个执行中任务重置回排队（at-least-once 重跑）", recovered);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-polling");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::pollOnce,
                properties.getPollIntervalMs(), properties.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        running = true;
        log.info("任务轮询循环已启动: intervalMs={}, concurrency={}, executorId={}",
                properties.getPollIntervalMs(), properties.getExecutor().getConcurrency(), executorId);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 单趟认领（异常必须吞——scheduleWithFixedDelay 任务抛异常会终止后续调度） */
    void pollOnce() {
        try {
            engine.checkTimeouts();
            int capacity = properties.getExecutor().getConcurrency() - engine.runningCount();
            if (capacity <= 0) {
                return;
            }
            List<Long> candidates = mapper.listQueuedIds(capacity);
            if (candidates.isEmpty()) {
                return;
            }
            int claimed = mapper.claimTasks(candidates, executorId);
            if (claimed == 0) {
                return;
            }
            List<AsyncTask> tasks = mapper.listClaimed(candidates, executorId);
            tasks.forEach(engine::dispatch);
        } catch (Exception e) {
            log.error("任务轮询单趟异常（不影响后续调度）", e);
        }
    }
}
