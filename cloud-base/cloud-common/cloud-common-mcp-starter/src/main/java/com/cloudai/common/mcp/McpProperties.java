package com.cloudai.common.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 面配置（真值放 Nacos <服务名>.yaml，不入仓库）。
 */
@Data
@ConfigurationProperties(prefix = "cloud.mcp")
public class McpProperties {

    /** starter 总开关：true 才装配认证过滤器与权限切面（默认关；未开启时若 spring.ai 激活了端点，哨兵会启动告警） */
    private boolean enabled = false;

    /** AI 基座共享密钥；空=未配置态（MCP 面拒绝全部调用，不阻断服务启动） */
    private String clientSecret = "";

    /** 认证过滤器 URL 模式；须覆盖 spring.ai 实际端点（默认对齐 streamable 端点 /mcp；SSE 模式端点是 /sse+/mcp/message 两路径） */
    private String endpointPattern = "/mcp/*";
}
