"""共享配置基座：CommonSettings（五公共段）+ settings provider 注册点。

公共段（nacos/llm/mcp/db/redis）由两个服务工程继承；rag 专属段（embedding_llm/
milvus/minio/sso_redis）在各服务侧 config.py 定义，不进本库。本模块不带 env_file
——.env 路径各服务侧自定（服务侧子类补 env_file=<本工程根>/.env）。

provider 机制解「common 代码要读配置但 common 不能 import 服务」：common 的
db/engine、redis_client、model_factory 经 get_settings() 取配置；服务侧 import
自己的 config 模块时（模块底部）把本侧 lru_cache 单例注册进来，全程一个实例。
"""

from collections.abc import Callable
from functools import lru_cache

from pydantic import BaseModel
from pydantic_settings import BaseSettings, SettingsConfigDict


class NacosSettings(BaseModel):
    """Nacos 注册参数（env 覆盖形态：CLOUD_BASE_AI_NACOS__*，嵌套分隔 "__"）。"""

    server_addr: str = "127.0.0.1:8848"
    namespace_id: str = ""  # 空 = public（SDK naming 自动转 "public"，封装统一空串入参）
    group_name: str = "DEFAULT_GROUP"
    # 默认空 = 未配置态（对齐 LLM api_key / mcp client_secret 哲学，不阻断启动）：
    # 三工程各有注册名，库级写死任何默认都会在漏配时静默注册成错误服务名（网关
    # lb 按名路由，错名即 404）。空值由注册器拦截降级（nacos_registered=false 可观测），
    # 正确值由服务入口 env setdefault 提供（app.py import config 前）。
    service_name: str = ""
    ip: str = "127.0.0.1"  # 注册 IP，可覆盖（不做网卡探测，多网卡机器显式配置）


class LLMSettings(BaseModel):
    """LLM 接入参数：api_key 真值仅存服务侧本地 .env，不入库不入档不入日志。

    env 覆盖形态：CLOUD_BASE_AI_LLM__API_KEY / __BASE_URL / __MODEL / __TIMEOUT /
    __MAX_RETRIES / __HISTORY_LIMIT；优先级 真实 env > .env > 默认值。
    """

    api_key: str = ""  # 默认空 = 未配置态（调用时报 5002，不阻断启动——同注册降级哲学）
    base_url: str = "https://api.deepseek.com"  # OpenAI 兼容端点，非 secret 可入库
    model: str = "deepseek-flash"
    timeout: float = 60.0  # 单次 LLM 请求超时（秒）
    max_retries: int = 1  # SDK 内建重试次数（过重放大流式挂起，取 1）
    history_limit: int = 20  # 服务层历史截断（条，防 context 爆量与费用滥用）


class McpSettings(BaseModel):
    """MCP 客户端通道参数：内置服务的连接地址已迁至 DB（ai_mcp_server 内置行 url 列）
    ——旧键 CLOUD_BASE_AI_MCP__BASE_URL 退役不读（保留无害）；外部 server 连接配置
    全在 DB 注册表。

    env 覆盖形态：CLOUD_BASE_AI_MCP__CLIENT_SECRET。
    client_secret 与 cloud-system Nacos cloud.mcp.client-secret 同值，真值仅本地 .env；
    默认空 = 未配置态（调用时报 5010，不阻断启动——同 LLM api_key 哲学）。
    """

    client_secret: str = ""


class DatabaseSettings(BaseModel):
    """MySQL 连接参数：默认本机事实值——DB 是本机基础设施（与 LLM 外部付费依赖的
    「默认空=未配置态」哲学不同源，对齐 Nacos 段默认 127.0.0.1 先例）。

    database 必填无默认：双库拆分（cloud_ai_rag / cloud_ai_agent）后，漏配库名
    静默回落旧库 cloud_ai 是数据写错库事故，必填把漏配提前到构造期。
    env 覆盖形态：CLOUD_BASE_AI_DB__HOST / __PORT / __USER / __PASSWORD / __DATABASE /
    __POOL_SIZE / __MAX_OVERFLOW / __POOL_RECYCLE / __ECHO。
    """

    host: str = "127.0.0.1"
    port: int = 3306
    user: str = "root"
    password: str = ""  # 本机 root 空密码事实默认；跨环境走 .env，真值不入库
    database: str
    pool_size: int = 5
    max_overflow: int = 10
    pool_recycle: int = 3600  # 防 MySQL wait_timeout（5.7 默认 8h）stale 连接
    echo: bool = False


class RedisSettings(BaseModel):
    """Redis Cluster 连接参数（redis-py RedisCluster）：集群模式**无 db 键**——客户端
    传 db 直接抛 RedisClusterException，且集群本身只支持 db 0。地址走配置不写死。

    env 覆盖形态：CLOUD_BASE_AI_REDIS__HOSTS / __PORT / __PASSWORD。
    hosts 为逗号分隔起始节点（任一可达即完成集群发现）；password 默认空 = 无密码集群。
    消费方 cloud_ai_common.redis_client.get_redis_client()。
    """

    hosts: str = "127.0.0.1"
    # 7000 = redis-cluster 容器（单节点集群，AI 消息存储）；6379 单机容器归 Java 侧
    # 会话（rag 侧另有 sso_redis 段，仅 rag 服务消费），两者并存互不影响
    port: int = 7000
    password: str = ""


class CommonSettings(BaseSettings):
    """公共配置根（env 前缀 CLOUD_BASE_AI_，嵌套分隔符 "__"——单 "_" 与内层字段名
    自身含下划线歧义，含下划线内层字段覆盖会静默失效，2026-10-12 实测后拍板 "__"）。

    服务侧 Settings 继承本类并补 env_file；app_port 必填——注册实例端口漏配属
    启动期事故（与 database 必填同理），由服务侧子类给各自默认（8011/8012）。
    """

    nacos: NacosSettings = NacosSettings()
    llm: LLMSettings = LLMSettings()
    mcp: McpSettings = McpSettings()
    db: DatabaseSettings  # 必填：库名默认值由服务侧子类段落给（cloud_ai_rag / cloud_ai_agent）
    redis: RedisSettings = RedisSettings()
    app_port: int  # 必填：服务侧子类给各自默认（8011 / 8012）

    model_config = SettingsConfigDict(
        env_prefix="CLOUD_BASE_AI_",
        env_nested_delimiter="__",
    )


_provider: Callable[[], CommonSettings] | None = None


def set_settings_provider(fn: Callable[[], CommonSettings]) -> None:
    """服务侧（或测试桩）注册 settings 工厂；import 服务侧 config 模块时执行。"""
    global _provider
    _provider = fn


def get_settings() -> CommonSettings:
    """取当前进程 settings 单例（common 代码统一入口）。

    未注册即 RuntimeError：common 是库不能 import 服务侧 config，漏注册时
    快速失败优于静默取错配置。
    """
    if _provider is None:
        raise RuntimeError("服务侧未注册 settings provider（须先 import 服务侧 config）")
    return _provider()
