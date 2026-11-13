"""RAG MCP 端点双主体认证中间件（纯 ASGI，先于 MCP 协议层执行）。

校验顺序与拒绝形态对齐 Java cloud-system 的 McpAuthFilter（契约
2026-10-17-mcp-tools-api.md §1 / 2026-11-13-rag-mcp-tools-api.md §1.3）：
① 服务密钥 X-MCP-Client-Secret 常量时间比对（配置空 = 未配置态 fail-closed
   拒绝，不阻断启动）；② Authorization Bearer 仅解 payload 取 jti——不验签，
   HS512 密钥只在 Java 侧 Nacos，Python 侧不持有，payload 其余声明一概不采信；
③ 会话 Redis GET sso:online:{jti}——键不存在/值损坏/Redis 异常均拒绝。
任一步不过 = HTTP 401 + R JSON（非 MCP 协议帧）。

身份五要素（userId/account/permissions/roleIds/deptIds）全部取会话值构建
security.Identity 注入 contextvar（与网关注入的五个 X-User-* 头同构），工具内
current_identity() 取用；finally 复位。会话 JSON 结构依赖已在契约 §1.3 声明。

日志红线：密钥真值、完整请求头 dump 绝不进日志；401 只记 warn 一行原因分类。
"""

import base64
import hmac
import json
import logging

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from cloud_base_ai.config import get_settings
from cloud_base_ai.redis_client import get_sso_redis_client
from cloud_base_ai.security import Identity, reset_current_identity, set_current_identity

logger = logging.getLogger(__name__)

SECRET_HEADER = "x-mcp-client-secret"
AUTHORIZATION_HEADER = "authorization"
BEARER_PREFIX = "Bearer "
ONLINE_KEY_PREFIX = "sso:online:"
MSG_MCP_AUTH_FAILED = "MCP 认证失败"


def _header(scope: Scope, name: str) -> str | None:
    """按小写名取首个匹配头（ASGI 头名恒小写）。"""
    for key, value in scope.get("headers", []):
        if key.decode("latin-1").lower() == name:
            return value.decode("latin-1")
    return None


def _send_unauthorized(scope: Scope, receive: Receive, send: Send) -> None:
    """HTTP 401 + R JSON（真实状态码，body 与契约 §1.4 逐字一致）。"""
    response = JSONResponse(
        {"code": 401, "msg": MSG_MCP_AUTH_FAILED, "data": None}, status_code=401
    )
    return response(scope, receive, send)


def _extract_jti(authorization: str | None) -> str | None:
    """Bearer token 解 payload 取 jti（不验签）：畸形 token / 无 jti 返回 None。

    payload 段 base64url 解码需补齐 padding；ValueError 同时覆盖 base64 与
    JSON 两类解码失败（binascii.Error 是其子类）。
    """
    if not authorization or not authorization.startswith(BEARER_PREFIX):
        return None
    parts = authorization[len(BEARER_PREFIX):].strip().split(".")
    if len(parts) < 2 or not parts[1]:
        return None
    segment = parts[1]
    try:
        payload = base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))
        claims = json.loads(payload)
    except ValueError:
        return None
    if not isinstance(claims, dict):
        return None
    jti = claims.get("jti")
    return jti if isinstance(jti, str) and jti else None


def _int_tuple(values: object) -> tuple[int, ...]:
    """会话 JSON 的 id 列表转 int 元组：非 list / 非整数元素跳过（解析容错）。"""
    if not isinstance(values, list):
        return ()
    return tuple(v for v in values if isinstance(v, int) and not isinstance(v, bool))


def _build_identity(session_json: str) -> Identity:
    """会话 JSON 五要素构建 Identity（字段名契约 §1.3 依赖声明；损坏抛 ValueError）。"""
    data = json.loads(session_json)
    if not isinstance(data, dict):
        raise ValueError("session value is not a json object")
    user_id = data.get("userId")
    account = data.get("account")
    perms = data.get("permissions")
    return Identity(
        user_id=user_id if isinstance(user_id, int) and not isinstance(user_id, bool) else None,
        account=account if isinstance(account, str) and account else None,
        perms=tuple(p for p in perms if isinstance(p, str)) if isinstance(perms, list) else (),
        role_ids=_int_tuple(data.get("roleIds")),
        dept_ids=_int_tuple(data.get("deptIds")),
    )


class RagMcpAuthMiddleware:
    """纯 ASGI 认证中间件：双主体校验 → 身份注入 contextvar → 内层 MCP 应用。"""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        if not self._check_client_secret(scope):
            await _send_unauthorized(scope, receive, send)
            return
        jti = _extract_jti(_header(scope, AUTHORIZATION_HEADER))
        if jti is None:
            logger.warning("rag mcp auth rejected: bearer token missing/malformed or no jti")
            await _send_unauthorized(scope, receive, send)
            return
        try:
            # 同步 Redis GET 在事件循环内短阻塞（本机内网低延迟 MVP 取舍，对齐
            # Java 网关 AuthGlobalFilter 同步 StringRedisTemplate 的已记档决策）
            raw = get_sso_redis_client().get(f"{ONLINE_KEY_PREFIX}{jti}")
        except Exception as e:
            # 只记异常类型与消息摘要（redis 客户端异常不含密钥真值），fail-closed
            logger.error("rag mcp auth: session redis error: %s: %s", type(e).__name__, e)
            await _send_unauthorized(scope, receive, send)
            return
        if raw is None:
            logger.warning("rag mcp auth rejected: session offline (jti not found)")
            await _send_unauthorized(scope, receive, send)
            return
        try:
            identity = _build_identity(raw)
        except ValueError:
            logger.warning("rag mcp auth rejected: session value corrupted (not valid json)")
            await _send_unauthorized(scope, receive, send)
            return
        token = set_current_identity(identity)
        try:
            await self.app(scope, receive, send)
        finally:
            reset_current_identity(token)

    def _check_client_secret(self, scope: Scope) -> bool:
        """服务密钥校验：未配置态 fail-closed；常量时间比对防时序探测。"""
        expected = get_settings().mcp.client_secret
        if not expected:
            logger.warning("rag mcp auth rejected: client secret not configured")
            return False
        provided = _header(scope, SECRET_HEADER)
        if provided is None or not hmac.compare_digest(
            provided.encode("utf-8"), expected.encode("utf-8")
        ):
            logger.warning("rag mcp auth rejected: client secret mismatch or missing")
            return False
        return True
