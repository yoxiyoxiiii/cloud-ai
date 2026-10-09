package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 审批事件通知消息体（契约 2026-10-09-rocketmq-tx-approval-api §1.3，topic APPROVAL_EVENT_NOTIFY；
 * 2026-10-09 投影轮 §1.3 增 processInstanceId——additive，旧消费方忽略）。
 * 事件由 bpmn 发布——模型归提供方，与 Feign 契约同位（设计 D4）。
 * 字段一律 String：Long→String 锁发送端全局 Jackson 口径；occurredAt 用 String 规避时区坑。
 */
@Data
public class ApprovalEventMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件类型：CREATE_RESULT（发起结果）| TERMINAL（终态） */
    private String eventType;

    /** 业务类型（如 leave） */
    private String businessType;

    /** 业务单据键（=leaveId） */
    private String businessKey;

    /** 审批单 id（CREATE_RESULT/FAILED 时 null） */
    private String approvalId;

    /** 流程实例 ID（CREATE_RESULT/SUCCESS 必填；FAILED/TERMINAL 为 null）——投影列回填依据（Q4=A） */
    private String processInstanceId;

    /** 仅 CREATE_RESULT：SUCCESS | FAILED */
    private String result;

    /** 仅 TERMINAL："1"通过 | "2"拒绝 | "3"撤销 */
    private String terminalStatus;

    /** 失败/终态说明（日志观测，不透出前端） */
    private String reason;

    /** 事件时间 yyyy-MM-dd HH:mm:ss（GMT+8） */
    private String occurredAt;
}
