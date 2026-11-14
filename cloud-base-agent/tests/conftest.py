"""pytest 共享配置：Windows 事件循环策略（对齐入口模块）+ anyio 插件接线。

import 本侧 config 触发 settings provider 注册（config 模块底部向共享库注册）；
入口 app.py 的 env setdefault 在 get_settings 首调（lru_cache）前执行，语义不变。
"""

import sys

if sys.platform == "win32":
    import asyncio

    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

import pytest

import cloud_base_agent.config  # noqa: F401  import 副作用：注册 settings provider

pytest_plugins = ("anyio",)


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
