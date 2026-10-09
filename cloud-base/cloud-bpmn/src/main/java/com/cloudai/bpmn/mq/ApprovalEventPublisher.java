package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 审批事件通知生产（契约 2026-10-09-rocketmq-tx-approval-api §1.3，topic APPROVAL_EVENT_NOTIFY）。
 * 事件组装/KEYS 组装统一口——TERMINAL 事件经 TxMessageSender 事务半消息发送（executor 形态，
 * completeTask/cancelApproval 编排化），CREATE_RESULT 补偿事件经 {@link #sendPlain} 普通发送
 * （流1 消费 ACK 前的补偿，不在本地事务内——失败事件无前置状态可保原子，契约 §1.3 触发点 3）。
 */
@Component
@RequiredArgsConstructor
public class ApprovalEventPublisher {

    /** 事件时间格式（契约 §1.3：String 规避时区坑，GMT+8） */
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RocketMQTemplate rocketMQTemplate;
    private final JsonPayloads jsonPayloads;

    /** TERMINAL 事件组装（completeTask 置 1/2、cancel 置 3） */
    public ApprovalEventMessage terminal(String businessType, String businessKey, Integer terminalStatus) {
        ApprovalEventMessage event = baseEvent(ApprovalMqTopics.TAG_TERMINAL, businessType, businessKey);
        event.setTerminalStatus(String.valueOf(terminalStatus));
        return event;
    }

    /** CREATE_RESULT/SUCCESS 事件组装（流1 消费成功，approvalId 必填） */
    public ApprovalEventMessage createResultSuccess(String businessType, String businessKey, Long approvalId) {
        ApprovalEventMessage event = baseEvent(ApprovalMqTopics.TAG_CREATE_RESULT, businessType, businessKey);
        event.setApprovalId(String.valueOf(approvalId));
        event.setResult(ApprovalMqTopics.RESULT_SUCCESS);
        return event;
    }

    /** CREATE_RESULT/FAILED 事件组装（流1 消费确定性失败白名单，reason=平台失败 msg） */
    public ApprovalEventMessage createResultFailed(String businessType, String businessKey, String reason) {
        ApprovalEventMessage event = baseEvent(ApprovalMqTopics.TAG_CREATE_RESULT, businessType, businessKey);
        event.setResult(ApprovalMqTopics.RESULT_FAILED);
        event.setReason(reason);
        return event;
    }

    /** 消息幂等键（契约 §1.3 KEYS）：{businessType}:{businessKey}:{eventType} */
    public String notifyKeys(ApprovalEventMessage event) {
        return event.getBusinessType() + ":" + event.getBusinessKey() + ":" + event.getEventType();
    }

    /** 普通同步发送（触发点 3 补偿事件）：body 按全局 Jackson 口径序列化，KEYS 走标准 header */
    public void sendPlain(ApprovalEventMessage event) {
        Message<byte[]> message = MessageBuilder.withPayload(jsonPayloads.toBytes(event))
                .setHeader(RocketMQHeaders.KEYS, notifyKeys(event))
                .build();
        String destination = ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY + ":" + event.getEventType();
        rocketMQTemplate.syncSend(destination, message);
    }

    private ApprovalEventMessage baseEvent(String eventType, String businessType, String businessKey) {
        ApprovalEventMessage event = new ApprovalEventMessage();
        event.setEventType(eventType);
        event.setBusinessType(businessType);
        event.setBusinessKey(businessKey);
        event.setOccurredAt(LocalDateTime.now().format(EVENT_TIME));
        return event;
    }
}
