# -*- coding: utf-8 -*-
"""Redis Cluster 真连接冒烟：经 Settings redis 段建客户端，PING + 读集群拓扑。

前置：目标 Redis 以 cluster 模式运行（--cluster-enabled yes）。单机模式实例
（默认 docker run redis）在集群发现阶段即失败——这是集群客户端的既定行为，
非配置错误。用法：uv run --project cloud-base-ai python scripts/smoke/smoke_redis.py
"""
import sys


def main() -> int:
    from cloud_base_ai.config import get_settings
    from cloud_base_ai.redis_client import get_redis_client

    cfg = get_settings().redis
    print(f"[cfg] hosts={cfg.hosts} port={cfg.port} password={'***' if cfg.password else '(empty)'}")

    client = get_redis_client()
    pong = client.ping()
    print(f"[ping] {pong}")

    info = client.info("cluster")
    print(f"[cluster] cluster_enabled={info.get('cluster_enabled')} "
          f"known_nodes={client.cluster_info().get('known_nodes')}")

    client.set("smoke:redis:key", "ok", ex=60)
    got = client.get("smoke:redis:key")
    print(f"[rw] set/get smoke:redis:key -> {got!r}")
    client.delete("smoke:redis:key")

    print("SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
