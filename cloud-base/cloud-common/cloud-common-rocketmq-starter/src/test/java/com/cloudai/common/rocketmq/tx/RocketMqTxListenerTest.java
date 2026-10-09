package com.cloudai.common.rocketmq.tx;

import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.TxLogDao;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事务监听单测（设计 D2 三分支）：executeLocalTransaction COMMIT/ROLLBACK/未注册通道 +
 * checkLocalTransaction 行在/行无/查询异常/缺 TX_NO 头。
 * TransactionTemplate 配 stub 事务管理器（单测只验编排：业务写 + tx_log 同回调、异常回滚路径）。
 */
@ExtendWith(MockitoExtension.class)
class RocketMqTxListenerTest {

    @Mock
    private TxLogDao txLogDao;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RocketMqTxListener listener;
    private JsonPayloads jsonPayloads;

    @BeforeEach
    void setUp() {
        jsonPayloads = new JsonPayloads(new ObjectMapper());
        TxExecutorRegistry registry = new TxExecutorRegistry(
                List.<TxLocalExecutor<?>>of(new SampleExecutor(), new ThrowingExecutor()));
        listener = new RocketMqTxListener(registry, txLogDao, jsonPayloads, transactionManager);
    }

    // ---- executeLocalTransaction ----

    @Test
    void execute_commitPath_runsBusinessWriteAndTxLogInSameCallback() {
        TxSendCommand command = command("sample-channel");
        Message<byte[]> msg = message("{\"name\":\"leave\"}");

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(msg, command);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        assertThat(command.getResult()).isEqualTo("sample:leave");
        // tx_log 落档参数：txNo/业务回执（executor ctx.setBusinessRef）齐全
        verify(txLogDao).insert(eq("tx-no-1"), eq("TX_TOPIC"), eq("sample-channel"),
                eq("leave"), eq("77"), eq("sample:leave"));
    }

    @Test
    void execute_businessFailure_rollsBackAndFillsError() {
        TxSendCommand command = command("throwing-channel");
        Message<byte[]> msg = message("{}");

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(msg, command);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        assertThat(command.getError()).isInstanceOf(IllegalStateException.class);
        verify(txLogDao, org.mockito.Mockito.never()).insert(anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void execute_unknownChannel_rollsBack() {
        TxSendCommand command = command("no-such-channel");
        Message<byte[]> msg = message("{}");

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(msg, command);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        assertThat(command.getError()).hasMessageContaining("channel not registered");
    }

    // ---- checkLocalTransaction（回查三分支 + 缺头防御） ----

    @Test
    void check_rowExists_commits() {
        when(txLogDao.existsByTxNo("tx-no-1")).thenReturn(true);
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(checkMessage("tx-no-1"));
        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    @Test
    void check_rowAbsent_rollbacks() {
        when(txLogDao.existsByTxNo("tx-no-1")).thenReturn(false);
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(checkMessage("tx-no-1"));
        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void check_queryFailure_returnsUnknown() {
        when(txLogDao.existsByTxNo("tx-no-1")).thenThrow(new RuntimeException("db down"));
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(checkMessage("tx-no-1"));
        assertThat(state).isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    @Test
    void check_missingTxNoHeader_returnsUnknown() {
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8)).build());
        assertThat(state).isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    // ---- 脚手架 ----

    private TxSendCommand command(String channel) {
        return new TxSendCommand("tx-no-1", "TX_TOPIC", "TAG_A", "leave:77", channel, Map.of("k", "v"));
    }

    private Message<byte[]> message(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader(TxMessageSenderImpl.HEADER_TX_NO, "tx-no-1")
                .build();
    }

    private Message<byte[]> checkMessage(String txNo) {
        return MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8))
                .setHeader(TxMessageSenderImpl.HEADER_TX_NO, txNo)
                .build();
    }

    /** 样例 executor：回写 businessRef 并返回摘要（COMMIT 路径） */
    static class SampleExecutor implements TxLocalExecutor<SamplePayload> {

        @Override
        public String channel() {
            return "sample-channel";
        }

        @Override
        public Class<SamplePayload> payloadType() {
            return SamplePayload.class;
        }

        @Override
        public Object executeInTx(SamplePayload payload, TxContext ctx) {
            ctx.setBusinessRef("leave", "77");
            return "sample:" + payload.name;
        }
    }

    /** 失败 executor：业务写抛异常（ROLLBACK 路径） */
    static class ThrowingExecutor implements TxLocalExecutor<SamplePayload> {

        @Override
        public String channel() {
            return "throwing-channel";
        }

        @Override
        public Class<SamplePayload> payloadType() {
            return SamplePayload.class;
        }

        @Override
        public Object executeInTx(SamplePayload payload, TxContext ctx) {
            throw new IllegalStateException("business write failed");
        }
    }

    public static class SamplePayload {
        public String name;
    }
}
