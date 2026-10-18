"""鉴权依赖组件（spec D4，R11 移交 #4 兑现）：AI 侧消费网关注入的 X-User-*。

契约事实对齐 Java 侧：网关 AuthGlobalFilter 注入 X-User-Perms（权限标识逗号连接，
SecurityConstants.PERMS_SEPARATOR=","），Java HeaderAuthFilter split 构建 authorities、
@PreAuthorize 拒绝 = ErrorCode.FORBIDDEN(403, "无操作权限")——本组件逐项对齐
（header 名 / 分隔符 / 拒绝文案）。信任边界同 Java 服务层：信任网关注入（网关已剥离
外部伪造 X-User-*），AI 侧不二次验签；本地直连调试（绕网关）须手工带 header。

2026-10-14 create_kb 轮：依赖同时 set_current_account（X-User-Account → db.base
contextvar，对齐 Java HeaderAuthFilter 单 filter 双职责——account+perms 一次透出；
无 header = None 可空列存 NULL）。

2026-10-18 知识库权限轮：身份头从两个扩为五个（契约 2026-10-18-kb-permission-api.md
§6）——新增 X-User-Id / X-User-RoleIds / X-User-DeptIds，依赖内解析为 Identity 存
contextvar，供 service 层做可见性求值（管理豁免 + 授权命中）。解析容错：逗号分段
非整数跳过记 warning 不报错；整头缺失/空串 = 空列表（身份头缺失=空集语义，非错误）。
"""

import logging
from collections.abc import Awaitable, Callable
from contextvars import ContextVar
from dataclasses import dataclass

from fastapi import Request

from cloud_base_ai.db.base import set_current_account
from cloud_base_ai.exceptions import BusinessException

logger = logging.getLogger(__name__)

PERMS_HEADER: str = "X-User-Perms"
ACCOUNT_HEADER: str = "X-User-Account"
USER_ID_HEADER: str = "X-User-Id"
ROLE_IDS_HEADER: str = "X-User-RoleIds"
DEPT_IDS_HEADER: str = "X-User-DeptIds"
PERMS_SEPARATOR: str = ","
MSG_FORBIDDEN: str = "无操作权限"
CODE_FORBIDDEN: int = 403

# 知识库管理权限集合：持任一者即管理豁免（列表全量可见、文件免实例校验）。
# 豁免只影响可见性；管理动作本身仍由各端点 require_perms 独立裁决（双闸门）。
MGMT_PERMS: frozenset[str] = frozenset({"ai:kb:add", "ai:kb:update", "ai:kb:delete"})


@dataclass(frozen=True)
class Identity:
    """请求身份快照（网关五头的解析结果；auth 依赖外不可构造真值）。

    user_id/role_ids/dept_ids 为可见性求值三要素：dept_ids 是自身部门+全部祖先链
    （root→self，网关登录时聚合），部门授权「固定含子树」由此退化为 IN 比较。
    """

    user_id: int | None
    account: str | None
    perms: tuple[str, ...]
    role_ids: tuple[int, ...]
    dept_ids: tuple[int, ...]


_current_identity: ContextVar[Identity | None] = ContextVar("current_identity", default=None)


def set_current_identity(identity: Identity | None) -> None:
    """设置当前请求身份（require_perms 内调用；测试可直接 set 模拟登录态）。"""
    _current_identity.set(identity)


def current_identity() -> Identity | None:
    """当前请求身份；无请求上下文（本地脚本/未走鉴权依赖的调用）时为 None。"""
    return _current_identity.get()


def is_mgmt_exemption(identity: Identity) -> bool:
    """管理豁免判定：perms 含任一知识库管理权限（MGMT_PERMS）即全量可见。

    与权限组件同文件内聚：豁免口径消费的就是 perms 解析结果。
    """
    return any(perm in MGMT_PERMS for perm in identity.perms)


def _parse_id_header(raw: str | None) -> tuple[int, ...]:
    """逗号分隔 id 头解析：空头空元组；分段去空白，非整数分段跳过记 warning。"""
    if not raw:
        return ()
    ids: list[int] = []
    for segment in raw.split(PERMS_SEPARATOR):
        segment = segment.strip()
        if not segment:
            continue
        try:
            ids.append(int(segment))
        except ValueError:
            logger.warning("身份头含非整数分段已跳过: %r", segment)
    return tuple(ids)


def _parse_user_id(raw: str | None) -> int | None:
    """单值 id 头解析（X-User-Id = JWT subject 十进制数字串）：缺失/非整数 = None。"""
    if not raw:
        return None
    try:
        return int(raw.strip())
    except ValueError:
        logger.warning("X-User-Id 非整数已按缺失处理: %r", raw)
        return None


def require_perms(required: str) -> Callable[[Request], Awaitable[None]]:
    """构造 FastAPI 依赖：解析五头存 Identity + set_current_account，并校验
    X-User-Perms 含 required，不含则抛 403 业务异常。

    用法：APIRouter(prefix=..., dependencies=[Depends(require_perms("ai:chat:list"))])；
    拒绝经既有全局 handler 转 HTTP 200 + R{code:403}（对齐 Java @PreAuthorize 语义）。
    注意 BusinessException 既有签名 (msg, code)——msg 在前，勿按 Java (code, msg) 习惯写反。
    """

    async def check_perms(request: Request) -> None:
        account = request.headers.get(ACCOUNT_HEADER) or None
        set_current_account(account)
        raw_perms: str | None = request.headers.get(PERMS_HEADER)
        perms = tuple(
            p.strip() for p in raw_perms.split(PERMS_SEPARATOR) if p.strip()
        ) if raw_perms else ()
        set_current_identity(Identity(
            user_id=_parse_user_id(request.headers.get(USER_ID_HEADER)),
            account=account,
            perms=perms,
            role_ids=_parse_id_header(request.headers.get(ROLE_IDS_HEADER)),
            dept_ids=_parse_id_header(request.headers.get(DEPT_IDS_HEADER)),
        ))
        if required not in perms:
            raise BusinessException(MSG_FORBIDDEN, CODE_FORBIDDEN)

    return check_perms
