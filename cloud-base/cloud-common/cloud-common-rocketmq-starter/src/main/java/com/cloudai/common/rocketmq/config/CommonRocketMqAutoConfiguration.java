package com.cloudai.common.rocketmq.config;

import com.cloudai.common.rocketmq.consume.DedupRocketMQListener;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.common.rocketmq.dao.TxLogDao;
import com.cloudai.common.rocketmq.job.MqTableCleanJob;
import com.cloudai.common.rocketmq.tx.RocketMqTxListener;
import com.cloudai.common.rocketmq.tx.TxExecutorRegistry;
import com.cloudai.common.rocketmq.tx.TxLocalExecutor;
import com.cloudai.common.rocketmq.tx.TxMessageSender;
import com.cloudai.common.rocketmq.tx.TxMessageSenderImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * RocketMQ 封装组件自动装配（惯例对齐既有 common starter：@ConditionalOnMissingBean 可覆盖）。
 * 发送门面/事务监听仅在 RocketMQTemplate 存在（rocketmq.name-server + producer.group 已配）时装配；
 * DAO/清理任务仅需 JdbcTemplate（两表 DDL 由 scripts/sql 落库）；dedup 清理仅事件消费方（存在
 * DedupRocketMQListener bean）执行——bpmn 库无 mq_consume_dedup 表。
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"})
@EnableConfigurationProperties(CommonRocketMqProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "cloud.common.rocketmq", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class CommonRocketMqAutoConfiguration {

    /** 消息体序列化（复用全局 ObjectMapper 口径：core-starter Jackson 定制） */
    @Bean
    @ConditionalOnMissingBean
    public JsonPayloads jsonPayloads(ObjectMapper objectMapper) {
        return new JsonPayloads(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TxLogDao txLogDao(JdbcTemplate jdbcTemplate) {
        return new TxLogDao(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumeDedupDao consumeDedupDao(JdbcTemplate jdbcTemplate) {
        return new ConsumeDedupDao(jdbcTemplate);
    }

    /** channel → executor 路由（自动收集宿主全部 TxLocalExecutor bean，无 executor 时空表；channel 重复启动即败） */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TxExecutorRegistry txExecutorRegistry(ObjectProvider<TxLocalExecutor> executors) {
        return new TxExecutorRegistry((List) executors.stream().toList());
    }

    /** 事务消息发送门面（需默认 rocketMQTemplate——name-server/producer.group 未配时整体退装配） */
    @Bean
    @ConditionalOnMissingBean(TxMessageSender.class)
    @ConditionalOnBean(RocketMQTemplate.class)
    public TxMessageSender txMessageSender(RocketMQTemplate rocketMQTemplate, JsonPayloads jsonPayloads) {
        return new TxMessageSenderImpl(rocketMQTemplate, jsonPayloads);
    }

    /** 统一事务监听（@RocketMQTransactionListener 由 rocketmq-spring 扫描绑定默认 template） */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RocketMQTemplate.class)
    public RocketMqTxListener rocketMqTxListener(TxExecutorRegistry registry, TxLogDao txLogDao,
                                                 JsonPayloads jsonPayloads,
                                                 PlatformTransactionManager transactionManager) {
        return new RocketMqTxListener(registry, txLogDao, jsonPayloads, transactionManager);
    }

    /** 两表清理（dedup 段仅事件消费方执行：无 DedupRocketMQListener bean 时传 null 跳过） */
    @Bean
    @ConditionalOnMissingBean
    public MqTableCleanJob mqTableCleanJob(TxLogDao txLogDao, ConsumeDedupDao consumeDedupDao,
                                           CommonRocketMqProperties properties,
                                           ObjectProvider<DedupRocketMQListener> dedupListeners) {
        ConsumeDedupDao effective = dedupListeners.getIfAvailable() == null ? null : consumeDedupDao;
        return new MqTableCleanJob(txLogDao, effective, properties);
    }
}
