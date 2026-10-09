package com.cloudai.common.rocketmq.consume;

import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;

/**
 * 消费幂等基类（L1 通用去重表路径，设计 D3）：先插 mq_consume_dedup(consumer_group, msg_key)——
 * DuplicateKey=已消费 ACK 跳过；doConsume 异常 → 删 dedup 行再抛（放行重试——
 * 失败不删行会导致消息被 dedup 吞掉=丢失）。msg_key 优先消息 KEYS（业务键），
 * 缺省退化 msgId（broker 重投保持原 msgId）。
 * <p>走 L2 业务 uk 幂等路径的消费者不继承本类，标注 {@link UkIdempotentListener} 豁免（守护规则）。</p>
 */
@Slf4j
public abstract class DedupRocketMQListener implements RocketMQListener<MessageExt> {

    private final ConsumeDedupDao consumeDedupDao;

    protected DedupRocketMQListener(ConsumeDedupDao consumeDedupDao) {
        this.consumeDedupDao = consumeDedupDao;
    }

    /** 消费组名（与 @RocketMQMessageListener consumerGroup 一致，dedup 表分组键） */
    protected abstract String dedupGroup();

    /** 业务消费逻辑（异常=系统态，删 dedup 行后抛出走重试 ×3 → 死信） */
    protected abstract void doConsume(MessageExt msg) throws Exception;

    @Override
    public void onMessage(MessageExt msg) {
        String msgKey = resolveMsgKey(msg);
        try {
            consumeDedupDao.insert(dedupGroup(), msgKey);
        } catch (DuplicateKeyException e) {
            log.info("重复消息幂等跳过: group={}, key={}", dedupGroup(), msgKey);
            return;
        }
        try {
            doConsume(msg);
        } catch (Exception e) {
            // 失败删行放行重试：行残留会吞掉后续重投（消息丢失），删失败仅记档（重投被吞转人工死信口径）
            try {
                consumeDedupDao.delete(dedupGroup(), msgKey);
            } catch (Exception deleteEx) {
                log.error("去重行删除失败（后续重投将被吞，需人工处理）: group={}, key={}",
                        dedupGroup(), msgKey, deleteEx);
            }
            throw asRuntimeException(e);
        }
    }

    /** KEYS 业务键优先（我们自设可控），缺省退化 msgId（broker 重投不变） */
    private String resolveMsgKey(MessageExt msg) {
        String keys = msg.getKeys();
        if (keys != null && !keys.isBlank()) {
            return keys;
        }
        return msg.getMsgId();
    }

    private RuntimeException asRuntimeException(Throwable error) {
        return error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
    }
}
