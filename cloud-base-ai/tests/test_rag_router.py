"""router 端点断言（会话契约 2026-10-19-ai-chat-session-api.md 全 6 端点 + kb 契约）：
TestClient 全 mock 零网络。

对话两端点（§6 换轨后）：403（无/错 perms，R JSON 非 SSE 帧）/ 1001（含旧形态
{"messages":[…]} 拒绝）/ 多 perms 含空格放行 / 非流式 R.ok 结构 / 流式帧序全文精确
断言 / 5001 与未知异常 msg 帧 + [DONE] 绝不裸断流 / prepare_chat 期 5012/5013 以
R JSON 拒绝（非 SSE 帧，§6 SSE 错误层次）/ 真实 5002（无 key）msg 帧全链路。
会话四端点（§2-§5）：403 / 1001 / 5012/5013/1001 透传 / 结构与参数透传断言。
"""

from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloud_base_ai.exception_handler import register_exception_handlers
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import router as router_module
from cloud_base_ai.rag import service as service_module
from cloud_base_ai.rag.router import kb_delete_router
from cloud_base_ai.rag.router import kb_read_router
from cloud_base_ai.rag.router import kb_router
from cloud_base_ai.rag.router import kb_update_router
from cloud_base_ai.rag.router import router as rag_router
from cloud_base_ai.rag.router import session_router
from cloud_base_ai.rag.schemas import AiPageResult, KbFileVo, KbVo, RagChatAnswerVo

PERMS: dict[str, str] = {"X-User-Perms": "ai:chat:list"}
BODY: dict[str, object] = {"session_id": 12, "question": "hi"}
DONE: str = "data: [DONE]\n\n"


@pytest.fixture()
def client() -> TestClient:
    app = FastAPI()  # 无 lifespan（不触发 Nacos 注册）
    register_exception_handlers(app)
    app.include_router(rag_router)
    app.include_router(session_router)  # 会话管理四端点（2026-10-19 会话管理轮）
    app.include_router(kb_router)  # 知识库管理面写端点（2026-10-14 create_kb 轮）
    app.include_router(kb_read_router)  # 知识库管理面读端点（2026-10-15 管理轮）
    app.include_router(kb_update_router)  # 知识库编辑端点（2026-10-15 列表管理轮）
    app.include_router(kb_delete_router)  # 知识库删除端点（2026-10-15 列表管理轮）
    return TestClient(app)


def mock_answer(monkeypatch: pytest.MonkeyPatch, answer: str) -> None:
    async def fake_answer(req: object) -> RagChatAnswerVo:
        return RagChatAnswerVo(answer=answer, model="deepseek-flash")
    monkeypatch.setattr(router_module, "chat_answer", fake_answer)


def mock_prepare(monkeypatch: pytest.MonkeyPatch, session_id: int = 12) -> None:
    """替身对话前置阶段：流式端点在 StreamingResponse 构造前调用它（真身连 DB/Redis）。"""
    async def fake_prepare(req: object):
        return SimpleNamespace(session_id=session_id, history=[])
    monkeypatch.setattr(router_module, "prepare_chat", fake_prepare)


def mock_stream(monkeypatch: pytest.MonkeyPatch, tokens: list[str]) -> None:
    async def fake_stream(prepared: object):
        for t in tokens:
            yield t
    monkeypatch.setattr(router_module, "stream_chat_answer", fake_stream)


def mock_stream_error(monkeypatch: pytest.MonkeyPatch, first: str | None,
                      exc: Exception) -> None:
    async def fake_stream(prepared: object):
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
    resp = client.post("/rag/chat", json={"session_id": 12},
                       headers={"X-User-Perms": "x, ai:chat:list , y"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 1001
    assert "question" in body["msg"]  # 缺必填 question


# ---------- 非流式端点（契约 §6 换轨后） ----------

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
    async def fake_answer(req: object) -> RagChatAnswerVo:
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)
    monkeypatch.setattr(router_module, "chat_answer", fake_answer)
    body = client.post("/rag/chat", json=BODY, headers=PERMS).json()
    assert body["code"] == 5002  # 非流式端点有完整 code 消费位


def test_chat_old_messages_form_rejected_1001(client: TestClient) -> None:
    """换轨断言（契约 §6 breaking）：旧形态 {"messages":[…]} 缺必填字段 → 1001。"""
    resp = client.post("/rag/chat",
                       json={"messages": [{"role": "user", "content": "hi"}]},
                       headers=PERMS)
    body = resp.json()
    assert resp.status_code == 200
    assert body["code"] == 1001


def test_chat_session_id_string_rejected_1001(client: TestClient) -> None:
    """session_id 必须 JSON number：数字串 "12" 拒收（契约 §6 strict）。"""
    resp = client.post("/rag/chat", json={"session_id": "12", "question": "hi"},
                       headers=PERMS)
    assert resp.json()["code"] == 1001


def test_chat_blank_question_rejected_1001(client: TestClient) -> None:
    resp = client.post("/rag/chat", json={"session_id": 12, "question": "   "},
                       headers=PERMS)
    assert resp.json()["code"] == 1001


# ---------- 流式端点（契约 §6 换轨后，SSE 帧协议零变更） ----------

def test_stream_frames_exact(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_prepare(monkeypatch)
    mock_stream(monkeypatch, ["你", "好"])
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    assert resp.headers["cache-control"] == "no-cache"
    # 帧序全文精确断言（ensure_ascii=False 中文直读）
    assert resp.text == 'data: {"content": "你"}\n\ndata: {"content": "好"}\n\n' + DONE


def test_stream_prepare_receives_parsed_req(client: TestClient,
                                            monkeypatch: pytest.MonkeyPatch) -> None:
    """入参换轨透传断言：prepare_chat 收到 pydantic 解析后的 ChatSendReq（strip 生效）。"""
    captured: dict[str, object] = {}

    async def fake_prepare(req: object):
        captured.update(session_id=req.session_id, question=req.question)
        return SimpleNamespace(session_id=req.session_id, history=[])
    monkeypatch.setattr(router_module, "prepare_chat", fake_prepare)
    mock_stream(monkeypatch, ["ok"])
    resp = client.post("/rag/chat/stream",
                       json={"session_id": 12, "question": "  hi  "}, headers=PERMS)
    assert resp.status_code == 200
    assert captured == {"session_id": 12, "question": "hi"}  # strip 后值入 service


def test_stream_llm_5001_msg_frame(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_prepare(monkeypatch)
    mock_stream_error(monkeypatch, "你",
                      BusinessException("LLM 调用失败，请稍后重试", 5001))
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.text == 'data: {"content": "你"}\n\n' \
        + 'data: {"msg": "LLM 调用失败，请稍后重试"}\n\n' + DONE


def test_stream_unexpected_error_frame(client: TestClient,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    # 首 token 前即失败：0 个 token 帧 + msg 帧 + [DONE]（catch 全量红线）
    mock_prepare(monkeypatch)
    mock_stream_error(monkeypatch, None, RuntimeError("secret upstream detail"))
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    # 未知异常：msg 帧文案固定，不泄内部细节；绝不裸断流（[DONE] 收口）
    assert resp.text == 'data: {"msg": "系统异常，请稍后重试"}\n\n' + DONE


def test_stream_no_perms_is_r_json_not_sse(client: TestClient) -> None:
    resp = client.post("/rag/chat/stream", json=BODY)  # 无 X-User-Perms
    assert resp.status_code == 200
    assert "text/event-stream" not in resp.headers.get("content-type", "")
    body = resp.json()
    assert body["code"] == 403  # R JSON（层次差异），非 SSE 帧


def test_stream_invalid_body_1001(client: TestClient) -> None:
    resp = client.post("/rag/chat/stream", json={"messages": []}, headers=PERMS)
    body = resp.json()
    assert body["code"] == 1001


def test_stream_prepare_5012_is_r_json_not_sse(client: TestClient,
                                               monkeypatch: pytest.MonkeyPatch) -> None:
    """SSE 错误层次（契约 §6）：流开始前的 5012 以 R JSON 拒绝，非 SSE 帧。"""
    async def fake_prepare(req: object):
        raise BusinessException("会话不存在", 5012)
    monkeypatch.setattr(router_module, "prepare_chat", fake_prepare)
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.status_code == 200
    assert "text/event-stream" not in resp.headers.get("content-type", "")
    body = resp.json()
    assert body["code"] == 5012
    assert body["msg"] == "会话不存在"


def test_stream_prepare_5013_is_r_json_not_sse(client: TestClient,
                                               monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_prepare(req: object):
        raise BusinessException("无权访问该会话", 5013)
    monkeypatch.setattr(router_module, "prepare_chat", fake_prepare)
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    body = resp.json()
    assert body["code"] == 5013
    assert "text/event-stream" not in resp.headers.get("content-type", "")


def test_stream_real_5002_no_key_frame(client: TestClient,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    # 前置阶段 mock（真身连 DB/Redis），LLM 段不 mock：走真实 service generator →
    # generation 工厂。钉 generation 侧 Settings 为空 key（防验收期真实 .env/env 使
    # 本用例变真网络调用），工厂 5002 → service 透传 → msg 帧。
    from cloud_base_ai.config import LLMSettings, Settings
    from cloud_base_ai.models import model_factory
    from cloud_base_ai.rag import service
    from cloud_base_ai.models.model_factory import get_chat_model
    mock_prepare(monkeypatch)
    monkeypatch.setattr(service, "get_settings",
                        lambda: Settings(_env_file=None, llm=LLMSettings()))
    monkeypatch.setattr(model_factory, "get_settings",
                        lambda: Settings(_env_file=None, llm=LLMSettings()))
    get_chat_model.cache_clear()
    resp = client.post("/rag/chat/stream", json=BODY, headers=PERMS)
    assert resp.text == 'data: {"msg": "LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）"}\n\n' + DONE
    get_chat_model.cache_clear()


# ---------- 会话四端点（2026-10-19 会话管理轮，契约 §2-§5） ----------

from datetime import datetime

from cloud_base_ai.rag.schemas import ChatSessionVo, SessionMessageVo, SessionMessagesVo


def fake_session_vo(session_id: int = 12) -> ChatSessionVo:
    return ChatSessionVo(id=session_id, title="新对话",
                         create_time=datetime(2026, 10, 19, 10, 30, 0))


def test_session_create_no_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/chat/sessions")
    body = resp.json()
    assert resp.status_code == 200
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_session_create_ok_structure(client: TestClient,
                                     monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create() -> ChatSessionVo:
        return fake_session_vo(12)
    monkeypatch.setattr(service_module, "create_session", fake_create)
    resp = client.post("/rag/chat/sessions", headers=PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert body["data"] == {"id": 12, "title": "新对话",
                            "create_time": "2026-10-19 10:30:00"}


def test_session_create_identity_missing_1001(client: TestClient,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create() -> ChatSessionVo:
        raise BusinessException("缺少用户身份（X-User-Id），无法创建会话", 1001)
    monkeypatch.setattr(service_module, "create_session", fake_create)
    resp = client.post("/rag/chat/sessions", headers=PERMS)
    assert resp.json()["code"] == 1001


def test_session_list_no_perms_403(client: TestClient) -> None:
    resp = client.get("/rag/chat/sessions/list")
    body = resp.json()
    assert resp.status_code == 200
    assert body["code"] == 403 and body["msg"] == "无操作权限"


def test_session_list_ok_structure_and_query_passthrough(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_page(page: int, size: int) -> AiPageResult[ChatSessionVo]:
        captured.update(page=page, size=size)
        return AiPageResult(total=2, rows=[fake_session_vo(13), fake_session_vo(12)])
    monkeypatch.setattr(service_module, "page_list_sessions", fake_page)
    resp = client.get("/rag/chat/sessions/list", params={"page": 2, "size": 5},
                      headers=PERMS)
    body = resp.json()
    assert body["code"] == 200
    assert captured == {"page": 2, "size": 5}  # query 透传 service
    assert isinstance(body["data"]["total"], int)  # JSON number（非 string）
    assert body["data"]["rows"][0]["id"] == 13
    assert body["data"]["rows"][0]["title"] == "新对话"


def test_session_list_defaults_page1_size10(client: TestClient,
                                            monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_page(page: int, size: int) -> AiPageResult[ChatSessionVo]:
        captured.update(page=page, size=size)
        return AiPageResult(total=0, rows=[])
    monkeypatch.setattr(service_module, "page_list_sessions", fake_page)
    resp = client.get("/rag/chat/sessions/list", headers=PERMS)
    assert resp.json()["code"] == 200
    assert captured == {"page": 1, "size": 10}  # 缺省 page=1 size=10
    assert resp.json()["data"] == {"total": 0, "rows": []}  # 空集语义同形


def test_session_list_page_not_int_1001(client: TestClient) -> None:
    resp = client.get("/rag/chat/sessions/list", params={"page": "abc"}, headers=PERMS)
    assert resp.json()["code"] == 1001


def test_session_delete_no_perms_403(client: TestClient) -> None:
    resp = client.delete("/rag/chat/sessions/12")
    body = resp.json()
    assert body["code"] == 403 and body["msg"] == "无操作权限"


def test_session_delete_session_id_not_int_1001(client: TestClient) -> None:
    resp = client.delete("/rag/chat/sessions/abc", headers=PERMS)
    assert resp.json()["code"] == 1001


def test_session_delete_ok_data_null_and_path_passthrough(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, int] = {}

    async def fake_delete(session_id: int) -> None:
        captured["session_id"] = session_id
    monkeypatch.setattr(service_module, "delete_session", fake_delete)
    resp = client.delete("/rag/chat/sessions/12", headers=PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert body["data"] is None  # R<null>：data 恒 null
    assert captured == {"session_id": 12}  # 路径参数透传 service


def test_session_delete_missing_5012_passthrough(client: TestClient,
                                                 monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_delete(session_id: int) -> None:
        raise BusinessException("会话不存在", 5012)
    monkeypatch.setattr(service_module, "delete_session", fake_delete)
    resp = client.delete("/rag/chat/sessions/999", headers=PERMS)
    assert resp.json()["code"] == 5012


def test_session_delete_not_owner_5013_passthrough(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_delete(session_id: int) -> None:
        raise BusinessException("无权访问该会话", 5013)
    monkeypatch.setattr(service_module, "delete_session", fake_delete)
    resp = client.delete("/rag/chat/sessions/12", headers=PERMS)
    assert resp.json()["code"] == 5013


def test_session_messages_no_perms_403(client: TestClient) -> None:
    resp = client.get("/rag/chat/sessions/12/messages")
    body = resp.json()
    assert body["code"] == 403 and body["msg"] == "无操作权限"


def test_session_messages_session_id_not_int_1001(client: TestClient) -> None:
    resp = client.get("/rag/chat/sessions/abc/messages", headers=PERMS)
    assert resp.json()["code"] == 1001


def test_session_messages_ok_ascending(client: TestClient,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_messages(session_id: int) -> SessionMessagesVo:
        return SessionMessagesVo(messages=[
            SessionMessageVo(role="user", content="你好"),
            SessionMessageVo(role="assistant", content="你好！有什么可以帮你？"),
        ])
    monkeypatch.setattr(service_module, "list_session_messages", fake_messages)
    resp = client.get("/rag/chat/sessions/12/messages", headers=PERMS)
    body = resp.json()
    assert body["code"] == 200
    assert body["data"]["messages"] == [
        {"role": "user", "content": "你好"},
        {"role": "assistant", "content": "你好！有什么可以帮你？"}]


def test_session_messages_not_owner_5013_passthrough(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_messages(session_id: int) -> SessionMessagesVo:
        raise BusinessException("无权访问该会话", 5013)
    monkeypatch.setattr(service_module, "list_session_messages", fake_messages)
    resp = client.get("/rag/chat/sessions/12/messages", headers=PERMS)
    assert resp.json()["code"] == 5013


# ---------- 知识库创建端点（2026-10-14，契约 2026-10-14-ai-kb-api.md） ----------

KB_PERMS: dict[str, str] = {"X-User-Perms": "ai:kb:add"}


def test_kb_create_no_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "e2e_x", "description": "d"})
    assert resp.json()["code"] == 403


def test_kb_create_wrong_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "e2e_x", "description": "d"}, headers={"X-User-Perms": "ai:chat:list"})
    assert resp.json()["code"] == 403


def test_kb_create_invalid_name_1001(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "", "description": "d"}, headers=KB_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_create_missing_description_1001(client: TestClient) -> None:
    resp = client.post("/rag/kb", json={"name": "e2e_x"}, headers=KB_PERMS)  # 缺 description
    assert resp.json()["code"] == 1001


def test_kb_create_ok_returns_id(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(req: object) -> int:
        return 42
    monkeypatch.setattr(service_module, "create_kb", fake_create)
    resp = client.post("/rag/kb", json={"name": "e2e_ok", "description": "e2e_desc"}, headers=KB_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["data"] == 42


def test_kb_create_duplicate_5003(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(req: object) -> int:
        raise BusinessException("知识库名称已存在", 5003)
    monkeypatch.setattr(service_module, "create_kb", fake_create)
    resp = client.post("/rag/kb", json={"name": "e2e_dup", "description": "e2e_desc"}, headers=KB_PERMS)
    assert resp.json()["code"] == 5003


# ---------- 知识库读端点（2026-10-15 管理轮，契约 2026-10-14-ai-kb-api.md §4/§5） ----------

from datetime import datetime

KB_READ_PERMS: dict[str, str] = {"X-User-Perms": "ai:kb:list"}


def fake_kbs() -> list[KbVo]:
    return [KbVo(id=1, name="e2e_kb_1", description="e2e_desc",
                 create_time=datetime(2026, 10, 15, 9, 0, 0)),
            KbVo(id=2, name="e2e_kb_2", description=None,
                 create_time=datetime(2026, 10, 14, 18, 0, 0))]


def fake_files_page() -> AiPageResult[KbFileVo]:
    return AiPageResult(total=2, rows=[
        KbFileVo(id=3, file_name="guide.pdf", file_split_type="parent-child",
                 create_time=datetime(2026, 10, 15, 9, 30, 0),
                 file_chunk_size=128, file_chunk_overlap=20)])


def test_kb_list_no_perms_403(client: TestClient) -> None:
    resp = client.get("/rag/kb/list")
    body = resp.json()
    assert resp.status_code == 200 and body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_kb_list_wrong_perms_403(client: TestClient) -> None:
    # ai:kb:add 是写端点权限：读端点必须不放行（权限拆分断言）
    resp = client.get("/rag/kb/list", headers=KB_PERMS)
    assert resp.json()["code"] == 403


def test_kb_list_ok_total_number_and_query_passthrough(client: TestClient,
                                                       monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_page(page: int, size: int, name: str | None = None) -> AiPageResult[KbVo]:
        captured.update(page=page, size=size, name=name)
        return AiPageResult(total=2, rows=fake_kbs())
    monkeypatch.setattr(service_module, "page_list_kbs", fake_page)
    resp = client.get("/rag/kb/list", params={"page": 2, "size": 5, "name": "e2e"},
                      headers=KB_READ_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert captured == {"page": 2, "size": 5, "name": "e2e"}  # query 透传 service
    assert isinstance(body["data"]["total"], int)  # total 为 JSON number（非 string）
    assert isinstance(body["data"]["rows"][0]["id"], int)
    assert body["data"]["rows"][0]["name"] == "e2e_kb_1"
    assert body["data"]["rows"][0]["create_time"] == "2026-10-15 09:00:00"
    assert body["data"]["rows"][1]["description"] is None


def test_kb_list_defaults_page1_size10_name_omitted(client: TestClient,
                                                    monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_page(page: int, size: int, name: str | None = None) -> AiPageResult[KbVo]:
        captured.update(page=page, size=size, name=name)
        return AiPageResult(total=0, rows=[])
    monkeypatch.setattr(service_module, "page_list_kbs", fake_page)
    resp = client.get("/rag/kb/list", headers=KB_READ_PERMS)
    assert resp.json()["code"] == 200
    assert captured == {"page": 1, "size": 10, "name": None}  # 缺省 page=1 size=10，name 不携带


def test_kb_list_page_not_int_1001(client: TestClient) -> None:
    resp = client.get("/rag/kb/list", params={"page": "abc"}, headers=KB_READ_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_list_name_too_long_1001(client: TestClient) -> None:
    resp = client.get("/rag/kb/list", params={"name": "a" * 51}, headers=KB_READ_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_files_no_perms_403(client: TestClient) -> None:
    resp = client.get("/rag/kb/1/files")
    assert resp.json()["code"] == 403


def test_kb_files_wrong_perms_403(client: TestClient) -> None:
    resp = client.get("/rag/kb/1/files", headers=KB_PERMS)
    assert resp.json()["code"] == 403


def test_kb_files_kb_id_not_int_1001(client: TestClient) -> None:
    resp = client.get("/rag/kb/abc/files", headers=KB_READ_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_files_page_not_int_1001(client: TestClient) -> None:
    resp = client.get("/rag/kb/1/files", params={"page": "abc"}, headers=KB_READ_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_files_kb_missing_5004(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_page(kb_id: int, page: int, size: int) -> AiPageResult[KbFileVo]:
        raise BusinessException("知识库不存在", 5004)
    monkeypatch.setattr(service_module, "page_list_kb_files", fake_page)
    resp = client.get("/rag/kb/9999/files", headers=KB_READ_PERMS)
    assert resp.json()["code"] == 5004


def test_kb_files_ok_total_number_and_time(client: TestClient,
                                           monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, int] = {}

    async def fake_page(kb_id: int, page: int, size: int) -> AiPageResult[KbFileVo]:
        captured.update(kb_id=kb_id, page=page, size=size)
        return fake_files_page()
    monkeypatch.setattr(service_module, "page_list_kb_files", fake_page)
    resp = client.get("/rag/kb/1/files", params={"page": 2, "size": 5}, headers=KB_READ_PERMS)
    body = resp.json()
    assert body["code"] == 200
    assert captured == {"kb_id": 1, "page": 2, "size": 5}  # query 透传 service
    assert isinstance(body["data"]["total"], int)  # JSON number（非 string）
    assert isinstance(body["data"]["rows"][0]["id"], int)
    assert body["data"]["rows"][0]["create_time"] == "2026-10-15 09:30:00"


def test_kb_files_defaults_page1_size10(client: TestClient,
                                        monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, int] = {}

    async def fake_page(kb_id: int, page: int, size: int) -> AiPageResult[KbFileVo]:
        captured.update(kb_id=kb_id, page=page, size=size)
        return fake_files_page()
    monkeypatch.setattr(service_module, "page_list_kb_files", fake_page)
    resp = client.get("/rag/kb/1/files", headers=KB_READ_PERMS)
    assert resp.json()["code"] == 200
    assert captured == {"kb_id": 1, "page": 1, "size": 10}  # 缺省 page=1 size=10


# ---------- 知识库编辑/删除端点（2026-10-15 列表管理轮，契约 §5/§6） ----------

KB_UPDATE_PERMS: dict[str, str] = {"X-User-Perms": "ai:kb:update"}
KB_DELETE_PERMS: dict[str, str] = {"X-User-Perms": "ai:kb:delete"}
UPDATE_BODY: dict[str, str] = {"name": "e2e_kb_new", "description": "e2e_desc"}


def test_kb_update_no_perms_403(client: TestClient) -> None:
    resp = client.put("/rag/kb/1", json=UPDATE_BODY)
    body = resp.json()
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_kb_update_add_perms_not_enough_403(client: TestClient) -> None:
    # 权限按动作分立：持创建权限 ai:kb:add 也不能编辑
    resp = client.put("/rag/kb/1", json=UPDATE_BODY, headers=KB_PERMS)
    assert resp.json()["code"] == 403


def test_kb_update_kb_id_not_int_1001(client: TestClient) -> None:
    resp = client.put("/rag/kb/abc", json=UPDATE_BODY, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_update_invalid_name_1001(client: TestClient) -> None:
    resp = client.put("/rag/kb/1", json={"name": "", "description": "d"}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_update_kb_missing_5004(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_update(kb_id: int, req: object) -> None:
        raise BusinessException("知识库不存在", 5004)
    monkeypatch.setattr(service_module, "update_kb", fake_update)
    resp = client.put("/rag/kb/9999", json=UPDATE_BODY, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 5004


def test_kb_update_duplicate_name_5003(client: TestClient,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_update(kb_id: int, req: object) -> None:
        raise BusinessException("知识库名称已存在", 5003)
    monkeypatch.setattr(service_module, "update_kb", fake_update)
    resp = client.put("/rag/kb/1", json=UPDATE_BODY, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 5003


def test_kb_update_ok_data_null(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_update(kb_id: int, req: object) -> None:
        captured.update(kb_id=kb_id, name=req.name)
    monkeypatch.setattr(service_module, "update_kb", fake_update)
    resp = client.put("/rag/kb/1", json=UPDATE_BODY, headers=KB_UPDATE_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert body["data"] is None  # data 恒 null
    assert captured == {"kb_id": 1, "name": "e2e_kb_new"}  # 路径参数与 body 透传 service


def test_kb_delete_no_perms_403(client: TestClient) -> None:
    resp = client.delete("/rag/kb/1")
    body = resp.json()
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_kb_delete_kb_id_not_int_1001(client: TestClient) -> None:
    resp = client.delete("/rag/kb/abc", headers=KB_DELETE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_delete_kb_missing_5004(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_delete(kb_id: int) -> None:
        raise BusinessException("知识库不存在", 5004)
    monkeypatch.setattr(service_module, "delete_kb", fake_delete)
    resp = client.delete("/rag/kb/9999", headers=KB_DELETE_PERMS)
    assert resp.json()["code"] == 5004


def test_kb_delete_ok_data_null(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, int] = {}

    async def fake_delete(kb_id: int) -> None:
        captured["kb_id"] = kb_id
    monkeypatch.setattr(service_module, "delete_kb", fake_delete)
    resp = client.delete("/rag/kb/1", headers=KB_DELETE_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert body["data"] is None  # data 恒 null
    assert captured == {"kb_id": 1}  # 路径参数透传 service


# ---------- 重新向量化端点（契约 §8：perms 复用 ai:kb:update） ----------

REEMBED_BODY: dict[str, int] = {"file_id": 1}


def test_kb_reembed_no_perms_403(client: TestClient) -> None:
    resp = client.post("/rag/kb/reembedding", json=REEMBED_BODY)
    body = resp.json()
    assert resp.status_code == 200
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_kb_reembed_add_perms_not_enough_403(client: TestClient) -> None:
    # 权限按动作分立：持创建权限 ai:kb:add 也不能重嵌入（须 ai:kb:update）
    resp = client.post("/rag/kb/reembedding", json=REEMBED_BODY, headers=KB_PERMS)
    assert resp.json()["code"] == 403


def test_kb_reembed_chunk_size_over_499_1001(client: TestClient) -> None:
    resp = client.post("/rag/kb/reembedding",
                       json={"file_id": 1, "chunk_size": 600}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_reembed_missing_file_id_1001(client: TestClient) -> None:
    resp = client.post("/rag/kb/reembedding", json={"chunk_size": 100},
                       headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_reembed_5006_passthrough(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_reembed(req: object) -> None:
        raise BusinessException("文件无原件，请删除后重新上传", 5006)
    monkeypatch.setattr(service_module, "reembedding", fake_reembed)
    resp = client.post("/rag/kb/reembedding", json=REEMBED_BODY, headers=KB_UPDATE_PERMS)
    body = resp.json()
    assert body["code"] == 5006
    assert body["msg"] == "文件无原件，请删除后重新上传"


def test_kb_reembed_ok_data_null_and_body_passthrough(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_reembed(req: object) -> None:
        captured.update(file_id=req.file_id, chunk_size=req.chunk_size,
                        chunk_overlap=req.chunk_overlap)
    monkeypatch.setattr(service_module, "reembedding", fake_reembed)
    resp = client.post("/rag/kb/reembedding",
                       json={"file_id": 7, "chunk_size": 200, "chunk_overlap": 30},
                       headers=KB_UPDATE_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert body["data"] is None  # data 恒 null
    assert captured == {"file_id": 7, "chunk_size": 200, "chunk_overlap": 30}  # body 透传


# ---------- 知识库授权端点（2026-10-18 权限轮，契约 2026-10-18-kb-permission-api.md §2/§3） ----------

from cloud_base_ai.rag.schemas import KbPermsVo


def test_kb_perms_get_no_perms_403(client: TestClient) -> None:
    resp = client.get("/rag/kb/1/perms")
    body = resp.json()
    assert resp.status_code == 200
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_kb_perms_get_list_perms_rejected_403(client: TestClient) -> None:
    # 读授权清单与保存同权限：持 ai:kb:list 亦拒（管理配置动作，权限按动作分立）
    resp = client.get("/rag/kb/1/perms", headers=KB_READ_PERMS)
    assert resp.json()["code"] == 403


def test_kb_perms_get_kb_id_not_int_1001(client: TestClient) -> None:
    resp = client.get("/rag/kb/abc/perms", headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_perms_get_missing_5004(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_find(kb_id: int) -> KbPermsVo:
        raise BusinessException("知识库不存在", 5004)
    monkeypatch.setattr(service_module, "find_kb_perms", fake_find)
    resp = client.get("/rag/kb/9999/perms", headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 5004


def test_kb_perms_get_ok_three_groups_numbers(client: TestClient,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, int] = {}

    async def fake_find(kb_id: int) -> KbPermsVo:
        captured["kb_id"] = kb_id
        return KbPermsVo(role_ids=[2, 5], dept_ids=[3], user_ids=[952])
    monkeypatch.setattr(service_module, "find_kb_perms", fake_find)
    resp = client.get("/rag/kb/1/perms", headers=KB_UPDATE_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert captured == {"kb_id": 1}  # 路径参数透传 service
    assert body["data"] == {"role_ids": [2, 5], "dept_ids": [3], "user_ids": [952]}
    assert all(isinstance(i, int) for i in body["data"]["role_ids"])  # JSON number 数组


def test_kb_perms_get_ok_empty_arrays(client: TestClient,
                                      monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_find(kb_id: int) -> KbPermsVo:
        return KbPermsVo(role_ids=[], dept_ids=[], user_ids=[])
    monkeypatch.setattr(service_module, "find_kb_perms", fake_find)
    resp = client.get("/rag/kb/2/perms", headers=KB_UPDATE_PERMS)
    body = resp.json()
    assert body["code"] == 200
    assert body["data"] == {"role_ids": [], "dept_ids": [], "user_ids": []}  # 无则空数组


def test_kb_perms_put_no_perms_403(client: TestClient) -> None:
    resp = client.put("/rag/kb/1/perms", json={"role_ids": [2]})
    body = resp.json()
    assert resp.status_code == 200
    assert body["code"] == 403
    assert body["msg"] == "无操作权限"


def test_kb_perms_put_kb_id_not_int_1001(client: TestClient) -> None:
    resp = client.put("/rag/kb/abc/perms", json={"role_ids": [2]}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_perms_put_element_below_one_1001(client: TestClient) -> None:
    resp = client.put("/rag/kb/1/perms", json={"role_ids": [0]}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_perms_put_element_not_int_1001(client: TestClient) -> None:
    resp = client.put("/rag/kb/1/perms", json={"dept_ids": ["3"]}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_perms_put_array_over_100_1001(client: TestClient) -> None:
    resp = client.put("/rag/kb/1/perms", json={"user_ids": list(range(1, 102))},
                      headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 1001


def test_kb_perms_put_missing_5004(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_save(kb_id: int, req: object) -> None:
        raise BusinessException("知识库不存在", 5004)
    monkeypatch.setattr(service_module, "save_kb_perms", fake_save)
    resp = client.put("/rag/kb/9999/perms", json={"role_ids": [2]}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 5004


def test_kb_perms_put_ok_data_null_and_body_passthrough(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_save(kb_id: int, req: object) -> None:
        captured.update(kb_id=kb_id, role_ids=list(req.role_ids),
                        dept_ids=list(req.dept_ids), user_ids=list(req.user_ids))
    monkeypatch.setattr(service_module, "save_kb_perms", fake_save)
    resp = client.put("/rag/kb/3/perms",
                      json={"role_ids": [2, 2, 5], "dept_ids": [3], "user_ids": [952]},
                      headers=KB_UPDATE_PERMS)
    body = resp.json()
    assert body["code"] == 200 and body["msg"] == "操作成功"
    assert body["data"] is None  # R<null>：data 恒 null
    assert captured == {"kb_id": 3, "role_ids": [2, 2, 5], "dept_ids": [3],
                        "user_ids": [952]}  # body 原样透传 service（去重是 service 责任）


def test_kb_perms_put_partial_body_defaults_empty(client: TestClient,
                                                  monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_save(kb_id: int, req: object) -> None:
        captured.update(role_ids=list(req.role_ids), dept_ids=list(req.dept_ids),
                        user_ids=list(req.user_ids))
    monkeypatch.setattr(service_module, "save_kb_perms", fake_save)
    resp = client.put("/rag/kb/4/perms", json={"role_ids": [7]}, headers=KB_UPDATE_PERMS)
    assert resp.json()["code"] == 200
    # 不传的两组缺省 []（= 清空该组授权，全量覆盖语义）
    assert captured == {"role_ids": [7], "dept_ids": [], "user_ids": []}
