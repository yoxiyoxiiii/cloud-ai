package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 批量状态回包行（契约 2026-10-08-approval-platform-api §4.2：businessKey 全集回包，
 * 无审批单的键 approvalId/status 均 null）。
 * （cloud-bpmn-api 归位 2026-10-09）
 */
@Data
public class InnerApprovalStatusVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务单据标识（回显请求键） */
    private String businessKey;

    /** 审批单 id（无审批单 null） */
    private String approvalId;

    /** 当前状态（无审批单 null） */
    private String status;
}
