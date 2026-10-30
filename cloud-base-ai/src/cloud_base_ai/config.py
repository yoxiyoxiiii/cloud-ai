"""配置模块：pydantic-settings，Nacos 注册（R11 D5）+ LLM 段（2026-10-13 AI 对话轮 D1）+ DB 段（2026-10-13 ORM/DAO 轮 D6）。"""

from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel
from pydantic_settings import BaseSettings, SettingsConfigDict

# 工程根绝对路径（src/cloud_base_ai/config.py 三层上 = cloud-base-ai/）。
# env_file 必须绝对路径：pydantic-settings 相对路径按进程 cwd 解析，起服命令 cwd 在仓库根会指错。
BASE_DIR = Path(__file__).resolve().parents[2]


class NacosSettings(BaseModel):
    """Nacos 注册参数（env 覆盖形态：CLOUD_BASE_AI_NACOS__*，嵌套分隔 "__"）。"""

    server_addr: str = "127.0.0.1:8848"
    namespace_id: str = ""  # 空 = public（SDK naming 自动转 "public"，封装统一空串入参）
    group_name: str = "DEFAULT_GROUP"
    service_name: str = "cloud-base-ai"
    ip: str = "127.0.0.1"  # 注册 IP，可覆盖（D3：不做网卡探测）


class LLMSettings(BaseModel):
    """LLM 接入参数（spec D1）：api_key 真值仅存本地 .env，不入库不入档不入日志。

    env 覆盖形态：CLOUD_BASE_AI_LLM__API_KEY / __BASE_URL / __MODEL / __TIMEOUT /
    __MAX_RETRIES / __HISTORY_LIMIT；优先级 真实 env > .env > 默认值。
    """

    api_key: str = ""  # 默认空 = 未配置态（调用时报 5002，不阻断启动——同注册降级哲学）
    base_url: str = "https://api.deepseek.com"  # OpenAI 兼容端点，非 secret 可入库
    model: str = "deepseek-flash"
    timeout: float = 60.0  # 单次 LLM 请求超时（秒，D11）
    max_retries: int = 1  # SDK 内建重试次数（D11：过重放大流式挂起，取 1）
    history_limit: int = 20  # 服务层历史截断（条，D10：防 context 爆量与费用滥用）

class EmbeddingModelSettings(BaseModel):
    """
    embedding model 配置信息
    """
    api_key: str = ""  # 默认空 = 未配置态（调用时报 5002，不阻断启动——同注册降级哲学）
    base_url: str = "http://127.0.0.1:11434"  # OpenAI 兼容端点，非 secret 可入库
    model: str = "qllama/bge-large-zh-v1.5:latest"
    embedding_dimension: int = 1024  # 向量维度
    type: str = "ollama"

class MilvusSettings(BaseModel):
    """Milvus 向量库连接参数：地址走配置不写死（规范「接入外部系统」）。

    env 覆盖形态：CLOUD_BASE_AI_MILVUS__URI。默认 127.0.0.1:19530 保持历史行为；
    宿主端口映射变化时经 .env / 环境变量覆盖，不动代码。
    """

    uri: str = "http://127.0.0.1:19530"


class MinIOSettings(BaseModel):
    """MinIO 对象存储连接参数：本机基础设施默认端口；凭证为部署期自设值，
    真值仅本地 .env（同 LLM api_key 哲学，默认空）。本轮无启动期消费方，空凭证不阻断启动。

    env 覆盖形态：CLOUD_BASE_AI_MINIO__ENDPOINT / __ACCESS_KEY / __SECRET_KEY /
    __BUCKET / __SECURE。
    """

    endpoint: str = "127.0.0.1:9100"  # host:port 无协议（minio SDK 入参格式），容器映射 9100→9000
    access_key: str = ""
    secret_key: str = ""
    bucket: str = "cloud-ai"
    secure: bool = False  # 本机 http；TLS 部署置 True


class McpSettings(BaseModel):
    """MCP 客户端通道参数：内置服务的连接地址已迁至 DB（ai_mcp_server 内置行 url 列，
    2026-10-30 MCP server 管理轮）——.env 旧键 CLOUD_BASE_AI_MCP__BASE_URL 退役不读
    （保留无害）；外部 server 连接配置全在 DB 注册表。

    env 覆盖形态：CLOUD_BASE_AI_MCP__CLIENT_SECRET。
    client_secret 与 cloud-system Nacos cloud.mcp.client-secret 同值，真值仅本地 .env；
    默认空 = 未配置态（调用时报 5010，不阻断启动——同 LLM api_key 哲学）。
    """

    client_secret: str = ""


class DatabaseSettings(BaseModel):
    """MySQL 连接参数（spec D6）：默认本机事实值——DB 是本机基础设施（与 LLM 外部
    付费依赖的「默认空=未配置态」哲学不同源，对齐 Nacos 段默认 127.0.0.1 先例）。

    env 覆盖形态：CLOUD_BASE_AI_DB__HOST / __PORT / __USER / __PASSWORD / __DATABASE /
    __POOL_SIZE / __MAX_OVERFLOW / __POOL_RECYCLE / __ECHO。
    """

    host: str = "127.0.0.1"
    port: int = 3306
    user: str = "root"
    password: str = ""  # 本机 root 空密码事实默认；跨环境走 .env，真值不入库
    database: str = "cloud_ai"
    pool_size: int = 5
    max_overflow: int = 10
    pool_recycle: int = 3600  # 防 MySQL wait_timeout（5.7 默认 8h）stale 连接
    echo: bool = False


class RedisSettings(BaseModel):
    """Redis Cluster 连接参数（redis-py RedisCluster，装机版 8.1.0）：
    集群模式**无 db 键**——客户端传 db 直接抛 RedisClusterException（源码实证），
    且集群本身只支持 db 0。地址走配置不写死（「接入外部系统」规范）。

    env 覆盖形态：CLOUD_BASE_AI_REDIS__HOSTS / __PORT / __PASSWORD。
    hosts 为逗号分隔起始节点（任一可达即可完成集群发现，拓扑发现后自动接管）；
    password 默认空 = 无密码集群（本机常态），真值跨环境走 .env 不入库。
    消费方 redis_client.get_redis_client()。
    """

    hosts: str = "127.0.0.1"
    # 7000 = redis-cluster 容器（单节点集群，AI 消息存储）；6379 单机容器归 Java 侧会话，
    # 两者并存互不影响
    port: int = 7000
    password: str = ""


class Settings(BaseSettings):
    """应用配置根（env 前缀 CLOUD_BASE_AI_，嵌套分隔符 "__"——单 "_" 与内层字段名
    自身含下划线歧义，含下划线内层字段覆盖会静默失效，2026-10-12 实测后主控拍板 "__"）。
    """

    nacos: NacosSettings = NacosSettings()
    llm: LLMSettings = LLMSettings()
    embedding_llm: EmbeddingModelSettings = EmbeddingModelSettings()
    milvus: MilvusSettings = MilvusSettings()
    minio: MinIOSettings = MinIOSettings()
    mcp: McpSettings = McpSettings()
    db: DatabaseSettings = DatabaseSettings()
    redis: RedisSettings = RedisSettings()
    app_port: int = 8010  # uvicorn 监听与注册实例端口同源

    model_config = SettingsConfigDict(
        env_prefix="CLOUD_BASE_AI_",
        env_nested_delimiter="__",
        env_file=str(BASE_DIR / ".env"),  # 绝对路径随工程走（D1 关键坑）
        env_file_encoding="utf-8",
    )


@lru_cache
def get_settings() -> Settings:
    """进程内单例。"""
    return Settings()
