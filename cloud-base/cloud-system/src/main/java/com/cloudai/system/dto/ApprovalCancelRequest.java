package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 按业务键撤销审批入参（契约 2026-10-08-approval-platform-api §4.3 镜像：
 * operator=当前登录人，平台侧校验 applyUser==operator 否则 4012）。
 */
@Data
public class ApprovalCancelRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型编码（sys_leave 恒 "leave"） */
    private String businessType;

    /** 业务单据标识（请假单 id 字符串化） */
    private String businessKey;

    /** 操作人账号（当前登录人） */
    private String operator;
}
