package com.cloudai.common.rocketmq.tx;

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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 事务消息发送门面单测：destination 拼接（tag 有/无）、消息头 TX_NO/KEYS、body 全局口径 JSON、
 * arg 为 TxSendCommand（listener 回填通道）、半消息失败 TxMessageSendException、本地事务失败原样上抛。
 */
@ExtendWith(MockitoExtension.class)
class TxMessageSenderImplTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private TxMessageSenderImpl sender;
    private JsonPayloads jsonPayloads;

    @BeforeEach
    void setUp() {
        jsonPayloads = new JsonPayloads(new ObjectMapper());
        sender = new TxMessageSenderImpl(rocketMQTemplate, jsonPayloads);
    }

    @Test
    void send_commitPath_returnsTxNoAndExecutorResult() {
        stubListenerResult(77L);

        TxSendResult result = sender.sendTransactional("TX_TOPIC", "TAG_A", "leave:77",
                Map.of("name", "leave"), "leave-create", Map.of("operator", "admin"));

        assertThat(result.getTxNo()).matches("[0-9a-f]{32}");
        assertThat(result.getResult()).isEqualTo(77L);
        verify(rocketMQTemplate).sendMessageInTransaction(eq("TX_TOPIC:TAG_A"), any(Message.class), any());
    }

    @Test
    void send_blankTag_destinationIsBareTopic() {
        stubListenerResult(null);

        sender.sendTransactional("TX_TOPIC", " ", "k", Map.of(), "ch", null);

        verify(rocketMQTemplate).sendMessageInTransaction(eq("TX_TOPIC"), any(Message.class), any());
    }

    @Test
    void send_carriesTxNoKeysHeadersAndJsonBodyAndCommandArg() {
        stubListenerResult(null);

        sender.sendTransactional("TX_TOPIC", "TAG_A", "leave:77", Map.of("name", "leave"), "leave-create", "biz");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rocketMQTemplate).sendMessageInTransaction(anyString(), messageCaptor.capture(), argCaptor.capture());

        Message<?> message = messageCaptor.getValue();
        assertThat(message.getHeaders().get(TxMessageSenderImpl.HEADER_TX_NO))
                .isEqualTo(argCaptor.getValue() instanceof TxSendCommand command ? command.getTxNo() : null);
        assertThat(message.getHeaders().get(TxMessageSenderImpl.HEADER_KEYS)).isEqualTo("leave:77");
        assertThat((byte[]) message.getPayload())
                .isEqualTo("{\"name\":\"leave\"}".getBytes(StandardCharsets.UTF_8));

        TxSendCommand command = (TxSendCommand) argCaptor.getValue();
        assertThat(command.getTopic()).isEqualTo("TX_TOPIC");
        assertThat(command.getTag()).isEqualTo("TAG_A");
        assertThat(command.getKeys()).isEqualTo("leave:77");
        assertThat(command.getChannel()).isEqualTo("leave-create");
        assertThat(command.getBizArg()).isEqualTo("biz");
        assertThat(command.getTxNo()).matches("[0-9a-f]{32}");
    }

    @Test
    void send_halfMessageFailure_throwsTxMessageSendException() {
        doThrow(new RuntimeException("broker unreachable"))
                .when(rocketMQTemplate).sendMessageInTransaction(anyString(), any(Message.class), any());

        assertThatThrownBy(() -> sender.sendTransactional("TX_TOPIC", null, "k", Map.of(), "ch", null))
                .isInstanceOf(TxMessageSendException.class)
                .hasMessageContaining("TX_TOPIC");
    }

    @Test
    void send_localTxFailure_runtimeErrorRethrownAsIs() {
        stubListenerError(new IllegalStateException("business failed"));

        assertThatThrownBy(() -> sender.sendTransactional("TX_TOPIC", null, "k", Map.of(), "ch", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("business failed");
    }

    @Test
    void send_localTxFailure_checkedErrorWrapped() {
        stubListenerError(new Exception("checked"));

        assertThatThrownBy(() -> sender.sendTransactional("TX_TOPIC", null, "k", Map.of(), "ch", null))
                .isInstanceOf(RuntimeException.class)
                .hasCause(new Exception("checked"));
    }

    // ---- 脚手架 ----

    /** 模拟 listener 成功路径：捕获 arg 并回填 result */
    private void stubListenerResult(Object value) {
        doAnswer(invocation -> {
            TxSendCommand command = invocation.getArgument(2);
            command.fillResult(value);
            return null;
        }).when(rocketMQTemplate).sendMessageInTransaction(anyString(), any(Message.class), any());
    }

    /** 模拟 listener 失败路径：捕获 arg 并回填 error */
    private void stubListenerError(Throwable error) {
        doAnswer(invocation -> {
            TxSendCommand command = invocation.getArgument(2);
            command.fillError(error);
            return null;
        }).when(rocketMQTemplate).sendMessageInTransaction(anyString(), any(Message.class), any());
    }
}
