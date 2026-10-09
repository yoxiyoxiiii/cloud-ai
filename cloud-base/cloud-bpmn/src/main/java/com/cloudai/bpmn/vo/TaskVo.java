package com.cloudai.bpmn.vo;

import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;

/**
 * 待办任务 VO（契约 2026-10-08-approval-platform-api §2.1 通用化：
 * ACT_RU_TASK → businessKey(=approvalId) 回查 bpmn_approval 快照 + 配置表渲染，
 * 零业务表回查、零跨服务）。applyUser 原字段为翻译源，译文未命中 null 走前端降级链。
 */
@TranslateVO
@Data
public class TaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引擎任务 id（办理回传锚点） */
    private String taskId;

    /** 审批单 id */
    private String approvalId;

    /** 业务类型编码 */
    private String businessType;

    /** 业务类型名（配置表） */
    private String businessTypeName;

    /** 单据标题快照 */
    private String title;

    /** 详情跳转路径（渲染失败/未配 null） */
    private String detailPath;

    /** 申请人 account */
    @UserTrans(labelField = "applyUserName")
    private String applyUser;

    /** 申请人昵称译文（未命中 null） */
    private String applyUserName;

    /** 任务创建时间 */
    private String createTime;
}
