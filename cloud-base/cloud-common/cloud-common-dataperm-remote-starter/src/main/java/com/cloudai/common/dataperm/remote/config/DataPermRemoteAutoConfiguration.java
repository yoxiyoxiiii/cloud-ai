package com.cloudai.common.dataperm.remote.config;

import com.cloudai.common.dataperm.evaluate.DataPermEvaluationProvider;
import com.cloudai.common.dataperm.remote.RemoteDataPermEvaluationProvider;
import com.cloudai.system.api.client.DataPermClient;
import feign.Request;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 数据权限远端求值自动装配：程序式构建 DataPermClient（FeignClientBuilder，不经
 * @EnableFeignClients 扫描——common 包不在消费方扫描范围，消费方引依赖即用、零配置）
 * + RemoteDataPermEvaluationProvider。
 *
 * <p><b>本地优先双保险</b>：Provider 与 client 均 @ConditionalOnMissingBean——cloud-system
 * 等自带本地求值 bean 的服务即使误引本模块，装配结果仍是本地求值（system 主通道是不引入本模块）；
 * 消费方若已用 @EnableFeignClients 注册 DataPermClient，程序式构建让位（不产生双 bean）。</p>
 *
 * <p><b>不设功能开关</b>：关闭 = 标注方法求值后端缺失 = 调用即拒绝（fail-closed 报错，
 * 非静默放行）——开关只会制造全量报错面，紧急止损路径是回滚版本而非线上开关。</p>
 *
 * <p><b>超时与熔断</b>：connect 1s / read 2s（内网小负载 RPC，故障窗口响应劣化上限压到秒级，
 * 沿 translate-remote 同款取值）；程序式 client 不经注解驱动的 circuit breaker 包装——
 * 求值失败统一走 Provider 的 catch-all fail-closed，语义与熔断打开期一致（拒绝访问）。</p>
 */
@AutoConfiguration
@ConditionalOnClass(FeignClientBuilder.class)
public class DataPermRemoteAutoConfiguration {

    /** 内网小负载 RPC 超时：connect 1s——system 不可达时快速失败进 fail-closed */
    static final long CONNECT_TIMEOUT_MILLIS = 1000;

    /** read 2s：求值是单表读 + 规则并集，秒级余量足够 */
    static final long READ_TIMEOUT_MILLIS = 2000;

    /**
     * 程序式构建（spring-cloud-openfeign 4.x FeignClientBuilder）：forType 传服务名（服务名寻址 +
     * loadbalancer），path 显式传（Builder 不读 @FeignClient 注解元数据）；customize 设
     * Request.Options——Builder customizer 在 properties/上下文配置之后应用，优先级最高。
     * 构建需要 FeignClientFactory（FeignAutoConfiguration 自动装配，无需 @EnableFeignClients）。
     */
    @Bean
    @ConditionalOnMissingBean(DataPermClient.class)
    public DataPermClient dataPermClient(ApplicationContext applicationContext) {
        return new FeignClientBuilder(applicationContext)
                .forType(DataPermClient.class, "cloud-system")
                .path("/inner/data-perm")
                .customize(builder -> builder.options(new Request.Options(
                        CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS,
                        READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, true)))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(DataPermEvaluationProvider.class)
    public RemoteDataPermEvaluationProvider remoteDataPermEvaluationProvider(DataPermClient dataPermClient) {
        return new RemoteDataPermEvaluationProvider(dataPermClient);
    }
}
