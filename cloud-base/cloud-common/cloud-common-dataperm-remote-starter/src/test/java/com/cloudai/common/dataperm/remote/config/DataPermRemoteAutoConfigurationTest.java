package com.cloudai.common.dataperm.remote.config;

import com.cloudai.common.dataperm.evaluate.DataPermEvaluation;
import com.cloudai.common.dataperm.evaluate.DataPermEvaluationProvider;
import com.cloudai.common.dataperm.remote.RemoteDataPermEvaluationProvider;
import com.cloudai.system.api.client.DataPermClient;
import feign.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 远端求值自动装配条件单测（ApplicationContextRunner）：
 * ① 无本地后端 → 程序式 client + 远端 Provider 装配（真实 FeignClientBuilder 构建，不经 @EnableFeignClients）；
 * ② 本地求值 bean 存在 → 远端 Provider 让位（@ConditionalOnMissingBean 本地优先锁死），client 照常装配；
 * ③ 消费方已 @EnableFeignClients 注册 DataPermClient → 程序式构建让位（不产生双 bean）。
 * runner 环境无 loadbalancer 基础设施，注入直连 Client 桩满足构建链（真实跨服务链路由宿主服务冒烟验收）。
 */
class DataPermRemoteAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FeignAutoConfiguration.class, DataPermRemoteAutoConfiguration.class))
            .withBean("feignClientStub", Client.class, () -> new Client.Default(null, null));

    @Test
    void remoteClientAndProviderAssembled_whenNoLocalBackend() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DataPermClient.class);
            assertThat(ctx).hasSingleBean(DataPermEvaluationProvider.class);
            assertThat(ctx.getBean(DataPermEvaluationProvider.class))
                    .isInstanceOf(RemoteDataPermEvaluationProvider.class);
        });
    }

    /** 本地优先双保险：宿主自带求值 bean（如 cloud-system 本地 provider）时远端 bean 让位 */
    @Test
    void remoteProviderBacksOff_whenLocalProviderPresent() {
        runner.withBean("localProvider", DataPermEvaluationProvider.class, LocalProvider::new)
                .run(ctx -> {
                    assertThat(ctx.getBean(DataPermEvaluationProvider.class)).isInstanceOf(LocalProvider.class);
                    assertThat(ctx).doesNotHaveBean(RemoteDataPermEvaluationProvider.class);
                    // client 装配独立于 provider 让位（本地后端也可能消费 deny 面）
                    assertThat(ctx).hasSingleBean(DataPermClient.class);
                });
    }

    /** 消费方 @EnableFeignClients 已注册同型 client → 程序式构建让位（单 bean 证明未重复构建） */
    @Test
    void programmaticClientBacksOff_whenConsumerRegisteredClient() {
        DataPermClient consumerClient = mock(DataPermClient.class);
        runner.withBean("consumerClient", DataPermClient.class, () -> consumerClient)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DataPermClient.class);
                    assertThat(ctx.getBean(DataPermClient.class)).isSameAs(consumerClient);
                    assertThat(ctx).hasSingleBean(RemoteDataPermEvaluationProvider.class);
                });
    }

    /** 模拟宿主本地求值后端（如 cloud-system 直读规则库） */
    static class LocalProvider implements DataPermEvaluationProvider {

        @Override
        public DataPermEvaluation evaluate(String resource, String operation, String businessKey) {
            throw new UnsupportedOperationException("测试桩不参与求值");
        }
    }
}
