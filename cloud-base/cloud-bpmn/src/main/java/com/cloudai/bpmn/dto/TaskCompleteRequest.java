package com.cloudai.bpmn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 办理任务入参（契约 2026-10-07-bpmn-leave-api §3.3：同意/拒绝意见均可空——宽松语义记档）。
 */
@Data
public class TaskCompleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引擎任务 id（待办列表回传锚点） */
    @NotBlank
    private String taskId;

    /** 办理结果 */
    @NotBlank
    @Pattern(regexp = "^(true|false)$")
    private String approve;

    /** 审批意见（≤200） */
    @Size(max = 200)
    private String comment;
}
