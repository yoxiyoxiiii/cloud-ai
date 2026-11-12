package com.cloudai.task.runtime;

import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.IOException;
import java.time.Clock;

/**
 * handler 执行上下文（设计 §5.3）：进度上报（节流写库）+ 协作式取消检查 + 参数解析。
 * 每次执行新建实例，非线程安全（单任务单线程）。
 */
public class TaskContext {

    /** 进度上限（百分比语义） */
    private static final int PROGRESS_MAX = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Getter
    private final long taskId;

    /** 提交参数 JSON 原文（可空），handler 经 paramsAs 按类型自解析 */
    private final String params;

    private final AsyncTaskMapper mapper;

    /** 进度写库节流间隔（毫秒） */
    private final long throttleMs;

    private final Clock clock;

    /** 上次进度写库时刻（-1=尚未写过），与 throttleMs 联合节流 */
    private long lastWriteAt = -1;

    public TaskContext(long taskId, String params, AsyncTaskMapper mapper, long throttleMs, Clock clock) {
        this.taskId = taskId;
        this.params = params;
        this.mapper = mapper;
        this.throttleMs = throttleMs;
        this.clock = clock;
    }

    /**
     * 上报进度（0-100 自动钳位）：距上次写库 ≥ 节流间隔或已达 100% 才落库，
     * 防千条批量任务打满 UPDATE；条件写仅执行中行生效（终态后迟到的进度为空更新）。
     */
    public void reportProgress(int percent, String phase) {
        int pct = Math.max(0, Math.min(PROGRESS_MAX, percent));
        long now = clock.millis();
        boolean throttled = lastWriteAt >= 0 && now - lastWriteAt < throttleMs && pct < PROGRESS_MAX;
        if (throttled) {
            return;
        }
        mapper.updateProgress(taskId, pct, phase);
        lastWriteAt = now;
    }

    /**
     * 协作式取消检查点：查库判定——已请求取消、或行已不在执行中（超时判死/终态/被清理）
     * 均抛 TaskCanceledException 停止后续工作；不检查取消的坏 handler 由超时兜底。
     */
    public void checkCanceled() {
        AsyncTask row = mapper.findById(taskId);
        boolean canceled = row == null
                || row.getCancelRequested() != null && row.getCancelRequested() == 1
                || row.getStatus() == null || row.getStatus() != AsyncTask.StatusEnum.RUNNING.getCode();
        if (canceled) {
            throw new TaskCanceledException("任务已请求取消或不再执行中: " + taskId);
        }
    }

    /** 参数按类型解析（params 为 null 时返回全默认值实例） */
    public <T> T paramsAs(Class<T> type) {
        if (params == null || params.isBlank()) {
            try {
                return JSON.readValue("{}", type);
            } catch (IOException e) {
                throw new IllegalArgumentException("参数解析失败", e);
            }
        }
        try {
            return JSON.readValue(params, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("参数解析失败: " + params, e);
        }
    }
}
