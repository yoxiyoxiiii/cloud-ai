package com.cloudai.common.mcp;

import java.util.List;

/**
 * MCP 请求主体（McpAuthFilter 三关通过后构建，存 request attribute）。
 * 身份唯一来源=验签后的 JWT claims + Redis 在线会话快照；不信任任何明文 header。
 */
public record McpPrincipal(Long userId, String account, List<String> perms) {

    /** request attribute 键 */
    public static final String ATTR = McpPrincipal.class.getName();
}
