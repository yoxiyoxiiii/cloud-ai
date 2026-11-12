package com.cloudai.task.runtime;

import com.cloudai.task.alert.TaskAlertService;
import com.cloudai.task.config.TaskProperties;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 任务执行引擎（设计 §5.1/§5.2）：认领行派发执行池，包装 handler 调用做终态分类——
 * 正常返回=成功(2)/TaskCanceledException=取消(4)/异常=自动重试或失败(3+告警)；
 * 本地看门狗每轮检查运行中超时行（Future.cancel(true) 中断 + 判失败）。
 * 状态推进全部单语句条件 UPDATE（WHERE status=1）：看门狗与执行线程竞争时空更新无害。
 */
@Slf4j
@Component
public class TaskEngine {

    /** error_msg 列宽（VARCHAR(1000)），异常摘要截断 */
    private static final int ERROR_MSG_MAX_LEN = 1000;

    private final AsyncTaskMapper mapper;
    private final TaskHandlerRegistry registry;
    private final TaskAlertService alertService;
    private final TaskProperties properties;
    private final Clock clock;
    private final ThreadPoolExecutor executor;

    /** 本机运行表：taskId → Future（看门狗超时中断用；单实例口径，多实例演进见设计 §9）。
     *  包可见：同包单测直接断言运行表收拢行为。 */
    final Map<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public TaskEngine(AsyncTaskMapper mapper, TaskHandlerRegistry registry,
                      TaskAlertService alertService, TaskProperties properties, Clock clock) {
        this.mapper = mapper;
        this.registry = registry;
        this.alertService = alertService;
        this.properties = properties;
        this.clock = clock;
        int concurrency = properties.getExecutor().getConcurrency();
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(concurrency * 2), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 本机运行数（轮询容量=并发上限-运行数） */
    public int runningCount() {
        return runningTasks.size();
    }

    /** 派发认领行到执行池（认领侧容量计算为主限流，CallerRuns 拒绝仅兜底不丢任务） */
    public void dispatch(AsyncTask task) {
        Future<?> future = executor.submit(() -> run(task));
        runningTasks.put(task.getId(), future);
    }

    /** 单任务执行包装（异常分类终态推进）；包内可见供单测直调同步路径 */
    void run(AsyncTask task) {
        TaskContext ctx = new TaskContext(task.getId(), task.getParams(), mapper,
                properties.getProgressThrottleMs(), clock);
        try {
            TaskHandler handler = registry.get(task.getTaskType());
            String result = handler.execute(ctx);
            mapper.updateTerminal(task.getId(), AsyncTask.StatusEnum.SUCCESS.getCode(), null, result);
        } catch (TaskCanceledException e) {
            mapper.updateTerminal(task.getId(), AsyncTask.StatusEnum.CANCELED.getCode(), null, null);
            log.info("任务已取消: id={}, type={}", task.getId(), task.getTaskType());
        } catch (Exception e) {
            handleFailure(task, e);
        } finally {
            runningTasks.remove(task.getId());
        }
    }

    /** 执行异常处置：重试未耗尽重置回队尾（自动重试），耗尽则终态失败+首次告警 */
    private void handleFailure(AsyncTask task, Exception e) {
        log.error("任务执行异常: id={}, type={}, retry={}/{}",
                task.getId(), task.getTaskType(), task.getRetryCount(), task.getMaxRetries(), e);
        if (task.getRetryCount() < task.getMaxRetries()) {
            mapper.requeueFailed(task.getId());
            return;
        }
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int affected = mapper.updateTerminal(task.getId(), AsyncTask.StatusEnum.FAILED.getCode(),
                truncate(reason), null);
        if (affected > 0 && task.getRetryCount() <= task.getMaxRetries()) {
            // 首次终态失败判定：到达本分支时 retry_count>=max，<=max 即链耗尽（==max）；
            // 手动重试会把计数顶过 max，再失败不重发（同任务一次）
            alertService.alertTaskFailed(task, reason);
        }
    }

    /** 本地看门狗（轮询线程每轮顺带）：运行中行超时即中断线程+判失败；行已终态则收拢运行表 */
    public void checkTimeouts() {
        for (Map.Entry<Long, Future<?>> entry : runningTasks.entrySet()) {
            long taskId = entry.getKey();
            AsyncTask row = mapper.findById(taskId);
            if (row == null || row.getStatus() == null
                    || row.getStatus() != AsyncTask.StatusEnum.RUNNING.getCode()) {
                runningTasks.remove(taskId);
                continue;
            }
            if (isTimeout(row)) {
                Future<?> future = runningTasks.remove(taskId);
                if (future != null) {
                    future.cancel(true);
                }
                int affected = mapper.updateTerminal(taskId, AsyncTask.StatusEnum.FAILED.getCode(),
                        "执行超时", null);
                if (affected > 0) {
                    log.error("任务执行超时判死: id={}, type={}, timeout={}s",
                            taskId, row.getTaskType(), row.getTimeoutSeconds());
                    if (row.getRetryCount() != null && row.getMaxRetries() != null
                            && row.getRetryCount() <= row.getMaxRetries()) {
                        // 首次终态失败判定同耗尽分支：手动重试后（retry_count>max）超时不重发
                        alertService.alertTaskFailed(row, "执行超时");
                    }
                }
            }
        }
    }

    private boolean isTimeout(AsyncTask row) {
        if (row.getStartTime() == null || row.getTimeoutSeconds() == null) {
            return false;
        }
        LocalDateTime deadline = row.getStartTime().plus(Duration.ofSeconds(row.getTimeoutSeconds()));
        return LocalDateTime.now(clock).isAfter(deadline);
    }

    private String truncate(String msg) {
        return msg.length() <= ERROR_MSG_MAX_LEN ? msg : msg.substring(0, ERROR_MSG_MAX_LEN);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
