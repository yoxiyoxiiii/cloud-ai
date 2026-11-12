package com.cloudai.task.job;

import com.cloudai.task.alert.TaskAlertService;
import com.cloudai.task.config.TaskProperties;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清扫任务单测（设计 §5.4）：僵尸判死（terminal 写+首次失败告警判定）、
 * 终态保留期物理清理、异常上抛（admin 红记录）。
 */
@ExtendWith(MockitoExtension.class)
class TaskSweepJobHandlerTest {

    @Mock
    private AsyncTaskMapper mapper;
    @Mock
    private TaskAlertService alertService;

    private TaskSweepJobHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 显式构造：TaskProperties 非 mock（缺省值 30 天保留期即测试口径）
        handler = new TaskSweepJobHandler(mapper, alertService, new TaskProperties());
    }

    private AsyncTask zombie(long id, int retryCount, int maxRetries) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setTaskType("demo-batch");
        task.setStatus(AsyncTask.StatusEnum.RUNNING.getCode());
        task.setRetryCount(retryCount);
        task.setMaxRetries(maxRetries);
        task.setStartTime(LocalDateTime.now().minusHours(2));
        task.setTimeoutSeconds(600);
        return task;
    }

    @Test
    void sweepZombies_failsTerminal_alertsFirstFailureOnly() throws Exception {
        AsyncTask firstFailure = zombie(1L, 3, 3);   // 重试耗尽（==max）→ 告警
        AsyncTask manualRetried = zombie(2L, 4, 3);  // 手动重试顶过 max → 不重发
        when(mapper.listZombieRunning(200)).thenReturn(List.of(firstFailure, manualRetried));
        when(mapper.updateTerminal(eq(1L), eq(AsyncTask.StatusEnum.FAILED.getCode()), any(), any()))
                .thenReturn(1);
        when(mapper.updateTerminal(eq(2L), eq(AsyncTask.StatusEnum.FAILED.getCode()), any(), any()))
                .thenReturn(1);

        handler.taskSweepJobHandler();

        verify(mapper).updateTerminal(1L, AsyncTask.StatusEnum.FAILED.getCode(), "执行超时（僵尸兜底）", null);
        verify(mapper).updateTerminal(2L, AsyncTask.StatusEnum.FAILED.getCode(), "执行超时（僵尸兜底）", null);
        verify(alertService).alertTaskFailed(firstFailure, "执行超时（僵尸兜底）");
        verify(alertService, never()).alertTaskFailed(eq(manualRetried), any());
    }

    /** 判死条件写空更新（他方已终态）：不误告警 */
    @Test
    void sweepZombies_lostRace_noAlert() throws Exception {
        AsyncTask raced = zombie(3L, 3, 3);
        when(mapper.listZombieRunning(200)).thenReturn(List.of(raced));
        when(mapper.updateTerminal(eq(3L), eq(AsyncTask.StatusEnum.FAILED.getCode()), any(), any()))
                .thenReturn(0);
        handler.taskSweepJobHandler();
        verify(alertService, never()).alertTaskFailed(any(), any());
    }

    @Test
    void sweepExpired_deletesTerminalBeforeCutoff() throws Exception {
        when(mapper.listZombieRunning(200)).thenReturn(List.of());
        when(mapper.deleteTerminalBefore(any(LocalDateTime.class), eq(200))).thenReturn(5);
        handler.taskSweepJobHandler();
        verify(mapper).deleteTerminalBefore(any(LocalDateTime.class), eq(200));
    }

    /** 单趟异常必须上抛——xxl-job admin 记红记录（无自动重试，下一分钟调度轮自然重试） */
    @Test
    void sweepFailure_rethrown() {
        when(mapper.listZombieRunning(200)).thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(handler::taskSweepJobHandler)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");
        verify(mapper, never()).deleteTerminalBefore(any(), anyInt());
    }
}
