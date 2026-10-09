package com.cloudai.common.rocketmq.consume;

import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费幂等基类单测（设计 D3 口径）：先插后消费 / 重复 ACK 跳过 / 失败删行放行重试 /
 * 删行失败仅记档仍抛原异常 / msgKey 取 KEYS 优先退化 msgId。
 */
@ExtendWith(MockitoExtension.class)
class DedupRocketMQListenerTest {

    @Mock
    private ConsumeDedupDao dedupDao;

    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        listener = new RecordingListener(dedupDao);
    }

    @Test
    void firstConsume_insertsDedupRowThenConsumes() throws Exception {
        MessageExt msg = msg("KEY-1", null);

        listener.onMessage(msg);

        verify(dedupDao).insert("g_test", "KEY-1");
        assertThat(listener.consumed).isTrue();
    }

    @Test
    void duplicateRow_skipsConsumeAck() throws Exception {
        when(dedupDao.insert(anyString(), anyString())).thenThrow(new DuplicateKeyException("dup"));

        listener.onMessage(msg("KEY-1", null));

        verify(dedupDao, never()).delete(anyString(), anyString());
        assertThat(listener.consumed).isFalse();
    }

    @Test
    void consumeFailure_deletesRowAndRethrows() throws Exception {
        listener.failWith = new IllegalStateException("system error");

        assertThatThrownBy(() -> listener.onMessage(msg("KEY-1", null)))
                .isInstanceOf(IllegalStateException.class);
        verify(dedupDao).delete("g_test", "KEY-1");
    }

    @Test
    void consumeFailure_deleteAlsoFails_originalExceptionStillPropagates() throws Exception {
        listener.failWith = new IllegalStateException("system error");
        doThrow(new RuntimeException("delete failed")).when(dedupDao).delete(anyString(), anyString());

        assertThatThrownBy(() -> listener.onMessage(msg("KEY-1", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system error");
        verify(dedupDao).delete("g_test", "KEY-1");
    }

    @Test
    void checkedFailure_wrapsAsRuntimeException() throws Exception {
        listener.failWith = new Exception("checked");

        assertThatThrownBy(() -> listener.onMessage(msg("KEY-1", null)))
                .isInstanceOf(RuntimeException.class)
                .hasCause(new Exception("checked"));
        verify(dedupDao).delete("g_test", "KEY-1");
    }

    @Test
    void msgKey_fallsBackToMsgIdWhenKeysMissing() throws Exception {
        MessageExt msg = msg(null, "MSG-ID-9");
        msg.setKeys("");

        listener.onMessage(msg);

        verify(dedupDao).insert("g_test", "MSG-ID-9");
    }

    // ---- 脚手架 ----

    private MessageExt msg(String keys, String msgId) {
        MessageExt msg = new MessageExt();
        msg.setBody("{}".getBytes());
        if (keys != null) {
            msg.setKeys(keys);
        }
        if (msgId != null) {
            msg.setMsgId(msgId);
        }
        return msg;
    }

    /** 测试桩：记录是否消费、可注入失败 */
    static class RecordingListener extends DedupRocketMQListener {

        boolean consumed;
        Exception failWith;

        RecordingListener(ConsumeDedupDao dao) {
            super(dao);
        }

        @Override
        protected String dedupGroup() {
            return "g_test";
        }

        @Override
        protected void doConsume(MessageExt msg) throws Exception {
            if (failWith != null) {
                throw failWith;
            }
            consumed = true;
        }
    }
}
