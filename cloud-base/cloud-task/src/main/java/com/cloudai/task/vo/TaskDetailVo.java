package com.cloudai.task.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务详情 VO（契约 2026-11-10-task-center-api §4）：列表 VO 全字段 + 详情扩展五字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskDetailVo extends TaskVo {

    private static final long serialVersionUID = 1L;

    /** 提交参数 JSON 原文（可空） */
    private String params;

    /** 成功结果 JSON（可空） */
    private String result;

    /** 超时配置（秒） */
    private Integer timeoutSeconds;

    /** 协作式取消标志 0/1（执行中已请求取消） */
    private Integer cancelRequested;

    /** 认领执行器标识（可空） */
    private String executorId;
}
