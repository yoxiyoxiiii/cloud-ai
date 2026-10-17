package com.cloudai.system.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 工具级权限判定测试：有所需 perms 放行 / 无 perms 拒绝 / 无 principal 上下文拒绝。
 */
class McpPermGuardTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withPrincipal(List<String> perms) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (perms != null) {
            request.setAttribute(McpPrincipal.ATTR, new McpPrincipal(1L, "admin", perms));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void require_withNeededPerm_passes() {
        withPrincipal(List.of("system:user:list"));
        assertThatCode(() -> McpPermGuard.require("system:user:list"))
                .doesNotThrowAnyException();
    }

    @Test
    void require_withoutPerm_denied() {
        withPrincipal(List.of());
        assertThatThrownBy(() -> McpPermGuard.require("system:user:list"))
                .isInstanceOf(McpPermissionDeniedException.class)
                .hasMessageContaining("PERMISSION_DENIED: system:user:list");
    }

    @Test
    void require_noPrincipalContext_denied() {
        withPrincipal(null);
        assertThatThrownBy(() -> McpPermGuard.require("system:user:list"))
                .isInstanceOf(McpPermissionDeniedException.class);
    }
}
