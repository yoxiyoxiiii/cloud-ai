package com.cloudai.system.mcp;

/**
 * MCP 工具权限拒绝：由 MCP 层转为 isError=true 结果
 * （契约 2026-10-17-mcp-tools-api §3.4：content 文本前缀 PERMISSION_DENIED）。
 */
public class McpPermissionDeniedException extends RuntimeException {

    public McpPermissionDeniedException(String perms) {
        super("PERMISSION_DENIED: " + perms);
    }
}
