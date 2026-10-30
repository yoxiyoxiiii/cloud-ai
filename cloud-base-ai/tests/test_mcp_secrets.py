"""MCP 凭据解析测试：os.environ 优先 / .env 兜底 / 命名空间 pattern / 未知键 None。

不触真实 .env 内容（monkeypatch 缓存函数桩），真值红线：断言只比对注入的测试值。
"""

import pytest

from cloud_base_ai.mcp import secrets


@pytest.fixture(autouse=True)
def _fresh_env_cache(monkeypatch: pytest.MonkeyPatch):
    """隔离 .env 读缓存与进程 env：每用例清缓存 + 清测试键，防跨用例与跨进程泄漏。

    收尾用 getattr 兜底：teardown 先于 monkeypatch 撤销执行，此时函数可能已被
    用例替换为无 cache_clear 的普通 lambda。
    """
    secrets._env_file_values.cache_clear()
    import os
    test_keys = [k for k in os.environ if k.startswith("CLOUD_BASE_AI_MCP__SERVERS__E2E_")]
    for k in test_keys:
        monkeypatch.delenv(k, raising=False)
    yield
    getattr(secrets._env_file_values, "cache_clear", lambda: None)()


def test_env_var_takes_priority(monkeypatch: pytest.MonkeyPatch) -> None:
    """真实环境变量优先于 .env 文件（冒烟脚本借此注入临时凭据）。"""
    key = "CLOUD_BASE_AI_MCP__SERVERS__E2E_WEATHER__TOKEN"
    monkeypatch.setattr(secrets, "_env_file_values", lambda: {key: "from-env-file"})
    monkeypatch.setenv(key, "from-os-environ")
    assert secrets.resolve(key) == "from-os-environ"


def test_env_file_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    """env 无该键时走 .env 文件兜底（dotenv_values 直读路径）。"""
    key = "CLOUD_BASE_AI_MCP__SERVERS__E2E_WEATHER__TOKEN"
    monkeypatch.setattr(secrets, "_env_file_values", lambda: {key: "from-env-file"})
    monkeypatch.delenv(key, raising=False)
    assert secrets.resolve(key) == "from-env-file"


def test_unknown_key_returns_none(monkeypatch: pytest.MonkeyPatch) -> None:
    """os.environ 与 .env 均无 → None（调用方按 5026 转业务码）。"""
    monkeypatch.setattr(secrets, "_env_file_values", lambda: {})
    assert secrets.resolve("CLOUD_BASE_AI_MCP__SERVERS__E2E_NOPE__TOKEN") is None


def test_credential_key_pattern() -> None:
    """命名空间护栏：仅 MCP server 凭据段键合法（防引用 LLM api_key 等其他敏感键）。"""
    ok = [
        "CLOUD_BASE_AI_MCP__SERVERS__WEATHER__TOKEN",
        "CLOUD_BASE_AI_MCP__SERVERS__MY_SERVER_2__API_KEY",
    ]
    bad = [
        "CLOUD_BASE_AI_LLM__API_KEY",  # 其他配置段的敏感键
        "CLOUD_BASE_AI_MCP__SERVERS__WEATHER",  # 缺 <KEY> 段
        "CLOUD_BASE_AI_MCP__SERVERS__weather__TOKEN",  # name 段须大写
        "CLOUD_BASE_AI_MCP__SERVERS__WEATHER__token",  # key 段须大写
        "PATH",
    ]
    for key in ok:
        assert secrets.CREDENTIAL_KEY_PATTERN.match(key), key
    for key in bad:
        assert not secrets.CREDENTIAL_KEY_PATTERN.match(key), key
