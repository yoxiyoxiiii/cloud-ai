package com.cloudai.common.rocketmq.job;

import com.cloudai.common.rocketmq.config.CommonRocketMqProperties;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.common.rocketmq.dao.TxLogDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/**
 * 两表定时清理（@Scheduled cron 可配，设计 D1）：tx_log 保 N 天（回查依据 + 审计）、
 * dedup 保 N 天（幂等窗口）。dedup 表仅事件消费方库存在——无 Dedup 消费者上下文时跳过该段。
 */
@Slf4j
public class MqTableCleanJob {

    private final TxLogDao txLogDao;
    private final ConsumeDedupDao consumeDedupDao;
    private final CommonRocketMqProperties properties;

    public MqTableCleanJob(TxLogDao txLogDao, ConsumeDedupDao consumeDedupDao,
                           CommonRocketMqProperties properties) {
        this.txLogDao = txLogDao;
        this.consumeDedupDao = consumeDedupDao;
        this.properties = properties;
    }

    /** 每日低峰清理（默认 03:00；删除行数 log.info 记档） */
    @Scheduled(cron = "${cloud.common.rocketmq.clean-cron:0 0 3 * * ?}")
    public void cleanExpired() {
        LocalDateTime now = LocalDateTime.now();
        int txRows = txLogDao.deleteBefore(now.minusDays(properties.getTxlogRetentionDays()));
        log.info("mq_tx_log 清理完成: cutoff={}, rows={}", now.minusDays(properties.getTxlogRetentionDays()), txRows);
        if (consumeDedupDao != null) {
            int dedupRows = consumeDedupDao.deleteBefore(now.minusDays(properties.getDedupRetentionDays()));
            log.info("mq_consume_dedup 清理完成: cutoff={}, rows={}",
                    now.minusDays(properties.getDedupRetentionDays()), dedupRows);
        }
    }
}
