package com.cloudai.system.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 请假单 VO（契约 2026-10-08-approval-platform-api §5.2：列表与详情共用主体；投影轮 §4.1 读语义取代版
 * ——字段/类型/示例逐字不变，仅数据来源改 JOIN approval_projection 派生）。
 * leaveType 契约形态为字符串（对齐字典 value；DB TINYINT，SQL CAST CHAR 直出）；
 * status/approvalId 派生读（事件秒级收敛，发起后秒级窗口 null/0 属正常时序）；不含时间线/图
 * （前端另调平台 §3.2/§3.4）；审计 createBy/updateBy 不出（applyUser/approver 已承载操作人语义）；
 * 译文字段未命中 null 走前端降级链。mapper resultMap 直出（派生列无实体承载，设计 D6 记档）。
 */
@TranslateVO
@Data
public class SysLeaveVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请假单 id（Jackson Long→String；即平台 businessKey） */
    private Long id;

    /** 审批单 id（投影 approval_id 直出；撤销后仍在、发起失败无） */
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

    /** 状态值（"0"-"4"，字典 bpmn_approval_status；JOIN 投影派生——§1.5 CASE 唯一口径，事件秒级实时） */
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
