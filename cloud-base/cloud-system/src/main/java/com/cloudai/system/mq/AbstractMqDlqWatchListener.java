package com.cloudai.system.mq;

import com.cloudai.common.rocketmq.tx.TxMessageSenderImpl;
import com.cloudai.system.entity.MqDlqLog;
import com.cloudai.system.service.MqDlqLogService;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQListener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * 死信 watch 监听基类（设计 D3）：消费 %DLQ% 系统主题实时落 mq_dlq_log（uk_msg_id 幂等——
 * broker 重投 DuplicateKey 由 Service 吸收 ACK）。子类为持有 RocketMQMessageListener 注解的
 * 薄壳（topic/consumerGroup 走 yml placeholder 配置），watchGroup 与注解同属性源解析防两处漂移。
 * <p>落库失败（DB 挂）原样抛出走重试 ×3 → %DLQ%g_system_dlq_watch_*（递归死信，Dashboard 人工
 * 兜底——Q5 口径：不递归监听自己的死信，终止递归）；消费起点 CONSUME_FROM_LAST_OFFSET 默认，
 * 上线前历史死信不回放（设计 D4）。</p>
 */
public abstract class AbstractMqDlqWatchListener implements RocketMQListener<MessageExt> {

    private final MqDlqLogService dlqLogService;

    protected AbstractMqDlqWatchListener(MqDlqLogService dlqLogService) {
        this.dlqLogService = dlqLogService;
    }

    /** watch 消费组名（与薄壳注解的 consumerGroup 占位符同一配置键解析，防漂移） */
    protected abstract String watchGroup();

    @Override
    public void onMessage(MessageExt msg) {
        dlqLogService.saveWatched(buildLog(msg, watchGroup()));
    }

    /**
     * MessageExt → 台账实体组装（字段口径见设计 D5）：
     * born_topic 取 RETRY_TOPIC userProperty（DLQ 消息的 topic 已被 broker 改写为 %DLQ%组名，
     * 原 topic 在重试链属性中保留；缺省退化当前 topic 防御）；born_group 由 DLQ topic 名 strip
     * %DLQ% 前缀派生（设计 D3：不重复配置，减漂移面）；msg_key 与 DedupRocketMQListener.resolveMsgKey
     * 同口径（KEYS 优先、缺省退化 msgId——与 L1 dedup 表行天然对齐）。
     */
    static MqDlqLog buildLog(MessageExt msg, String watchGroup) {
        MqDlqLog log = new MqDlqLog();
        log.setMsgId(msg.getMsgId());
        log.setTxNo(msg.getProperty(TxMessageSenderImpl.HEADER_TX_NO));
        log.setBornTopic(resolveBornTopic(msg));
        log.setBornGroup(resolveBornGroup(msg));
        log.setWatchGroup(watchGroup);
        log.setTag(msg.getTags());
        log.setMsgKey(resolveMsgKey(msg));
        log.setBody(decodeBody(msg.getBody()));
        log.setReconsumeTimes(msg.getReconsumeTimes());
        log.setBornTime(toLocalDateTime(msg.getBornTimestamp()));
        log.setDlqTime(toLocalDateTime(msg.getStoreTimestamp()));
        log.setStatus(MqDlqLog.StatusEnum.PENDING.getCode());
        log.setResendCount(0);
        return log;
    }

    /** 原 topic：RETRY_TOPIC 属性（broker 写 %RETRY%/%DLQ% 时保留的原 topic），缺省退化当前 topic */
    private static String resolveBornTopic(MessageExt msg) {
        String retryTopic = msg.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
        if (retryTopic != null && !retryTopic.isBlank()) {
            return retryTopic;
        }
        return msg.getTopic();
    }

    /** 死信来源组：DLQ topic 名 strip %DLQ% 前缀（watch 只订 %DLQ% 主题，前缀必在；防御退化全名） */
    private static String resolveBornGroup(MessageExt msg) {
        String topic = msg.getTopic();
        if (topic.startsWith(MixAll.DLQ_GROUP_TOPIC_PREFIX)) {
            return topic.substring(MixAll.DLQ_GROUP_TOPIC_PREFIX.length());
        }
        return topic;
    }

    /** KEYS 业务键优先，缺省退化 msgId（broker 重投保持原 msgId）——与 L1 dedup 行键同口径 */
    private static String resolveMsgKey(MessageExt msg) {
        String keys = msg.getKeys();
        if (keys != null && !keys.isBlank()) {
            return keys;
        }
        return msg.getMsgId();
    }

    /** UTF-8 解码；超 60000 字节截断并加尾标记（截断后重投 body 不完整属极端防御场景，设计 R4） */
    private static String decodeBody(byte[] body) {
        if (body == null) {
            return null;
        }
        if (body.length <= MqDlqLog.BODY_MAX_BYTES) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(Arrays.copyOf(body, MqDlqLog.BODY_MAX_BYTES), StandardCharsets.UTF_8)
                + MqDlqLog.TRUNCATED_TAIL;
    }

    private static LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }
}
