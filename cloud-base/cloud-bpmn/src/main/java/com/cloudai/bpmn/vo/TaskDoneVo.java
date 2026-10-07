package com.cloudai.bpmn.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 已办任务 VO（契约 2026-10-07-bpmn-leave-api §3.2：TaskVo 全字段 + 四字段）。
 * 数据源 ACT_HI_TASKINST + ACT_HI_COMMENT + businessKey 回查；approve 由实例 endActivityId 判定。
 * 继承 TaskVo：TransFieldScanner 沿类层级收集字段，翻译注解对子类实例同样生效。
 */
@TranslateVO
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskDoneVo extends TaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 办理时间 */
    private String endTime;

    /** 办理结果（"true"/"false"） */
    private String approve;

    /** 审批意见 */
    private String comment;

    /** 请假单当前状态值（"0"-"3"） */
    @DictTrans(dictKey = "bpmn_leave_status", labelField = "leaveStatusLabel")
    private String leaveStatus;

    /** 请假单当前状态译文（未命中 null） */
    private String leaveStatusLabel;
}
