package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.common.rocketmq.config.CommonRocketMqAutoConfiguration;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

/**
 * 审批投影组件自动装配（设计 D3）：默认关——@ConditionalOnProperty(cloud.bpmn.projection.enabled=true)，
 * 防提供方 cloud-bpmn 自身引本 jar 时误装配（消费自己发的事件写自己库，风险 R5）；
 * 消费方（如 cloud-system）两行配置显式启用即得唯一监听对象 + 定时对账（业务零代码）。
 * <p>@ConditionalOnMissingBean 可覆盖（common starter 惯例对齐）；消费组名必配校验在此处
 * fail-fast（缺组名启动报错防 offset 漂移重放）。@EnableScheduling 已摘（2026-10-10 对账迁移轮）：
 * projection 包已无 @Scheduled 方法（定时对账走 xxl-job CRON 薄壳 handler），
 * 调度保障面由 cloud-common-rocketmq-starter 的 CommonRocketMqAutoConfiguration @EnableScheduling
 * 自持（默认开，保障 MqTableCleanJob——本模块 pom 已依赖该 starter，引 jar 即生效）。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(CommonRocketMqAutoConfiguration.class)
@EnableConfigurationProperties(ApprovalProjectionProperties.class)
@ConditionalOnProperty(name = "cloud.bpmn.projection.enabled", havingValue = "true")
public class ApprovalProjectionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApprovalProjectionDao approvalProjectionDao(JdbcTemplate jdbcTemplate) {
        return new ApprovalProjectionDao(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalProjectionListener approvalProjectionListener(ConsumeDedupDao consumeDedupDao,
                                                                 JsonPayloads jsonPayloads,
                                                                 ApprovalProjectionDao projectionDao,
                                                                 ApprovalProjectionProperties properties) {
        if (!StringUtils.hasText(properties.getConsumerGroup())) {
            throw new IllegalStateException(
                    "cloud.bpmn.projection.consumer-group 必配（消费组名沿用防 broker offset 漂移重放）");
        }
        return new ApprovalProjectionListener(consumeDedupDao, jsonPayloads, projectionDao, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalProjectionReconciler approvalProjectionReconciler(ApprovalProjectionDao projectionDao,
                                                                     BpmnApprovalClient approvalClient,
                                                                     ApprovalProjectionProperties properties) {
        return new ApprovalProjectionReconciler(projectionDao, approvalClient, properties);
    }

    /** xxl-job 对账薄壳（2026-10-10 对账迁移轮：admin 任务 id 7 CRON 每分钟 0 * * * * ? 调度 → reconcileActive） */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalProjectionReconcileJobHandler approvalProjectionReconcileJobHandler(ApprovalProjectionReconciler reconciler) {
        return new ApprovalProjectionReconcileJobHandler(reconciler);
    }
}
