package com.cloudai.common.inner.config;

import com.cloudai.common.inner.auth.InnerAccessFilter;
import com.cloudai.common.inner.client.InnerSecretInterceptor;
import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * /inner 面准入组件自动装配（契约 2026-11-09-inner-access-secret-api §2）：引 jar 即生效，无 enabled 开关。
 * 一体两面：入站过滤器（提供方校验 /inner/*）+ 出站全局拦截器（消费方注入密钥头）。
 */
@AutoConfiguration
@EnableConfigurationProperties(InnerProperties.class)
public class CloudInnerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudInnerAutoConfiguration.class);

    /** URL 模式固定不可配：inner 路径是本仓强约定（/inner/**），可配反而引入与实际端点对不齐的保护落空面 */
    static final String INNER_URL_PATTERN = "/inner/*";

    public CloudInnerAutoConfiguration(InnerProperties properties) {
        // 启动哨兵：fail-closed 提示（不阻断启动——未配置是可恢复的运维缺口，不是启动错误）
        if (properties.getSharedSecret() == null || properties.getSharedSecret().isEmpty()) {
            log.warn("cloud.inner.shared-secret 未配置：/inner/** 将拒绝全部调用（fail-closed）且 Feign 出站不注入密钥头；"
                    + "请在 Nacos per-service yaml 配置该键（契约 2026-11-09-inner-access-secret-api.md §3）");
        }
    }

    /** 入站闸门：仅 servlet 环境装配（网关是 WebFlux 且不引本 jar，此条件属双保险） */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class InnerAccessFilterConfiguration {

        /**
         * 注册序取 HIGHEST_PRECEDENCE + 10：排在字符编码过滤器（HIGHEST_PRECEDENCE）之后、
         * spring security 链（-100）之前——纯拒绝闸门不触碰 SecurityContext，越早拒绝越好，
         * 无 McpAuthFilter 须排在安全链之后的约束（该过滤器要读安全链构建的主体，本过滤器不需要）。
         */
        @Bean
        @ConditionalOnMissingBean(name = "innerAccessFilterRegistration")
        public FilterRegistrationBean<InnerAccessFilter> innerAccessFilterRegistration(
                InnerProperties properties) {
            FilterRegistrationBean<InnerAccessFilter> registration =
                    new FilterRegistrationBean<>(new InnerAccessFilter(properties));
            registration.addUrlPatterns(INNER_URL_PATTERN);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }

    /** 出站注入：仅 classpath 有 openfeign 时装配（条件求值先于类加载的标准 Boot 嵌套模式，宿主无 openfeign 不炸） */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestInterceptor.class)
    static class InnerSecretInterceptorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public InnerSecretInterceptor innerSecretInterceptor(InnerProperties properties) {
            return new InnerSecretInterceptor(properties);
        }
    }
}
