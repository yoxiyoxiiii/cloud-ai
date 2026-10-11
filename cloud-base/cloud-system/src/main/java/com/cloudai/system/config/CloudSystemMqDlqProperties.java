package com.cloudai.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQ 死信治理配置（设计 D11：架构口径非密钥，仓库化默认值、Nacos 可覆盖；
 * 规范 6 同前缀多值对象封装，禁散装 @Value）。
 * <p>watch 组名全新拟定（g_system_dlq_watch_*），禁与业务消费组重名——同 group 订阅关系不一致
 * 是 RocketMQ 明令禁止的 rebalance 事故源；DLQ topic 名与来源业务组名的对应关系需手工同步
 * （设计 D11 记档评审规则：新增消费组接入死信面时，watch 壳 + 本配置两处同 PR 变更）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloud.system.mq-dlq")
public class CloudSystemMqDlqProperties {

    /** watch 监听清单（create/event 两组薄壳各持一 endpoint；扩展=新增薄壳 + 配置段） */
    private Watch watch = new Watch();

    /** 单行重投上限（第 resend-max+1 次尝试自动置已放弃，错误码 3041） */
    private int resendMax = 5;

    /** 终态行清理保留天数（对齐 mq_tx_log 先例；待处理行永不清） */
    private int retentionDays = 30;

    /** watch 监听组配置 */
    @Data
    public static class Watch {

        /** create 流死信监听（来源组 g_bpmn_approval_create） */
        private Endpoint create = new Endpoint();

        /** event 流死信监听（来源组 g_system_approval_event） */
        private Endpoint event = new Endpoint();
    }

    /** 单个 watch endpoint：DLQ topic + watch 消费组 */
    @Data
    public static class Endpoint {

        /** watch 订阅的死信主题（%DLQ% 前缀 + 来源业务组名，手工同步） */
        private String dlqTopic;

        /** watch 消费组（全新拟定，禁与业务消费组重名） */
        private String group;
    }
}
