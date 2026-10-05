package com.cloudai.common.security.config;

import com.cloudai.common.security.filter.HeaderAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class CommonSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(ServletWebConfig.class)
            .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class));

    @Test
    void registersFilterChainAndAdvice() {
        runner.run(ctx -> {
            org.assertj.core.api.Assertions.assertThat(ctx).hasSingleBean(HeaderAuthFilter.class);
            org.assertj.core.api.Assertions.assertThat(ctx).hasBean("cloudSecurityFilterChain");
            org.assertj.core.api.Assertions.assertThat(ctx).hasBean("securityExceptionAdvice");
            org.assertj.core.api.Assertions.assertThat(ctx).hasBean("passwordEncoder");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class ServletWebConfig {
    }
}
