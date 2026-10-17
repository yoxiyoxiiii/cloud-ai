"""Settings 默认值、env 覆盖与 .env 文件加载断言（R11 Task 3 Step 3 + AI 对话轮 B7）。

隔离纪律：默认值/覆盖断言一律 `Settings(_env_file=None)` 构造——防验收期真实 .env
（含真 key）使「默认空」断言翻车（spec §6）；.env 文件加载用 tmp 文件 + `_env_file`
显式传参单独测。
"""

import pytest

from pathlib import Path

from cloud_base_ai.config import Settings, get_settings


def test_defaults() -> None:
    s = Settings(_env_file=None)
    assert s.nacos.server_addr == "127.0.0.1:8848"
    assert s.nacos.namespace_id == ""
    assert s.nacos.group_name == "DEFAULT_GROUP"
    assert s.nacos.service_name == "cloud-base-ai"
    assert s.nacos.ip == "127.0.0.1"
    assert s.app_port == 8010


def test_env_override_ip(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__IP", "10.0.0.9")
    assert Settings(_env_file=None).nacos.ip == "10.0.0.9"


def test_env_override_server_addr(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__SERVER_ADDR", "1.2.3.4:8848")
    assert Settings(_env_file=None).nacos.server_addr == "1.2.3.4:8848"


def test_env_override_namespace_id(monkeypatch: pytest.MonkeyPatch) -> None:
    # 含下划线内层字段在 "__" 分隔下同样生效（单 "_" 时此形态会静默失效——实测拍板依据）
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__NAMESPACE_ID", "ns-override")
    assert Settings(_env_file=None).nacos.namespace_id == "ns-override"


def test_env_override_app_port(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_APP_PORT", "8020")
    assert Settings(_env_file=None).app_port == 8020


def test_llm_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    # 先 delenv 再断言（同 milvus 段先例）：uv run 注入 .env 进程 env，本地真实 key
    # 会让「默认空」断言翻车——隔离后不依赖本地 .env 状态
    for key in ("API_KEY", "BASE_URL", "MODEL", "TIMEOUT", "MAX_RETRIES", "HISTORY_LIMIT"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_LLM__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.llm.api_key == ""  # 默认空 = 未配置态（端点报 5002，不阻断启动）
    assert s.llm.base_url == "https://api.deepseek.com"
    assert s.llm.model == "deepseek-flash"
    assert s.llm.timeout == 60.0
    assert s.llm.max_retries == 1
    assert s.llm.history_limit == 20


def test_llm_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__API_KEY", "k-123")
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__BASE_URL", "https://example.com")
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__MODEL", "m-override")
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__TIMEOUT", "30")
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__MAX_RETRIES", "2")
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__HISTORY_LIMIT", "5")
    s = Settings(_env_file=None)
    assert s.llm.api_key == "k-123"
    assert s.llm.base_url == "https://example.com"
    assert s.llm.model == "m-override"
    assert s.llm.timeout == 30.0
    assert s.llm.max_retries == 2
    assert s.llm.history_limit == 5


def test_env_file_loading(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # delenv 同上：进程 env 里若已有同名键（uv run 注入的本地 .env），会盖过
    # 本用例显式传入的 _env_file 文件——隔离后才能专测「文件加载」这一个变量
    monkeypatch.delenv("CLOUD_BASE_AI_LLM__API_KEY", raising=False)
    p = tmp_path / ".env"
    p.write_text("CLOUD_BASE_AI_LLM__API_KEY=key-from-file\n", encoding="utf-8")
    s = Settings(_env_file=str(p))
    assert s.llm.api_key == "key-from-file"


def test_real_env_beats_env_file(tmp_path: Path,
                                 monkeypatch: pytest.MonkeyPatch) -> None:
    # 优先级：真实 env > .env 文件 > 默认值（pydantic-settings 默认序）
    p = tmp_path / ".env"
    p.write_text("CLOUD_BASE_AI_LLM__API_KEY=key-from-file\n", encoding="utf-8")
    monkeypatch.setenv("CLOUD_BASE_AI_LLM__API_KEY", "key-from-env")
    s = Settings(_env_file=str(p))
    assert s.llm.api_key == "key-from-env"


def test_get_settings_cached() -> None:
    assert get_settings() is get_settings()


def test_db_defaults():
    """db 段默认本机事实值（对齐 Nacos 段默认先例，零配置即连——spec D6）。"""
    s = Settings(_env_file=None)
    assert s.db.host == "127.0.0.1"
    assert s.db.port == 3306
    assert s.db.user == "root"
    assert s.db.password == ""
    assert s.db.database == "cloud_ai"
    assert s.db.pool_size == 5
    assert s.db.max_overflow == 10
    assert s.db.pool_recycle == 3600
    assert s.db.echo is False


def test_db_env_override(monkeypatch):
    """db 段 env 覆盖（嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_DB__HOST", "10.0.0.5")
    monkeypatch.setenv("CLOUD_BASE_AI_DB__PORT", "3307")
    monkeypatch.setenv("CLOUD_BASE_AI_DB__PASSWORD", "secret")
    s = Settings(_env_file=None)
    assert (s.db.host, s.db.port, s.db.password) == ("10.0.0.5", 3307, "secret")


def test_milvus_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """milvus 段默认值：uri 指向本机 19530（历史行为兼容；宿主端口映射变化走 .env 覆盖）。

    先 delenv 再断言：uv run 会把项目 .env 自动注入进程 env，真实 env 优先级高于
    _env_file=None 的加载策略——不隔离则本用例随本地 .env 的 milvus 键翻转（不依赖 .env 状态）。
    """
    monkeypatch.delenv("CLOUD_BASE_AI_MILVUS__URI", raising=False)
    s = Settings(_env_file=None)
    assert s.milvus.uri == "http://127.0.0.1:19530"


def test_milvus_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    """milvus 段 env 覆盖（CLOUD_BASE_AI_MILVUS__URI，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_MILVUS__URI", "http://127.0.0.1:9092")
    s = Settings(_env_file=None)
    assert s.milvus.uri == "http://127.0.0.1:9092"


def test_minio_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """minio 段默认值：endpoint 指向本机容器映射 9100，bucket=cloud-ai，明文 http。

    先 delenv 再断言（同 milvus 段先例）：uv run 会把项目 .env 注入进程 env，
    真实 env 优先于 _env_file=None 的加载策略——不隔离则默认断言随本地 .env 翻转。
    """
    for key in ("ENDPOINT", "ACCESS_KEY", "SECRET_KEY", "BUCKET", "SECURE"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_MINIO__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.minio.endpoint == "127.0.0.1:9100"
    assert s.minio.access_key == ""
    assert s.minio.secret_key == ""
    assert s.minio.bucket == "cloud-ai"
    assert s.minio.secure is False


def test_minio_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    """minio 段 env 覆盖（CLOUD_BASE_AI_MINIO__BUCKET，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_MINIO__BUCKET", "other-bucket")
    s = Settings(_env_file=None)
    assert s.minio.bucket == "other-bucket"


def test_mcp_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """mcp 段默认值：直连 cloud-system 9202（网关屏蔽 /system/mcp，内网直连是既定寻址），
    client_secret 默认空 = 未配置态（调用时报 5010，不阻断启动）。

    先 delenv 再断言（同 minio 段先例）：防 uv run 注入的真实 .env 使「默认空」翻转。
    """
    for key in ("BASE_URL", "CLIENT_SECRET"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_MCP__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.mcp.base_url == "http://127.0.0.1:9202/mcp"
    assert s.mcp.client_secret == ""


def test_mcp_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    """mcp 段 env 覆盖（CLOUD_BASE_AI_MCP__CLIENT_SECRET，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_MCP__CLIENT_SECRET", "test-secret")
    s = Settings(_env_file=None)
    assert s.mcp.client_secret == "test-secret"
