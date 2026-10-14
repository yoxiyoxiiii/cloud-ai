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


def test_llm_defaults() -> None:
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


def test_env_file_loading(tmp_path: Path) -> None:
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
