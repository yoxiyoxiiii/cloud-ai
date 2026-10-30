/**
 * MCP Server 配置化管理接口（契约 2026-10-30-mcp-server-mgmt-api，cloud-base-ai，网关前缀 /ai）：
 * 管理面九端点（§0/§2，perms ai:mcp:list/add/update/delete/sync）。
 * AI 域出参口径（契约沿 agent 契约 §0）：字段 snake_case、id/total 为 JSON number
 * （非 Java 域 Long→String）、分页查询参数名 page/size。本轮无流式端点（utils/sse.ts 冻结）
 */
import { request } from '../utils/request'
import type { AiPageResult } from '../types/api'
import type {
  CreateMcpServerPayload,
  McpServerDetailVo,
  McpServerPageQuery,
  McpServerSummaryVo,
  McpSyncReportVo,
  McpTestResultVo,
  McpToolListQuery,
  McpToolManageVo,
  ReviewMcpToolPayload,
  TestMcpConfigPayload,
  UpdateMcpServerPayload,
} from '../types/api'

/**
 * MCP 服务分页（契约 §2.1，perms ai:mcp:list）：id 倒序、软删不可见；
 * name 仅非空时携带（包含匹配，≤30 字符）；错误分支（403/1001）拦截器统一 toast
 */
export function pageMcpServers(query: McpServerPageQuery): Promise<AiPageResult<McpServerSummaryVo>> {
  return request<AiPageResult<McpServerSummaryVo>>({
    url: '/ai/mcp/server/list',
    method: 'get',
    params: { page: query.page, size: query.size, name: query.name || undefined },
  })
}

/**
 * 创建 MCP 服务（契约 §2.2，perms ai:mcp:add）：入参即契约 §1 JSON 导入格式对象，
 * 返回新服务 id（JSON number）；5023 名称已存在 / 5022 stdio / 1001 参数
 * （name 格式、credential_headers 键名护栏）由拦截器统一 toast
 */
export function createMcpServer(payload: CreateMcpServerPayload): Promise<number> {
  return request<number>({ url: '/ai/mcp/server', method: 'post', data: payload })
}

/**
 * MCP 服务详情（契约 §2.3，perms ai:mcp:list）：编辑回显数据源——列表行不含
 * headers/credential_headers/timeout_ms，须走本端点取全量；5021 拦截器统一 toast
 */
export function getMcpServer(id: number): Promise<McpServerDetailVo> {
  return request<McpServerDetailVo>({ url: `/ai/mcp/server/${id}`, method: 'get' })
}

/**
 * 全量编辑 MCP 服务（契约 §2.4，perms ai:mcp:update）：入参与创建同构 + status 必填，
 * 全量覆盖语义——前端恒提交完整表单（状态开关场景：先拉详情再合并提交）；
 * 内置行 name 不可改（5022）；5021/5023/1001 拦截器统一 toast
 */
export function updateMcpServer(id: number, payload: UpdateMcpServerPayload): Promise<null> {
  return request<null>({ url: `/ai/mcp/server/${id}`, method: 'put', data: payload })
}

/**
 * 删除 MCP 服务（契约 §2.5，perms ai:mcp:delete）：软删，名下工具行保留
 * （对 agent 运行不可见）；内置行不可删（5022）——前端对 is_builtin 行禁用删除按钮
 */
export function deleteMcpServer(id: number): Promise<null> {
  return request<null>({ url: `/ai/mcp/server/${id}`, method: 'delete' })
}

/**
 * 保存前连通测试（契约 §2.6，perms ai:mcp:sync）：无 DB 写，按提交的配置直连远端
 * 握手 + tools/list；失败走 R 错误分支（5025 连接失败 / 5026 凭据未配置 / 1001）。
 * 远端握手+清单拉取耗时可能超默认 15s，timeout 放大至 60s（计划 F1 口径）
 */
export function testMcpConfig(payload: TestMcpConfigPayload): Promise<McpTestResultVo> {
  return request<McpTestResultVo>({ url: '/ai/mcp/server/test-config', method: 'post', data: payload, timeout: 60000 })
}

/**
 * 工具同步（契约 §2.7，perms ai:mcp:sync）：按 server 行拉远端 tools/list 事务内
 * upsert 草稿/失效状态机，返回同步报告；5021/5024/5025/5026 拦截器统一 toast。
 * 远端拉取耗时可能超默认 15s，timeout 放大至 60s（同 testMcpConfig 口径）
 */
export function syncMcpServer(id: number): Promise<McpSyncReportVo> {
  return request<McpSyncReportVo>({ url: `/ai/mcp/server/${id}/sync`, method: 'post', timeout: 60000 })
}

/**
 * 工具清单（契约 §2.8，perms ai:mcp:list）：管理面含草稿/失效，不分页（每 server
 * 工具量级十位数内）；server_id/status 均可选过滤——工具审核抽屉按 server_id 过展
 */
export function listMcpTools(query: McpToolListQuery = {}): Promise<McpToolManageVo[]> {
  return request<McpToolManageVo[]>({
    url: '/ai/mcp/tool/list',
    method: 'get',
    params: { server_id: query.server_id, status: query.status },
  })
}

/**
 * 工具审核（契约 §2.9，perms ai:mcp:update）：配置 required_perms（可空数组=登录即可）
 * + 可选显示名 → 校验 schema 可构建（失败 5028 停留草稿）→ 置已发布；
 * 已发布工具重复审核=改 perms/显示名；5027 工具不存在
 */
export function reviewMcpTool(id: number, payload: ReviewMcpToolPayload): Promise<null> {
  return request<null>({ url: `/ai/mcp/tool/${id}/review`, method: 'put', data: payload })
}
