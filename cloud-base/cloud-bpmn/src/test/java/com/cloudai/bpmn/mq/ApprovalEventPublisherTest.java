package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 事件组装/KEYS/普通发送单测（契约 2026-10-09 §1.3 消息体逐字段 + KEYS 组装口径）。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalEventPublisherTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private final JsonPayloads jsonPayloads = new JsonPayloads(new ObjectMapper());

    private ApprovalEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ApprovalEventPublisher(rocketMQTemplate, jsonPayloads);
    }

    @Test
    void terminal_assemblesTerminalEvent() {
        ApprovalEventMessage event = publisher.terminal("leave", "7", 1);

        assertThat(event.getEventType()).isEqualTo("TERMINAL");
        assertThat(event.getBusinessType()).isEqualTo("leave");
        assertThat(event.getBusinessKey()).isEqualTo("7");
        assertThat(event.getTerminalStatus()).isEqualTo("1");
        assertThat(event.getApprovalId()).isNull();
        assertThat(event.getResult()).isNull();
        // 事件时间 String 规避时区坑（契约 §1.3）
        assertThat(event.getOccurredAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void createResultSuccess_assemblesWithApprovalId() {
        ApprovalEventMessage event = publisher.createResultSuccess("leave", "7", 12L);

        assertThat(event.getEventType()).isEqualTo("CREATE_RESULT");
        assertThat(event.getResult()).isEqualTo("SUCCESS");
        assertThat(event.getApprovalId()).isEqualTo("12");
        assertThat(event.getReason()).isNull();
    }

    @Test
    void createResultFailed_assemblesWithReason() {
        ApprovalEventMessage event = publisher.createResultFailed("leave", "7", "审批人无效: ghost");

        assertThat(event.getEventType()).isEqualTo("CREATE_RESULT");
        assertThat(event.getResult()).isEqualTo("FAILED");
        assertThat(event.getApprovalId()).isNull();
        assertThat(event.getReason()).isEqualTo("审批人无效: ghost");
    }

    @Test
    void notifyKeys_joinsBusinessAndEventType() {
        // KEYS 组装口径（契约 §1.3）：{businessType}:{businessKey}:{eventType}
        assertThat(publisher.notifyKeys(publisher.terminal("leave", "7", 3)))
                .isEqualTo("leave:7:TERMINAL");
        assertThat(publisher.notifyKeys(publisher.createResultSuccess("leave", "7", 12L)))
                .isEqualTo("leave:7:CREATE_RESULT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendPlain_destinationBodyAndKeys() {
        ApprovalEventMessage event = publisher.terminal("leave", "7", 1);

        publisher.sendPlain(event);

        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass((Class) Message.class);
        verify(rocketMQTemplate).syncSend(eq("APPROVAL_EVENT_NOTIFY:TERMINAL"), (Message<?>) captor.capture());
        assertThat(captor.getValue().getPayload()).isEqualTo(jsonPayloads.toBytes(event));
        assertThat(captor.getValue().getHeaders().get("KEYS")).isEqualTo("leave:7:TERMINAL");
    }
}
