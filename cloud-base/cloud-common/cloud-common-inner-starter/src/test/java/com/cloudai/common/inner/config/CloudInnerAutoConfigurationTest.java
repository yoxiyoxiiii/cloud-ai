package com.cloudai.common.inner.config;

import com.cloudai.common.inner.client.InnerSecretInterceptor;
import com.cloudai.common.inner.core.InnerSecretSource;
import com.cloudai.common.inner.core.RedisInnerSecretSource;
import com.cloudai.common.inner.core.StaticInnerSecretSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /inner 面组件装配测试（契约 v2 §2）：密钥已配 → 过滤器（URL 模式 /inner/*、注册序 HIGHEST_PRECEDENCE+10）
 * 与拦截器 bean 均注册；有 StringRedisTemplate → Redis 来源注册且 @Primary 生效；
 * 无 Redis classpath → static-only（InnerSecretSource 解析到静态来源，v1 行为保留）；
 * 密钥为空 → bean 仍在（fail-closed 拒绝在过滤器内部，不拆装配）+ 启动哨兵 WARN。
 * 用 WebApplicationContextRunner：servlet 条件（@ConditionalOnWebApplication）在非 web runner 下不成立。
 */
class CloudInnerAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(CloudInnerAutoConfiguration.class);

    @Test
    void secretConfigured_registersFilterAndInterceptor() {
        runner.withPropertyValues("cloud.inner.shared-secret=test-secret")
                .run(context -> {
                    assertThat(context).hasBean("innerAccessFilterRegistration");
                    FilterRegistrationBean<?> registration = context.getBean(
                            "innerAccessFilterRegistration", FilterRegistrationBean.class);
                    assertThat(registration.getUrlPatterns()).containsExactly("/inner/*");
                    assertThat(registration.getOrder())
                            .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                    assertThat(context).hasBean("innerSecretInterceptor");
                    assertThat(context.getBean(InnerSecretInterceptor.class)).isNotNull();
                });
    }

    @Test
    void stringRedisTemplatePresent_redisSourcePrimary() {
        // mock 实例：真实 StringRedisTemplate 初始化要求 RedisConnectionFactory，装配断言不依赖连接
        runner.withBean(StringRedisTemplate.class, () -> org.mockito.Mockito.mock(StringRedisTemplate.class))
                .withPropertyValues("cloud.inner.shared-secret=test-secret")
                .run(context -> {
                    // 有 Redis → RedisInnerSecretSource 注册且为 @Primary（过滤器/拦截器注入来源）
                    assertThat(context).hasBean("redisInnerSecretSource");
                    assertThat(context.getBean(InnerSecretSource.class))
                            .isInstanceOf(RedisInnerSecretSource.class);
                    // 静态来源仍在（键不存在时的回落目标）
                    assertThat(context.getBean(StaticInnerSecretSource.class)).isNotNull();
                    assertThat(context).hasBean("innerAccessFilterRegistration");
                    assertThat(context).hasBean("innerSecretInterceptor");
                });
    }

    @Test
    void redisClasspathAbsent_staticOnly_v1BehaviorPreserved() {
        // FilteredClassLoader 隐藏 StringRedisTemplate：模拟无 Redis 宿主——
        // Redis 来源不注册，InnerSecretSource 解析到静态来源，装配/行为与 v1 完全一致
        runner.withClassLoader(new FilteredClassLoader(StringRedisTemplate.class))
                .withPropertyValues("cloud.inner.shared-secret=test-secret")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("redisInnerSecretSource");
                    assertThat(context.getBean(InnerSecretSource.class))
                            .isInstanceOf(StaticInnerSecretSource.class);
                    assertThat(context.getBean(InnerSecretSource.class).injectSecret())
                            .isEqualTo("test-secret");
                    assertThat(context).hasBean("innerAccessFilterRegistration");
                    assertThat(context).hasBean("innerSecretInterceptor");
                });
    }

    @Test
    void secretEmpty_beansStillRegistered_failClosedNotDismantled() {
        runner.run(context -> {
            // fail-closed 语义落在过滤器内部（未配置 → 恒 401），装配本身不随配置缺席而拆除
            assertThat(context).hasBean("innerAccessFilterRegistration");
            assertThat(context).hasBean("innerSecretInterceptor");
        });
    }

    @Test
    void secretEmpty_constructorLogsStartupWarn() {
        // 启动哨兵：捕获与解码同用 JVM 默认 charset（闭环往返，规避 Windows GBK 控制台错配）
        Charset charset = Charset.defaultCharset();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured, true, charset));
            new CloudInnerAutoConfiguration(new InnerProperties());
        } finally {
            System.setOut(originalOut);
        }
        assertThat(captured.toString(charset))
                .contains("cloud.inner.shared-secret 未配置");
    }
}
