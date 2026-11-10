package com.cloudai.common.inner.config;

import com.cloudai.common.inner.auth.InnerAccessFilter;
import com.cloudai.common.inner.client.InnerSecretInterceptor;
import com.cloudai.common.inner.core.InnerSecretSource;
import com.cloudai.common.inner.core.RedisInnerSecretSource;
import com.cloudai.common.inner.core.StaticInnerSecretSource;
import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * /inner 面准入组件自动装配（契约 2026-11-09-inner-access-secret-api v2 §2）：引 jar 即生效，无 enabled 开关。
 * 密钥来源两级：StaticInnerSecretSource 恒注册（v1 行为兜底）；classpath 且容器有 StringRedisTemplate 时
 * 追加 RedisInnerSecretSource 并以 @Primary 生效（活跃集分发）——无 Redis 宿主自动回落 static-only，
 * 行为与 v1 完全一致（引 jar 即生效、fail-closed 语义不变）。
 * after = RedisAutoConfiguration：保证 stringRedisTemplate bean 定义先于本配置的 @ConditionalOnBean 求值
 * （缺 spring-data-redis 的宿主按类名安全忽略本项排序）。
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@EnableConfigurationProperties(InnerProperties.class)
public class CloudInnerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudInnerAutoConfiguration.class);

    /** URL 模式固定不可配：inner 路径是本仓强约定（/inner/**），可配反而引入与实际端点对不齐的保护落空面 */
    static final String INNER_URL_PATTERN = "/inner/*";

    public CloudInnerAutoConfiguration(InnerProperties properties) {
        // 启动哨兵：fail-closed 提示（不阻断启动——未配置是可恢复的运维缺口，不是启动错误）
        if (properties.getSharedSecret() == null || properties.getSharedSecret().isEmpty()) {
            log.warn("cloud.inner.shared-secret 未配置：Redis 活跃集键不存在时将无兜底密钥（fail-closed），"
                    + "请在 Nacos per-service yaml 配置该键（契约 2026-11-09-inner-access-secret-api.md §3）");
        }
    }

    /** 静态来源恒注册：Redis 键不存在时的回落目标，也是无 Redis 宿主的唯一来源 */
    @Bean
    @ConditionalOnMissingBean(StaticInnerSecretSource.class)
    public StaticInnerSecretSource staticInnerSecretSource(InnerProperties properties) {
        return new StaticInnerSecretSource(properties);
    }

    /** Redis 活跃集来源：仅 classpath 有 spring-data-redis 且容器已有 stringRedisTemplate 时注册；
     *  嵌套类条件求值先于类加载的标准 Boot 模式，宿主无 Redis 依赖不炸 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisSecretSourceConfiguration {

        @Bean
        @Primary
        @ConditionalOnBean(StringRedisTemplate.class)
        public RedisInnerSecretSource redisInnerSecretSource(StringRedisTemplate redisTemplate,
                                                             InnerProperties properties) {
            return new RedisInnerSecretSource(redisTemplate,
                    new StaticInnerSecretSource(properties), properties);
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
                InnerSecretSource secretSource) {
            FilterRegistrationBean<InnerAccessFilter> registration =
                    new FilterRegistrationBean<>(new InnerAccessFilter(secretSource));
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
        public InnerSecretInterceptor innerSecretInterceptor(InnerSecretSource secretSource) {
            return new InnerSecretInterceptor(secretSource);
        }
    }
}
