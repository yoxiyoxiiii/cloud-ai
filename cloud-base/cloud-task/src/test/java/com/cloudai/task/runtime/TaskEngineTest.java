package com.cloudai.task.runtime;

import com.cloudai.task.alert.TaskAlertService;
import com.cloudai.task.config.TaskProperties;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行引擎单测（设计 §5.1）：终态分类（成功/取消/异常重试或耗尽）、
 * 首次终态失败告警判定（手动重试顶过 max 不重发）、看门狗超时中断与运行表收拢。
 * 测试 handler 须带 @TaskHandler 注解（注册表启动校验的一致性要求），故具名声明。
 */
@ExtendWith(MockitoExtension.class)
class TaskEngineTest {

    private static final long POLL_TIMEOUT_MS = 5000;

    /** 正常返回 handler */
    @com.cloudai.task.runtime.annotation.TaskHandler("t-ok")
    static class OkHandler implements TaskHandler {
        @Override
        public String taskType() {
            return "t-ok";
        }

        @Override
        public String execute(TaskContext ctx) {
            return "{\"processed\": 5}";
        }
    }

    /** 检查点即取消 handler */
    @com.cloudai.task.runtime.annotation.TaskHandler("t-cancel")
    static class CancelHandler implements TaskHandler {
        @Override
        public String taskType() {
            return "t-cancel";
        }

        @Override
        public String execute(TaskContext ctx) {
            throw new TaskCanceledException("checkpoint");
        }
    }

    /** 恒抛异常 handler（重试/耗尽场景共用） */
    @com.cloudai.task.runtime.annotation.TaskHandler("t-fail")
    static class FailingHandler implements TaskHandler {
        @Override
        public String taskType() {
            return "t-fail";
        }

        @Override
        public String execute(TaskContext ctx) {
            throw new RuntimeException("boom");
        }
    }

    /** 阻塞至释放/中断的 handler（看门狗超时场景） */
    @com.cloudai.task.runtime.annotation.TaskHandler("t-block")
    static class BlockingHandler implements TaskHandler {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean(false);

        @Override
        public String taskType() {
            return "t-block";
        }

        @Override
        public String execute(TaskContext ctx) {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(true);
            }
            return "{}";
        }
    }

    @Mock
    private AsyncTaskMapper mapper;
    @Mock
    private TaskAlertService alertService;

    private TaskEngine engine;

    private TaskProperties properties() {
        TaskProperties props = new TaskProperties();
        props.getExecutor().setConcurrency(2);
        return props;
    }

    /** 注册表直建（绕过 Spring 容器；单 handler 场景） */
    private TaskHandlerRegistry registryOf(TaskHandler handler) {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handler));
        registry.afterSingletonsInstantiated();
        return registry;
    }

    private TaskEngine engineOf(TaskHandler handler) {
        return new TaskEngine(mapper, registryOf(handler), alertService, properties(), Clock.systemDefaultZone());
    }

    private AsyncTask task(long id, String type, int retryCount, int maxRetries) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setTaskType(type);
        task.setRetryCount(retryCount);
        task.setMaxRetries(maxRetries);
        task.setTimeoutSeconds(600);
        return task;
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    /** 简易轮询等待（无 Awaitility 依赖；超时返回末次判定值由调用方断言） */
    private static boolean awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Test
    void run_successWritesTerminalWithResult() {
        engine = engineOf(new OkHandler());
        engine.run(task(1L, "t-ok", 0, 3));
        verify(mapper).updateTerminal(1L, AsyncTask.StatusEnum.SUCCESS.getCode(), null, "{\"processed\": 5}");
        verify(alertService, never()).alertTaskFailed(any(), any());
    }

    @Test
    void run_canceledExceptionWritesCancelTerminal() {
        engine = engineOf(new CancelHandler());
        engine.run(task(2L, "t-cancel", 0, 3));
        verify(mapper).updateTerminal(2L, AsyncTask.StatusEnum.CANCELED.getCode(), null, null);
    }

    @Test
    void run_failureWithRetriesLeft_requeuesWithoutTerminal() {
        engine = engineOf(new FailingHandler());
        engine.run(task(3L, "t-fail", 1, 3));
        verify(mapper).requeueFailed(3L);
        verify(mapper, never()).updateTerminal(anyLong(), anyInt(), any(), any());
        verify(alertService, never()).alertTaskFailed(any(), any());
    }

    @Test
    void run_retriesExhausted_failsTerminalAndAlerts() {
        when(mapper.updateTerminal(eq(4L), eq(AsyncTask.StatusEnum.FAILED.getCode()), any(), any()))
                .thenReturn(1);
        engine = engineOf(new FailingHandler());
        engine.run(task(4L, "t-fail", 3, 3));
        verify(mapper).updateTerminal(4L, AsyncTask.StatusEnum.FAILED.getCode(), "boom", null);
        verify(alertService).alertTaskFailed(any(), eq("boom"));
    }

    /** 手动重试把 retry_count 顶过 max 后再失败：终态照写但告警不重发（每任务一次） */
    @Test
    void run_manualRetryOverMax_failsTerminalWithoutReAlert() {
        when(mapper.updateTerminal(eq(5L), eq(AsyncTask.StatusEnum.FAILED.getCode()), any(), any()))
                .thenReturn(1);
        engine = engineOf(new FailingHandler());
        engine.run(task(5L, "t-fail", 4, 3));
        verify(mapper).updateTerminal(5L, AsyncTask.StatusEnum.FAILED.getCode(), "boom", null);
        verify(alertService, never()).alertTaskFailed(any(), any());
    }

    @Test
    void checkTimeouts_timeoutCancelsFuture_failsTerminalAndAlerts() throws Exception {
        BlockingHandler blocking = new BlockingHandler();
        engine = engineOf(blocking);
        AsyncTask running = task(6L, "t-block", 3, 3);
        running.setStatus(AsyncTask.StatusEnum.RUNNING.getCode());
        running.setStartTime(LocalDateTime.now().minusSeconds(720)); // 600s 超时已过
        engine.dispatch(running);
        assertThat(awaitTrue(() -> engine.runningCount() == 1)).isTrue();

        when(mapper.findById(6L)).thenReturn(running);
        when(mapper.updateTerminal(eq(6L), eq(AsyncTask.StatusEnum.FAILED.getCode()), eq("执行超时"), any()))
                .thenReturn(1);
        engine.checkTimeouts();

        verify(mapper).updateTerminal(6L, AsyncTask.StatusEnum.FAILED.getCode(), "执行超时", null);
        verify(alertService).alertTaskFailed(any(), eq("执行超时"));
        assertThat(engine.runningTasks).doesNotContainKey(6L);
        // 看门狗 cancel(true) 中断了阻塞 handler
        assertThat(awaitTrue(blocking.interrupted::get)).isTrue();
        blocking.release.countDown();
    }

    /** 运行行未超时：不打终态、不动运行表 */
    @Test
    void checkTimeouts_notYetTimeout_leavesRunning() {
        engine = engineOf(new OkHandler());
        AsyncTask running = task(7L, "t-ok", 0, 3);
        running.setStatus(AsyncTask.StatusEnum.RUNNING.getCode());
        running.setStartTime(LocalDateTime.now()); // 刚开始，远未到 600s
        engine.runningTasks.put(7L, new FutureTask<>(() -> null));
        when(mapper.findById(7L)).thenReturn(running);
        engine.checkTimeouts();
        verify(mapper, never()).updateTerminal(anyLong(), anyInt(), any(), any());
        assertThat(engine.runningTasks).containsKey(7L);
    }

    /** 行已被他方置终态（如手动路径）：收拢运行表不再处理 */
    @Test
    void checkTimeouts_terminalRow_removedFromRunning() {
        engine = engineOf(new OkHandler());
        AsyncTask terminal = task(8L, "t-ok", 0, 3);
        terminal.setStatus(AsyncTask.StatusEnum.SUCCESS.getCode());
        engine.runningTasks.put(8L, new FutureTask<>(() -> null));
        when(mapper.findById(8L)).thenReturn(terminal);
        engine.checkTimeouts();
        verify(mapper, never()).updateTerminal(anyLong(), anyInt(), any(), any());
        assertThat(engine.runningTasks).doesNotContainKey(8L);
    }
}
