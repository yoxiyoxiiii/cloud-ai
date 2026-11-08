package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 审批任务通知消息体（契约 2026-11-08-approval-notify-mq-api §1.2，topic BPMN_TASK_NOTIFY；
 * tag TASK_ARRIVED 任务到达 / TERMINAL_RESULT 审批结果）。
 * 通知由 bpmn 发布、system 通知域消费——模型归提供方，与 Feign 契约同位。
 * 字段一律 String：Long→String 沿发送端全局 Jackson 口径；occurredAt 用 String 规避时区坑。
 */
@Data
public class ApprovalNotifyMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知类型：TASK_ARRIVED（任务到达）| TERMINAL_RESULT（审批结果），与消息 tag 一致 */
    private String notifyType;

    /** 审批单 id（bpmn_approval.id） */
    private String approvalId;

    /** 业务类型编码（如 leave） */
    private String businessType;

    /** 业务单据键（业务方主键字符串化，如请假单 id） */
    private String businessKey;

    /** 单据标题快照（bpmn_approval.title，发起时定格） */
    private String title;

    /** 业务类型名（生产方从业务类型配置渲染——消费方无此表，必须由生产方填充） */
    private String businessTypeName;

    /** 申请人账号 */
    private String applyUser;

    /** 收件人账号：TASK_ARRIVED=节点 assignee；TERMINAL_RESULT=applyUser */
    private String receiverAccount;

    /** 任务 id（仅 TASK_ARRIVED） */
    private String taskId;

    /** 节点名（引擎 task.name；仅 TASK_ARRIVED） */
    private String taskName;

    /** 仅 TERMINAL_RESULT："1"通过 | "2"拒绝 | "3"撤销 */
    private String terminalStatus;

    /** 事件时间 yyyy-MM-dd HH:mm:ss（GMT+8） */
    private String occurredAt;
}
