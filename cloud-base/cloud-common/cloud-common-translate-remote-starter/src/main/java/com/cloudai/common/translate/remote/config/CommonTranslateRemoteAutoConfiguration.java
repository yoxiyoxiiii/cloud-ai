package com.cloudai.common.translate.remote.config;

import com.cloudai.common.translate.provider.DictSourceProvider;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.cloudai.common.translate.remote.client.SystemTranslateClient;
import com.cloudai.common.translate.remote.provider.RemoteDictSourceProvider;
import com.cloudai.common.translate.remote.provider.RemoteUserSourceProvider;
import feign.Request;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 翻译远程回源自动装配：程序式构建 SystemTranslateClient（FeignClientBuilder，不经 @EnableFeignClients
 * 扫描——消费方引依赖即用）+ 两远程 Provider。
 * <p>条件：classpath 有 openfeign 且 {@code cloud.translate.remote.enabled} 未显式关闭（默认开，应急可关）。
 * <p>本地优先双保险（设计 D2）：两 Provider 均 @ConditionalOnMissingBean(SPI)——system 等自带 @Service
 * Provider 的服务即使误引本模块，装配结果仍是本地直读（system 主通道是不引入本模块）。
 */
@AutoConfiguration
@ConditionalOnClass(FeignClientBuilder.class)
@ConditionalOnProperty(name = "cloud.translate.remote.enabled", havingValue = "true", matchIfMissing = true)
public class CommonTranslateRemoteAutoConfiguration {

    /** 内网小负载 RPC 超时（设计 D3）：connect 1s / read 2s——openfeign 默认数十秒不可接受（故障窗口响应劣化上限压到秒级） */
    static final long CONNECT_TIMEOUT_MILLIS = 1000;

    static final long READ_TIMEOUT_MILLIS = 2000;

    /**
     * 程序式构建（spring-cloud-openfeign 4.x FeignClientBuilder）：forType 传服务名（服务名寻址 + loadbalancer），
     * path 显式传（Builder 不读 @FeignClient 注解元数据）；customize 设 Request.Options——Builder customizer
     * 在 properties/上下文配置之后应用，优先级最高（feign.client.config 不覆盖它）。
     * 构建需要 FeignClientFactory（FeignAutoConfiguration 自动装配，无需 @EnableFeignClients）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SystemTranslateClient systemTranslateClient(ApplicationContext applicationContext) {
        return new FeignClientBuilder(applicationContext)
                .forType(SystemTranslateClient.class, "cloud-system")
                .path("/inner")
                .customize(builder -> builder.options(new Request.Options(
                        CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS,
                        READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, true)))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(DictSourceProvider.class)
    public RemoteDictSourceProvider remoteDictSourceProvider(SystemTranslateClient client) {
        return new RemoteDictSourceProvider(client);
    }

    @Bean
    @ConditionalOnMissingBean(UserSourceProvider.class)
    public RemoteUserSourceProvider remoteUserSourceProvider(SystemTranslateClient client) {
        return new RemoteUserSourceProvider(client);
    }
}
