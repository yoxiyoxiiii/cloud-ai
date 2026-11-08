package com.cloudai.system.mq;

import com.cloudai.common.rocketmq.consume.UkIdempotentListener;
import com.cloudai.system.config.CloudSystemMqDlqProperties;
import com.cloudai.system.service.MqDlqLogService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.stereotype.Component;

/**
 * 审批任务通知流死信 watch 薄壳（契约 2026-11-08-approval-notify-mq-api §1.5）：
 * 订阅 %DLQ%g_system_notify（审批站内信消费组的死信）实时落 mq_dlq_log。
 * topic/consumerGroup 走 yml placeholder 配置（cloud.system.mq-dlq.watch.notify.*），
 * watchGroup 与注解同一配置键解析（防两处漂移）；maxReconsumeTimes=3：落库失败重试 3 次后进
 * %DLQ%g_system_dlq_watch_notify（递归死信，Dashboard 人工兜底）。
 * 消费侧确定性失败已在 ApprovalNotifyConsumer 内 ACK——进入本死信面的均为系统态（DB/网络/契约违例）。
 */
@Component
@UkIdempotentListener("uk_msg_id(mq_dlq_log)：watch 重复投递幂等吸收（msgId 全局唯一）")
@RocketMQMessageListener(topic = "${cloud.system.mq-dlq.watch.notify.dlq-topic}",
        consumerGroup = "${cloud.system.mq-dlq.watch.notify.group}", maxReconsumeTimes = 3)
public class MqDlqWatchNotifyListener extends AbstractMqDlqWatchListener {

    private final CloudSystemMqDlqProperties properties;

    public MqDlqWatchNotifyListener(MqDlqLogService dlqLogService, CloudSystemMqDlqProperties properties) {
        super(dlqLogService);
        this.properties = properties;
    }

    @Override
    protected String watchGroup() {
        return properties.getWatch().getNotify().getGroup();
    }
}
