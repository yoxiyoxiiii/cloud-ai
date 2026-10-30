"""平台 Agent 域 router 端点断言（契约 2026-10-29-agent-platform-api.md §2/§3/§4）：
TestClient 全 mock 零网络。

覆盖：六 router 各自 403（权限分立——错挂他权限同样拒绝）/ 管理面 R.ok 结构与
5015/5016/5017 透传 / 非流式 JWT 剥离传递 / 流式帧序全文精确断言（content 帧 N 个 +
[DONE]；msg 帧 + [DONE] 绝不裸断流）/ 流开始前 5015/5019/5020 以 R JSON 拒绝非
SSE 帧 / 会话四端点结构与 5019/5020 透传。
"""

from datetime import datetime

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloud_base_ai.agent import manage_service as mgmt
from cloud_base_ai.agent import run_service as runs
from cloud_base_ai.agent.router import (
    agent_add_router,
    agent_delete_router,
    agent_list_router,
    agent_run_router,
    agent_session_router,
    agent_update_router,
)
from cloud_base_ai.agent.schemas import (
    AgentAnswerVo,
    AgentSessionMessagesVo,
    AgentSessionMessageVo,
    AgentSessionVo,
    AgentDetailVo,
    AiPageResult,
    RunLogVo,
    ToolVo,
)
from cloud_base_ai.exception_handler import register_exception_handlers
from cloud_base_ai.exceptions import BusinessException

LIST_PERMS: dict[str, str] = {"X-User-Perms": "ai:agent:list"}
ADD_PERMS: dict[str, str] = {"X-User-Perms": "ai:agent:add"}
UPDATE_PERMS: dict[str, str] = {"X-User-Perms": "ai:agent:update"}
DELETE_PERMS: dict[str, str] = {"X-User-Perms": "ai:agent:delete"}
RUN_PERMS: dict[str, str] = {"X-User-Perms": "ai:agent:run"}
RUN_BODY: dict[str, object] = {"session_id": 12, "question": "你好"}
DONE: str = "data: [DONE]\n\n"


@pytest.fixture()
def client() -> TestClient:
    app = FastAPI()  # 无 lifespan（不触发 Nacos 注册/DB 连接池）
    register_exception_handlers(app)
    app.include_router(agent_list_router)
    app.include_router(agent_add_router)
    app.include_router(agent_update_router)
    app.include_router(agent_delete_router)
    app.include_router(agent_run_router)
    app.include_router(agent_session_router)
    return TestClient(app)


# ---------- 鉴权（六 router 权限分立，各至少一 403 用例） ----------


def test_no_perms_403_per_router(client: TestClient) -> None:
    cases = [
        ("get", "/agent/list", None, LIST_PERMS),
        ("get", "/agent/tool/list", None, LIST_PERMS),
        ("get", "/agent/3", None, LIST_PERMS),
        ("get", "/agent/3/runs", None, LIST_PERMS),
        ("post", "/agent", {}, ADD_PERMS),
        ("put", "/agent/3", {}, UPDATE_PERMS),
        ("delete", "/agent/3", None, DELETE_PERMS),
        ("post", "/agent/3/run", RUN_BODY, RUN_PERMS),
        ("post", "/agent/3/run/stream", RUN_BODY, RUN_PERMS),
        ("post", "/agent/3/sessions", None, RUN_PERMS),
        ("get", "/agent/3/sessions/list", None, RUN_PERMS),
        ("delete", "/agent/sessions/5", None, RUN_PERMS),
        ("get", "/agent/sessions/5/messages", None, RUN_PERMS),
    ]
    for method, url, body, needed in cases:
        resp = getattr(client, method)(url, json=body) if body is not None \
            else getattr(client, method)(url)
        assert resp.status_code == 200, url
        result = resp.json()
        assert result["code"] == 403, url
        assert result["msg"] == "无操作权限", url
        # 持无关权限同样拒绝（权限分立：任一 router 的所需权限不含它即 403）
        assert needed  # 消除未用变量告警：cases 逐项声明所需权限，语义见上方断言
        resp2 = getattr(client, method)(
            url, json=body, headers={"X-User-Perms": "system:user:list"}) \
            if body is not None else getattr(client, method)(
                url, headers={"X-User-Perms": "system:user:list"})
        assert resp2.json()["code"] == 403, url


# ---------- 管理面（契约 §2） ----------


def test_list_agents_ok_structure(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_page(page: int, size: int, name: str | None = None):
        return AiPageResult(total=1, rows=[_detail_vo()])
    monkeypatch.setattr(mgmt, "page_list_agents", fake_page)
    body = client.get("/agent/list", params={"page": 1, "size": 10, "name": "e2e"},
                      headers=LIST_PERMS).json()
    assert body["code"] == 200
    row = body["data"]["rows"][0]
    assert row["name"] == "e2e_客服助手"
    assert row["tool_names"] == ["查询字典项"]
    assert body["data"]["total"] == 1


def test_tool_list_ok_structure(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_registry():
        return [ToolVo(id=1, tool_key="system_query_dict_items", name="查询字典项",
                       description="按类型查询", required_perms=[],
                       server_id=3, server_name="SYSTEM")]
    monkeypatch.setattr(mgmt, "list_tool_registry", fake_registry)
    body = client.get("/agent/tool/list", headers=LIST_PERMS).json()
    assert body["code"] == 200
    assert body["data"][0]["tool_key"] == "system_query_dict_items"
    assert body["data"][0]["required_perms"] == []
    assert body["data"][0]["server_id"] == 3  # server 归属回显
    assert body["data"][0]["server_name"] == "SYSTEM"


def test_detail_ok_and_5015(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_detail(agent_id: int):
        return _detail_vo()
    monkeypatch.setattr(mgmt, "find_agent_detail", fake_detail)
    body = client.get("/agent/3", headers=LIST_PERMS).json()
    assert body["code"] == 200
    assert body["data"]["system_prompt"] == "你是客服"
    assert body["data"]["config_version"] == 1

    async def raise_5015(agent_id: int):
        raise BusinessException("智能体不存在", 5015)
    monkeypatch.setattr(mgmt, "find_agent_detail", raise_5015)
    assert client.get("/agent/999", headers=LIST_PERMS).json()["code"] == 5015


def test_create_agent_ok_and_errors(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(req):
        return 7
    monkeypatch.setattr(mgmt, "create_agent", fake_create)
    resp = client.post("/agent", json={
        "name": "e2e_客服助手", "system_prompt": "你是客服", "tool_ids": [],
    }, headers=ADD_PERMS)
    assert resp.json() == {"code": 200, "msg": "操作成功", "data": 7}

    # 入参校验（1001）：缺 system_prompt
    assert client.post("/agent", json={"name": "e2e_x"}, headers=ADD_PERMS
                       ).json()["code"] == 1001

    async def raise_5016(req):
        raise BusinessException("智能体名称已存在", 5016)
    monkeypatch.setattr(mgmt, "create_agent", raise_5016)
    assert client.post("/agent", json={
        "name": "e2e_客服助手", "system_prompt": "s"}, headers=ADD_PERMS
    ).json()["code"] == 5016


def test_update_and_delete_ok_data_null(client: TestClient,
                                        monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_update(agent_id: int, req):
        return None
    monkeypatch.setattr(mgmt, "update_agent", fake_update)
    body = client.put("/agent/3", json={
        "name": "e2e_客服助手", "system_prompt": "s", "status": 0, "tool_ids": [],
    }, headers=UPDATE_PERMS).json()
    assert body == {"code": 200, "msg": "操作成功", "data": None}

    async def fake_delete(agent_id: int):
        return None
    monkeypatch.setattr(mgmt, "delete_agent", fake_delete)
    assert client.delete("/agent/3", headers=DELETE_PERMS).json()["data"] is None


def test_run_logs_ok_structure(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_logs(agent_id: int, page: int, size: int):
        return AiPageResult(total=1, rows=[RunLogVo(
            id=9, session_id=12, user_id=1, status=0,
            tool_calls=[{"tool_key": "t", "arguments": {}, "ok": True, "error": None}],
            error_msg=None, duration_ms=15, create_time=datetime(2026, 10, 29, 12, 0, 0),
        )])
    monkeypatch.setattr(mgmt, "page_list_run_logs", fake_logs)
    body = client.get("/agent/3/runs", headers=LIST_PERMS).json()
    assert body["code"] == 200
    assert body["data"]["rows"][0]["tool_calls"][0]["tool_key"] == "t"


# ---------- 非流式运行（契约 §3.1） ----------


def test_run_ok_and_jwt_extraction(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_run(agent_id: int, req, user_jwt: str | None):
        captured["agent_id"] = agent_id
        captured["jwt"] = user_jwt
        return AgentAnswerVo(answer="答", model="glm-5.3")
    monkeypatch.setattr(runs, "run_answer", fake_run)
    resp = client.post("/agent/3/run", json=RUN_BODY,
                       headers={**RUN_PERMS, "Authorization": "Bearer abc.def"},
                       )
    body = resp.json()
    assert body["code"] == 200
    assert body["data"]["answer"] == "答"
    assert body["data"]["model"] == "glm-5.3"
    # Bearer 前缀已剥（MCP 通道消费裸 JWT）
    assert captured["agent_id"] == 3
    assert captured["jwt"] == "abc.def"

    # 非 Bearer 形态 / 缺头 → None（MCP 通道按无效凭证拒绝，不阻断对话本体）
    client.post("/agent/3/run", json=RUN_BODY,
                headers={**RUN_PERMS, "Authorization": "Basic xyz"})
    assert captured["jwt"] is None
    client.post("/agent/3/run", json=RUN_BODY, headers=RUN_PERMS)
    assert captured["jwt"] is None


def test_run_5015_R_json(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def raise_5015(agent_id: int, req, user_jwt: str | None):
        raise BusinessException("智能体不存在或已停用", 5015)
    monkeypatch.setattr(runs, "run_answer", raise_5015)
    assert client.post("/agent/9/run", json=RUN_BODY,
                       headers=RUN_PERMS).json()["code"] == 5015


def test_run_blank_question_1001(client: TestClient) -> None:
    resp = client.post("/agent/3/run", json={"session_id": 12, "question": "   "},
                       headers=RUN_PERMS)
    assert resp.json()["code"] == 1001


# ---------- 流式运行（契约 §3.2，SSE 帧协议前端定死后端对齐） ----------


def _mock_stream(monkeypatch: pytest.MonkeyPatch, tokens: list[str]) -> None:
    async def fake_stream(prepared, user_jwt: str | None):
        for t in tokens:
            yield t
    monkeypatch.setattr(runs, "stream_run_answer", fake_stream)


def _mock_prepare_ok(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_prepare(agent_id: int, req):
        return "prepared-marker"
    monkeypatch.setattr(runs, "prepare_run", fake_prepare)


def test_stream_frames_exact(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_prepare_ok(monkeypatch)
    _mock_stream(monkeypatch, ["你", "好，我是客服"])
    resp = client.post("/agent/3/run/stream", json=RUN_BODY, headers=RUN_PERMS)
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    assert resp.text == ('data: {"content": "你"}\n\n'
                         'data: {"content": "好，我是客服"}\n\n'
                         'data: [DONE]\n\n')


def test_stream_biz_error_msg_frame_then_done(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    """LLM 未配置（5002）等业务异常：msg 帧 + [DONE]，绝不裸断流。"""
    _mock_prepare_ok(monkeypatch)

    async def fake_stream(prepared, user_jwt: str | None):
        yield "半"
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", 5002)
    monkeypatch.setattr(runs, "stream_run_answer", fake_stream)
    resp = client.post("/agent/3/run/stream", json=RUN_BODY, headers=RUN_PERMS)
    assert resp.text == ('data: {"content": "半"}\n\n'
                         'data: {"msg": "LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）"}\n\n'
                         'data: [DONE]\n\n')


def test_stream_unknown_error_generic_msg(client: TestClient,
                                          monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_prepare_ok(monkeypatch)

    async def fake_stream(prepared, user_jwt: str | None):
        if False:  # 无 yield 的 async def 是协程不是 async generator——此行使形态正确
            yield ""
        raise RuntimeError("boom")
    monkeypatch.setattr(runs, "stream_run_answer", fake_stream)
    resp = client.post("/agent/3/run/stream", json=RUN_BODY, headers=RUN_PERMS)
    assert resp.text == ('data: {"msg": "系统异常，请稍后重试"}\n\n'
                         'data: [DONE]\n\n')


def test_stream_pre_start_errors_are_R_json_not_sse(
        client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    """流开始前拒绝（prepare_run 在 StreamingResponse 构造前调用）：R JSON 非 SSE 帧。"""
    for msg, code in [("智能体不存在或已停用", 5015), ("会话不存在", 5019),
                      ("无权访问该会话", 5020)]:
        async def fake_prepare(agent_id: int, req, _m=msg, _c=code):
            raise BusinessException(_m, _c)
        monkeypatch.setattr(runs, "prepare_run", fake_prepare)
        resp = client.post("/agent/3/run/stream", json=RUN_BODY, headers=RUN_PERMS)
        body = resp.json()
        assert body["code"] == code, msg
        assert body["msg"] == msg
        assert "data:" not in resp.text


def test_stream_jwt_passed_to_service(client: TestClient,
                                      monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_prepare_ok(monkeypatch)
    captured: dict[str, object] = {}

    async def fake_stream(prepared, user_jwt: str | None):
        captured["jwt"] = user_jwt
        yield "ok"
    monkeypatch.setattr(runs, "stream_run_answer", fake_stream)
    client.post("/agent/3/run/stream", json=RUN_BODY,
                headers={**RUN_PERMS, "Authorization": "Bearer jwt123"})
    assert captured["jwt"] == "jwt123"


# ---------- 会话（契约 §4） ----------


def test_create_session_ok(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(agent_id: int):
        return AgentSessionVo(id=7, agent_id=3, title="新对话",
                              create_time=datetime(2026, 10, 29, 9, 0, 0))
    monkeypatch.setattr(runs, "create_session", fake_create)
    body = client.post("/agent/3/sessions", headers=RUN_PERMS).json()
    assert body["code"] == 200
    assert body["data"] == {"id": 7, "agent_id": 3, "title": "新对话",
                            "create_time": "2026-10-29 09:00:00"}


def test_create_session_5015_and_identity_1001(client: TestClient,
                                               monkeypatch: pytest.MonkeyPatch) -> None:
    async def raise_5015(agent_id: int):
        raise BusinessException("智能体不存在或已停用", 5015)
    monkeypatch.setattr(runs, "create_session", raise_5015)
    assert client.post("/agent/9/sessions", headers=RUN_PERMS).json()["code"] == 5015

    async def raise_1001(agent_id: int):
        raise BusinessException("缺少用户身份（X-User-Id），无法创建会话", 1001)
    monkeypatch.setattr(runs, "create_session", raise_1001)
    assert client.post("/agent/3/sessions", headers=RUN_PERMS).json()["code"] == 1001


def test_session_list_ok(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_page(agent_id: int, page: int, size: int):
        return AiPageResult(total=1, rows=[AgentSessionVo(
            id=7, agent_id=3, title="退货怎么办", create_time=None)])
    monkeypatch.setattr(runs, "page_list_sessions", fake_page)
    body = client.get("/agent/3/sessions/list", headers=RUN_PERMS).json()
    assert body["code"] == 200
    assert body["data"]["rows"][0]["title"] == "退货怎么办"


def test_session_delete_and_messages_errors(client: TestClient,
                                            monkeypatch: pytest.MonkeyPatch) -> None:
    async def raise_5019(session_id: int):
        raise BusinessException("会话不存在", 5019)
    monkeypatch.setattr(runs, "delete_session", raise_5019)
    assert client.delete("/agent/sessions/5", headers=RUN_PERMS
                         ).json()["code"] == 5019

    async def raise_5020(session_id: int):
        raise BusinessException("无权访问该会话", 5020)
    monkeypatch.setattr(runs, "list_session_messages", raise_5020)
    assert client.get("/agent/sessions/5/messages", headers=RUN_PERMS
                      ).json()["code"] == 5020


def test_session_messages_ok(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_msgs(session_id: int):
        return AgentSessionMessagesVo(messages=[
            AgentSessionMessageVo(role="user", content="问"),
            AgentSessionMessageVo(role="assistant", content="答"),
        ])
    monkeypatch.setattr(runs, "list_session_messages", fake_msgs)
    body = client.get("/agent/sessions/5/messages", headers=RUN_PERMS).json()
    assert body["code"] == 200
    assert [m["role"] for m in body["data"]["messages"]] == ["user", "assistant"]


def _detail_vo() -> AgentDetailVo:
    return AgentDetailVo(
        id=3, name="e2e_客服助手", description=None, temperature=0.7, status=0,
        tool_names=["查询字典项"], system_prompt="你是客服", tool_ids=[2],
        config_version=1, create_time=None, update_time=None,
    )
