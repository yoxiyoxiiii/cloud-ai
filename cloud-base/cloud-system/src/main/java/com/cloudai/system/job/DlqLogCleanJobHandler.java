package com.cloudai.system.job;

import com.cloudai.system.config.CloudSystemMqDlqProperties;
import com.cloudai.system.mapper.MqDlqLogMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 死信台账定时清理（设计 D10；xxl-job admin 任务 id 10，组 3 cloud-system，
 * CRON 每日 03:30 错峰 03:00 两表清理）：终态行（已重投/已忽略/已放弃）保留 N 天
 * （cloud.system.mq-dlq.retention-days，默认 30 对齐 mq_tx_log 先例）后物理删；
 * 待处理行（0）永不清——未处置死信被清=丢告警。删除行数 log.info 记档
 * （沿 MqTableCleanJob 口径）；不抛异常即成功（xxl 缺省成功口径）。
 * 不并入 starter 的 mqTableCleanJobHandler：mq_dlq_log 是 system 业务表，
 * starter（通用组件）不得感知业务表（分层，设计 D10）。
 */
@Slf4j
@Component
public class DlqLogCleanJobHandler {

    private final MqDlqLogMapper dlqLogMapper;
    private final CloudSystemMqDlqProperties properties;

    public DlqLogCleanJobHandler(MqDlqLogMapper dlqLogMapper, CloudSystemMqDlqProperties properties) {
        this.dlqLogMapper = dlqLogMapper;
        this.properties = properties;
    }

    /** 终态行过期清理（idx_status_dlq_time 命中：status IN (1,2,3) AND dlq_time &lt; cutoff） */
    @XxlJob("dlqLogCleanJobHandler")
    public void cleanExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        int rows = dlqLogMapper.deleteExpiredDlqLog(cutoff);
        log.info("mq_dlq_log 清理完成: cutoff={}, rows={}", cutoff, rows);
    }
}
