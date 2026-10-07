package com.cloudai.bpmn.vo;

import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;

/**
 * 审批时间线单步 VO（契约 2026-10-07-bpmn-leave-api §2.3：apply/approval/end 三步，按时间升序）。
 * 三源拼装：请假单行（apply）+ ACT_HI_COMMENT（approval）+ HistoricProcessInstance（end）。
 */
@TranslateVO
@Data
public class ApprovalStepVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** apply / approval / end */
    private String stepKey;

    /** 步骤名（发起申请 / 审批意见 / 流程结束） */
    private String title;

    /** 操作人 account */
    @UserTrans(labelField = "operatorName")
    private String operator;

    /** 昵称译文（未命中 null） */
    private String operatorName;

    /** 意见文本（approval 步骤） */
    private String comment;

    /** 步骤时间 yyyy-MM-dd HH:mm:ss */
    private String time;

    /** 仅 end 步骤：已通过/已拒绝/已撤销（statusLabel 同文案） */
    private String result;
}
