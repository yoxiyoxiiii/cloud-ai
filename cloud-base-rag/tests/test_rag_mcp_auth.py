"""RAG MCP 双主体认证中间件测试（纯 ASGI 直调，不依赖 HTTP 服务器）。

分支矩阵：密钥不符/缺失/未配置态、Authorization 缺失/畸形/无 jti、会话键不存在、
会话 JSON 损坏、Redis 异常——各断言 401 状态码与 body 逐字（契约 §1.4）；
正常路径断言 Identity 五要素注入与复位。Redis/Settings 均以替身注入。
"""

import base64
import json
from types import SimpleNamespace

import pytest

from cloud_base_rag.mcp_auth import RagMcpAuthMiddleware
from cloud_ai_common.security import Identity, current_identity

pytestmark = pytest.mark.anyio

SECRET = "unit-secret"
SESSION_JSON = json.dumps({
    "tokenId": "tid-1", "userId": 7, "account": "admin",
    "permissions": ["ai:kb:list", "ai:kb:add"],
    "roleIds": [1, 2], "deptIds": [10, 30],
})
EXPECTED_401_BODY = '{"code":401,"msg":"MCP 认证失败","data":null}'


def b64seg(data: dict | str) -> str:
    raw = json.dumps(data).encode() if isinstance(data, dict) else data.encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def make_jwt(payload: dict | str) -> str:
    """三段式 JWT（中间件不验签，签名段占位即可）。"""
    return f"{b64seg({'alg': 'none'})}.{b64seg(payload)}.{b64seg('sig')}"


def make_scope(headers: dict[str, str] | None = None) -> dict:
    return {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
        "method": "POST", "path": "/",
        "headers": [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()],
    }


class Recorder:
    """内层应用替身：记录是否被调与调用瞬间的 contextvar 身份。"""

    def __init__(self) -> None:
        self.called = False
        self.identity_at_call: Identity | None = "unset"  # type: ignore[assignment]

    async def __call__(self, scope, receive, send) -> None:
        self.called = True
        self.identity_at_call = current_identity()


class FakeRedis:
    """会话 Redis 替身：固定返回值或抛错；记录被查询的键。"""

    def __init__(self, value: str | None = None, error: Exception | None = None) -> None:
        self.value = value
        self.error = error
        self.keys: list[str] = []

    def get(self, key: str) -> str | None:
        self.keys.append(key)
        if self.error is not None:
            raise self.error
        return self.value


async def run(scope: dict) -> tuple[int, str, Recorder]:
    """直调中间件（内层为 Recorder 替身）：返回 (status, body_text, 替身)。

    status=-1 表示未写出 401 响应（即请求已放行至内层应用）。
    """
    recorder = Recorder()
    middleware = RagMcpAuthMiddleware(recorder)
    messages: list[dict] = []

    async def receive() -> dict:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: dict) -> None:
        messages.append(message)

    await middleware(scope, receive, send)
    if messages and messages[0]["type"] == "http.response.start":
        status = messages[0]["status"]
        body = b"".join(m.get("body", b"") for m in messages
                        if m["type"] == "http.response.body").decode("utf-8")
        return status, body, recorder
    return -1, "", recorder


@pytest.fixture
def patched_deps(monkeypatch: pytest.MonkeyPatch):
    """注入替身 Settings 与会话 Redis；返回 (redis 替身注册表)。

    redis_for(secret=..., value=..., error=...) 生成一次运行所用的替身。
    """
    holder: dict = {}

    def install(secret: str = SECRET, value: str | None = SESSION_JSON,
                error: Exception | None = None) -> FakeRedis:
        fake = FakeRedis(value=value, error=error)
        monkeypatch.setattr(
            "cloud_base_rag.mcp_auth.get_settings",
            lambda: SimpleNamespace(mcp=SimpleNamespace(client_secret=secret)),
        )
        monkeypatch.setattr(
            "cloud_base_rag.mcp_auth.get_sso_redis_client", lambda: fake
        )
        return fake

    holder["install"] = install
    return holder


def good_headers() -> dict[str, str]:
    return {"X-MCP-Client-Secret": SECRET,
            "Authorization": f"Bearer {make_jwt({'jti': 'jti-1'})}"}




async def test_reject_wrong_secret(patched_deps) -> None:
    patched_deps["install"]()
    status, body, recorder = await run(make_scope({
        "X-MCP-Client-Secret": "wrong", "Authorization": f"Bearer {make_jwt({'jti': 'j'})}"}))
    assert (status, body) == (401, EXPECTED_401_BODY)
    assert not recorder.called


async def test_reject_missing_secret_header(patched_deps) -> None:
    patched_deps["install"]()
    status, body, _ = await run(make_scope(
        {"Authorization": f"Bearer {make_jwt({'jti': 'j'})}"}))
    assert (status, body) == (401, EXPECTED_401_BODY)


async def test_reject_secret_unconfigured(patched_deps) -> None:
    """未配置态 fail-closed：配置空时即使请求头同空也拒绝。"""
    patched_deps["install"](secret="")
    status, body, _ = await run(make_scope(
        {"X-MCP-Client-Secret": "", "Authorization": f"Bearer {make_jwt({'jti': 'j'})}"}))
    assert (status, body) == (401, EXPECTED_401_BODY)


async def test_reject_authorization_missing(patched_deps) -> None:
    patched_deps["install"]()
    status, body, _ = await run(make_scope({"X-MCP-Client-Secret": SECRET}))
    assert (status, body) == (401, EXPECTED_401_BODY)


@pytest.mark.parametrize("authorization", [
    "not-a-token",                      # 无分段
    "Bearer one-segment",               # 无点分隔
    "Bearer a.##invalid-b64##.c",       # payload 非法 base64url
    f"Bearer {b64seg('not-json-object')}.x",  # payload 不是 JWT 结构（首段即 payload）
])
async def test_reject_malformed_token(patched_deps, authorization: str) -> None:
    patched_deps["install"]()
    status, body, _ = await run(make_scope(
        {"X-MCP-Client-Secret": SECRET, "Authorization": authorization}))
    assert (status, body) == (401, EXPECTED_401_BODY)


async def test_reject_payload_without_jti(patched_deps) -> None:
    patched_deps["install"]()
    status, body, _ = await run(make_scope(
        {"X-MCP-Client-Secret": SECRET,
         "Authorization": f"Bearer {make_jwt({'sub': '7', 'account': 'admin'})}"}))
    assert (status, body) == (401, EXPECTED_401_BODY)


async def test_reject_session_key_absent(patched_deps) -> None:
    """会话键不存在（未登录/过期/注销/强退）。"""
    fake = patched_deps["install"](value=None)
    status, body, _ = await run(make_scope(good_headers()))
    assert (status, body) == (401, EXPECTED_401_BODY)
    assert fake.keys == ["sso:online:jti-1"]


async def test_reject_session_json_corrupted(patched_deps) -> None:
    patched_deps["install"](value="{not-json")
    status, body, _ = await run(make_scope(good_headers()))
    assert (status, body) == (401, EXPECTED_401_BODY)


async def test_reject_redis_error(patched_deps) -> None:
    """Redis 异常 fail-closed：记 error 日志后仍 401。"""
    patched_deps["install"](error=ConnectionError("redis down"))
    status, body, _ = await run(make_scope(good_headers()))
    assert (status, body) == (401, EXPECTED_401_BODY)


async def test_normal_path_injects_and_resets_identity(patched_deps) -> None:
    patched_deps["install"]()
    status, body, recorder = await run(make_scope(good_headers()))
    assert status == -1  # 无 401 响应写出 = 请求到达内层应用
    assert recorder.called
    identity = recorder.identity_at_call
    assert isinstance(identity, Identity)
    assert (identity.user_id, identity.account) == (7, "admin")
    assert identity.perms == ("ai:kb:list", "ai:kb:add")
    assert identity.role_ids == (1, 2)
    assert identity.dept_ids == (10, 30)
    assert current_identity() is None  # finally 复位


async def test_session_fields_tolerant(patched_deps) -> None:
    """会话值字段类型容错：非整数 userId/缺 roleIds 不炸、归空集语义。"""
    patched_deps["install"](value=json.dumps({"userId": "7", "account": "admin"}))
    _status, _body, recorder = await run(make_scope(good_headers()))
    identity = recorder.identity_at_call
    assert identity is not None
    assert identity.user_id is None
    assert identity.perms == ()
    assert identity.role_ids == ()


async def test_non_http_scope_passes_through(patched_deps) -> None:
    """lifespan 等非 http 控制帧不经认证直通（挂载子应用的 lifespan 组合依赖此行为）。"""
    patched_deps["install"]()
    recorder = Recorder()
    mw = RagMcpAuthMiddleware(recorder)
    messages: list[dict] = []

    async def receive() -> dict:
        return {"type": "lifespan.startup"}

    async def send(message: dict) -> None:
        messages.append(message)

    await mw({"type": "lifespan"}, receive, send)
    assert recorder.called
