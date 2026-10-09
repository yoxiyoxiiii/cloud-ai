package com.cloudai.bpmn.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审批单 VO（契约 2026-10-08-approval-platform-api §3.1：我的审批列表与详情共用主体）。
 * status 契约形态为字符串（对齐字典 value 与 Long→String 惯例；DB TINYINT，VO 出参 String 化）；
 * businessTypeName/detailPath 非字典（配置表渲染，详情嵌套结构由 Service 手动回填）；
 * 审计 createBy/updateBy 不出（applyUser/approver 已承载操作人语义）；译文字段未命中 null 走前端降级链。
 */
@TranslateVO
@Data
public class ApprovalVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审批单 id（Jackson Long→String；即流程实例 businessKey） */
    private Long id;

    /** 业务类型编码 */
    private String businessType;

    /** 业务类型名（配置表，必返非空；配置行缺失防御 null） */
    private String businessTypeName;

    /** 业务单据标识 */
    private String businessKey;

    /** 标题快照 */
    private String title;

    /** 状态值（"0"-"3"，字典 bpmn_approval_status） */
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

    /** 详情跳转路径（配置渲染失败/未配 null，前端隐藏跳转） */
    private String detailPath;

    /** 流程实例 id（撤销后 null） */
    private String processInstanceId;

    /** 发起时间 */
    private LocalDateTime createTime;

    /** 最近状态变更时间 */
    private LocalDateTime updateTime;
}
