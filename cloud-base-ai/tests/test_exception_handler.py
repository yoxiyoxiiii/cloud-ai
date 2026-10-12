"""异常处理器三分支断言（计划 Task 3 Step 2）：HTTP 恒 200 + R body 转译。

独立小 app 不挂 lifespan；raise_server_exceptions=False 才能拿到 Exception 兜底
handler 的响应（Starlette ServerErrorMiddleware 用完 handler 仍向测试层 re-raise）。
"""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloud_base_ai.exception_handler import register_exception_handlers
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.response import MSG_SYSTEM_ERROR


@pytest.fixture()
def client() -> TestClient:
    app = FastAPI()  # 无 lifespan
    register_exception_handlers(app)

    @app.get("/biz")
    def biz() -> None:
        raise BusinessException("自定义", 1002)

    @app.get("/item/{item_id}")
    def read_item(item_id: int) -> dict[str, int]:
        return {"item_id": item_id}

    @app.get("/boom")
    def boom() -> None:
        raise RuntimeError("secret detail")

    return TestClient(app, raise_server_exceptions=False)


def test_business_exception(client: TestClient) -> None:
    resp = client.get("/biz")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 1002
    assert body["data"] is None


def test_validation_error(client: TestClient) -> None:
    resp = client.get("/item/abc")  # item_id: int 收非数字 → 422 转译
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 1001
    assert "item_id" in body["msg"]  # msg 带字段名拼接


def test_unhandled_exception(client: TestClient) -> None:
    resp = client.get("/boom")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 500
    assert body["msg"] == MSG_SYSTEM_ERROR
    assert "secret detail" not in body["msg"]  # 不泄内部细节


def test_normal_route_untouched(client: TestClient) -> None:
    resp = client.get("/item/42")  # 正常路径不受 handler 注册影响
    assert resp.status_code == 200
    assert resp.json() == {"item_id": 42}
