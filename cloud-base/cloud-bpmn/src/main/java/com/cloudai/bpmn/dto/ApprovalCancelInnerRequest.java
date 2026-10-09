package com.cloudai.bpmn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 撤销审批入参（契约 2026-10-08-approval-platform-api §4.3：/inner/approval/cancel，
 * 按 (businessType, businessKey) 定位审批单；校验序 4010→4012→4011）。
 */
@Data
public class ApprovalCancelInnerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型编码 */
    @NotBlank
    private String businessType;

    /** 业务单据标识 */
    @NotBlank
    private String businessKey;

    /** 操作人账号（调用方传入；须=审批单 applyUser，否则 4012） */
    @NotBlank
    @Size(max = 30)
    private String operator;
}
