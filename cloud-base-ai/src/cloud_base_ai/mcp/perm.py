"""MCP 工具可见性过滤（契约 2026-10-17-mcp-tools-api §5；工具名/权限档位变更先改契约）。

服务端仍做最终权限裁决（McpPermGuard）；本模块只决定把哪些工具呈现给对话侧用户。
"""

TOOL_PERMS: dict[str, set[str]] = {
    "system_query_users": {"system:user:list"},
    "system_query_dict_items": set(),  # 登录即可（无权限档位要求）
}


def visible_tools(user_perms: set[str]) -> list[str]:
    """按用户 perms 快照过滤可见工具名；空档位=登录即可见。"""
    return sorted(
        name for name, required in TOOL_PERMS.items() if required <= user_perms
    )
