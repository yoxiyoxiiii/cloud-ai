"""MCP Server 管理面 service：注册表 CRUD（查重两层/内置行保护）+ 保存前连通测试
+ 用户授权 get/save（默认拒绝 + 管理豁免模型，契约 2026-10-30-mcp-server-permission-api.md）。

事务边界 @transactional 收口写路径；test_config 零库访问（保存前探测）不挂事务。
凭据红线：test_config 的错误 msg 只含异常类型与短摘要、日志只记 url 与键名，绝不落真值。
工具同步/审核（事务状态机）见 sync_service.py。
"""

import json
import logging
from types import SimpleNamespace
from urllib.parse import urlparse

from sqlalchemy.exc import IntegrityError

from cloud_base_agent import runtime
from cloud_ai_common.db.tx import current_session, transactional
from cloud_ai_common.exceptions import BusinessException
from cloud_base_agent.mcp import client as mcp_client
from cloud_base_agent.mcp_server import dao
from cloud_base_agent.mcp_server.entities import AiMcpServer, AiMcpServerGrant
from cloud_base_agent.mcp_server.schemas import (
    AiPageResult,
    CreateServerReq,
    ServerDetailVo,
    ServerPermsSaveReq,
    ServerPermsVo,
    ServerSummaryVo,
    TestConfigReq,
    TestResultVo,
    ToolManageVo,
    UpdateServerReq,
)
from cloud_base_agent.entities import AiTool

logger = logging.getLogger(__name__)

CODE_SERVER_NOT_EXISTS: int = 5021
MSG_SERVER_NOT_EXISTS: str = "MCP服务不存在"
CODE_SERVER_RESTRICTED: int = 5022
MSG_SERVER_RESTRICTED: str = "MCP服务操作受限"
MSG_SERVER_RESTRICTED_BUILTIN: str = "MCP服务操作受限：内置服务不可删除或改名"
MSG_SERVER_RESTRICTED_STDIO: str = "MCP服务操作受限：stdio 传输本轮不开放"
CODE_SERVER_NAME_EXISTS: int = 5023
MSG_SERVER_NAME_EXISTS: str = "MCP服务名已存在"


def _normalized_transport(transport: str | None, url: str) -> str:
    """transport 归一化：stdio 拒绝（5022）；省略按 url 路径含 /sse 推断 sse，否则 http。"""
    if transport == "stdio":
        raise BusinessException(MSG_SERVER_RESTRICTED_STDIO, CODE_SERVER_RESTRICTED)
    if transport in ("http", "sse"):
        return transport
    return "sse" if "/sse" in urlparse(url).path else "http"


def _dump_headers(headers: dict[str, str] | None) -> str | None:
    """头对象 → JSON 文本列（空对象/None 存 NULL）。"""
    return json.dumps(headers, ensure_ascii=False) if headers else None


def _load_headers(raw: str | None) -> dict[str, str]:
    """JSON 文本列 → 头对象（NULL/空回空 dict；详情回显用）。"""
    return json.loads(raw) if raw else {}


def _tool_counts_str(counts: dict[int, int]) -> str:
    """「草稿/已发布/失效」摘要串（契约 §2.1 tool_counts）。"""
    return f"{counts.get(AiTool.StatusEnum.DRAFT.value, 0)}" \
           f"/{counts.get(AiTool.StatusEnum.PUBLISHED.value, 0)}" \
           f"/{counts.get(AiTool.StatusEnum.MISSING.value, 0)}"


@transactional
async def create_server(req: CreateServerReq) -> int:
    """创建 server 行（契约 §2.2）：stdio 5022 → 查重 5023 → 落库。

    撞重双防：先查重挡常规重名（含 SYSTEM 内置占名）；并发窗口或软删墓碑占键由
    uk_name 的 IntegrityError 兜底——先 log.error 记根因，再转 5023。
    """
    session = current_session()
    transport = _normalized_transport(req.transport, req.url)
    exists = await dao.find_server_by_name(session, req.name)
    if exists is not None:
        raise BusinessException(MSG_SERVER_NAME_EXISTS, CODE_SERVER_NAME_EXISTS)
    try:
        row = await dao.save_server(session, AiMcpServer(
            name=req.name, description=req.description, url=req.url,
            transport=transport,
            headers_json=_dump_headers(req.headers),
            credential_headers_json=_dump_headers(req.credential_headers),
            timeout_ms=req.timeout_ms, status=req.status,
            is_builtin=0,
        ))
    except IntegrityError as e:
        logger.error("create_server duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_SERVER_NAME_EXISTS, CODE_SERVER_NAME_EXISTS) from e
    return row.id


async def update_server(server_id: int, req: UpdateServerReq) -> None:
    """外壳：内核事务提交后清 compiled agent 缓存——server 启停/编辑改变 agent 生效
    工具面，停用语义在缓存温热期也须成立。内核抛业务码（5021/5022/5023）路径不清
    缓存（零变更零失效）；commit 失败（罕见）缓存未清，行为退化到修复前不劣化。"""
    await _update_server_tx(server_id, req)
    runtime.reset_cache()


@transactional
async def _update_server_tx(server_id: int, req: UpdateServerReq) -> None:
    """全量编辑 server 行（契约 §2.4）：存在 5021 → 内置行保护（改名 5022）→ 查重
    排除自身 5023 → 全列覆盖 + 审计两值。内置行 url/transport/头配置/启停可编辑。"""
    session = current_session()
    row = await dao.find_server_by_id(session, server_id)
    if row is None:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)
    if row.is_builtin == 1 and req.name != row.name:
        raise BusinessException(MSG_SERVER_RESTRICTED_BUILTIN, CODE_SERVER_RESTRICTED)
    transport = _normalized_transport(req.transport, req.url)
    same_name = await dao.find_server_by_name(session, req.name)
    if same_name is not None and same_name.id != server_id:
        raise BusinessException(MSG_SERVER_NAME_EXISTS, CODE_SERVER_NAME_EXISTS)
    try:
        rowcount = await dao.update_server(
            session, server_id, name=req.name, description=req.description,
            url=req.url, transport=transport,
            headers_json=_dump_headers(req.headers),
            credential_headers_json=_dump_headers(req.credential_headers),
            timeout_ms=req.timeout_ms, status=req.status,
        )
    except IntegrityError as e:
        logger.error("update_server duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_SERVER_NAME_EXISTS, CODE_SERVER_NAME_EXISTS) from e
    if rowcount == 0:
        # 校验后被并发软删的窗口兜底（WHERE deleted=0 未命中）
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)


async def delete_server(server_id: int) -> None:
    """外壳：内核事务提交后清 compiled agent 缓存——软删使 server 工具面对运行
    不可见，温热期同样须生效。业务码路径不清缓存（零变更零失效）。"""
    await _delete_server_tx(server_id)
    runtime.reset_cache()


@transactional
async def _delete_server_tx(server_id: int) -> None:
    """软删 server 行（契约 §2.5）：内置行不可删（5022）；名下工具行与绑定行保留
    （对运行不可见由运行链路过滤）。停用语义请用 PUT status=1。"""
    session = current_session()
    row = await dao.find_server_by_id(session, server_id)
    if row is None:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)
    if row.is_builtin == 1:
        raise BusinessException(MSG_SERVER_RESTRICTED_BUILTIN, CODE_SERVER_RESTRICTED)
    rowcount = await dao.soft_delete_server(session, server_id)
    if rowcount == 0:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)


@transactional
async def page_list_servers(
        page: int, size: int, name: str | None = None) -> AiPageResult[ServerSummaryVo]:
    """分页查活行（契约 §2.1）：id 倒序，name 包含匹配可选；tool_counts 按页批量聚合。"""
    session = current_session()
    rows, total = await dao.page_list_servers(session, page, size, name)
    counts = await dao.count_tools_by_server(session, [r.id for r in rows])
    vos = [
        ServerSummaryVo(
            id=r.id, name=r.name, description=r.description, url=r.url,
            transport=r.transport, is_builtin=r.is_builtin, status=r.status,
            tool_counts=_tool_counts_str(counts.get(r.id, {})),
            create_time=r.create_time, update_time=r.update_time,
        )
        for r in rows
    ]
    return AiPageResult(total=total, rows=vos)


@transactional
async def find_server_detail(server_id: int) -> ServerDetailVo:
    """server 详情（契约 §2.3）：不存在 5021；credential_headers 回显键名映射不含真值。"""
    session = current_session()
    row = await dao.find_server_by_id(session, server_id)
    if row is None:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)
    counts = await dao.count_tools_by_server(session, [server_id])
    return ServerDetailVo(
        id=row.id, name=row.name, description=row.description, url=row.url,
        transport=row.transport, is_builtin=row.is_builtin, status=row.status,
        tool_counts=_tool_counts_str(counts.get(server_id, {})),
        create_time=row.create_time, update_time=row.update_time,
        headers=_load_headers(row.headers_json),
        credential_headers=_load_headers(row.credential_headers_json),
        timeout_ms=row.timeout_ms,
    )


@transactional
async def list_tools(server_id: int | None = None,
                     status: int | None = None) -> list[ToolManageVo]:
    """工具管理清单（契约 §2.8）：草稿/已发布/失效全态可见，不分页；归属 server
    已软删仍回显其名（台账完整）；input_schema 对象回显、perms 逗号串拆数组。"""
    session = current_session()
    rows = await dao.list_tools_with_server(session, server_id, status)
    return [
        ToolManageVo(
            id=tool.id, tool_key=tool.tool_key, name=tool.name,
            description=tool.description,
            input_schema=json.loads(tool.input_schema) if tool.input_schema else {},
            required_perms=[p for p in tool.required_perms.split(",") if p],
            status=tool.status, server_id=tool.server_id,
            server_name=server.name if server is not None else "",
            create_time=tool.create_time, update_time=tool.update_time,
        )
        for tool, server in rows
    ]


async def test_config(req: TestConfigReq, caller_jwt: str | None) -> TestResultVo:
    """保存前连通测试（契约 §2.6）：零 DB 写；握手 + tools/list，成功返回工具摘要。

    platform_auth=true 按内置服务双主体注入调用方 JWT + 服务密钥（把平台 /mcp 当
    外部形态测试的场景）；失败 5025（原因摘要不含凭据真值）/凭据缺 5026/未配置 5010
    ——转译统一在通道动词 mcp_client.list_tools。连通成功但清单为空是合法结果
    （ok=true, tool_count=0）。
    """
    transport = _normalized_transport(req.transport, req.url)
    if req.platform_auth:
        row = SimpleNamespace(is_builtin=1, name=req.name, url=req.url,
                              transport=transport, timeout_ms=req.timeout_ms)
    else:
        row = SimpleNamespace(
            is_builtin=0, name=req.name, url=req.url, transport=transport,
            headers_json=_dump_headers(req.headers),
            credential_headers_json=_dump_headers(req.credential_headers),
            timeout_ms=req.timeout_ms,
        )
    tools = await mcp_client.list_tools(row, caller_jwt)
    names = [t.name for t in tools]
    return TestResultVo(ok=True, tool_count=len(names), sample_tools=names[:5], error=None)


# ---------- 用户授权（契约 2026-10-30-mcp-server-permission-api.md §2/§3） ----------


@transactional
async def get_server_perms(server_id: int) -> ServerPermsVo:
    """查询 MCP 服务授权（契约 §2）：先校验 server 存在/未软删（5021），再按主体类型
    三组分型回显；读授权清单与保存同权限（管理配置动作，不开放给 list-only 探测）。"""
    session = current_session()
    if await dao.find_server_by_id(session, server_id) is None:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)
    grants = await dao.list_server_grants(session, server_id)
    role_ids = [g.subject_id for g in grants
                if g.subject_type == AiMcpServerGrant.SubjectTypeEnum.ROLE]
    dept_ids = [g.subject_id for g in grants
                if g.subject_type == AiMcpServerGrant.SubjectTypeEnum.DEPT]
    user_ids = [g.subject_id for g in grants
                if g.subject_type == AiMcpServerGrant.SubjectTypeEnum.USER]
    return ServerPermsVo(role_ids=role_ids, dept_ids=dept_ids, user_ids=user_ids)


@transactional
async def save_server_perms(server_id: int, req: ServerPermsSaveReq) -> None:
    """保存 MCP 服务授权（契约 §3）：先校验 server 存在（5021），再全量覆盖（按
    server_id 物理删全部旧授权行 → 批插新行，同一事务）；三组全空/不传 = 清空授权
    （该 server 回到默认拒绝，仅豁免者可用）——合法语义非错误。

    主体弱引用：不校验 role/dept/user 存在性（主数据在 cloud_system 库），失效授权
    永不命中（自愈语义）。同请求重复 id 由组装层去重（dict.fromkeys 保序）；并发保存
    同 server 撞 uk 属极端窗口，事务级回滚走全局 500（契约 §3 分支无专用码）。
    保存后无需缓存失效动作：compiled agent 缓存键已含工具面指纹，授权变化自然走
    新键重组装（设计 §2.2）。"""
    session = current_session()
    if await dao.find_server_by_id(session, server_id) is None:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)
    await dao.save_server_grants(session, server_id, _build_server_grants(req))


def _build_server_grants(req: ServerPermsSaveReq) -> list[AiMcpServerGrant]:
    """三组 id 保序去重后组装授权行实体（server_id/create_by 由 dao 统一落值，
    create_time 由 DB DEFAULT 时钟生成——应用层不传时间）。"""
    grants: list[AiMcpServerGrant] = []
    for subject_type, ids in (
        (AiMcpServerGrant.SubjectTypeEnum.ROLE, req.role_ids),
        (AiMcpServerGrant.SubjectTypeEnum.DEPT, req.dept_ids),
        (AiMcpServerGrant.SubjectTypeEnum.USER, req.user_ids),
    ):
        for subject_id in dict.fromkeys(ids):
            grants.append(AiMcpServerGrant(subject_type=subject_type.value,
                                           subject_id=subject_id))
    return grants
