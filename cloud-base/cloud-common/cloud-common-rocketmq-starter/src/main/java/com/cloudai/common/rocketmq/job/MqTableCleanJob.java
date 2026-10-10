package com.cloudai.common.rocketmq.job;

import com.cloudai.common.rocketmq.config.CommonRocketMqProperties;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.common.rocketmq.dao.TxLogDao;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * 两表定时清理（2026-10-10 迁移轮起 xxl-job 调度：admin 任务 id 8（组 cloud-system）/ id 9（组 cloud-bpmn）
 * CRON 每日 03:00 0 0 3 * * ?，handler 名两组同名——每实例清自己库的表；clean-cron 配置键退役）：
 * tx_log 保 N 天（回查依据 + 审计）、dedup 保 N 天（幂等窗口）。dedup 表仅事件消费方库存在——
 * 无 Dedup 消费者上下文时跳过该段。保留天数（txlog/dedup retention days）仍走
 * cloud.common.rocketmq.* 配置（Nacos），仅调度节奏治理权移交 admin 控制台。
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

    /** 每日低峰清理（默认 03:00；删除行数 log.info 记档；不抛异常即成功——xxl 缺省成功口径） */
    @XxlJob("mqTableCleanJobHandler")
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
