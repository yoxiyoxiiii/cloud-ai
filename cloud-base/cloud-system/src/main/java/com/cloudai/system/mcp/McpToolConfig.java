package com.cloudai.system.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册（显式声明工具对象——starter 无 @Tool 自动扫描，工具必须经
 * provider 注册才进 tools/list；新增工具在 toolObjects 追加）。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider systemMcpToolCallbackProvider() {
        // 骨架态：显式空数组（builder 零参 build 会因 toolObjects=null 拒绝构造）
        return MethodToolCallbackProvider.builder().toolObjects(new Object[0]).build();
    }
}
