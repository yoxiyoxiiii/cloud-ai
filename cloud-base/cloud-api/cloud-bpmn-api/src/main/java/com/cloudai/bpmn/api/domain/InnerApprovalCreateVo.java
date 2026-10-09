package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 发起审批出参（契约 2026-10-08-approval-platform-api §4.1：{ approvalId, status } 全 string）。
 * （cloud-bpmn-api 归位 2026-10-09）
 */
@Data
public class InnerApprovalCreateVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审批单 id（字符串化） */
    private String approvalId;

    /** 初始状态（"0" 审批中） */
    private String status;
}
