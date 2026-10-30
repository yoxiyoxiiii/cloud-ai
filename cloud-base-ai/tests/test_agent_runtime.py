"""平台 Agent 运行时测试：schema 映射 / 工具包装器三分支 / 审计收集 / 缓存失效 / 组装零网络。

全部不触网：MCP 通道 monkeypatch 替身；模型工厂 monkeypatch 桩（ChatOpenAI 真构造
断言在 test_model_factory.py 覆盖）。deepagents 组装（create_deep_agent）本身零网络。
"""

from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from cloud_base_ai.agent import runtime
from cloud_base_ai.agent.entities import AiAgent, AiTool
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import client as mcp_client

pytestmark = pytest.mark.anyio


def _tool_row(input_schema: str, tool_key: str = "e2e_query") -> AiTool:
    return AiTool(tool_key=tool_key, name="显示名", description="工具描述",
                  input_schema=input_schema, required_perms="")


def _server_row(**overrides):
    """归属 server 替身行（内置形态；build_mcp_tool 只透传给通道层）。"""
    row = SimpleNamespace(name="SYSTEM", url="http://127.0.0.1:9202/mcp",
                          transport="http", headers_json=None,
                          credential_headers_json=None, timeout_ms=30000,
                          is_builtin=1)
    for k, v in overrides.items():
        setattr(row, k, v)
    return row


def _agent_row(config_version: int = 1) -> AiAgent:
    return AiAgent(id=101, name="e2e_智能体", system_prompt="你是测试助手",
                   temperature=0.7, status=0, config_version=config_version)


@pytest.fixture(autouse=True)
def _reset_run_context():
    """隔离 contextvar 与缓存跨用例泄漏。"""
    runtime.run_jwt.set(None)
    runtime.run_tool_calls.set(None)
    runtime.reset_cache()
    yield
    runtime.run_jwt.set(None)
    runtime.run_tool_calls.set(None)
    runtime.reset_cache()


@pytest.fixture
def stub_model(monkeypatch: pytest.MonkeyPatch):
    """模型工厂桩：真 ChatOpenAI 实例（构造零网络、假 key）——deepagents 组装会内省
    模型实例解析 profile key，哑对象过不去；假 key 不触网。"""
    from langchain_openai import ChatOpenAI

    def fake_factory(temperature=None):
        return ChatOpenAI(model="test-model", api_key="test-key", temperature=temperature)

    monkeypatch.setattr(runtime.model_factory, "get_chat_model", fake_factory)
    return None


# ---------- schema 映射 ----------


def test_args_schema_types_required_default():
    schema = runtime.build_args_schema(_tool_row(
        '{"type":"object","properties":{'
        '"city":{"type":"string"},"pageNum":{"type":"integer","default":1},'
        '"ratio":{"type":"number"},"fresh":{"type":"boolean"}},'
        '"required":["city","ratio"]}'))
    # required：无默认必填
    assert schema.model_fields["city"].is_required()
    assert schema.model_fields["ratio"].is_required()
    # default：可选带默认
    assert schema.model_fields["pageNum"].default == 1
    # 可选无默认：None
    assert schema.model_fields["fresh"].default is None
    # 实例化与校验（int 字段给 str 数字被 pydantic 拒绝/收敛行为不在此断言，只验类型挂上）
    inst = schema(city="x", ratio=1.5, pageNum=2, fresh=True)
    assert inst.city == "x" and inst.pageNum == 2
    with pytest.raises(ValidationError):
        schema(ratio=1.5)  # 缺 required city


def test_args_schema_extra_forbidden():
    schema = runtime.build_args_schema(_tool_row(
        '{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}'))
    with pytest.raises(ValidationError):
        schema(dictKey="k", invented="x")  # 模型自造参数面外字段被拒


def test_args_schema_unsupported_type_raises():
    with pytest.raises(BusinessException) as ei:
        runtime.build_args_schema(_tool_row(
            '{"type":"object","properties":{"a":{"type":"null"}}}'))
    assert ei.value.code == runtime.CODE_AGENT_RUN_FAILED
    with pytest.raises(BusinessException):
        runtime.build_args_schema(_tool_row("not-json"))


def test_args_schema_array_and_object_shallow():
    """array→list、object→dict 浅映射：嵌套结构整体降级容器，不递归内层定义。"""
    schema = runtime.build_args_schema(_tool_row(
        '{"type":"object","properties":{'
        '"tags":{"type":"array","items":{"type":"string"}},'
        '"filter":{"type":"object","properties":{"k":{"type":"string"}}}'
        '},"required":["tags"]}'))
    inst = schema(tags=["a", "b"], filter={"k": "v", "extra": 1})  # 内层不校验
    assert inst.tags == ["a", "b"]
    assert inst.filter == {"k": "v", "extra": 1}
    with pytest.raises(ValidationError):
        schema(filter={})  # 缺 required tags


def test_args_schema_enum_literal():
    """enum 标量枚举 → Literal：成员外取值校验失败，default 成员生效。"""
    schema = runtime.build_args_schema(_tool_row(
        '{"type":"object","properties":{'
        '"unit":{"type":"string","enum":["c","f"],"default":"c"},'
        '"level":{"type":"integer","enum":[1,2,3]}'
        '},"required":["level"]}'))
    inst = schema(level=2)
    assert inst.unit == "c"
    assert inst.level == 2
    with pytest.raises(ValidationError):
        schema(level=2, unit="k")  # 非 enum 成员
    with pytest.raises(ValidationError):
        schema(level=9)  # 非 enum 成员


def test_args_schema_enum_non_scalar_rejected():
    """enum 成员含非标量（dict 等）：不支持，5018。"""
    with pytest.raises(BusinessException) as ei:
        runtime.build_args_schema(_tool_row(
            '{"type":"object","properties":{"a":{"enum":[{"x":1}]}}}'))
    assert ei.value.code == runtime.CODE_AGENT_RUN_FAILED


def test_args_schema_no_args_ok():
    schema = runtime.build_args_schema(_tool_row('{"type":"object","properties":{}}'))
    schema()  # 无参工具可实例化


# ---------- 工具包装器三分支 + JWT/审计 contextvar ----------


def _result(text: str, is_error: bool = False):
    return SimpleNamespace(is_error=is_error, content=[SimpleNamespace(text=text)])


async def test_tool_wrapper_normal_and_audit_ok(monkeypatch: pytest.MonkeyPatch):
    seen: dict = {}

    async def fake_call(server_row, tool_key, arguments, jwt):
        seen.update(jwt=jwt, tool_key=tool_key, arguments=arguments,
                    server_name=server_row.name)
        return _result('{"rows":[]}')

    monkeypatch.setattr(mcp_client, "call_tool", fake_call)
    tool = runtime.build_mcp_tool(_tool_row(
        '{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}'),
        _server_row())
    runtime.run_jwt.set("e2e-jwt-token")
    calls: list[dict] = []
    runtime.run_tool_calls.set(calls)
    out = await tool.ainvoke({"dictKey": "user_status"})
    assert out == '{"rows":[]}'
    # JWT 经 contextvar 到达通道层；server 行/工具名/参数透传（按 server 路由调用）
    assert seen == {"jwt": "e2e-jwt-token", "tool_key": "e2e_query",
                    "arguments": {"dictKey": "user_status"}, "server_name": "SYSTEM"}
    # 审计：ok=true error=None，arguments 原样
    assert calls == [{"tool_key": "e2e_query", "arguments": {"dictKey": "user_status"},
                      "ok": True, "error": None}]


async def test_tool_wrapper_routes_by_server_row(monkeypatch: pytest.MonkeyPatch):
    """per-server 路由：外部 server 行原样进通道（url/timeout 不在包装器解析）。"""
    seen: dict = {}

    async def fake_call(server_row, tool_key, arguments, jwt):
        seen.update(url=server_row.url, is_builtin=server_row.is_builtin)
        return _result("ok")

    monkeypatch.setattr(mcp_client, "call_tool", fake_call)
    tool = runtime.build_mcp_tool(_tool_row('{"type":"object","properties":{}}'),
                                  _server_row(url="http://e2e-ext:9000/mcp", is_builtin=0))
    runtime.run_jwt.set("e2e-jwt")
    await tool.ainvoke({})
    assert seen == {"url": "http://e2e-ext:9000/mcp", "is_builtin": 0}


async def test_tool_wrapper_permission_denied_fixed_message(monkeypatch: pytest.MonkeyPatch):
    async def fake_call(server_row, tool_key, arguments, jwt):
        return _result("PERMISSION_DENIED: system:user:list required", is_error=True)

    monkeypatch.setattr(mcp_client, "call_tool", fake_call)
    tool = runtime.build_mcp_tool(_tool_row(
        '{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}'),
        _server_row())
    runtime.run_jwt.set("e2e-jwt")
    calls: list[dict] = []
    runtime.run_tool_calls.set(calls)
    out = await tool.ainvoke({"dictKey": "user_status"})
    # 固定文案，不透出服务端原文
    assert out == runtime.MSG_PERMISSION_DENIED_TO_MODEL
    assert calls[0]["ok"] is False
    assert calls[0]["error"] == runtime.MSG_PERMISSION_DENIED_TO_MODEL


async def test_tool_wrapper_iserror_plain_text_to_model(monkeypatch: pytest.MonkeyPatch):
    async def fake_call(server_row, tool_key, arguments, jwt):
        return _result("dictKey not found", is_error=True)

    monkeypatch.setattr(mcp_client, "call_tool", fake_call)
    tool = runtime.build_mcp_tool(_tool_row(
        '{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}'),
        _server_row())
    runtime.run_jwt.set("e2e-jwt")
    calls: list[dict] = []
    runtime.run_tool_calls.set(calls)
    out = await tool.ainvoke({"dictKey": "x"})
    # 错误文本回模型自救（不抛异常终断运行）
    assert out.startswith("工具执行出错：")
    assert "dictKey not found" in out
    assert calls[0]["ok"] is False
    assert calls[0]["error"] == "dictKey not found"


async def test_tool_wrapper_channel_exception_to_model(monkeypatch: pytest.MonkeyPatch):
    async def fake_call(server_row, tool_key, arguments, jwt):
        raise BusinessException("MCP 未配置", 5010)

    monkeypatch.setattr(mcp_client, "call_tool", fake_call)
    tool = runtime.build_mcp_tool(_tool_row(
        '{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}'),
        _server_row())
    runtime.run_jwt.set("e2e-jwt")
    calls: list[dict] = []
    runtime.run_tool_calls.set(calls)
    out = await tool.ainvoke({"dictKey": "x"})
    assert out == "工具调用失败：MCP 未配置"
    assert calls[0]["ok"] is False
    assert calls[0]["error"] == "工具调用失败：MCP 未配置"


async def test_tool_wrapper_missing_jwt_recorded():
    tool = runtime.build_mcp_tool(_tool_row(
        '{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}'),
        _server_row())
    runtime.run_jwt.set(None)  # 编排漏注入的防御分支
    calls: list[dict] = []
    runtime.run_tool_calls.set(calls)
    out = await tool.ainvoke({"dictKey": "x"})
    assert out == "工具调用失败：用户凭证缺失"
    assert calls[0]["error"] == "用户凭证缺失"


def test_tool_wrapper_name_and_description():
    tool = runtime.build_mcp_tool(_tool_row('{"type":"object","properties":{}}'),
                                  _server_row())
    assert tool.name == "e2e_query"
    assert tool.description == "工具描述"


# ---------- 组装与缓存 ----------


def test_get_agent_cache_hit_and_version_mismatch(stub_model):
    agent_row = _agent_row(config_version=1)
    first = runtime.get_agent(agent_row, [])
    assert runtime.get_agent(agent_row, []) is first  # 版本相同复用
    edited = _agent_row(config_version=2)
    second = runtime.get_agent(edited, [])
    assert second is not first  # 版本不匹配重建
    assert runtime.get_agent(edited, []) is second  # 新版本复用


def test_get_agent_lru_eviction(stub_model):
    # 33 个不同 id 触发逐最旧：id=101（最早）被逐出，重建后是新对象
    first = runtime.get_agent(_agent_row(config_version=1), [])
    for i in range(32):
        row = _agent_row(config_version=1)
        row.id = 200 + i
        runtime.get_agent(row, [])
    assert len(runtime._agent_cache) == 32
    again = runtime.get_agent(_agent_row(config_version=1), [])
    assert again is not first  # 已被逐出，重建


def test_assemble_agent_registers_trim_profile(stub_model):
    runtime.assemble_agent(_agent_row(), [])
    # 模型名已注册裁剪 profile（幂等集合判定）
    assert "test-model" in runtime._trim_profile_registered
    # 同名重复注册不报错（幂等）
    runtime.assemble_agent(_agent_row(), [])


def test_assemble_agent_with_tools_zero_network(stub_model, monkeypatch: pytest.MonkeyPatch):
    """组装带工具的 agent：deepagents 组装零网络（无 MCP 连接、无 LLM 请求）。"""
    tool_rows = [
        _tool_row('{"type":"object","properties":{"dictKey":{"type":"string"}},"required":["dictKey"]}',
                  tool_key="system_query_dict_items"),
        _tool_row('{"type":"object","properties":{"pageNum":{"type":"integer","default":1}}}',
                  tool_key="system_query_users"),
    ]
    # 组装入参是 (工具, 归属 server) 元组序列——server 行仅透传给通道层，组装本身不触网
    agent = runtime.assemble_agent(_agent_row(), [(tool, _server_row()) for tool in tool_rows])
    assert agent is not None
