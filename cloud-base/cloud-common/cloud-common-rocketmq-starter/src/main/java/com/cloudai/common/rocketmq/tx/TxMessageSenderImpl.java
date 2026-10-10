package com.cloudai.common.rocketmq.tx;

import com.cloudai.common.rocketmq.consume.JsonPayloads;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;

/**
 * 事务消息发送实现（绑定默认 rocketMQTemplate——单 listener 单 template 规避多绑定问题，设计 R3）。
 * 消息体按全局 Jackson 口径序列化为 byte[]（Long→String 等契约口径由发送端锁定，
 * 不经 rocketmq-spring 自带 converter 的独立 ObjectMapper）；TX_NO/KEYS 走 Spring header →
 * rocketmq-spring 映射为 userProperty/消息键。
 */
@Slf4j
@RequiredArgsConstructor
public class TxMessageSenderImpl implements TxMessageSender {

    /** userProperty 键：事务消息流水号（发送前预生成 UUID，回查唯一依据，设计 D2） */
    public static final String HEADER_TX_NO = "TX_NO";
    /** 消息键头（rocketmq-spring 标准映射：KEYS header → 消息 keys） */
    public static final String HEADER_KEYS = "KEYS";

    private final RocketMQTemplate rocketMQTemplate;
    private final JsonPayloads jsonPayloads;

    @Override
    public TxSendResult sendTransactional(String topic, String tag, String keys, Object payload,
                                          String channel, Object bizArg) {
        String txNo = UUID.randomUUID().toString().replace("-", "");
        TxSendCommand command = new TxSendCommand(txNo, topic, tag, keys, channel, bizArg);
        byte[] body = jsonPayloads.toBytes(payload);
        Message<byte[]> message = MessageBuilder.withPayload(body)
                .setHeader(HEADER_TX_NO, txNo)
                .setHeader(HEADER_KEYS, keys)
                .build();
        String destination = tag == null || tag.isBlank() ? topic : topic + ":" + tag;
        try {
            // 发送，半事务消息；@see RocketMqTxListener
            rocketMQTemplate.sendMessageInTransaction(destination, message, command);
        } catch (Exception e) {
            // 半消息发送失败：本地事务未执行（executor 零写），调用方转译域码（如 3025）
            log.error("事务半消息发送失败: destination={}, channel={}", destination, channel, e);
            throw new TxMessageSendException("事务半消息发送失败: " + destination, e);
        }
        if (command.getError() != null) {
            // 本地事务失败：原样上抛根因（系统错误由全局异常处理兜底，本地业务零写）
            throw asRuntimeException(command.getError());
        }
        return new TxSendResult(txNo, command.getResult());
    }

    private RuntimeException asRuntimeException(Throwable error) {
        return error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
    }
}
