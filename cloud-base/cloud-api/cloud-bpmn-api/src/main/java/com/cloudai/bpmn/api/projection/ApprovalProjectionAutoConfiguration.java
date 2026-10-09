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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * 审批投影组件自动装配（设计 D3）：默认关——@ConditionalOnProperty(cloud.bpmn.projection.enabled=true)，
 * 防提供方 cloud-bpmn 自身引本 jar 时误装配（消费自己发的事件写自己库，风险 R5）；
 * 消费方（如 cloud-system）两行配置显式启用即得唯一监听对象 + 定时对账（业务零代码）。
 * <p>@ConditionalOnMissingBean 可覆盖（common starter 惯例对齐）；@EnableScheduling 幂无害
 * （宿主已开则共存）；消费组名必配校验在此处 fail-fast（缺组名启动报错防 offset 漂移重放）。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(CommonRocketMqAutoConfiguration.class)
@EnableConfigurationProperties(ApprovalProjectionProperties.class)
@EnableScheduling
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
}
