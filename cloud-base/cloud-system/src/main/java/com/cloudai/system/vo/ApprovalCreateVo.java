package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 发起审批回执 VO（契约 2026-10-08-approval-platform-api §4.1 镜像：approvalId 供回填 sys_leave.approval_id）。
 */
@Data
public class ApprovalCreateVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审批单 id（字符串化） */
    private String approvalId;

    /** 初始状态（"0" 审批中） */
    private String status;
}
