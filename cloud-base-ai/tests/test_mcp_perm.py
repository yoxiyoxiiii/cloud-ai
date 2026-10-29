"""MCP 工具可见性过滤测试（契约 2026-10-17-mcp-tools-api §5 修订版：真相源=ai_tool 注册表）。"""

from cloud_base_ai.agent.entities import AiTool
from cloud_base_ai.mcp.perm import visible_tools


def _row(tool_key: str, required_perms: str) -> AiTool:
    return AiTool(tool_key=tool_key, name="显示名", description="d",
                  input_schema='{"type": "object"}', required_perms=required_perms)


USERS = _row("system_query_users", "system:user:list")
DICT = _row("system_query_dict_items", "")  # 登录即可（空 required_perms）


def test_admin_perms_see_all_tools() -> None:
    """含 system:user:list 的用户：两工具全可见。"""
    rows = visible_tools([USERS, DICT], {"system:user:list"})
    assert [r.tool_key for r in rows] == ["system_query_users", "system_query_dict_items"]


def test_empty_perms_login_only() -> None:
    """空 perms：仅登录即可档位（dict 工具）可见，user 工具不可见。"""
    rows = visible_tools([USERS, DICT], set())
    assert [r.tool_key for r in rows] == ["system_query_dict_items"]


def test_whitelist_semantics_no_extra_tools() -> None:
    """白名单语义：额外 perms 不引入新工具——过滤只收窄不放大呈现面。"""
    bogus = {"*", "some:other:perm"}
    assert visible_tools([DICT], bogus) == [DICT]
    assert visible_tools([USERS], bogus) == []


def test_multi_perms_comma_string_parsing() -> None:
    """required_perms 逗号串拆集（未来多权限工具）；全含才可见。"""
    multi = _row("e2e_multi", "p:a, p:b")
    assert visible_tools([multi], {"p:a"}) == []
    assert visible_tools([multi], {"p:a", "p:b"}) == [multi]


def test_rows_passed_through_unmodified() -> None:
    """过滤返回原行对象（agent 运行链直接拿行注入模型，不做拷贝转换）。"""
    assert visible_tools([USERS], {"system:user:list"}) == [USERS]
    assert visible_tools([], {"system:user:list"}) == []
