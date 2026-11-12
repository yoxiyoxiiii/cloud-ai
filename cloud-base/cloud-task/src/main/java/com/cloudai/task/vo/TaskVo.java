package com.cloudai.task.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务列表行 VO（契约 2026-11-10-task-center-api §3 逐字段）。
 * taskId 为 Long，线上 JSON 按全局 Jackson 口径序列化为 string（防前端精度丢失）。
 */
@Data
public class TaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务 ID */
    private Long taskId;

    /** 任务类型码 */
    private String taskType;

    /** 业务幂等键（可空） */
    private String bizKey;

    /** 状态：0排队 1执行中 2成功 3失败 4已取消 */
    private Integer status;

    /** 进度百分比 0-100 */
    private Integer progress;

    /** 执行阶段描述（可空） */
    private String phase;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大自动重试次数 */
    private Integer maxRetries;

    /** 失败原因（可空） */
    private String errorMsg;

    /** 提交人账号 */
    private String submitBy;

    /** 提交来源服务名 */
    private String submitSource;

    /** 提交时间 */
    private LocalDateTime createTime;

    /** 开始执行时间（可空） */
    private LocalDateTime startTime;

    /** 终态时间（可空） */
    private LocalDateTime finishTime;
}
