package com.cloudai.common.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * MCP 配置哨兵（常驻、零门控）：检测「spring.ai 已激活 MCP server 而 cloud.mcp.enabled 未开启」的错配并在启动时告警。
 * 与「密钥空=拒绝全部调用不阻断启动」同哲学——提醒而非阻断。
 */
@AutoConfiguration
public class CloudMcpGuardSentinelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudMcpGuardSentinelAutoConfiguration.class);

    @Bean
    public ApplicationRunner mcpConfigGuardRunner(Environment environment) {
        return args -> {
            String serverName = environment.getProperty("spring.ai.mcp.server.name");
            boolean enabled = environment.getProperty("cloud.mcp.enabled", Boolean.class, false);
            if (serverName != null && !enabled) {
                log.warn("spring.ai.mcp.server.name=[{}] 已配置但 cloud.mcp.enabled 未开启——"
                        + "MCP 端点已激活而认证过滤器未装配，端点无保护", serverName);
            }
        };
    }
}
