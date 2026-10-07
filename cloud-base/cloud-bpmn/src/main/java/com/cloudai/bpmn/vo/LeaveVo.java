package com.cloudai.bpmn.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 请假单 VO（契约 2026-10-07-bpmn-leave-api §2.2：列表与详情共用主体）。
 * leaveType/status 契约形态为字符串（对齐字典 value 与 Long→String 惯例；DB TINYINT，VO 出参 String 化）；
 * 审计 createBy/updateBy 不出（applyUser/approver 已承载操作人语义，设计 D6）；
 * 译文字段随 translation-api §8 体系，未命中 null 走前端降级链。
 */
@TranslateVO
@Data
public class LeaveVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请假单 id（Jackson Long→String） */
    private Long id;

    /** 标题 */
    private String title;

    /** 类型值（"1"/"2"/"3"，字典 bpmn_leave_type） */
    @DictTrans(dictKey = "bpmn_leave_type", labelField = "leaveTypeLabel")
    private String leaveType;

    /** 类型译文（未命中 null） */
    private String leaveTypeLabel;

    /** 开始日期 yyyy-MM-dd */
    private String startDate;

    /** 结束日期 yyyy-MM-dd */
    private String endDate;

    /** 事由 */
    private String reason;

    /** 状态值（"0"-"3"，字典 bpmn_leave_status） */
    @DictTrans(dictKey = "bpmn_leave_status", labelField = "statusLabel")
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

    /** 流程实例 id（撤销后实例已删→null） */
    private String processInstanceId;

    /** 发起时间 */
    private LocalDateTime createTime;

    /** 最近状态变更时间 */
    private LocalDateTime updateTime;
}
