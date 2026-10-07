package com.cloudai.bpmn.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;

/**
 * 待办任务 VO（契约 2026-10-07-bpmn-leave-api §3.1：ACT_RU_TASK + businessKey 回查请假单）。
 * leaveType/applyUser 原字段为翻译源（契约 §7 同款声明），译文未命中 null 走前端降级链。
 */
@TranslateVO
@Data
public class TaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引擎任务 id（办理回传锚点） */
    private String taskId;

    /** 请假单 id */
    private String leaveId;

    /** 请假标题 */
    private String leaveTitle;

    /** 类型值（"1"/"2"/"3"，字典 bpmn_leave_type） */
    @DictTrans(dictKey = "bpmn_leave_type", labelField = "leaveTypeLabel")
    private String leaveType;

    /** 类型译文（未命中 null） */
    private String leaveTypeLabel;

    /** 申请人 account */
    @UserTrans(labelField = "applyUserName")
    private String applyUser;

    /** 申请人昵称译文（未命中 null） */
    private String applyUserName;

    /** 任务创建时间（=发起时刻） */
    private String createTime;
}
