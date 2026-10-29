"""MCP 工具可见性过滤（契约 2026-10-17-mcp-tools-api §5 修订版，见
2026-10-29-agent-platform-api.md §7；工具名/权限档位变更先改契约再改种子）。

真相源已换轨：原「本文件硬编码映射」改为 cloud_ai 库 ai_tool 注册表
（required_perms 列）——本模块只做纯过滤，不再持有任何工具清单。
服务端仍做最终权限裁决（@McpRequirePerm）；本模块只决定把哪些工具呈现给模型。

行对象鸭子类型（tool_key/required_perms 两属性，无包依赖——AiTool 行直接可用，
测试/冒烟可构造替身行）。
"""

from collections.abc import Iterable


def visible_tools(tool_rows: Iterable, user_perms: set[str]) -> list:
    """按注册表行过滤可见工具：required_perms（逗号分隔，空=登录即可）⊆ 用户 perms。

    只影响呈现给模型的工具清单，不影响服务端判定——直接调用无权限工具仍由
    服务端 PERMISSION_DENIED 拒绝（防御纵深语义不变）。
    """
    return [row for row in tool_rows if _required_set(row) <= user_perms]


def _required_set(row) -> set[str]:
    """required_perms 逗号串拆集合；空串/缺失 = 空集（登录即可档位）。"""
    raw = getattr(row, "required_perms", "") or ""
    return {p.strip() for p in raw.split(",") if p.strip()}
