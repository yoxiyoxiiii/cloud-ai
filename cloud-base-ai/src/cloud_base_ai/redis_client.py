from functools import lru_cache

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
