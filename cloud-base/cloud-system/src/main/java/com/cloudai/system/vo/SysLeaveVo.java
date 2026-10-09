package com.cloudai.system.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 请假单 VO（契约 2026-10-08-approval-platform-api §5.2：列表与详情共用主体）。
 * leaveType/status 契约形态为字符串（对齐字典 value；DB TINYINT，VO 出参 String 化）；
 * 状态经读时纠偏后为实时值（真相源=bpmn_approval.status）；不含时间线/图（前端另调平台 §3.2/§3.4）；
 * 审计 createBy/updateBy 不出（applyUser/approver 已承载操作人语义）；译文字段未命中 null 走前端降级链。
 */
@TranslateVO
@Data
public class SysLeaveVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请假单 id（Jackson Long→String；即平台 businessKey） */
    private Long id;

    /** 审批单 id（撤销后仍在；发起失败无） */
    private Long approvalId;

    /** 标题 */
    private String title;

    /** 类型值（"1"/"2"/"3"，字典 system_leave_type） */
    @DictTrans(dictKey = "system_leave_type", labelField = "leaveTypeLabel")
    private String leaveType;

    /** 类型译文（未命中 null） */
    private String leaveTypeLabel;

    /** 开始日期 yyyy-MM-dd */
    private String startDate;

    /** 结束日期 yyyy-MM-dd */
    private String endDate;

    /** 事由 */
    private String reason;

    /** 状态值（"0"-"3"，字典 bpmn_approval_status；纠偏后实时） */
    @DictTrans(dictKey = "bpmn_approval_status", labelField = "statusLabel")
    private String status;

    /** 状态译文（未命中 null） */
    private String statusLabel;

    /** 申请人 account */
    @UserTrans(labelField = "applyUserName")
    private String applyUser;

    /** 申请人昵称译文（未命中 null） */
    private String applyUserName;

    /** 审批人 account */
    @UserTrans(labelField = "approverName")
    private String approver;

    /** 审批人昵称译文（未命中 null） */
    private String approverName;

    /** 发起时间 */
    private LocalDateTime createTime;
}
