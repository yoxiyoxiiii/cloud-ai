"""pytest 共享配置：Windows 事件循环策略对齐 main.py + anyio 插件接线（spec D9）。

anyio 随 fastapi 传递依赖在装且自带 pytest11 entry point——async 测试零新增插件依赖；
模块级 pytestmark = pytest.mark.anyio 驱动。若 pytest 9 与 anyio 插件不兼容（实现时验证），
fallback：dev 组加 pytest-asyncio 并改 pytestmark = pytest.mark.asyncio（不动实现代码）。
"""

import sys

if sys.platform == "win32":
    import asyncio

    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

import pytest

pytest_plugins = ("anyio",)


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
