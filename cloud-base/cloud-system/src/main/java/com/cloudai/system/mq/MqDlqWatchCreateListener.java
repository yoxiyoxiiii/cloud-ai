package com.cloudai.system.mq;

import com.cloudai.common.rocketmq.consume.UkIdempotentListener;
import com.cloudai.system.config.CloudSystemMqDlqProperties;
import com.cloudai.system.service.MqDlqLogService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.stereotype.Component;

/**
 * create 流死信 watch 薄壳（设计 D3 清单行 1）：订阅 %DLQ%g_bpmn_approval_create
 * （bpmn 发起审批消费组的死信）实时落 mq_dlq_log。topic/consumerGroup 走 yml placeholder 配置
 * （cloud.system.mq-dlq.watch.create.*），watchGroup 与注解同一配置键解析（防两处漂移）。
 * maxReconsumeTimes=3：落库失败重试 3 次后进 %DLQ%g_system_dlq_watch_create（递归死信，
 * Dashboard 人工兜底）；messageModel=CLUSTERING、consumeMode=CONCURRENTLY（注解默认）。
 */
@Component
@UkIdempotentListener("uk_msg_id(mq_dlq_log)：watch 重复投递幂等吸收（msgId 全局唯一）")
@RocketMQMessageListener(topic = "${cloud.system.mq-dlq.watch.create.dlq-topic}",
        consumerGroup = "${cloud.system.mq-dlq.watch.create.group}", maxReconsumeTimes = 3)
public class MqDlqWatchCreateListener extends AbstractMqDlqWatchListener {

    private final CloudSystemMqDlqProperties properties;

    public MqDlqWatchCreateListener(MqDlqLogService dlqLogService, CloudSystemMqDlqProperties properties) {
        super(dlqLogService);
        this.properties = properties;
    }

    @Override
    protected String watchGroup() {
        return properties.getWatch().getCreate().getGroup();
    }
}
