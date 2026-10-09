package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 投影消费者单测（契约 2026-10-09-approval-projection-api §2.2 三分支取代语义）：
 * SUCCESS/FAILED/TERMINAL 三分支调 DAO upsert（列族按设计 D4）+ 未知 eventType/result/终态值抛出重试 +
 * L1 去重（重复跳过/失败删行放行）+ dedupGroup 取 Properties 配置值。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalProjectionListenerTest {

    private static final String GROUP = "g_system_approval_event";

    @Mock
    private ConsumeDedupDao consumeDedupDao;
    @Mock
    private ApprovalProjectionDao projectionDao;

    private final JsonPayloads jsonPayloads = new JsonPayloads(new ObjectMapper());

    private ApprovalProjectionListener listener;

    @BeforeEach
    void setUp() {
        ApprovalProjectionProperties properties = new ApprovalProjectionProperties();
        properties.setConsumerGroup(GROUP);
        listener = new ApprovalProjectionListener(consumeDedupDao, jsonPayloads, projectionDao, properties);
        // dedup 前置插行默认成功（重复分支单独打桩）
        lenient().when(consumeDedupDao.insert(eq(GROUP), anyString())).thenReturn(1);
    }

    @Test
    void createResultSuccess_upsertsCreateSuccessFamily() {
        listener.onMessage(msg(event("CREATE_RESULT", "SUCCESS", null, "pid-9")));

        ArgumentCaptor<ApprovalEventMessage> captor = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(projectionDao).applyCreateSuccess(captor.capture(), eq("bpmn-event"), any(LocalDateTime.class));
        assertThat(captor.getValue().getBusinessType()).isEqualTo("leave");
        assertThat(captor.getValue().getBusinessKey()).isEqualTo("5");
        assertThat(captor.getValue().getApprovalId()).isEqualTo("12");
        assertThat(captor.getValue().getProcessInstanceId()).isEqualTo("pid-9");
        verify(projectionDao, never()).applyCreateFailed(any(), anyString(), any());
        verify(projectionDao, never()).applyTerminal(any(), anyInt(), anyString(), any());
    }

    @Test
    void createResultSuccess_missingApprovalIdThrownForRetry() {
        // approvalId 是 Long 解析目标（投影 approval_id 列）：缺失=消息契约破坏，走重试→死信
        ApprovalEventMessage event = event("CREATE_RESULT", "SUCCESS", null, "pid-9");
        event.setApprovalId(null);

        assertThatThrownBy(() -> listener.onMessage(msg(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUCCESS 事件缺 approvalId");
        verifyNoInteractions(projectionDao);
    }

    @Test
    void createResultFailed_upsertsCreateFailedFamily() {
        listener.onMessage(msg(event("CREATE_RESULT", "FAILED", null, null)));

        verify(projectionDao).applyCreateFailed(any(), eq("bpmn-event"), any(LocalDateTime.class));
        verify(projectionDao, never()).applyCreateSuccess(any(), anyString(), any());
    }

    @Test
    void terminal_writesTerminalStatus() {
        listener.onMessage(msg(event("TERMINAL", null, "3", null)));

        verify(projectionDao).applyTerminal(any(), eq(3), eq("bpmn-event"), any(LocalDateTime.class));
    }

    @Test
    void unknownEventType_thrownForRetry() {
        assertThatThrownBy(() -> listener.onMessage(msg(event("MYSTERY", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知事件类型");
        verifyNoInteractions(projectionDao);
    }

    @Test
    void unknownResult_thrownForRetry() {
        assertThatThrownBy(() -> listener.onMessage(msg(event("CREATE_RESULT", "MAYBE", null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知发起结果");
        verifyNoInteractions(projectionDao);
    }

    @Test
    void terminalStatusOutOfRange_rejected_neverWrite4() {
        // 投影列永不落 4（契约 §1.2 不变式）：值域白名单 1-3，越界/非数值走重试→死信
        assertThatThrownBy(() -> listener.onMessage(msg(event("TERMINAL", null, "4", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态值越界");
        assertThatThrownBy(() -> listener.onMessage(msg(event("TERMINAL", null, "abc", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态值非法");
        verifyNoInteractions(projectionDao);
    }

    @Test
    void duplicateMessage_skipsBusinessWrite() {
        // L1 去重：uk 冲突=已消费 ACK 跳过（doConsume 不触达）
        when(consumeDedupDao.insert(eq(GROUP), anyString()))
                .thenThrow(new DuplicateKeyException("uk_group_key duplicate"));

        assertThatCode(() -> listener.onMessage(msg(event("TERMINAL", null, "1", null))))
                .doesNotThrowAnyException();
        verifyNoInteractions(projectionDao);
    }

    @Test
    void businessFailure_releasesDedupRowAndRethrows() {
        // doConsume 异常 → 删 dedup 行放行重试（失败不删行=消息被吞丢失，starter L1 契约）
        when(projectionDao.applyTerminal(any(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> listener.onMessage(msg(event("TERMINAL", null, "1", null))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(consumeDedupDao).delete(eq(GROUP), key.capture());
        assertThat(key.getValue()).isEqualTo("leave:5:TERMINAL");
    }

    // ---- 脚手架 ----

    /** KEYS 头构造与生产端 notifyKeys 同口径；body lenient：重复消费用例在 parse 前短路 */
    private MessageExt msg(ApprovalEventMessage event) {
        try {
            MessageExt msg = mock(MessageExt.class);
            lenient().when(msg.getBody()).thenReturn(new ObjectMapper().writeValueAsBytes(event));
            when(msg.getKeys()).thenReturn(event.getBusinessType() + ":" + event.getBusinessKey()
                    + ":" + event.getEventType());
            return msg;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ApprovalEventMessage event(String eventType, String result, String terminalStatus, String pid) {
        ApprovalEventMessage event = new ApprovalEventMessage();
        event.setEventType(eventType);
        event.setBusinessType("leave");
        event.setBusinessKey("5");
        event.setApprovalId("12");
        event.setProcessInstanceId(pid);
        event.setResult(result);
        event.setTerminalStatus(terminalStatus);
        event.setOccurredAt("2026-10-09 15:00:00");
        return event;
    }
}
