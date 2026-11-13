from functools import lru_cache

import redis
from redis.cluster import ClusterNode, RedisCluster

from cloud_base_ai.config import get_settings


@lru_cache
def get_redis_client() -> RedisCluster:
    """Redis Cluster 客户端进程单例：连接参数取 Settings redis 段（地址不写死）。

    hosts 逗号分隔的每个地址各建一个起始节点（任一可达即完成集群发现）；
    集群模式仅 db 0，不传 db（redis-py 8.1.0 集群模式传 db 直接抛
    RedisClusterException——源码实证，勿加回）。
    """
    cfg = get_settings().redis
    startup_nodes = [
        ClusterNode(host=host, port=cfg.port)
        for host in (item.strip() for item in cfg.hosts.split(","))
        if host
    ]
    # 空串转 None：无密码集群跳过 AUTH 认证握手
    return RedisCluster(startup_nodes=startup_nodes, password=cfg.password or None)


@lru_cache
def get_sso_redis_client() -> redis.Redis:
    """单机会话 Redis 客户端进程单例：读 Java 侧在线会话键 sso:online:{jti}。

    与上方集群客户端并存、互不相干：6379 单机 = Java 会话库（本客户端），
    7000 集群 = AI 消息存储（上方客户端）。decode_responses=True——Java 侧值为
    JSON 字符串，直读即 str；空串密码归一 None（无密码实例跳过 AUTH）。
    连接参数取 Settings sso_redis 段（地址不写死）。
    显式 2s 套接字超时：GET 在事件循环内同步执行，redis-py 默认无超时会把
    6379 挂起放大成整个服务冻结——超时后由认证中间件按 Redis 异常 fail-closed 401。
    """
    cfg = get_settings().sso_redis
    return redis.Redis(host=cfg.host, port=cfg.port,
                       password=cfg.password or None, decode_responses=True,
                       socket_timeout=2.0, socket_connect_timeout=2.0)
