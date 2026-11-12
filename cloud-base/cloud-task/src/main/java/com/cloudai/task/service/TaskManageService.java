package com.cloudai.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.task.convert.TaskConvert;
import com.cloudai.task.dto.TaskPageQuery;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import com.cloudai.task.vo.TaskDetailVo;
import com.cloudai.task.vo.TaskVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务管理读/操作面（契约 §4）：分页、详情、取消、重试。
 * 状态推进全部单语句条件写（CAS 语义）——affected=0 即状态已变迁，转对应业务码；零 @Transactional。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskManageService {

    /** 错误码 6xxx task 段（契约 §9） */
    private static final int ERR_TASK_NOT_FOUND = 6006;
    private static final int ERR_CANCEL_NOT_ALLOWED = 6004;
    private static final int ERR_RETRY_NOT_ALLOWED = 6005;

    private final AsyncTaskMapper mapper;

    /** 分页查询（全部任务，含终态——流水不清理前的全生命周期可见） */
    public PageResult<TaskVo> pageList(TaskPageQuery query) {
        IPage<AsyncTask> page = mapper.pageList(new Page<>(query.getPageNum(), query.getPageSize()),
                query.getStatus(), query.getTaskType(), query.getSubmitBy());
        List<TaskVo> rows = page.getRecords().stream().map(TaskConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    /** 详情（含 params/result 执行明细） */
    public TaskDetailVo findById(Long id) {
        AsyncTask task = requireTask(id);
        return TaskConvert.toDetailVo(task);
    }

    /**
     * 取消（契约 §4.3 两分支）：
     * 排队中 → 单语句置终态 4；执行中 → 置协作取消标记，由 handler 检查点自发停止；
     * 已终态不可取消。affected=0 统一 6004。
     */
    public void cancel(Long id) {
        AsyncTask task = requireTask(id);
        if (isStatus(task, AsyncTask.StatusEnum.QUEUED)) {
            int affected = mapper.cancelQueued(id);
            if (affected == 0) {
                throw new BusinessException(ERR_CANCEL_NOT_ALLOWED, "任务不在排队状态，无法取消");
            }
            return;
        }
        if (isStatus(task, AsyncTask.StatusEnum.RUNNING)) {
            int affected = mapper.flagCancelRunning(id);
            if (affected == 0) {
                throw new BusinessException(ERR_CANCEL_NOT_ALLOWED, "任务已结束，无法取消");
            }
            return;
        }
        throw new BusinessException(ERR_CANCEL_NOT_ALLOWED, "任务已终态，无法取消");
    }

    /**
     * 手动重试：仅终态失败/已取消可重试，retry_count+1 回排队。
     * affected=0（状态已被并发改变）统一 6005。
     */
    public void retry(Long id) {
        AsyncTask task = requireTask(id);
        if (!isStatus(task, AsyncTask.StatusEnum.FAILED) && !isStatus(task, AsyncTask.StatusEnum.CANCELED)) {
            throw new BusinessException(ERR_RETRY_NOT_ALLOWED, "仅失败/已取消任务可重试");
        }
        int affected = mapper.retryTask(id);
        if (affected == 0) {
            throw new BusinessException(ERR_RETRY_NOT_ALLOWED, "任务状态已变化，重试失败");
        }
    }

    /** 状态等值判断（枚举 code 为原始 int，字段为 Integer——统一拆箱比较，null 视为不等） */
    private boolean isStatus(AsyncTask task, AsyncTask.StatusEnum expect) {
        return task.getStatus() != null && task.getStatus() == expect.getCode();
    }

    /** 存在性前置：任意状态的任务都可能被查询/操作，仅不存在时 6006 */
    private AsyncTask requireTask(Long id) {
        AsyncTask task = mapper.findById(id);
        if (task == null) {
            throw new BusinessException(ERR_TASK_NOT_FOUND, "任务不存在");
        }
        return task;
    }
}
