package com.cloudai.system.mcp;

import com.cloudai.common.security.props.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * MCP 面过滤器装配：仅拦截 /mcp/*（容器级 filter 不全路径），order 最前先于既有过滤器链。
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(McpProperties.class)
public class McpFilterConfig {

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(McpProperties props) {
        McpAuthFilter filter = new McpAuthFilter(props, jwtProperties, stringRedisTemplate);
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
