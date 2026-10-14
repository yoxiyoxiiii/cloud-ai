"""鉴权依赖组件（spec D4，R11 移交 #4 兑现）：AI 侧消费网关注入的 X-User-*。

契约事实对齐 Java 侧：网关 AuthGlobalFilter 注入 X-User-Perms（权限标识逗号连接，
SecurityConstants.PERMS_SEPARATOR=","），Java HeaderAuthFilter split 构建 authorities、
@PreAuthorize 拒绝 = ErrorCode.FORBIDDEN(403, "无操作权限")——本组件逐项对齐
（header 名 / 分隔符 / 拒绝文案）。信任边界同 Java 服务层：信任网关注入（网关已剥离
外部伪造 X-User-*），AI 侧不二次验签；本地直连调试（绕网关）须手工带 header。

2026-10-14 create_kb 轮：依赖同时 set_current_account（X-User-Account → db.base
contextvar，对齐 Java HeaderAuthFilter 单 filter 双职责——account+perms 一次透出；
无 header = None 可空列存 NULL）。
"""

from collections.abc import Awaitable, Callable

from fastapi import Request

from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException

PERMS_HEADER: str = "X-User-Perms"
ACCOUNT_HEADER: str = "X-User-Account"
PERMS_SEPARATOR: str = ","
MSG_FORBIDDEN: str = "无操作权限"
CODE_FORBIDDEN: int = 403


def require_perms(required: str) -> Callable[[Request], Awaitable[None]]:
    """构造 FastAPI 依赖：校验 X-User-Perms 含 required，不含则抛 403 业务异常。

    用法：APIRouter(prefix=..., dependencies=[Depends(require_perms("ai:chat:list"))])；
    拒绝经既有全局 handler 转 HTTP 200 + R{code:403}（对齐 Java @PreAuthorize 语义）。
    注意 BusinessException 既有签名 (msg, code)——msg 在前，勿按 Java (code, msg) 习惯写反。
    """

    async def check_perms(request: Request) -> None:
        set_current_account(request.headers.get(ACCOUNT_HEADER) or None)
        raw: str | None = request.headers.get(PERMS_HEADER)
        perms: list[str] = [p.strip() for p in raw.split(PERMS_SEPARATOR)] if raw else []
        if required not in perms:
            raise BusinessException(MSG_FORBIDDEN, CODE_FORBIDDEN)

    return check_perms
