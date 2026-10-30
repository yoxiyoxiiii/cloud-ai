"""MCP Server 配置化管理 API 入口（契约 2026-10-30-mcp-server-mgmt-api.md §2）。

鉴权沿 agent 域形态：路由级依赖 require_perms——X-User-Perms 缺失经全局 handler
转 HTTP 200 + R{code:403}；权限五枚分立挂五个 router（list/add/update/delete/sync），
路由级依赖保证挂进来的新端点自动受保护（漏写依赖即放行的防线）。

工具同步/清单/审核端点（§2.7-§2.9）在文件下半段。
"""

import logging

from fastapi import APIRouter, Depends, Header, Query

from cloud_base_ai.mcp_server import manage_service, sync_service
from cloud_base_ai.mcp_server.schemas import (
    AiPageResult,
    CreateServerReq,
    ReviewToolReq,
    ServerDetailVo,
    ServerSummaryVo,
    SyncReportVo,
    TestConfigReq,
    TestResultVo,
    ToolManageVo,
    UpdateServerReq,
)
from cloud_base_ai.response import R
from cloud_base_ai.security import require_perms

logger = logging.getLogger(__name__)


def _extract_jwt(authorization: str | None) -> str | None:
    """从 Authorization 头取裸 token（网关验签后原样转发该头）。

    test-config 的 platform_auth=true / 内置 server 同步要以发起管理员身份走
    双主体；缺失/非 Bearer 形态返回 None（MCP 通道按无效凭证拒绝）。
    """
    if not authorization or not authorization.startswith("Bearer "):
        return None
    return authorization[len("Bearer "):].strip() or None


# 管理查询（契约 §2.1/§2.3；B5 的工具清单 §2.8 同挂本 perms）
mcp_server_list_router = APIRouter(prefix="/mcp/server",
                                   dependencies=[Depends(require_perms("ai:mcp:list"))])
mcp_server_add_router = APIRouter(prefix="/mcp/server",
                                  dependencies=[Depends(require_perms("ai:mcp:add"))])
mcp_server_update_router = APIRouter(prefix="/mcp/server",
                                     dependencies=[Depends(require_perms("ai:mcp:update"))])
mcp_server_delete_router = APIRouter(prefix="/mcp/server",
                                     dependencies=[Depends(require_perms("ai:mcp:delete"))])
# 连通测试 + 工具同步（契约 §2.6/§2.7）
mcp_server_sync_router = APIRouter(prefix="/mcp/server",
                                   dependencies=[Depends(require_perms("ai:mcp:sync"))])


@mcp_server_list_router.get("/list")
async def page_list_servers(
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，钳制 1..200"),
        name: str | None = Query(default=None, max_length=30,
                                 description="服务名包含匹配；不传或空串=不过滤"),
) -> R[AiPageResult[ServerSummaryVo]]:
    """server 分页列表（契约 §2.1）：id 倒序，软删不可见，tool_counts 实时聚合。"""
    result = await manage_service.page_list_servers(page, size, name)
    return R.ok(result)


@mcp_server_list_router.get("/{server_id}")
async def find_server_detail(server_id: int) -> R[ServerDetailVo]:
    """server 详情（契约 §2.3）：不存在 5021；credential_headers 只回显键名映射。"""
    vo = await manage_service.find_server_detail(server_id)
    return R.ok(vo)


@mcp_server_add_router.post("")
async def create_server(req: CreateServerReq) -> R[int]:
    """创建 server（契约 §2.2）：5023 重名 / 5022 stdio / 1001 参数；出参新 id。"""
    server_id: int = await manage_service.create_server(req)
    return R.ok(server_id)


@mcp_server_update_router.put("/{server_id}")
async def update_server(server_id: int, req: UpdateServerReq) -> R[None]:
    """全量编辑 server（契约 §2.4）：内置行改名 5022，data 恒 null。"""
    await manage_service.update_server(server_id, req)
    return R.ok(None)


@mcp_server_delete_router.delete("/{server_id}")
async def delete_server(server_id: int) -> R[None]:
    """软删 server（契约 §2.5）：内置行不可删 5022，data 恒 null。"""
    await manage_service.delete_server(server_id)
    return R.ok(None)


@mcp_server_sync_router.post("/test-config")
async def test_server_config(
        req: TestConfigReq,
        authorization: str | None = Header(default=None),
) -> R[TestResultVo]:
    """保存前连通测试（契约 §2.6）：无 DB 写；platform_auth=true 按内置双主体
    注入调用方 JWT + 服务密钥（把平台 /mcp 当外部形态测试的场景）。"""
    vo = await manage_service.test_config(req, _extract_jwt(authorization))
    return R.ok(vo)


@mcp_server_sync_router.post("/{server_id}/sync")
async def sync_server_tools(
        server_id: int,
        authorization: str | None = Header(default=None),
) -> R[SyncReportVo]:
    """工具同步（契约 §2.7）：tools/list → 草稿/失效状态机；内置 server 以发起
    管理员 JWT + 服务密钥双主体调用（Authorization 原样转发）。"""
    report = await sync_service.sync_server(server_id, _extract_jwt(authorization))
    return R.ok(report)


# 工具管理面（契约 §2.8/§2.9）：工具清单挂 list perms、审核挂 update perms
# （§0 分段表），路径前缀 /mcp/tool 与 server 面分开
mcp_tool_list_router = APIRouter(prefix="/mcp/tool",
                                 dependencies=[Depends(require_perms("ai:mcp:list"))])
mcp_tool_update_router = APIRouter(prefix="/mcp/tool",
                                   dependencies=[Depends(require_perms("ai:mcp:update"))])


@mcp_tool_list_router.get("/list")
async def list_tools(
        server_id: int | None = Query(default=None, description="按归属 server 过滤"),
        status: int | None = Query(default=None, ge=0, le=2,
                                   description="按状态过滤：0=草稿 1=已发布 2=已失效"),
) -> R[list[ToolManageVo]]:
    """工具管理清单（契约 §2.8）：全态可见不分页（每 server 量级十位数内）。"""
    vos = await manage_service.list_tools(server_id, status)
    return R.ok(vos)


@mcp_tool_update_router.put("/{tool_id}/review")
async def review_tool(tool_id: int, req: ReviewToolReq) -> R[None]:
    """工具审核（契约 §2.9）：配置 required_perms（空数组=登录即可）与可选显示名
    → 校验 schema 可构建 → 置已发布；不可构建 5028 停留草稿。"""
    await sync_service.review_tool(tool_id, req)
    return R.ok(None)
