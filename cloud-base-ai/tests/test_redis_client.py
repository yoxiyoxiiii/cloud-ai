"""单机会话 Redis 客户端构造测试（redis_client.get_sso_redis_client）。

构造零网络调用（redis-py 懒连接），可真构造；单例/参数归一口径在此断言，
真实读写由 scripts/smoke/smoke_rag_mcp.py 真连接冒烟覆盖。
"""

import redis

from cloud_base_ai.redis_client import get_sso_redis_client


def test_sso_client_singleton() -> None:
    """lru_cache 进程单例：两次调用同实例。"""
    try:
        assert get_sso_redis_client() is get_sso_redis_client()
    finally:
        get_sso_redis_client.cache_clear()


def test_sso_client_shape(monkeypatch) -> None:
    """单机客户端形态：decode_responses=True（Java 侧 JSON 字符串直读得 str）；
    空串密码归一 None（无密码实例跳过 AUTH）——setenv 空串使断言不依赖本机
    .env 是否配了密码键（与 test_settings 的 env 隔离纪律同向）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_SSO_REDIS__PASSWORD", "")
    try:
        client = get_sso_redis_client()
        assert isinstance(client, redis.Redis)
        kwargs = client.get_connection_kwargs()
        assert kwargs.get("decode_responses") is True
        assert kwargs.get("password") is None
        assert kwargs.get("socket_timeout") == 2.0
    finally:
        get_sso_redis_client.cache_clear()
