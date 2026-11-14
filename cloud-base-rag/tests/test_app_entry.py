"""rag 入口（cloud_base_rag.app）注册行为断言：三工程拆分后随入口落 rag 工程
（原共享 test_nacos_registration.py 的双入口参数化分支拆两侧；NacosRegistration
组件本身的行为断言在 cloud-ai-common/tests/）。

SDK 全程 monkeypatch 假实现——零真网络。`with TestClient(app)` 触发 lifespan，
断言注册参数（cloud-base-rag / 8011 分叉锚）、停机时序、失败降级、stop 幂等与
/demo/ping 文案（防误挂他域）。
"""

import asyncio

import pytest
from fastapi.testclient import TestClient

from cloud_base_rag.app import app
from cloud_base_rag.config import get_settings


class FakeNacosNamingService:
    """假 SDK：记录实例方法调用序列与 register 参数；可注入注册失败。"""

    calls: list[str] = []
    last_register_param: object | None = None
    fail_on_register: bool = False

    @classmethod
    async def create_naming_service(cls, client_config) -> "FakeNacosNamingService":
        return cls()

    async def register_instance(self, param) -> bool:
        type(self).last_register_param = param
        type(self).calls.append("register_instance")
        if type(self).fail_on_register:
            raise RuntimeError("register boom")
        return True

    async def deregister_instance(self, param) -> bool:
        type(self).calls.append("deregister_instance")
        return True

    async def shutdown(self) -> None:
        type(self).calls.append("shutdown")


@pytest.fixture(autouse=True)
def _fake_sdk(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeNacosNamingService.calls = []
    FakeNacosNamingService.last_register_param = None
    FakeNacosNamingService.fail_on_register = False
    monkeypatch.setattr("cloud_ai_common.nacos_registration.NacosNamingService",
                        FakeNacosNamingService)
    # 入口 setdefault 已注入服务名/端口；清缓存后 lifespan 重建单例读当前 env
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_register_params() -> None:
    with TestClient(app):
        pass
    p = FakeNacosNamingService.last_register_param
    assert p.ip == "127.0.0.1"
    assert p.port == 8011
    assert p.service_name == "cloud-base-rag"
    assert p.group_name == "DEFAULT_GROUP"
    assert p.ephemeral is True


def test_stop_order() -> None:
    with TestClient(app):
        pass
    # 停机时序：先 deregister 后 shutdown（with 退出即 lifespan shutdown）
    assert FakeNacosNamingService.calls == ["register_instance", "deregister_instance", "shutdown"]


def test_degraded_start_not_fatal() -> None:
    FakeNacosNamingService.fail_on_register = True
    with TestClient(app) as c:  # lifespan 启动不抛（降级）
        assert c.get("/health").json()["nacos_registered"] is False
        assert c.get("/demo/ping").json()["code"] == 200  # 服务本地仍可用


def test_stop_idempotent() -> None:
    with TestClient(app):
        pass
    reg = app.state.nacos
    snapshot = list(FakeNacosNamingService.calls)
    asyncio.run(reg.stop())  # 二次调用不抛且不再发 SDK 调用
    assert FakeNacosNamingService.calls == snapshot


def test_ping_reports_own_service_name() -> None:
    """入口分叉可见性：/demo/ping 文案随入口变化（防两服务误挂同一域）。"""
    with TestClient(app) as c:
        assert c.get("/demo/ping").json()["data"] == "cloud-base-rag running"
