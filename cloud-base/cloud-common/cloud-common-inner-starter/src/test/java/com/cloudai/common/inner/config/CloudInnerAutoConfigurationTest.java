package com.cloudai.common.inner.config;

import com.cloudai.common.inner.client.InnerSecretInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /inner 面组件装配测试（契约 §2）：密钥已配 → 过滤器（URL 模式 /inner/*、注册序 HIGHEST_PRECEDENCE+10）
 * 与拦截器 bean 均注册；密钥为空 → bean 仍在（fail-closed 拒绝在过滤器内部，不拆装配）+ 启动哨兵 WARN。
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
