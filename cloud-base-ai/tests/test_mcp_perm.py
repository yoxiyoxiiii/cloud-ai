"""MCP 工具可见性过滤测试（契约 2026-10-17-mcp-tools-api §5 的映射语义）。"""

from cloud_base_ai.mcp.perm import TOOL_PERMS, visible_tools


def test_admin_perms_see_all_tools() -> None:
    """含 system:user:list 的用户：两工具全可见（admin perms 快照为其超集）。"""
    assert visible_tools({"system:user:list"}) == [
        "system_query_dict_items",
        "system_query_users",
    ]


def test_empty_perms_login_only() -> None:
    """空 perms：仅登录即可档位（dict 工具）可见，user 工具不可见。"""
    assert visible_tools(set()) == ["system_query_dict_items"]


def test_unmapped_tool_never_visible() -> None:
    """白名单语义：返回值只可能取自映射键——额外 perms（含伪装通配）不引入新工具。"""
    bogus_perms = set().union(*TOOL_PERMS.values()) | {"*", "some:other:perm"}
    assert visible_tools(bogus_perms) == [
        "system_query_dict_items",
        "system_query_users",
    ]


def test_tool_perms_matches_contract_tools() -> None:
    """映射键=契约 §2 的两个工具名（工具面变更先改契约再改映射）。"""
    assert sorted(TOOL_PERMS) == ["system_query_dict_items", "system_query_users"]
    assert TOOL_PERMS["system_query_users"] == {"system:user:list"}
    assert TOOL_PERMS["system_query_dict_items"] == set()
