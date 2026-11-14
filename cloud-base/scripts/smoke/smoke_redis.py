# -*- coding: utf-8 -*-
"""Redis Cluster 真连接冒烟：经 Settings redis 段建客户端，PING + 读集群拓扑。

前置：目标 Redis 以 cluster 模式运行（--cluster-enabled yes）。单机模式实例
（默认 docker run redis）在集群发现阶段即失败——这是集群客户端的既定行为，
非配置错误。用法（仓库根执行）：
C:/Users/jy-root/.local/bin/uv.exe run --project cloud-ai-common python cloud-base/scripts/smoke/smoke_redis.py

Redis 段默认值即本机事实值（127.0.0.1:7000 无密码），跨环境经
CLOUD_BASE_AI_REDIS__* 环境变量覆盖。共享库工程没有服务侧可注册 settings
provider，在此就地注册最小桩（库段为占位值，本脚本不触库）。
"""
import sys


def main() -> int:
    from cloud_ai_common.config import (
        CommonSettings,
        DatabaseSettings,
        get_settings,
        set_settings_provider,
    )
    from cloud_ai_common.redis_client import get_redis_client

    set_settings_provider(
        lambda: CommonSettings(
            db=DatabaseSettings(database="cloud_ai_smoke_stub"),
            app_port=0,
            _env_file=None,
        )
    )

    cfg = get_settings().redis
    print(f"[cfg] hosts={cfg.hosts} port={cfg.port} password={'***' if cfg.password else '(empty)'}")

    client = get_redis_client()
    pong = client.ping()
    print(f"[ping] {pong}")

    info = client.info("cluster")
    print(f"[cluster] cluster_enabled={info.get('cluster_enabled')} "
          f"known_nodes={client.cluster_info().get('cluster_known_nodes')}")

    client.set("smoke:redis:key", "ok", ex=60)
    got = client.get("smoke:redis:key")
    print(f"[rw] set/get smoke:redis:key -> {got!r}")
    client.delete("smoke:redis:key")

    print("SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
