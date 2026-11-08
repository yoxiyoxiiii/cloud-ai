package com.cloudai.system.job;

import com.cloudai.system.config.CloudSystemNotifyProperties;
import com.cloudai.system.mapper.SysNotifyMessageMapper;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知中心定时清理（通知中心设计 §6；xxl-job admin 任务 id 11、组 3 cloud-system，
 * CRON 每日 03:40 错峰 03:00 MQ 两表清理 / 03:30 死信清理）。两步同一次执行内顺序完成，
 * 不包事务——清理幂等，中断重跑无害：①物理删收件表过期已读行（未读行永不删）；
 * ②物理删无收件行残留的消息主表行（第一步删空收件行的消息随之收敛）。
 * 删除行数 log.info 记档；不抛异常即成功（xxl 缺省成功口径，沿 DlqLogCleanJobHandler）。
 */
@Slf4j
@Component
public class NotifyCleanJobHandler {

    private final SysNotifyUserMessageMapper userMessageMapper;
    private final SysNotifyMessageMapper messageMapper;
    private final CloudSystemNotifyProperties properties;

    public NotifyCleanJobHandler(SysNotifyUserMessageMapper userMessageMapper,
                                 SysNotifyMessageMapper messageMapper,
                                 CloudSystemNotifyProperties properties) {
        this.userMessageMapper = userMessageMapper;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    /** 过期已读收件行 + 孤儿消息主表行两步清理（idx_read_time / uk_message_user 最左前缀命中） */
    @XxlJob("notifyCleanJobHandler")
    public void cleanExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        int receiptRows = userMessageMapper.deleteReadBefore(cutoff);
        int orphanRows = messageMapper.deleteOrphanMessages();
        log.info("notify clean done: cutoff={}, receiptRows={}, orphanMessageRows={}",
                cutoff, receiptRows, orphanRows);
    }
}
