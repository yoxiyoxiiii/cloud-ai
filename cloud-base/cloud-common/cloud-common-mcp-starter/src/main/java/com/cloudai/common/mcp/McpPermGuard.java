package com.cloudai.common.mcp;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * MCP 工具级权限判定（安全边界，语义对齐 REST 面 @PreAuthorize）。
 * 每个需要 perms 的工具方法第一行调用；无 perms 档位的工具不调用。
 */
public final class McpPermGuard {

    private McpPermGuard() {
    }

    public static void require(String perm) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new McpPermissionDeniedException(perm);
        }
        McpPrincipal principal = (McpPrincipal) attrs.getRequest().getAttribute(McpPrincipal.ATTR);
        List<String> perms = principal == null ? List.of() : principal.perms();
        if (perm == null || !perms.contains(perm)) {
            throw new McpPermissionDeniedException(perm);
        }
    }
}
