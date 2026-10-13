"""router 端点断言（B7 / spec D5/D6/D12 + 契约 §2/§3）：TestClient 全 mock 零网络。

覆盖：403（无/错 perms，R JSON 非 SSE 帧）/1001（非法入参）/多 perms 含空格放行 /
非流式 R.ok 结构 / 流式帧序全文精确断言（中文 ensure_ascii=False 直读）/ 5001 与
未知异常 msg 帧 + [DONE] 绝不裸断流 / 真实 5002（无 key）msg 帧全链路。
"""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloud_base_ai.exception_handler import register_exception_handlers
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import router as router_module
from cloud_base_ai.rag.router import router as rag_router
from cloud_base_ai.rag.schemas import RagChatAnswerVo

PERMS: dict[str, str] = {"X-User-Perms": "ai:chat:list"}
BODY: dict[str, list[dict[str, str]]] = {"messages": [{"role": "user", "content": "hi"}]}
DONE: str = "data: [DONE]\n\n"


@pytest.fixture()
def client() -> TestClient:
    app = FastAPI()  # 无 lifespan（不触发 Nacos 注册）
    register_exception_handlers(app)
    app.include_router(rag_router)
    return TestClient(app)


def mock_answer(monkeypatch: pytest.MonkeyPatch, answer: str) -> None:
    async def fake_answer(dto: object) -> RagChatAnswerVo:
        return RagChatAnswerVo(answer=answer, model="deepseek-flash")
    monkeypatch.setattr(router_module, "chat_answer", fake_answer)


def mock_stream(monkeypatch: pytest.MonkeyPatch, tokens: list[str]) -> None:
    async def fake_stream(dto: object):
        for t in tokens:
            yield t
    monkeypatch.setattr(router_module, "stream_chat_answer", fake_stream)


def mock_stream_error(monkeypatch: pytest.MonkeyPatch, first: str | None,
                      exc: Exception) -> None:
    async def fake_stream(dto: object):
        if first is not None:
            yield first
        raise exc
    monkeypatch.setattr(router_module, "stream_chat_answer", fake_stream)


# ---------- 鉴权（D6：R JSON 非 SSE 帧） ----------

def test_no_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/chat", json=BODY)
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_wrong_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/chat", json=BODY, headers={"X-User-Perms": "system:user:list"})
    assert resp.json()["code"] == 403


def test_multi_perms_with_spaces_passes_then_1001(client: TestClient) -> None:
    # 依赖先于 body 校验求解：得到 1001 即证明含空格多 perms 已放行（否则 403）
    resp = client.post("/rag/chat", json={"messages": []},
                       headers={"X-User-Perms": "x, ai:chat:list , y"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 1001
    assert "messages" in body["msg"]


# ---------- 非流式端点（契约 §2） ----------

def test_chat_ok_structure(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_answer(monkeypatch, "你好，我是DeepSeek助手")
    resp = client.post("/rag/chat", json=BODY, headers=PERMS)
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 200
    assert body["msg"] == "操作成功"
    assert body["data"]["answer"] == "你好，我是DeepSeek助手"
    assert body["data"]["model"] == "deepseek-flash"


def test_chat_business_error_5002(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_answer(dto: object) -> RagChatAnswerVo:
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)
    monkeypatch.setattr(router_module, "chat_answer", fake_answer)
    body = client.post("/rag/chat", json=BODY, headers=PERMS).json()
    assert body["code"] == 5002  # 非流式端点有完整 code 消费位


# ---------- 流式端点（契约 §3） ----------

def test_stream_frames_exact(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_stream(monkeypatch, ["你", "好"])
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    assert resp.headers["cache-control"] == "no-cache"
    # 帧序全文精确断言（ensure_ascii=False 中文直读）
    assert resp.text == 'data: {"content": "你"}\n\ndata: {"content": "好"}\n\n' + DONE


def test_stream_llm_5001_msg_frame(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_stream_error(monkeypatch, "你",
                      BusinessException("LLM 调用失败，请稍后重试", 5001))
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.text == 'data: {"content": "你"}\n\n' \
        + 'data: {"msg": "LLM 调用失败，请稍后重试"}\n\n' + DONE


def test_stream_unexpected_error_frame(client: TestClient,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    # 首 token 前即失败：0 个 token 帧 + msg 帧 + [DONE]（D12 catch 全量红线）
    mock_stream_error(monkeypatch, None, RuntimeError("secret upstream detail"))
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    # 未知异常：msg 帧文案固定，不泄内部细节；绝不裸断流（[DONE] 收口）
    assert resp.text == 'data: {"msg": "系统异常，请稍后重试"}\n\n' + DONE


def test_stream_no_perms_is_r_json_not_sse(client: TestClient) -> None:
    resp = client.post("/rag/chat/stream", json=BODY)  # 无 X-User-Perms
    assert resp.status_code == 200
    assert "text/event-stream" not in resp.headers.get("content-type", "")
    body = resp.json()
    assert body["code"] == 403  # R JSON（D6 层次差异），非 SSE 帧


def test_stream_invalid_body_1001(client: TestClient) -> None:
    resp = client.post("/rag/chat/stream", json={"messages": []}, headers=PERMS)
    body = resp.json()
    assert body["code"] == 1001


def test_stream_real_5002_no_key_frame(client: TestClient,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    # 不 mock service：走真实 service → generation 工厂。钉 generation 侧 Settings 为空 key
    # （防验收期真实 .env/env 使本用例变真网络调用），工厂 5002 → service 透传 → msg 帧。
    from cloud_base_ai.config import LLMSettings, Settings
    from cloud_base_ai.rag import generation, service
    from cloud_base_ai.rag.generation import get_chat_model
    monkeypatch.setattr(service, "get_settings",
                        lambda: Settings(_env_file=None, llm=LLMSettings()))
    monkeypatch.setattr(generation, "get_settings",
                        lambda: Settings(_env_file=None, llm=LLMSettings()))
    get_chat_model.cache_clear()
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.text == 'data: {"msg": "LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）"}\n\n' + DONE
    get_chat_model.cache_clear()
