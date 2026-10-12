"""Settings 默认值与 env 覆盖断言（计划 Task 3 Step 3）。"""

import pytest

from cloud_base_ai.config import Settings, get_settings


def test_defaults() -> None:
    s = Settings()
    assert s.nacos.server_addr == "127.0.0.1:8848"
    assert s.nacos.namespace_id == ""
    assert s.nacos.group_name == "DEFAULT_GROUP"
    assert s.nacos.service_name == "cloud-base-ai"
    assert s.nacos.ip == "127.0.0.1"
    assert s.app_port == 8010


def test_env_override_ip(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__IP", "10.0.0.9")
    assert Settings().nacos.ip == "10.0.0.9"


def test_env_override_server_addr(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__SERVER_ADDR", "1.2.3.4:8848")
    assert Settings().nacos.server_addr == "1.2.3.4:8848"


def test_env_override_namespace_id(monkeypatch: pytest.MonkeyPatch) -> None:
    # 含下划线内层字段在 "__" 分隔下同样生效（单 "_" 时此形态会静默失效——实测拍板依据）
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__NAMESPACE_ID", "ns-override")
    assert Settings().nacos.namespace_id == "ns-override"


def test_env_override_app_port(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_APP_PORT", "8020")
    assert Settings().app_port == 8020


def test_get_settings_cached() -> None:
    assert get_settings() is get_settings()
