package com.cloudai.common.rocketmq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 封装组件属性（前缀 cloud.common.rocketmq.*，设计 D1）。
 * name-server 与 producer.group 属环境差异配置，走各服务 rocketmq.* 原生属性（Nacos）；
 * 本前缀只管封装组件自身行为（开关 + 两表清理保留期；调度节奏自 2026-10-10 迁移轮起归 xxl-job admin）。
 */
@Data
@ConfigurationProperties(prefix = "cloud.common.rocketmq")
public class CommonRocketMqProperties {

    /** 组件总开关（false 时发送门面/事务监听/清理任务全部退装配） */
    private boolean enabled = true;

    /** mq_consume_dedup 去重行保留天数（消费幂等窗口，默认 7 天清理） */
    private int dedupRetentionDays = 7;

    /** mq_tx_log 流水保留天数（回查依据 + 审计，默认 30 天清理） */
    private int txlogRetentionDays = 30;
}
