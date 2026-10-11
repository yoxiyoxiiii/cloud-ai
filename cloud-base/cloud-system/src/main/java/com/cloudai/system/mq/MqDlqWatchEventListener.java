package com.cloudai.system.mq;

import com.cloudai.common.rocketmq.consume.UkIdempotentListener;
import com.cloudai.system.config.CloudSystemMqDlqProperties;
import com.cloudai.system.service.MqDlqLogService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.stereotype.Component;

/**
 * event 流死信 watch 薄壳（设计 D3 清单行 2）：订阅 %DLQ%g_system_approval_event
 * （system 投影事件消费组的死信）实时落 mq_dlq_log。topic/consumerGroup 走 yml placeholder 配置
 * （cloud.system.mq-dlq.watch.event.*），watchGroup 与注解同一配置键解析（防两处漂移）。
 * maxReconsumeTimes=3：落库失败重试 3 次后进 %DLQ%g_system_dlq_watch_event（递归死信，
 * Dashboard 人工兜底）；TERMINAL 类死信对账自愈兜底，CREATE_RESULT 类死信无兜底（设计 §1.1）。
 */
@Component
@UkIdempotentListener("uk_msg_id(mq_dlq_log)：watch 重复投递幂等吸收（msgId 全局唯一）")
@RocketMQMessageListener(topic = "${cloud.system.mq-dlq.watch.event.dlq-topic}",
        consumerGroup = "${cloud.system.mq-dlq.watch.event.group}", maxReconsumeTimes = 3)
public class MqDlqWatchEventListener extends AbstractMqDlqWatchListener {

    private final CloudSystemMqDlqProperties properties;

    public MqDlqWatchEventListener(MqDlqLogService dlqLogService, CloudSystemMqDlqProperties properties) {
        super(dlqLogService);
        this.properties = properties;
    }

    @Override
    protected String watchGroup() {
        return properties.getWatch().getEvent().getGroup();
    }
}
