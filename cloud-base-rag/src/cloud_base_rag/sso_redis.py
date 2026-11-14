"""Java 会话单机 Redis 客户端（rag 专属）：读 6379 在线会话键 sso:online:{jti}。

AI 消息集群客户端（7000）是两侧共享面，在 cloud_ai_common.redis_client；
本客户端仅 rag 服务的 MCP 认证中间件消费（agent 进程不连 6379）。
"""

from functools import lru_cache

import redis

from cloud_base_rag.config import get_settings


@lru_cache
def get_sso_redis_client() -> redis.Redis:
    """单机会话 Redis 客户端进程单例。

    decode_responses=True——Java 侧值为 JSON 字符串，直读即 str；空串密码归一
    None（无密码实例跳过 AUTH）。连接参数取 Settings sso_redis 段（地址不写死）。
    显式 2s 套接字超时：GET 在事件循环内同步执行，redis-py 默认无超时会把
    6379 挂起放大成整个服务冻结——超时后由认证中间件按 Redis 异常 fail-closed 401。
    """
    cfg = get_settings().sso_redis
    return redis.Redis(host=cfg.host, port=cfg.port,
                       password=cfg.password or None, decode_responses=True,
                       socket_timeout=2.0, socket_connect_timeout=2.0)
