"""pytest 共享配置：Windows 事件循环策略 + anyio 插件 + settings provider 测试桩。

common 是库不独立运行：get_settings 依赖服务侧注册。本工程测试经 conftest 注册
测试桩（CommonSettings 子类，必填段给确定值、不读任何 .env），使 db / redis /
model_factory 等共享模块的测试无需真实服务侧。服务工程各自的 conftest 不需要
这段——各自 config 模块 import 时自注册。
"""

import sys

if sys.platform == "win32":
    import asyncio

    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

import pytest
from pydantic import Field

from cloud_ai_common.config import (
    CommonSettings,
    DatabaseSettings,
    NacosSettings,
    set_settings_provider,
)

pytest_plugins = ("anyio",)


class SettingsStub(CommonSettings):
    """测试桩：db 库名/app_port/nacos 服务名给确定值；_env_file=None 防本地 .env 差异。"""

    db: DatabaseSettings = Field(default_factory=lambda: DatabaseSettings(database="cloud_ai_common_test"))
    app_port: int = 8011
    nacos: NacosSettings = Field(
        default_factory=lambda: NacosSettings(service_name="cloud_ai_common_test")
    )


def _test_settings() -> SettingsStub:
    return SettingsStub(_env_file=None)


set_settings_provider(_test_settings)


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
