"""NacosRegistration 生命周期断言：

SDK 全程 monkeypatch 假实现——零真网络。`with TestClient(app)` 触发 lifespan
（startup=start / shutdown=stop），四组断言：注册参数 / 停机时序 / 失败降级 / stop 幂等。
双服务拆分后 rag_app / agent_app 两入口参数化各跑一遍（服务名与端口随入口分叉）。

同一测试进程先后 import 两个入口模块时，模块顶部的 setdefault 只对先到者生效，
因此各用例显式 setenv + get_settings.cache_clear() 驱动，不依赖 import 副作用
（生产是双进程，各进程只 import 自己的入口，无此问题）。
"""

import asyncio

import pytest
from fastapi.testclient import TestClient

from cloud_base_ai.agent_app import app as agent_app
from cloud_base_ai.config import get_settings
from cloud_base_ai.nacos_registration import NacosRegistration
from cloud_base_ai.rag_app import app as rag_app
from cloud_base_ai.config import Settings


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


# 两入口的期望注册参数（入口模块顶部 setdefault 的镜像——分叉点唯一性回归锚）。
# 外层再包一层 tuple：fixture params 的裸多值元组会被 pytest 按多参数集展开。
ENTRIES = [
    pytest.param((rag_app, "cloud-base-rag", 8011), id="rag"),
    pytest.param((agent_app, "cloud-base-agent", 8012), id="agent"),
]


def _enter(monkeypatch: pytest.MonkeyPatch, service_name: str, port: int) -> None:
    """按入口注入服务名/端口并重建 settings 单例（lifespan 里 get_settings 读缓存）。"""
    monkeypatch.setenv("CLOUD_BASE_AI_NACOS__SERVICE_NAME", service_name)
    monkeypatch.setenv("CLOUD_BASE_AI_APP_PORT", str(port))
    get_settings.cache_clear()


@pytest.fixture(params=ENTRIES)
def entry(request, monkeypatch: pytest.MonkeyPatch):
    """参数化入口：(app, 服务名, 端口)；用例结束清 settings 缓存防串场。"""
    app, service_name, port = request.param
    _enter(monkeypatch, service_name, port)
    yield app, service_name, port
    get_settings.cache_clear()


def test_register_params(entry) -> None:
    app, service_name, port = entry
    with TestClient(app) as c:
        assert c.get("/health").json()["nacos_registered"] is True
    p = FakeNacosNamingService.last_register_param
    assert p.ip == "127.0.0.1"
    assert p.port == port
    assert p.service_name == service_name
    assert p.group_name == "DEFAULT_GROUP"
    assert p.ephemeral is True
    assert p.metadata == {"lang": "python", "framework": "fastapi", "version": "0.1.0"}


def test_stop_order(entry) -> None:
    app, _, _ = entry
    with TestClient(app):
        pass
    # 停机时序：先 deregister 后 shutdown（with 退出即 lifespan shutdown）
    assert FakeNacosNamingService.calls == ["register_instance", "deregister_instance", "shutdown"]


def test_degraded_start_not_fatal(entry) -> None:
    app, _, _ = entry
    FakeNacosNamingService.fail_on_register = True
    with TestClient(app) as c:  # lifespan 启动不抛（降级）
        assert c.get("/health").json()["nacos_registered"] is False
        assert c.get("/demo/ping").json()["code"] == 200  # 服务本地仍可用


def test_stop_idempotent(entry) -> None:
    app, _, _ = entry
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


def test_ping_reports_own_service_name(entry) -> None:
    """入口分叉可见性：/demo/ping 文案随入口变化（防两入口误挂同一域）。"""
    app, service_name, _ = entry
    with TestClient(app) as c:
        assert c.get("/demo/ping").json()["data"] == f"{service_name} running"
