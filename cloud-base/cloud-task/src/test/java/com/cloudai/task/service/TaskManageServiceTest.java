package com.cloudai.task.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.task.dto.TaskPageQuery;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import com.cloudai.task.vo.TaskDetailVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理面单测（契约 §4-§6/§9）：取消两分支（排队直接终态/执行中协作标记）+ affected=0 → 6004、
 * 终态不可取消；重试仅失败/已取消（6005 分支）、不存在 6006。
 */
@ExtendWith(MockitoExtension.class)
class TaskManageServiceTest {

    @Mock
    private AsyncTaskMapper mapper;

    @InjectMocks
    private TaskManageService service;

    private AsyncTask task(int status) {
        AsyncTask task = new AsyncTask();
        task.setId(1L);
        task.setTaskType("demo-batch");
        task.setStatus(status);
        return task;
    }

    @Test
    void cancel_queuedTask_terminalCancelImmediately() {
        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.QUEUED.getCode()));
        when(mapper.cancelQueued(1L)).thenReturn(1);
        service.cancel(1L);
        verify(mapper).cancelQueued(1L);
        verify(mapper, never()).flagCancelRunning(1L);
    }

    @Test
    void cancel_queuedButLostRace_6004() {
        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.QUEUED.getCode()));
        when(mapper.cancelQueued(1L)).thenReturn(0); // 查询后到他方已认领
        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6004);
    }

    @Test
    void cancel_runningTask_flagsCooperativeCancel() {
        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.RUNNING.getCode()));
        when(mapper.flagCancelRunning(1L)).thenReturn(1);
        service.cancel(1L);
        verify(mapper).flagCancelRunning(1L);
        verify(mapper, never()).cancelQueued(1L);
    }

    @Test
    void cancel_runningButAlreadyEnded_6004() {
        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.RUNNING.getCode()));
        when(mapper.flagCancelRunning(1L)).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6004);
    }

    @Test
    void cancel_terminalStates_6004() {
        for (int status : new int[]{AsyncTask.StatusEnum.SUCCESS.getCode(),
                AsyncTask.StatusEnum.FAILED.getCode(), AsyncTask.StatusEnum.CANCELED.getCode()}) {
            when(mapper.findById(1L)).thenReturn(task(status));
            assertThatThrownBy(() -> service.cancel(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", 6004);
        }
        verify(mapper, never()).cancelQueued(1L);
        verify(mapper, never()).flagCancelRunning(1L);
    }

    @Test
    void retry_failedOrCanceled_ok() {
        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.FAILED.getCode()));
        when(mapper.retryTask(1L)).thenReturn(1);
        service.retry(1L);

        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.CANCELED.getCode()));
        when(mapper.retryTask(1L)).thenReturn(1);
        service.retry(1L);
        verify(mapper, org.mockito.Mockito.times(2)).retryTask(1L);
    }

    @Test
    void retry_successOrActive_6005() {
        for (int status : new int[]{AsyncTask.StatusEnum.QUEUED.getCode(),
                AsyncTask.StatusEnum.RUNNING.getCode(), AsyncTask.StatusEnum.SUCCESS.getCode()}) {
            when(mapper.findById(1L)).thenReturn(task(status));
            assertThatThrownBy(() -> service.retry(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", 6005);
        }
        verify(mapper, never()).retryTask(1L);
    }

    @Test
    void retry_lostRace_6005() {
        when(mapper.findById(1L)).thenReturn(task(AsyncTask.StatusEnum.FAILED.getCode()));
        when(mapper.retryTask(1L)).thenReturn(0);
        assertThatThrownBy(() -> service.retry(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6005);
    }

    @Test
    void missingTask_6006() {
        when(mapper.findById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6006);
        assertThatThrownBy(() -> service.cancel(99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6006);
        assertThatThrownBy(() -> service.retry(99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6006);
    }

    @Test
    void findById_returnsDetailVo() {
        AsyncTask task = task(AsyncTask.StatusEnum.SUCCESS.getCode());
        task.setParams("{\"total\": 5}");
        task.setResult("{\"processed\": 5}");
        task.setTimeoutSeconds(600);
        task.setCancelRequested(0);
        task.setExecutorId("host:9204");
        when(mapper.findById(1L)).thenReturn(task);
        TaskDetailVo vo = service.findById(1L);
        assertThat(vo.getTaskId()).isEqualTo(1L);
        assertThat(vo.getParams()).isEqualTo("{\"total\": 5}");
        assertThat(vo.getResult()).isEqualTo("{\"processed\": 5}");
        assertThat(vo.getCancelRequested()).isZero();
        assertThat(vo.getExecutorId()).isEqualTo("host:9204");
    }

    @Test
    void pageList_passthroughWithFilters() {
        TaskPageQuery query = new TaskPageQuery();
        query.setPageNum(1);
        query.setPageSize(10);
        query.setStatus(0);
        query.setTaskType("demo-batch");
        org.mockito.Mockito.when(mapper.pageList(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq("demo-batch"),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(0, 10));
        org.assertj.core.api.Assertions.assertThat(service.pageList(query).getTotal()).isZero();
    }
}
