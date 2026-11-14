"""RAG 服务配置：继承公共段（cloud_ai_common.CommonSettings）+ rag 专属四段。

专属段：embedding_llm（Ollama）/ milvus / minio / sso_redis（Java 会话单机库）。
库名默认 cloud_ai_rag（双库拆分）；模块底部向 common 注册 settings provider——
import 本模块即完成注册，common 的 db/redis/model 工厂经 provider 读到本侧单例。
"""

import os
from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel, Field
from pydantic_settings import SettingsConfigDict

from cloud_ai_common.config import CommonSettings, DatabaseSettings, set_settings_provider

# 注册名必填（库级无默认，漏配即构造期 ValidationError）：运行时由入口 app.py 的
# setdefault 提供；本模块级再 setdefault 一次兜测试链——测试 import config 不经入口。
os.environ.setdefault("CLOUD_BASE_AI_NACOS__SERVICE_NAME", "cloud-base-rag")

# 工程根绝对路径（src/cloud_base_rag/config.py 三层上 = cloud-base-rag/）。
# env_file 必须绝对路径：pydantic-settings 相对路径按进程 cwd 解析，起服命令 cwd
# 在仓库根会指错。
BASE_DIR = Path(__file__).resolve().parents[2]


class EmbeddingModelSettings(BaseModel):
    """embedding 模型配置（Ollama 本地端点，零鉴权无未配置态）。

    env 覆盖形态：CLOUD_BASE_AI_EMBEDDING_LLM__BASE_URL / __MODEL /
    __EMBEDDING_DIMENSION / __TYPE。
    """

    api_key: str = ""
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
    真值仅本地 .env（同 LLM api_key 哲学，默认空）。空凭证不阻断启动。

    env 覆盖形态：CLOUD_BASE_AI_MINIO__ENDPOINT / __ACCESS_KEY / __SECRET_KEY /
    __BUCKET / __SECURE。
    """

    endpoint: str = "127.0.0.1:9100"  # host:port 无协议（minio SDK 入参格式），容器映射 9100→9000
    access_key: str = ""
    secret_key: str = ""
    bucket: str = "cloud-ai"
    secure: bool = False  # 本机 http；TLS 部署置 True


class SsoRedisSettings(BaseModel):
    """Java 侧会话 Redis 连接参数（单机 6379，MCP 认证中间件读 sso:online:{jti}）。

    与消息集群段（cloud_ai_common RedisSettings，7000）是两个独立客户端/两个独立库：
    7000 集群存 AI 消息，6379 单机存 Java 在线会话。会话在默认 db 0，不加 db 键。
    默认本机事实值零配置即连。

    env 覆盖形态：CLOUD_BASE_AI_SSO_REDIS__HOST / __PORT / __PASSWORD。
    消费方 cloud_base_rag.sso_redis.get_sso_redis_client()。
    """

    host: str = "127.0.0.1"
    port: int = 6379
    password: str = ""


class RagDatabaseSettings(DatabaseSettings):
    """rag 侧库名默认值（双库拆分：rag 五表在 cloud_ai_rag）。"""

    database: str = "cloud_ai_rag"


class Settings(CommonSettings):
    """RAG 服务配置根：env 前缀 CLOUD_BASE_AI_（沿用不改），嵌套分隔 "__"，
    env_file 指本工程 .env。"""

    embedding_llm: EmbeddingModelSettings = EmbeddingModelSettings()
    milvus: MilvusSettings = MilvusSettings()
    minio: MinIOSettings = MinIOSettings()
    sso_redis: SsoRedisSettings = SsoRedisSettings()
    db: RagDatabaseSettings = Field(default_factory=RagDatabaseSettings)
    app_port: int = 8011

    model_config = SettingsConfigDict(
        env_prefix="CLOUD_BASE_AI_",
        env_nested_delimiter="__",
        env_file=str(BASE_DIR / ".env"),  # 绝对路径随工程走
        env_file_encoding="utf-8",
    )


@lru_cache
def get_settings() -> Settings:
    """进程内单例（common 经 provider 取到的即本函数）。"""
    return Settings()


# 向 common 注册：import 本模块即生效（入口与服务模块都 import 本模块）。
set_settings_provider(get_settings)
