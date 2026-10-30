"""MCP 客户端通道测试：per-server 头组装（内置双主体/外部静态+凭据）/ transport 分流 /
超时换算 / 未配置 5010 / 凭据缺失 5026 / 调用异常转 5009 / 权限拒绝判定。

不连真实 MCP server（ai-spec：单测不连外部系统）；Client 用捕获替身、secrets 用
monkeypatch 桩；断言只比对头的键名与已知测试值，真值红线不涉及。
"""

import asyncio
from types import SimpleNamespace

import pytest
from fastmcp.client import StreamableHttpTransport
from fastmcp.client.transports.sse import SSETransport

from cloud_base_ai.config import Settings
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import client as mcp_client


def use_settings(monkeypatch: pytest.MonkeyPatch, secret: str) -> None:
    """让通道读到受控配置：直接替换模块内 get_settings（lru_cache 单例不进测试）。

    先 delenv 再构造——uv run 会把项目 .env 注入进程 env，真实 secret 会盖过
    默认空，且真值严禁出现在测试环境（同 test_settings 的 delenv 先例）。
    """
    monkeypatch.delenv("CLOUD_BASE_AI_MCP__CLIENT_SECRET", raising=False)
    monkeypatch.setattr(
        mcp_client, "get_settings", lambda: Settings(_env_file=None)
    )
    if secret:
        monkeypatch.setenv("CLOUD_BASE_AI_MCP__CLIENT_SECRET", secret)


def _server_row(**overrides) -> SimpleNamespace:
    """外部 server 替身行（鸭子类型六属性）；覆盖项按用例注入。"""
    row = SimpleNamespace(
        name="e2e_EXT", url="http://127.0.0.1:9000/mcp", transport="http",
        headers_json=None, credential_headers_json=None,
        timeout_ms=30000, is_builtin=0,
    )
    for k, v in overrides.items():
        setattr(row, k, v)
    return row


def _builtin_row() -> SimpleNamespace:
    return _server_row(name="SYSTEM", url="http://127.0.0.1:9202/mcp", is_builtin=1)


class _ClientCapture:
    """Client 替身：捕获构造入参（transport/timeout），不做任何连接。"""

    def __init__(self, transport, timeout=None):
        self.transport = transport
        self.timeout = timeout


@pytest.fixture
def capture_client(monkeypatch: pytest.MonkeyPatch) -> type[_ClientCapture]:
    monkeypatch.setattr(mcp_client, "Client", _ClientCapture)
    return _ClientCapture


# ---------- 头组装：内置双主体 ----------


def test_builtin_headers_dual_principal(capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """内置行：Authorization=当次用户 JWT + X-MCP-Client-Secret；StreamableHttpTransport。"""
    use_settings(monkeypatch, secret="test-secret")
    client = mcp_client.build_client(_builtin_row(), "e2e-jwt")
    assert isinstance(client.transport, StreamableHttpTransport)
    assert client.transport.url == "http://127.0.0.1:9202/mcp"
    assert client.transport.headers == {
        "Authorization": "Bearer e2e-jwt",
        "X-MCP-Client-Secret": "test-secret",
    }


def test_builtin_not_configured_5010(capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """内置行 secret 空 = 未配置态：5010，不做任何连接。"""
    use_settings(monkeypatch, secret="")
    with pytest.raises(BusinessException) as ei:
        mcp_client.build_client(_builtin_row(), "e2e-jwt")
    assert ei.value.code == 5010
    assert ei.value.msg == "MCP 未配置"


def test_builtin_missing_jwt_rejected(capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """内置行用户 JWT 缺失（调用方缺陷防御分支）：按调用失败拒绝。"""
    use_settings(monkeypatch, secret="test-secret")
    with pytest.raises(BusinessException) as ei:
        mcp_client.build_client(_builtin_row(), None)
    assert ei.value.code == 5009


# ---------- 头组装：外部静态 + 凭据键 ----------


def test_external_headers_static_and_credentials(
        capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """外部行：静态字面值 ∪ 凭据键解析值；同名冲突凭据头胜；用户 JWT 不转发。"""
    use_settings(monkeypatch, secret="")
    row = _server_row(
        headers_json='{"X-Org-Id": "cloudai", "Authorization": "static-should-lose"}',
        credential_headers_json='{"Authorization": "CLOUD_BASE_AI_MCP__SERVERS__E2E_EXT__TOKEN"}',
    )
    monkeypatch.setattr(mcp_client.secrets, "resolve", lambda key: "resolved-token-value")
    client = mcp_client.build_client(row, "e2e-user-jwt")
    assert client.transport.headers == {
        "X-Org-Id": "cloudai",
        "Authorization": "resolved-token-value",  # 凭据头覆盖静态同名
    }
    # 外部体系不认识平台 JWT——头里不得出现当次用户 JWT
    assert "e2e-user-jwt" not in str(client.transport.headers)


def test_external_credential_missing_5026(capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """凭据键解析不到值（os.environ 与 .env 均无）：5026，msg 只含键名不含真值。"""
    use_settings(monkeypatch, secret="")
    row = _server_row(
        credential_headers_json='{"Authorization": "CLOUD_BASE_AI_MCP__SERVERS__E2E_EXT__TOKEN"}',
    )
    monkeypatch.setattr(mcp_client.secrets, "resolve", lambda key: None)
    with pytest.raises(BusinessException) as ei:
        mcp_client.build_client(row, None)
    assert ei.value.code == 5026
    assert "CLOUD_BASE_AI_MCP__SERVERS__E2E_EXT__TOKEN" in ei.value.msg


# ---------- transport 分流与超时换算 ----------


def test_transport_sse_branch_and_timeout(capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """transport='sse' → SSETransport；timeout_ms 毫秒 → Client(timeout=秒)。"""
    use_settings(monkeypatch, secret="")
    row = _server_row(transport="sse", timeout_ms=4500)
    client = mcp_client.build_client(row, None)
    assert isinstance(client.transport, SSETransport)
    assert client.timeout == 4.5


def test_transport_http_default_and_default_timeout(
        capture_client, monkeypatch: pytest.MonkeyPatch) -> None:
    """transport='http' → StreamableHttpTransport；默认 30000ms → 30.0s。"""
    use_settings(monkeypatch, secret="")
    client = mcp_client.build_client(_server_row(), None)
    assert isinstance(client.transport, StreamableHttpTransport)
    assert client.timeout == 30.0


# ---------- 调用异常转译 ----------


class _RefusedClient:
    """连接即失败替身：模拟网络拒绝/协议错误。"""

    def __init__(self, *args, **kwargs) -> None:
        pass

    async def __aenter__(self):
        raise ConnectionError("connection refused")

    async def __aexit__(self, *exc):
        return False


def test_call_failure_translates_5009(monkeypatch: pytest.MonkeyPatch) -> None:
    """连接失败统一转 5009（MSG/CODE 成对常量）。"""
    use_settings(monkeypatch, secret="test-secret")
    monkeypatch.setattr(mcp_client, "Client", _RefusedClient)
    with pytest.raises(BusinessException) as ei:
        asyncio.run(mcp_client.call_tool(_builtin_row(), "system_query_users", {}, "jwt"))
    assert ei.value.code == 5009
    assert ei.value.msg == "MCP 调用失败"


def test_call_business_exception_passthrough(monkeypatch: pytest.MonkeyPatch) -> None:
    """BusinessException（5026/5010 等）不吞不转——通道层只包网络类异常。"""
    use_settings(monkeypatch, secret="test-secret")

    def boom(server_row, user_jwt):
        raise BusinessException("MCP服务连接失败", 5025)

    monkeypatch.setattr(mcp_client, "build_client", boom)
    with pytest.raises(BusinessException) as ei:
        asyncio.run(mcp_client.call_tool(_builtin_row(), "t", {}, "jwt"))
    assert ei.value.code == 5025


# ---------- tools/list 通道动词（同步/连通测试共用） ----------


class _ToolsClient:
    """握手 + list_tools 成功替身：返回两个具名工具。"""

    def __init__(self, *args, **kwargs) -> None:
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def list_tools(self):
        from types import SimpleNamespace
        return [SimpleNamespace(name="t1"), SimpleNamespace(name="t2")]


def test_list_tools_ok_returns_elements(monkeypatch: pytest.MonkeyPatch) -> None:
    use_settings(monkeypatch, secret="test-secret")
    monkeypatch.setattr(mcp_client, "Client", _ToolsClient)
    tools = asyncio.run(mcp_client.list_tools(_builtin_row(), "jwt"))
    assert [t.name for t in tools] == ["t1", "t2"]


def test_list_tools_connect_failed_5025(monkeypatch: pytest.MonkeyPatch) -> None:
    """网络失败统一转 5025，msg 带异常类型摘要（不含凭据真值）。"""
    use_settings(monkeypatch, secret="test-secret")
    monkeypatch.setattr(mcp_client, "Client", _RefusedClient)
    with pytest.raises(BusinessException) as ei:
        asyncio.run(mcp_client.list_tools(_builtin_row(), "jwt"))
    assert ei.value.code == 5025
    assert "ConnectionError" in ei.value.msg


# ---------- 权限拒绝判定（既有语义不变） ----------


class _DeniedContent:
    def __init__(self, text: str) -> None:
        self.text = text


class _DeniedResult:
    def __init__(self, text: str) -> None:
        self.content = [_DeniedContent(text)]
        self.is_error = True


def test_is_permission_denied_by_prefix() -> None:
    """服务端权限拒绝约定：isError + 文本前缀 PERMISSION_DENIED:。"""
    assert mcp_client.is_permission_denied(
        _DeniedResult("PERMISSION_DENIED: system:user:list")
    )
    assert not mcp_client.is_permission_denied(_DeniedResult("other error"))
    # 前缀必须精确（PERMISSION_DENIEDx 不算）
    assert not mcp_client.is_permission_denied(
        _DeniedResult("PERMISSION_DENIEDx: system:user:list")
    )


def test_is_permission_denied_no_content() -> None:
    """无 content 的结果不误判为权限拒绝。"""

    class _Empty:
        content = []
        is_error = False

    assert not mcp_client.is_permission_denied(_Empty())
