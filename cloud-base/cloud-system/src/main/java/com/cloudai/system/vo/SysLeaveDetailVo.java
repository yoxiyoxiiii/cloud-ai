package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 请假单详情 VO（契约 2026-10-08-approval-platform-api §5.3：仅 leave 主体，
 * 不含时间线/图——前端按 approvalId 另调平台 §3.2/§3.4 拼装）。
 */
@Data
public class SysLeaveDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请假单主体（列表行同构，状态已纠偏） */
    private SysLeaveVo leave;
}
