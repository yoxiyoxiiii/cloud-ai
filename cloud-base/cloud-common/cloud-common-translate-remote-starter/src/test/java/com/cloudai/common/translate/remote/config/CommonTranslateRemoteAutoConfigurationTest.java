package com.cloudai.common.translate.remote.config;

import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.api.domain.UserEntry;
import com.cloudai.common.translate.provider.DictSourceProvider;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.cloudai.common.translate.remote.client.SystemTranslateClient;
import com.cloudai.common.translate.remote.provider.RemoteDictSourceProvider;
import com.cloudai.common.translate.remote.provider.RemoteUserSourceProvider;
import feign.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 远程回源自动装配条件单测（设计 §7.1，ApplicationContextRunner）：
 * ① 无本地 Provider → 远程两 Provider + 程序式 client 装配（真实 FeignClientBuilder 构建，不经 @EnableFeignClients）；
 * ② 注册本地 SPI bean → 远程对应 bean 不装配（@ConditionalOnMissingBean 本地优先锁死）；
 * ③ cloud.translate.remote.enabled=false → 整组不装配（应急开关）。
 * runner 环境无 loadbalancer 基础设施，注入直连 Client 桩满足构建链（真实跨服务链路 bpmn 阶段 4 验收，设计 §9.1）。
 */
class CommonTranslateRemoteAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FeignAutoConfiguration.class, CommonTranslateRemoteAutoConfiguration.class))
            .withBean("feignClientStub", Client.class, () -> new Client.Default(null, null));

    @Test
    void remoteBeansAssembled_whenNoLocalProviders() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(SystemTranslateClient.class);
            assertThat(ctx).hasSingleBean(DictSourceProvider.class);
            assertThat(ctx).hasSingleBean(UserSourceProvider.class);
            assertThat(ctx.getBean(DictSourceProvider.class)).isInstanceOf(RemoteDictSourceProvider.class);
            assertThat(ctx.getBean(UserSourceProvider.class)).isInstanceOf(RemoteUserSourceProvider.class);
        });
    }

    /** 本地优先双保险（设计 D2）：system 侧 @Service Provider 存在时远程 bean 全部让位 */
    @Test
    void remoteProvidersBackOff_whenLocalBeansPresent() {
        runner.withBean("localDictSource", DictSourceProvider.class, LocalDictSource::new)
                .withBean("localUserSource", UserSourceProvider.class, LocalUserSource::new)
                .run(ctx -> {
                    assertThat(ctx.getBean(DictSourceProvider.class)).isInstanceOf(LocalDictSource.class);
                    assertThat(ctx.getBean(UserSourceProvider.class)).isInstanceOf(LocalUserSource.class);
                    assertThat(ctx).doesNotHaveBean(RemoteDictSourceProvider.class);
                    assertThat(ctx).doesNotHaveBean(RemoteUserSourceProvider.class);
                });
    }

    @Test
    void remoteDisabledByProperty() {
        runner.withPropertyValues("cloud.translate.remote.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(SystemTranslateClient.class);
            assertThat(ctx).doesNotHaveBean(RemoteDictSourceProvider.class);
            assertThat(ctx).doesNotHaveBean(RemoteUserSourceProvider.class);
        });
    }

    /** 模拟 system 侧本地 @Service Provider（直读 mapper，无网络跳数） */
    static class LocalDictSource implements DictSourceProvider {

        @Override
        public List<DictItemEntry> listByDictKey(String dictKey) {
            return List.of();
        }
    }

    static class LocalUserSource implements UserSourceProvider {

        @Override
        public List<UserEntry> listAll() {
            return List.of();
        }
    }
}
