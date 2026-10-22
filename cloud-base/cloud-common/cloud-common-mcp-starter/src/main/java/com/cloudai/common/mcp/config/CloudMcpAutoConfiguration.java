package com.cloudai.common.mcp.config;

import com.cloudai.common.mcp.auth.McpAuthFilter;
import com.cloudai.common.mcp.perm.McpPermissionAspect;
import com.cloudai.common.security.props.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * MCP 安全组件自动装配（cloud.mcp.enabled=true 才生效，默认关）。
 * 装配认证过滤器（仅拦 endpoint-pattern，注册序须在 spring-security 链之后——见
 * mcpAuthFilterRegistration 内注释）与配置对齐校验；工具类与 ToolCallbackProvider 注册永远留在业务服务侧。
 */
@AutoConfiguration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "cloud.mcp", name = "enabled", havingValue = "true")
public class CloudMcpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudMcpAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "mcpAuthFilterRegistration")
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(
            McpProperties properties, JwtProperties jwtProperties,
            StringRedisTemplate stringRedisTemplate, Environment environment) {
        warnIfEndpointMisaligned(properties, environment);
        McpAuthFilter filter = new McpAuthFilter(properties, jwtProperties, stringRedisTemplate);
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(properties.getEndpointPattern());
        // 须排 springSecurityFilterChain（注册序 -100，SecurityProperties.DEFAULT_FILTER_ORDER）之后：
        // Spring Security 链首 SecurityContextHolderFilter 以 setDeferredContext 整体置换先前
        // context——本过滤器先于安全链运行时塞入的认证主体会被置换成空，工具执行线程读到匿名
        //（设计 §12 D67）。-100 之后到 0 之间任取，取 -90 便于阅读。
        registration.setOrder(-90);
        return registration;
    }

    /** 工具权限切面（@ConditionalOnMissingBean 允许宿主覆盖） */
    @Bean
    @ConditionalOnMissingBean
    public McpPermissionAspect mcpPermissionAspect() {
        return new McpPermissionAspect();
    }

    /** 两个配置来源错位即保护落空——装配期发现即告警（不阻断：SSE 模式等合法的宽 pattern 场景需要放行） */
    private void warnIfEndpointMisaligned(McpProperties properties, Environment environment) {
        String endpoint = environment.getProperty(
                "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        if (!properties.getEndpointPattern().equals(endpoint + "/*")) {
            log.warn("MCP 认证过滤器 URL 模式 [{}] 与 MCP 端点 [{}] 不对齐，端点可能无保护；"
                            + "请对齐 cloud.mcp.endpoint-pattern 与 spring.ai.mcp.server.streamable-http.mcp-endpoint"
                            + "（SSE 模式端点为 /sse 与 /mcp/message，pattern 需覆盖两路径）",
                    properties.getEndpointPattern(), endpoint);
        }
    }
}
