"""MCP Server 工具同步 + 审核 service（契约 §2.7/§2.9；状态机设计文档 §3 D5/D6）。

sync 三段式（事务边界关键）：
1. 校验段（短事务）：server 存在（5021）且启用（5024）——行经 expire_on_commit=False
   安全跨事务持有；
2. 网络段（事务外）：通道动词拉 tools/list——失败 5025/5026 时**库内零变更**
   （防止网络故障清空注册面）；
3. 事务段：D5 状态机 upsert（新增草稿 / 更新 schema / 失效复活回草稿 / 置失效 /
   跨 server 撞名记 conflicts 跳过）；空清单=合法全置失效。
同步与审核成功后（事务提交后）显式清 compiled agent 缓存（设计文档 D8——工具行
每运行查库，缓存是第二级新鲜度兜底）。
"""

import json
import logging

from cloud_base_ai.agent import runtime
from cloud_base_ai.agent.entities import AiTool
from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import client as mcp_client
from cloud_base_ai.mcp_server import dao
from cloud_base_ai.mcp_server.entities import AiMcpServer
from cloud_base_ai.mcp_server.manage_service import (
    CODE_SERVER_NOT_EXISTS,
    MSG_SERVER_NOT_EXISTS,
)
from cloud_base_ai.mcp_server.schemas import ReviewToolReq, SyncReportVo

logger = logging.getLogger(__name__)

CODE_SERVER_NOT_ENABLED: int = 5024
MSG_SERVER_NOT_ENABLED: str = "MCP服务未启用"
CODE_TOOL_NOT_EXISTS: int = 5027
MSG_TOOL_NOT_EXISTS: str = "工具不存在"
CODE_SCHEMA_UNBUILDABLE: int = 5028
MSG_SCHEMA_UNBUILDABLE: str = "工具入参模型不可构建"

# 超长防御截断上限（对齐列宽：tool_key 100 / name 50 / description 1024）
_MAX_TOOL_KEY = 100
_MAX_NAME = 50
_MAX_DESCRIPTION = 1024


def _truncate(value: str, limit: int) -> str:
    """超长截断加 … 后缀（server 上报名不受信，截后总长不超列宽）。"""
    return value if len(value) <= limit else value[: limit - 1] + "…"


# ---------- 同步（契约 §2.7） ----------


async def sync_server(server_id: int, admin_jwt: str | None) -> SyncReportVo:
    """工具同步：校验 → 网络拉取 → 事务 upsert → 清 agent 缓存，返回同步报告。"""
    server_row = await _load_enabled_server(server_id)
    remote_tools = await mcp_client.list_tools(server_row, admin_jwt)
    report = await _apply_sync_upsert(server_id, remote_tools)
    runtime.reset_cache()
    return report


@transactional
async def _load_enabled_server(server_id: int) -> AiMcpServer:
    """校验段：存在且启用（停用 server 拒绝同步——先启停再同步的操作次序）。"""
    session = current_session()
    row = await dao.find_server_by_id(session, server_id)
    if row is None:
        raise BusinessException(MSG_SERVER_NOT_EXISTS, CODE_SERVER_NOT_EXISTS)
    if row.status != AiMcpServer.StatusEnum.ENABLED.value:
        raise BusinessException(MSG_SERVER_NOT_ENABLED, CODE_SERVER_NOT_ENABLED)
    return row


@transactional
async def _apply_sync_upsert(server_id: int, remote_tools: list) -> SyncReportVo:
    """事务段：D5 状态机 upsert。

    - 库中无此 tool_key → 新增草稿（name 初始=tool_key，perms 空=登录即可）；
    - 本 server 既有行 → 以 server 上报为准覆盖 description/input_schema
      （required_perms/name 是审核域字段不动）；失效行复活回退草稿（防 server 端
      换实现后免审生效），草稿/已发布保持原状态；
    - 上报名已挂其他 server → 跳过进 conflicts（全局 uk_tool_key 的管理面语义）；
    - 本 server 既有行未再上报 → 置失效（幂等：已失效不重复计数不刷时间）。
    """
    session = current_session()
    existing = {t.tool_key: t for t in await dao.list_tools_by_server(session, server_id)}
    owner_of = await dao.map_tool_key_owner(session, [t.name for t in remote_tools])
    added = updated = missing = 0
    conflicts: list[str] = []
    reported: set[str] = set()
    for tool in remote_tools:
        tool_key = _truncate(str(tool.name or ""), _MAX_TOOL_KEY)
        if not tool_key or tool_key in reported:
            continue  # 空名/同批重名上报（异常数据防御，不入库不计数）
        reported.add(tool_key)
        schema_obj = tool.input_schema if isinstance(tool.input_schema, dict) else {}
        schema_text = json.dumps(schema_obj, ensure_ascii=False)
        description = _truncate(str(tool.description or ""), _MAX_DESCRIPTION)
        row = existing.get(tool_key)
        if row is not None:
            status = (AiTool.StatusEnum.DRAFT.value
                      if row.status == AiTool.StatusEnum.MISSING.value else row.status)
            await dao.update_tool_schema(
                session, row.id, description=description,
                input_schema=schema_text, status=status)
            updated += 1
        elif tool_key in owner_of:
            conflicts.append(tool_key)
        else:
            await dao.save_tool(session, AiTool(
                tool_key=tool_key, name=_truncate(tool_key, _MAX_NAME),
                description=description, input_schema=schema_text,
                required_perms="", server_id=server_id,
                status=AiTool.StatusEnum.DRAFT.value))
            added += 1
    for tool_key, row in existing.items():
        if tool_key not in reported:
            missing += await dao.mark_tool_missing(session, row.id)
    return SyncReportVo(added=added, updated=updated, missing=missing, conflicts=conflicts)


# ---------- 审核（契约 §2.9） ----------


async def review_tool(tool_id: int, req: ReviewToolReq) -> None:
    """工具审核：schema 可构建校验（不可构建 5028 停留草稿）→ 置已发布 +
    required_perms/可选显示名 → 清 agent 缓存。已发布行重复审核=改 perms/显示名。"""
    await _review_tool_tx(tool_id, req)
    runtime.reset_cache()


@transactional
async def _review_tool_tx(tool_id: int, req: ReviewToolReq) -> None:
    session = current_session()
    tool = await dao.find_tool_by_id(session, tool_id)
    if tool is None:
        raise BusinessException(MSG_TOOL_NOT_EXISTS, CODE_TOOL_NOT_EXISTS)
    try:
        runtime.build_args_schema(tool)
    except BusinessException as e:
        logger.error("tool review schema unbuildable, tool_key=%s: %s",
                     tool.tool_key, e.msg)
        raise BusinessException(
            f"{MSG_SCHEMA_UNBUILDABLE}：{e.msg}", CODE_SCHEMA_UNBUILDABLE) from e
    await dao.review_publish_tool(
        session, tool_id,
        required_perms=",".join(req.required_perms),
        name=req.name if req.name is not None else tool.name,
    )
