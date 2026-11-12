package com.cloudai.task.api.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 任务提交入参（契约 2026-11-10-task-center-api §2 逐字段）。
 * 提交人由业务方从登录态取账号显式传入——inner 面无登录态，不信任任何 header 透传。
 */
@Data
public class TaskSubmitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务类型码（handler 注册键，如 demo-batch）；未注册 6001 */
    @NotBlank
    @Size(max = 50)
    private String taskType;

    /** 任务参数：JSON 对象文本（如 "{\"total\":100}"），透传不解析、handler 按类型自解析；非 JSON 对象 6002 */
    private String params;

    /** 业务幂等键：非空时与 taskType 库级唯一，已存在（任意状态）6003；终态后同 key 不可再提交（重跑走重试端点） */
    @Size(max = 100)
    private String bizKey;

    /** 自动重试上限，默认 3（0-10，越界 6002） */
    @Min(0)
    @Max(10)
    private Integer maxRetries;

    /** 执行超时秒数，默认 600（10-86400，越界 6002） */
    @Min(10)
    @Max(86400)
    private Integer timeoutSeconds;

    /** 提交人账号（业务方登录态取） */
    @NotBlank
    @Size(max = 50)
    private String submitterAccount;

    /** 提交来源服务名（如 cloud-system，列表筛选用） */
    @NotBlank
    @Size(max = 50)
    private String submitSource;
}
