package com.cloudai.system.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.system.entity.SysLeave.StatusEnum;
import com.cloudai.system.mapper.SysLeaveMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审批事件消费单测（契约 2026-10-09 §1.3 消费三分支 + L1 去重口径）：
 * SUCCESS 回填 / FAILED 置 4 / TERMINAL 置 1|2|3（均条件 UPDATE）+ 未知 eventType/result 抛重试 +
 * 重复消息（dedup uk 冲突）幂等跳过零业务写。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalEventConsumerTest {

    @Mock
    private ConsumeDedupDao consumeDedupDao;
    @Mock
    private SysLeaveMapper leaveMapper;

    private final JsonPayloads jsonPayloads = new JsonPayloads(new ObjectMapper());

    private ApprovalEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ApprovalEventConsumer(consumeDedupDao, leaveMapper, jsonPayloads);
        // dedup 前置插行默认成功（重复分支单独打桩）
        lenient().when(consumeDedupDao.insert(eq("g_system_approval_event"), anyString())).thenReturn(1);
    }

    @Test
    void createResultSuccess_backfillsApprovalIdIfAbsent() {
        consumer.onMessage(msg(event("CREATE_RESULT", "SUCCESS", null)));

        verify(leaveMapper).updateApprovalIdIfAbsent(eq(5L), eq(12L), eq("bpmn-event"), any());
        verify(leaveMapper, never()).updateStatusIfApproving(anyLong(), any(), anyString(), any());
    }

    @Test
    void createResultFailed_marksFailedTerminal() {
        consumer.onMessage(msg(event("CREATE_RESULT", "FAILED", null)));

        verify(leaveMapper).updateStatusIfApproving(eq(5L), eq(StatusEnum.FAILED.getCode()),
                eq("bpmn-event"), any());
        verify(leaveMapper, never()).updateApprovalIdIfAbsent(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void terminal_writesTerminalStatus() {
        consumer.onMessage(msg(event("TERMINAL", null, "1")));

        verify(leaveMapper).updateStatusIfApproving(eq(5L), eq(StatusEnum.APPROVED.getCode()),
                eq("bpmn-event"), any());
    }

    @Test
    void unknownEventType_thrownForRetry() {
        assertThatThrownBy(() -> consumer.onMessage(msg(event("MYSTERY", null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知事件类型");
        verifyNoInteractions(leaveMapper);
    }

    @Test
    void unknownResult_thrownForRetry() {
        assertThatThrownBy(() -> consumer.onMessage(msg(event("CREATE_RESULT", "MAYBE", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知发起结果");
        verifyNoInteractions(leaveMapper);
    }

    @Test
    void duplicateMessage_skipsBusinessWrite() {
        // L1 去重：uk 冲突=已消费 ACK 跳过（doConsume 不触达）
        when(consumeDedupDao.insert(eq("g_system_approval_event"), anyString()))
                .thenThrow(new DuplicateKeyException("uk_group_key duplicate"));

        assertThatCode(() -> consumer.onMessage(msg(event("TERMINAL", null, "1"))))
                .doesNotThrowAnyException();
        verifyNoInteractions(leaveMapper);
    }

    @Test
    void businessFailure_releasesDedupRowAndRethrows() {
        // doConsume 异常 → 删 dedup 行放行重试（失败删行是重投不丢的口径，starter L1 契约）
        when(leaveMapper.updateStatusIfApproving(anyLong(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> consumer.onMessage(msg(event("TERMINAL", null, "1"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(consumeDedupDao).delete(eq("g_system_approval_event"), key.capture());
        assertThat(key.getValue()).isEqualTo("leave:5:TERMINAL");
    }

    // ---- 脚手架 ----

    /** KEYS 头由 broker 映射——msg_key 优先消息 KEYS（构造即设，与生产端 notifyKeys 同口径）；
     *  getBody lenient：重复消费用例在 parse 前 DuplicateKey 短路，不触达 body */
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

    private ApprovalEventMessage event(String eventType, String result, String terminalStatus) {
        ApprovalEventMessage event = new ApprovalEventMessage();
        event.setEventType(eventType);
        event.setBusinessType("leave");
        event.setBusinessKey("5");
        event.setApprovalId("12");
        event.setResult(result);
        event.setTerminalStatus(terminalStatus);
        event.setOccurredAt("2026-10-09 15:00:00");
        return event;
    }
}
