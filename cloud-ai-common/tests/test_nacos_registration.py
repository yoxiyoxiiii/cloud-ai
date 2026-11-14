"""NacosRegistration 生命周期断言（common 组件级）：

SDK 全程 monkeypatch 假实现——零真网络。`with TestClient(app)` 触发 lifespan
（startup=start / shutdown=stop），五组断言：注册参数 / 停机时序 / 失败降级 /
stop 幂等 / 未启动即 stop。本文件用自建最小 FastAPI app（lifespan 挂
NacosRegistration，等价复刻服务入口写法）——服务入口的分叉参数（服务名/端口）
由各服务工程的入口测试覆盖，common 无法 import 服务侧入口。
"""

import asyncio
from contextlib import asynccontextmanager

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloud_ai_common import nacos_registration
from cloud_ai_common.config import NacosSettings, get_settings
from cloud_ai_common.nacos_registration import NacosRegistration
from tests.conftest import SettingsStub


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
    monkeypatch.setattr("cloud_ai_common.nacos_registration.NacosNamingService",
                        FakeNacosNamingService)


@pytest.fixture
def app(monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    """最小复刻服务入口的 lifespan 注册写法；服务名/端口经 env 注入（conftest 桩
    每次构造读当前 env，setenv 即时生效）。"""

    @asynccontextmanager
    async def lifespan(a: FastAPI):
        registration = NacosRegistration(get_settings())
        await registration.start()
        a.state.nacos = registration
        yield
        await registration.stop()

    return FastAPI(lifespan=lifespan)


def _enter(monkeypatch: pytest.MonkeyPatch, service_name: str, port: int) -> None:
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__SERVICE_NAME", service_name)
    monkeypatch.setenv("CLOUD_BASE_AI_APP_PORT", str(port))


def test_register_params(app: FastAPI, monkeypatch: pytest.MonkeyPatch) -> None:
    _enter(monkeypatch, "cloud-base-rag", 8011)
    with TestClient(app) as c:
        assert c.get("/").status_code == 404  # app 无路由，仅驱动 lifespan
    p = FakeNacosNamingService.last_register_param
    assert p.ip == "127.0.0.1"
    assert p.port == 8011
    assert p.service_name == "cloud-base-rag"
    assert p.group_name == "DEFAULT_GROUP"
    assert p.ephemeral is True
    assert p.metadata == {"lang": "python", "framework": "fastapi", "version": "0.1.0"}


def test_stop_order(app: FastAPI, monkeypatch: pytest.MonkeyPatch) -> None:
    _enter(monkeypatch, "cloud-base-agent", 8012)
    with TestClient(app):
        pass
    # 停机时序：先 deregister 后 shutdown（with 退出即 lifespan shutdown）
    assert FakeNacosNamingService.calls == ["register_instance", "deregister_instance", "shutdown"]


def test_degraded_start_not_fatal(app: FastAPI, monkeypatch: pytest.MonkeyPatch) -> None:
    _enter(monkeypatch, "cloud-base-rag", 8011)
    FakeNacosNamingService.fail_on_register = True
    with TestClient(app) as c:  # lifespan 启动不抛（降级）
        reg: NacosRegistration | None = getattr(c.app.state, "nacos", None)
        assert reg is not None and reg.registered is False


def test_stop_idempotent(app: FastAPI, monkeypatch: pytest.MonkeyPatch) -> None:
    _enter(monkeypatch, "cloud-base-rag", 8011)
    with TestClient(app):
        pass
    reg: NacosRegistration = app.state.nacos
    snapshot = list(FakeNacosNamingService.calls)
    asyncio.run(reg.stop())  # 二次调用不抛且不再发 SDK 调用
    assert FakeNacosNamingService.calls == snapshot


def test_stop_without_start_does_not_raise(monkeypatch: pytest.MonkeyPatch) -> None:
    # create_naming_service 从未发生（_service=None 防护分支）：stop 直接返回不抛
    _enter(monkeypatch, "cloud-base-rag", 8011)
    reg = NacosRegistration(SettingsStub(_env_file=None))
    asyncio.run(reg.stop())
    assert FakeNacosNamingService.calls == []


def test_empty_service_name_skips_register(monkeypatch: pytest.MonkeyPatch) -> None:
    """服务名未配置（空）即不建连不注册：库级默认空是未配置态，注册错名比不注册
    更糟（网关 lb 按名路由）——拦截后 registered=false 供 /health 可观测。"""
    monkeypatch.delenv("CLOUD_BASE_AI_NACOS__SERVICE_NAME", raising=False)
    stub = SettingsStub(_env_file=None, nacos=NacosSettings())
    reg = NacosRegistration(stub)
    assert asyncio.run(reg.start()) is False
    assert reg.registered is False
    assert FakeNacosNamingService.create_count == 0  # 连建连都不发生
