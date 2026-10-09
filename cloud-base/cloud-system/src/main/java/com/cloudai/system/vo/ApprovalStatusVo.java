package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 批量审批状态 VO（契约 2026-10-08-approval-platform-api §4.2 镜像：
 * 纠偏回源行——与 sys_leave.status 快照比对，不一致回写真相源值）。
 */
@Data
public class ApprovalStatusVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务单据标识（回显） */
    private String businessKey;

    /** 审批单 id（无审批单 null——发起失败/孤儿窗口） */
    private String approvalId;

    /** 当前状态（无审批单 null） */
    private String status;
}
