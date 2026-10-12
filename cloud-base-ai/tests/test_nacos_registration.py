"""NacosRegistration 生命周期断言（计划 Task 3 Step 4）：

SDK 全程 monkeypatch 假实现——零真网络。`with TestClient(app)` 触发 lifespan
（startup=start / shutdown=stop），四组断言：注册参数 / 停机时序 / 失败降级 / stop 幂等。
"""

import asyncio

import pytest
from fastapi.testclient import TestClient

from cloud_base_ai.config import Settings
from cloud_base_ai.main import app
from cloud_base_ai.nacos_registration import NacosRegistration


class FakeNacosNamingService:
    """假 SDK：记录实例方法调用序列与 register 参数；可注入注册失败。"""

    calls: list[str] = []          # 实例方法序列（register/deregister/shutdown）
    create_count: int = 0
    last_register_param: object | None = None
    fail_on_register: bool = False

    @classmethod
    async def create_naming_service(cls, client_config) -> "FakeNacosNamingService":
        cls.create_count += 1
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
    FakeNacosNamingService.create_count = 0
    FakeNacosNamingService.last_register_param = None
    FakeNacosNamingService.fail_on_register = False
    monkeypatch.setattr("cloud_base_ai.nacos_registration.NacosNamingService",
                        FakeNacosNamingService)


def test_register_params() -> None:
    with TestClient(app) as c:
        assert c.get("/health").json()["nacos_registered"] is True
    p = FakeNacosNamingService.last_register_param
    assert p.ip == "127.0.0.1"
    assert p.port == 8010
    assert p.service_name == "cloud-base-ai"
    assert p.group_name == "DEFAULT_GROUP"
    assert p.ephemeral is True
    assert p.metadata == {"lang": "python", "framework": "fastapi", "version": "0.1.0"}


def test_stop_order() -> None:
    with TestClient(app):
        pass
    # D8 时序：先 deregister 后 shutdown（with 退出即 lifespan shutdown）
    assert FakeNacosNamingService.calls == ["register_instance", "deregister_instance", "shutdown"]


def test_degraded_start_not_fatal() -> None:
    FakeNacosNamingService.fail_on_register = True
    with TestClient(app) as c:  # lifespan 启动不抛（D7 降级）
        assert c.get("/health").json()["nacos_registered"] is False
        assert c.get("/demo/ping").json()["code"] == 200  # 服务本地仍可用


def test_stop_idempotent() -> None:
    with TestClient(app):
        pass
    reg: NacosRegistration = app.state.nacos
    snapshot = list(FakeNacosNamingService.calls)
    asyncio.run(reg.stop())  # 二次调用不抛且不再发 SDK 调用
    assert FakeNacosNamingService.calls == snapshot


def test_stop_without_start_does_not_raise() -> None:
    # create_naming_service 从未发生（_service=None 防护分支）：stop 直接返回不抛
    reg = NacosRegistration(Settings())
    asyncio.run(reg.stop())
    assert FakeNacosNamingService.calls == []
