package com.cloudai.system.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 面配置（client-secret 真值放 Nacos cloud-system.yaml，不入仓库）。
 */
@Data
@ConfigurationProperties(prefix = "cloud.mcp")
public class McpProperties {

    /** AI 基座共享密钥；空=未配置态（MCP 面拒绝全部调用，不阻断服务启动） */
    private String clientSecret = "";
}
