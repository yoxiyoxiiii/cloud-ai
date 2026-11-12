package com.cloudai.task.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 任务提交出参（契约 2026-11-10-task-center-api §2）。
 * taskId 为 Long，线上 JSON 按全局 Jackson 口径序列化为 string（防前端精度丢失）。
 */
@Data
public class TaskSubmitResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务 ID（后续查询/取消/重试凭据） */
    private Long taskId;
}
