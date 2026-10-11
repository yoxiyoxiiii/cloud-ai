package com.cloudai.system.mq;

import com.cloudai.system.entity.MqDlqLog;
import com.cloudai.system.service.MqDlqLogService;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * watch 落库组装口径单测（设计 D5/D3）：msg_key 退化（无 KEYS→msgId）、born_group strip %DLQ% 前缀、
 * born_topic 取 RETRY_TOPIC 属性（缺省退化）、body 60000 字节截断尾标记、tag 可空。
 */
@ExtendWith(MockitoExtension.class)
class MqDlqWatchListenerTest {

    @Mock
    private MqDlqLogService dlqLogService;

    /** 最小可用消息骨架（%DLQ% 主题投递形态） */
    private MessageExt dlqMessage() {
        MessageExt msg = new MessageExt();
        msg.setTopic("%DLQ%g_bpmn_approval_create");
        msg.setBody("{\"businessType\":\"leave\"}".getBytes(StandardCharsets.UTF_8));
        msg.setReconsumeTimes(3);
        msg.setBornTimestamp(1760000000000L);
        msg.setStoreTimestamp(1760000060000L);
        return msg;
    }

    /** 测试薄壳（复用生产 onMessage 链路，验证组装→落库传递） */
    private class TestWatchListener extends AbstractMqDlqWatchListener {
        TestWatchListener(MqDlqLogService service) {
            super(service);
        }

        @Override
        protected String watchGroup() {
            return "g_system_dlq_watch_create";
        }
    }

    @Test
    void buildLog_fullyAssembled() {
        MessageExt msg = dlqMessage();
        msg.setKeys("leave:224");
        msg.setTags("CREATE_RESULT");
        MessageAccessor.putProperty(msg, "TX_NO", "tx-no-abc");
        MessageAccessor.putProperty(msg, "RETRY_TOPIC", "TX_APPROVAL_CREATE");
        msg.setMsgId("7F0000010000");

        new TestWatchListener(dlqLogService).onMessage(msg);

        ArgumentCaptor<MqDlqLog> captor = ArgumentCaptor.forClass(MqDlqLog.class);
        verify(dlqLogService).saveWatched(captor.capture());
        MqDlqLog log = captor.getValue();
        assertThat(log.getMsgId()).isEqualTo("7F0000010000");
        assertThat(log.getTxNo()).isEqualTo("tx-no-abc");
        assertThat(log.getBornTopic()).isEqualTo("TX_APPROVAL_CREATE");
        assertThat(log.getBornGroup()).isEqualTo("g_bpmn_approval_create");
        assertThat(log.getWatchGroup()).isEqualTo("g_system_dlq_watch_create");
        assertThat(log.getTag()).isEqualTo("CREATE_RESULT");
        assertThat(log.getMsgKey()).isEqualTo("leave:224");
        assertThat(log.getBody()).isEqualTo("{\"businessType\":\"leave\"}");
        assertThat(log.getReconsumeTimes()).isEqualTo(3);
        assertThat(log.getBornTime()).isNotNull();
        assertThat(log.getDlqTime()).isNotNull();
        assertThat(log.getStatus()).isEqualTo(MqDlqLog.StatusEnum.PENDING.getCode());
        assertThat(log.getResendCount()).isZero();
        assertThat(log.getCreateTime()).isNotNull();
    }

    @Test
    void buildLog_msgKeyFallsBackToMsgIdWhenKeysAbsent() {
        MessageExt msg = dlqMessage();
        msg.setMsgId("7F0000019999");
        // 无 KEYS：msg_key 退化 msgId（与 DedupRocketMQListener.resolveMsgKey 同口径）
        MqDlqLog log = AbstractMqDlqWatchListener.buildLog(msg, "g_system_dlq_watch_event");
        assertThat(log.getMsgKey()).isEqualTo("7F0000019999");
        assertThat(log.getTag()).isNull();
        assertThat(log.getTxNo()).isNull();
    }

    @Test
    void buildLog_bornTopicFallsBackToCurrentTopicWhenRetryTopicAbsent() {
        MessageExt msg = dlqMessage();
        // 无 RETRY_TOPIC 属性（防御）：退化当前 topic（%DLQ% 名）
        MqDlqLog log = AbstractMqDlqWatchListener.buildLog(msg, "g_system_dlq_watch_create");
        assertThat(log.getBornTopic()).isEqualTo("%DLQ%g_bpmn_approval_create");
    }

    @Test
    void buildLog_bornGroupStrippedFromDlqTopicPrefix() {
        MessageExt msg = dlqMessage();
        msg.setTopic("%DLQ%g_system_approval_event");
        MqDlqLog log = AbstractMqDlqWatchListener.buildLog(msg, "g_system_dlq_watch_event");
        assertThat(log.getBornGroup()).isEqualTo("g_system_approval_event");
    }

    @Test
    void buildLog_bodyTruncatedWithTailMarkerOverLimit() {
        MessageExt msg = dlqMessage();
        msg.setBody(new byte[MqDlqLog.BODY_MAX_BYTES + 10]);
        MqDlqLog log = AbstractMqDlqWatchListener.buildLog(msg, "g_system_dlq_watch_create");
        assertThat(log.getBody()).endsWith(MqDlqLog.TRUNCATED_TAIL);
        assertThat(log.getBody().length()).isGreaterThan(MqDlqLog.BODY_MAX_BYTES);

        // 边界：恰好上限不截断
        MessageExt exact = dlqMessage();
        exact.setBody(new byte[MqDlqLog.BODY_MAX_BYTES]);
        MqDlqLog exactLog = AbstractMqDlqWatchListener.buildLog(exact, "g_system_dlq_watch_create");
        assertThat(exactLog.getBody()).doesNotEndWith(MqDlqLog.TRUNCATED_TAIL);
    }
}
