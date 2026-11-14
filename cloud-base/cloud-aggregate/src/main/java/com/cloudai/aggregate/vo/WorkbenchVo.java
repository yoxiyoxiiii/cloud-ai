package com.cloudai.aggregate.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 工作台聚合出参（契约 2026-11-14-aggregate-workbench-api §1.2）：三段并置，
 * 每段自带 status/msg/data，段级失败不影响其它段与整体 code=200。
 */
@Data
public class WorkbenchVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 我的待办段（cloud-bpmn） */
    private TodoSectionVo todo;

    /** 我的审批段（cloud-bpmn） */
    private ApprovalSectionVo approval;

    /** 未读通知摘要段（cloud-system） */
    private NotifySectionVo notify;
}
