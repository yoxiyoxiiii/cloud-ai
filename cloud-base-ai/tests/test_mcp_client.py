"""MCP 客户端通道封装测试：未配置 5010 / 调用异常转 5009 / 权限拒绝判定。

不连真实 MCP server（ai-spec：单测不连外部系统）；连接行为用假 Client 替身。
"""

import asyncio

import pytest

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


class _RefusedClient:
    """连接即失败替身：模拟网络拒绝/协议错误。"""

    def __init__(self, *args, **kwargs) -> None:
        pass

    async def __aenter__(self):
        raise ConnectionError("connection refused")

    async def __aexit__(self, *exc):
        return False


def test_not_configured_raises_5010(monkeypatch: pytest.MonkeyPatch) -> None:
    """secret 空=未配置态：调 build_client 即抛 5010，不做任何连接。"""
    use_settings(monkeypatch, secret="")
    with pytest.raises(BusinessException) as ei:
        mcp_client.build_client("jwt")
    assert ei.value.code == 5010
    assert ei.value.msg == "MCP 未配置"


def test_call_failure_translates_5009(monkeypatch: pytest.MonkeyPatch) -> None:
    """连接失败统一转 5009（MSG/CODE 成对常量）。"""
    use_settings(monkeypatch, secret="test-secret")
    monkeypatch.setattr(mcp_client, "Client", _RefusedClient)
    with pytest.raises(BusinessException) as ei:
        asyncio.run(mcp_client.call_tool("jwt", "system_query_users", {}))
    assert ei.value.code == 5009
    assert ei.value.msg == "MCP 调用失败"


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
