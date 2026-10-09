package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 投影组件自动装配单测（设计 D3 默认关 / 风险 R5）：无配置零装配（提供方 bpmn 引 jar 不误装）/
 * enabled=true 全三件套装配 / 缺 consumerGroup 启动 fail-fast（防 offset 漂移重放）/
 * 用户同名 bean 可覆盖（@ConditionalOnMissingBean 惯例）。
 */
class ApprovalProjectionAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApprovalProjectionAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ConsumeDedupDao.class, () -> mock(ConsumeDedupDao.class))
            .withBean(JsonPayloads.class, () -> new JsonPayloads(new ObjectMapper()))
            .withBean(BpmnApprovalClient.class, () -> mock(BpmnApprovalClient.class));

    @Test
    void disabledByDefault_zeroProjectionBeans() {
        // R5：cloud-bpmn 自身引本 jar（Feign 契约同位）不得装配投影消费——默认关是硬门槛
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ApprovalProjectionDao.class);
            assertThat(context).doesNotHaveBean(ApprovalProjectionListener.class);
            assertThat(context).doesNotHaveBean(ApprovalProjectionReconciler.class);
        });
    }

    @Test
    void enabled_registersAllThreeComponents() {
        runner.withPropertyValues(
                        "cloud.bpmn.projection.enabled=true",
                        "cloud.bpmn.projection.consumer-group=g_system_approval_event")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApprovalProjectionDao.class);
                    assertThat(context).hasSingleBean(ApprovalProjectionListener.class);
                    assertThat(context).hasSingleBean(ApprovalProjectionReconciler.class);
                    assertThat(context).hasSingleBean(ApprovalProjectionProperties.class);
                });
    }

    @Test
    void enabledWithoutConsumerGroup_failsFast() {
        // 消费组名缺配=静默用默认组名 → broker offset 漂移重放历史事件——启动即报错
        runner.withPropertyValues("cloud.bpmn.projection.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("consumer-group");
                });
    }

    @Test
    void userDefinedDaoBeanOverridesDefault() {
        runner.withPropertyValues(
                        "cloud.bpmn.projection.enabled=true",
                        "cloud.bpmn.projection.consumer-group=g_system_approval_event")
                .withUserConfiguration(UserOverrideConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApprovalProjectionDao.class);
                    assertThat(context.getBean(ApprovalProjectionDao.class))
                            .isSameAs(context.getBean(UserOverrideConfig.class).customDao());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserOverrideConfig {

        private final ApprovalProjectionDao customDao = mock(ApprovalProjectionDao.class);

        @Bean
        ApprovalProjectionDao approvalProjectionDao() {
            return customDao;
        }

        ApprovalProjectionDao customDao() {
            return customDao;
        }
    }
}
