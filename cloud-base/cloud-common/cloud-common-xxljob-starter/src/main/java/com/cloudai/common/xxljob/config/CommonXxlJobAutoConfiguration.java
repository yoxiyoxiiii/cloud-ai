package com.cloudai.common.xxljob.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * xxl-job 执行器自动装配（设计 D1）。
 * 门控 cloud.common.xxljob.enabled 显式 true 才装配、默认关：executor 启动会绑定回调端口
 * （19202/19203），绑定失败（afterSingletonsInstantiated 抛出）会阻断宿主服务启动——
 * admin 容器是独立基础设施，未起/CI/单测环境不应被连坐（与 rocketmq enabled 默认 true 相反、
 * 与 projection 默认关一致；sso 不配即永不接入）。
 * 裸 @Bean 即官方 sample 形态：XxlJobSpringExecutor 实现 DisposableBean（3.5.0 源码核实），
 * 容器关闭自动 destroy（内嵌 netty server 停止 + 注销注册），勿加 destroyMethod 声明。
 */
@AutoConfiguration
@EnableConfigurationProperties(CommonXxlJobProperties.class)
@ConditionalOnProperty(prefix = "cloud.common.xxljob", name = "enabled", havingValue = "true")
public class CommonXxlJobAutoConfiguration {

    /** Properties→setter 逐项映射（键名官方零转译，官方 sample XxlJobConfig 同构）；@ConditionalOnMissingBean 允许宿主自定义覆盖（本仓惯例） */
    @Bean
    @ConditionalOnMissingBean
    public XxlJobSpringExecutor xxlJobSpringExecutor(CommonXxlJobProperties properties) {
        CommonXxlJobProperties.Admin admin = properties.getAdmin();
        CommonXxlJobProperties.Executor executorConfig = properties.getExecutor();
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(admin.getAddresses());
        executor.setTimeout(admin.getTimeout());
        executor.setEnabled(executorConfig.getEnabled());
        executor.setAppname(executorConfig.getAppname());
        executor.setAccessToken(executorConfig.getAccessToken());
        if (executorConfig.getIp() != null) {
            executor.setIp(executorConfig.getIp());
        }
        if (executorConfig.getPort() != null) {
            executor.setPort(executorConfig.getPort());
        }
        if (executorConfig.getAddress() != null) {
            executor.setAddress(executorConfig.getAddress());
        }
        if (executorConfig.getLogPath() != null) {
            executor.setLogPath(executorConfig.getLogPath());
        }
        if (executorConfig.getLogRetentionDays() != null) {
            executor.setLogRetentionDays(executorConfig.getLogRetentionDays());
        }
        if (executorConfig.getExcludedPackage() != null) {
            executor.setExcludedPackage(executorConfig.getExcludedPackage());
        }
        executor.setGlueEnabled(executorConfig.getGlueEnabled());
        return executor;
    }
}
