package com.cloudai.task.convert;

import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.vo.TaskDetailVo;
import com.cloudai.task.vo.TaskVo;

/** 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具） */
public final class TaskConvert {

    private TaskConvert() {
    }

    public static TaskVo toVo(AsyncTask task) {
        TaskVo vo = new TaskVo();
        vo.setTaskId(task.getId());
        vo.setTaskType(task.getTaskType());
        vo.setBizKey(task.getBizKey());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setPhase(task.getPhase());
        vo.setRetryCount(task.getRetryCount());
        vo.setMaxRetries(task.getMaxRetries());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setSubmitBy(task.getSubmitBy());
        vo.setSubmitSource(task.getSubmitSource());
        vo.setCreateTime(task.getCreateTime());
        vo.setStartTime(task.getStartTime());
        vo.setFinishTime(task.getFinishTime());
        return vo;
    }

    public static TaskDetailVo toDetailVo(AsyncTask task) {
        TaskDetailVo vo = new TaskDetailVo();
        vo.setTaskId(task.getId());
        vo.setTaskType(task.getTaskType());
        vo.setBizKey(task.getBizKey());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setPhase(task.getPhase());
        vo.setRetryCount(task.getRetryCount());
        vo.setMaxRetries(task.getMaxRetries());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setSubmitBy(task.getSubmitBy());
        vo.setSubmitSource(task.getSubmitSource());
        vo.setCreateTime(task.getCreateTime());
        vo.setStartTime(task.getStartTime());
        vo.setFinishTime(task.getFinishTime());
        vo.setParams(task.getParams());
        vo.setResult(task.getResult());
        vo.setTimeoutSeconds(task.getTimeoutSeconds());
        vo.setCancelRequested(task.getCancelRequested());
        vo.setExecutorId(task.getExecutorId());
        return vo;
    }
}
