"""RAG 侧 Settings 断言：专属段（embedding_llm/milvus/minio/sso_redis）默认值与
env 覆盖 + 库名/端口/env_file 落点（三工程拆分后从原共享 test_settings.py 随段迁入）。

隔离纪律：一律 `Settings(_env_file=None)` 构造——防验收期真实 .env 使「默认空」
断言翻车；env_file 落点用例单独显式传参。
"""

from pathlib import Path

from cloud_base_rag.config import BASE_DIR, Settings


def test_db_defaults_cloud_ai_rag(monkeypatch) -> None:
    """rag 侧库名默认 cloud_ai_rag（双库拆分），其余默认本机事实值。"""
    for key in ("HOST", "PORT", "USER", "PASSWORD", "DATABASE"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_DB__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.db.database == "cloud_ai_rag"
    assert s.db.host == "127.0.0.1"


def test_app_port_default_8011(monkeypatch) -> None:
    monkeypatch.delenv("CLOUD_BASE_AI_APP_PORT", raising=False)
    assert Settings(_env_file=None).app_port == 8011


def test_env_file_points_to_rag_project_root() -> None:
    """env_file 落点=本工程根（config.py 在 src/cloud_base_rag/ 下三层上=cloud-base-rag/）。"""
    assert BASE_DIR == Path(__file__).resolve().parents[1]
    assert Settings.model_config.get("env_file") == str(BASE_DIR / ".env")


def test_milvus_defaults(monkeypatch) -> None:
    """milvus 段默认值：uri 指向本机 19530（宿主端口映射变化走 .env 覆盖）。"""
    monkeypatch.delenv("CLOUD_BASE_AI_MILVUS__URI", raising=False)
    s = Settings(_env_file=None)
    assert s.milvus.uri == "http://127.0.0.1:19530"


def test_milvus_env_override(monkeypatch) -> None:
    """milvus 段 env 覆盖（CLOUD_BASE_AI_MILVUS__URI，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_MILVUS__URI", "http://127.0.0.1:9092")
    s = Settings(_env_file=None)
    assert s.milvus.uri == "http://127.0.0.1:9092"


def test_minio_defaults(monkeypatch) -> None:
    """minio 段默认值：endpoint 指向本机容器映射 9100，bucket=cloud-ai，明文 http。"""
    for key in ("ENDPOINT", "ACCESS_KEY", "SECRET_KEY", "BUCKET", "SECURE"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_MINIO__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.minio.endpoint == "127.0.0.1:9100"
    assert s.minio.access_key == ""
    assert s.minio.secret_key == ""
    assert s.minio.bucket == "cloud-ai"
    assert s.minio.secure is False


def test_minio_env_override(monkeypatch) -> None:
    """minio 段 env 覆盖（CLOUD_BASE_AI_MINIO__BUCKET，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_MINIO__BUCKET", "other-bucket")
    s = Settings(_env_file=None)
    assert s.minio.bucket == "other-bucket"


def test_embedding_llm_defaults(monkeypatch) -> None:
    """embedding_llm 段默认值：本地 Ollama 端点 + bge 模型 + 1024 维。"""
    for key in ("BASE_URL", "MODEL", "EMBEDDING_DIMENSION", "TYPE"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_EMBEDDING_LLM__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.embedding_llm.base_url == "http://127.0.0.1:11434"
    assert s.embedding_llm.model == "qllama/bge-large-zh-v1.5:latest"
    assert s.embedding_llm.embedding_dimension == 1024
    assert s.embedding_llm.type == "ollama"


def test_sso_redis_defaults(monkeypatch) -> None:
    """sso_redis 段默认值：Java 会话单机库本机 6379、无密码（与消息集群 7000 并存）。"""
    for key in ("HOST", "PORT", "PASSWORD"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_SSO_REDIS__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.sso_redis.host == "127.0.0.1"
    assert s.sso_redis.port == 6379
    assert s.sso_redis.password == ""


def test_sso_redis_env_override(monkeypatch) -> None:
    """sso_redis 段 env 覆盖（CLOUD_BASE_AI_SSO_REDIS__*，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_SSO_REDIS__HOST", "10.0.0.7")
    monkeypatch.setenv("CLOUD_BASE_AI_SSO_REDIS__PORT", "6380")
    monkeypatch.setenv("CLOUD_BASE_AI_SSO_REDIS__PASSWORD", "secret")
    s = Settings(_env_file=None)
    assert (s.sso_redis.host, s.sso_redis.port, s.sso_redis.password) == \
        ("10.0.0.7", 6380, "secret")
