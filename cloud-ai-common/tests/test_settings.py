"""CommonSettings 公共段断言：默认值 / env 覆盖 / 必填防线 / provider 行为。

隔离纪律：默认值/覆盖断言一律 `_env_file=None` 构造——防验收期真实 .env（含真 key）
使「默认空」断言翻车。服务专属段（rag 的 embedding_llm/milvus/minio/sso_redis）
在各服务工程测试，不进本文件。
"""

from pathlib import Path

import pytest
from pydantic import ValidationError

from cloud_ai_common import config
from cloud_ai_common.config import CommonSettings, DatabaseSettings

# 本文件专测 CommonSettings（必填段未给默认）：db/app_port 由桩子类补齐。
from tests.conftest import SettingsStub as Settings


def test_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    # 先 delenv 再断言：进程 env 若被入口注入（服务名/端口），隔离后专测「无任何覆盖时的默认值」。
    # service_name 库级默认空=未配置态（注册器拦截降级），桩子类提供确定测试值。
    for key in ("SERVICE_NAME", "IP"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_NACOS__{key}", raising=False)
    monkeypatch.delenv("CLOUD_BASE_AI_APP_PORT", raising=False)
    s = Settings(_env_file=None)
    assert s.nacos.server_addr == "127.0.0.1:8848"
    assert s.nacos.namespace_id == ""
    assert s.nacos.group_name == "DEFAULT_GROUP"
    assert s.nacos.service_name == "cloud_ai_common_test"
    assert s.nacos.ip == "127.0.0.1"
    assert s.app_port == 8011


def test_nacos_service_name_defaults_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    """service_name 库级默认空 = 未配置态：三工程各有注册名，库级写死默认会在漏配时
    静默注册成错误服务名（网关 lb 按名路由即 404）——空值由注册器拦截降级可观测。"""
    monkeypatch.delenv("CLOUD_BASE_AI_NACOS__SERVICE_NAME", raising=False)
    assert config.NacosSettings().service_name == ""


def test_nacos_env_override_service_name(monkeypatch: pytest.MonkeyPatch) -> None:
    """服务注册名经 env 提供（服务入口 setdefault / 部署环境注入同一通路）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__SERVICE_NAME", "svc-from-env")
    s = Settings(_env_file=None)
    assert s.nacos.service_name == "svc-from-env"


def test_env_override_ip(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__IP", "10.0.0.9")
    assert Settings(_env_file=None).nacos.ip == "10.0.0.9"


def test_env_override_server_addr(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__SERVER_ADDR", "1.2.3.4:8848")
    assert Settings(_env_file=None).nacos.server_addr == "1.2.3.4:8848"


def test_env_override_namespace_id(monkeypatch: pytest.MonkeyPatch) -> None:
    # 含下划线内层字段在 "__" 分隔下同样生效（单 "_" 时此形态会静默失效——历史实测拍板依据）
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__NAMESPACE_ID", "ns-override")
    assert Settings(_env_file=None).nacos.namespace_id == "ns-override"


def test_env_override_app_port(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_APP_PORT", "8020")
    assert Settings(_env_file=None).app_port == 8020


def test_llm_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    # 先 delenv 再断言：本地真实 key 会让「默认空」断言翻车——隔离后不依赖本地 .env 状态
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
    # 机制级断言：_env_file 显式传参的文件加载（服务侧子类 env_file 配置依赖同一机制）。
    # delenv：进程 env 里若已有同名键会盖过显式传入的文件——隔离后才能专测「文件加载」
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


# ---------- db 段（database 必填是双库拆分的防漏配红线） ----------


def test_db_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """db 段默认本机事实值（零配置即连），库名除外——必填。"""
    for key in ("HOST", "PORT", "USER", "PASSWORD", "DATABASE"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_DB__{key}", raising=False)
    cfg = DatabaseSettings(database="cloud_ai_test")
    assert cfg.host == "127.0.0.1"
    assert cfg.port == 3306
    assert cfg.user == "root"
    assert cfg.password == ""
    assert cfg.pool_size == 5
    assert cfg.max_overflow == 10
    assert cfg.pool_recycle == 3600
    assert cfg.echo is False


def test_db_database_required() -> None:
    """database 必填无默认：漏配在构造期即 ValidationError（双库拆分后漏配静默写回
    旧库 cloud_ai 是数据写错库事故，防线前移）。"""
    with pytest.raises(ValidationError):
        DatabaseSettings()  # type: ignore[call-arg]


def test_db_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    """db 段 env 覆盖（嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_DB__HOST", "10.0.0.5")
    monkeypatch.setenv("CLOUD_BASE_AI_DB__PORT", "3307")
    monkeypatch.setenv("CLOUD_BASE_AI_DB__PASSWORD", "secret")
    monkeypatch.setenv("CLOUD_BASE_AI_DB__DATABASE", "cloud_ai_env_db")
    s = Settings(_env_file=None)
    assert (s.db.host, s.db.port, s.db.password) == ("10.0.0.5", 3307, "secret")
    assert s.db.database == "cloud_ai_env_db"


# ---------- mcp / redis 公共段 ----------


def test_mcp_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """mcp 段默认值：仅 client_secret 一键（内置服务连接地址已迁 DB 的 ai_mcp_server 行），
    默认空 = 未配置态（调用时报 5010，不阻断启动）。"""
    for key in ("BASE_URL", "CLIENT_SECRET"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_MCP__{key}", raising=False)
    s = Settings(_env_file=None)
    assert not hasattr(s.mcp, "base_url")
    assert s.mcp.client_secret == ""


def test_mcp_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    """mcp 段 env 覆盖（CLOUD_BASE_AI_MCP__CLIENT_SECRET，嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_MCP__CLIENT_SECRET", "test-secret")
    s = Settings(_env_file=None)
    assert s.mcp.client_secret == "test-secret"


def test_redis_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """redis 段默认值：单起始节点本机 7000（redis-cluster 单节点集群容器），无密码。
    集群模式无 db 键（redis-py 8.1.0 集群模式传 db 直接抛 RedisClusterException）。"""
    for key in ("HOSTS", "PORT", "PASSWORD"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_REDIS__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.redis.hosts == "127.0.0.1"
    assert s.redis.port == 7000
    assert s.redis.password == ""


def test_redis_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    """redis 段 env 覆盖：hosts 逗号分隔多起始节点 + 密码（嵌套分隔 "__"）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_REDIS__HOSTS", "10.0.0.5, 10.0.0.6")
    monkeypatch.setenv("CLOUD_BASE_AI_REDIS__PASSWORD", "secret")
    s = Settings(_env_file=None)
    assert s.redis.hosts == "10.0.0.5, 10.0.0.6"
    assert s.redis.password == "secret"


# ---------- provider（common 读配置的唯一入口） ----------


def test_get_settings_without_provider_raises() -> None:
    """未注册 provider 即 RuntimeError：库不能 import 服务侧 config，快速失败优于
    静默取错配置。"""
    original = config._provider
    try:
        config._provider = None
        with pytest.raises(RuntimeError, match="settings provider"):
            config.get_settings()
    finally:
        config._provider = original


def test_get_settings_returns_provider_value(monkeypatch: pytest.MonkeyPatch) -> None:
    """注册后 get_settings 恒经 provider：换注册即换值（服务侧注册的是各自
    lru_cache 单例，同一实例语义由服务侧保证）。"""
    sentinel = Settings(_env_file=None, app_port=7999)
    monkeypatch.setattr(config, "_provider", lambda: sentinel)
    assert config.get_settings() is sentinel
