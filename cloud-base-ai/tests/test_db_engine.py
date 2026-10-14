"""db/engine 单测：URL 组装（charset/quote）、工厂单例、dispose 重建（零网络零真库）。"""

import pytest

from cloud_base_ai.config import DatabaseSettings
from cloud_base_ai.db.engine import build_url, dispose_engine, get_engine, get_session_factory

pytestmark = pytest.mark.anyio


def test_build_url_defaults():
    url = build_url(DatabaseSettings())
    assert url == "mysql+aiomysql://root:@127.0.0.1:3306/cloud_ai?charset=utf8mb4"


def test_build_url_quotes_special_chars():
    cfg = DatabaseSettings(user="ad min", password="p@ss:word", host="10.0.0.5", port=3307)
    url = build_url(cfg)
    assert url == "mysql+aiomysql://ad%20min:p%40ss%3Aword@10.0.0.5:3307/cloud_ai?charset=utf8mb4"


async def test_engine_singleton_and_rebuild_after_dispose():
    e1, e2 = get_engine(), get_engine()
    assert e1 is e2
    await dispose_engine()
    assert get_engine() is not e1


async def test_session_factory_singleton():
    assert get_session_factory() is get_session_factory()
