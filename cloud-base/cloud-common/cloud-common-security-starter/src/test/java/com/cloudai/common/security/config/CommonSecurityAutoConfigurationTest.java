package com.cloudai.common.security.config;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.GlobalExceptionHandler;
import com.cloudai.common.security.filter.HeaderAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void accessDeniedReturns403EvenWithGlobalCatchAllAdvice() throws Exception {
        var mvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(),
                        new CommonSecurityAutoConfiguration.SecurityExceptionAdvice())
                .build();
        mvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class ServletWebConfig {
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        public R<Void> probe() {
            throw new AccessDeniedException("denied");
        }
    }
}
