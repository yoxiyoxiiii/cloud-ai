/**
 * 平台 Agent 接口（契约 2026-10-29-agent-platform-api，cloud-base-ai，网关前缀 /ai）：
 * 管理面七端点（§2，perms ai:agent:list/add/update/delete）+ 运行非流式（§3.1）+
 * 会话四端点与消息回显（§4，perms ai:agent:run）。
 * 流式端点 §3.2 POST /ai/agent/{agent_id}/run/stream 不在本文件——SSE 增量读取
 * 走 utils/sse.ts 的 ssePost（帧协议逐字沿 RAG 对话母契约，sse.ts 为冻结红线零改动）。
 * AI 域出参口径（契约 §0/§2）：字段 snake_case、id/total 为 JSON number（非 Java 域
 * Long→String）、分页查询参数名 page/size
 */
import { request } from '../utils/request'
import type {
  AgentAnswerVo,
  AgentDetailVo,
  AgentPageQuery,
  AgentRunPayload,
  AgentRunsPageQuery,
  AgentSessionMessagesVo,
  AgentSessionPageQuery,
  AgentSessionVo,
  AgentSummaryVo,
  CreateAgentPayload,
  RunLogVo,
  ToolVo,
  UpdateAgentPayload,
} from '../types/api'
import type { AiPageResult } from '../types/api'

/**
 * 智能体分页（契约 §2.1，perms ai:agent:list）：id 倒序、软删不可见；
 * name 仅非空时携带（包含匹配，≤50 字符）；错误分支（403/1001）拦截器统一 toast
 */
export function pageAgents(query: AgentPageQuery): Promise<AiPageResult<AgentSummaryVo>> {
  return request<AiPageResult<AgentSummaryVo>>({
    url: '/ai/agent/list',
    method: 'get',
    params: { page: query.page, size: query.size, name: query.name || undefined },
  })
}

/**
 * 创建智能体（契约 §2.2，perms ai:agent:add）：返回新智能体 id（JSON number）；
 * 5016 名称已存在 / 5017 tool_ids 含无效项 / 1001 参数由拦截器统一 toast
 */
export function createAgent(payload: CreateAgentPayload): Promise<number> {
  return request<number>({ url: '/ai/agent', method: 'post', data: payload })
}

/**
 * 智能体详情（契约 §2.3，perms ai:agent:list）：编辑回显数据源——列表行不含
 * system_prompt/tool_ids，须走本端点取全量；5015 不存在由拦截器统一 toast
 */
export function getAgent(id: number): Promise<AgentDetailVo> {
  return request<AgentDetailVo>({ url: `/ai/agent/${id}`, method: 'get' })
}

/**
 * 全量编辑智能体（契约 §2.4，perms ai:agent:update）：入参与创建同构 + status 必填，
 * 不传字段=清空/回默认——前端恒提交完整表单（含状态开关场景：先拉详情再合并提交）；
 * 每次成功编辑服务端 config_version +1；5015/5016/5017/1001 拦截器统一 toast
 */
export function updateAgent(id: number, payload: UpdateAgentPayload): Promise<null> {
  return request<null>({ url: `/ai/agent/${id}`, method: 'put', data: payload })
}

/**
 * 删除智能体（契约 §2.5，perms ai:agent:delete）：软删，会话与审计保留
 * （消息回显的智能体名降级「已删除的智能体」）；5015 拦截器统一 toast
 */
export function deleteAgent(id: number): Promise<null> {
  return request<null>({ url: `/ai/agent/${id}`, method: 'delete' })
}

/**
 * 工具注册表（契约 §2.6，perms ai:agent:list）：只读不分页（量级个位数），
 * 编辑弹窗工具多选的数据源；管理面不提供注册表编辑
 */
export function listAgentTools(): Promise<ToolVo[]> {
  return request<ToolVo[]>({ url: '/ai/agent/tool/list', method: 'get' })
}

/**
 * 运行历史分页（契约 §2.7，perms ai:agent:list）：时间倒序；tool_calls 明细
 * 含工具入参与成败（无工具运行为空数组）
 */
export function pageAgentRuns(agentId: number, query: AgentRunsPageQuery): Promise<AiPageResult<RunLogVo>> {
  return request<AiPageResult<RunLogVo>>({
    url: `/ai/agent/${agentId}/runs`,
    method: 'get',
    params: query,
  })
}

/**
 * 运行智能体·非流式（契约 §3.1，perms ai:agent:run）：返回完整回答与实际模型名；
 * agent 多步执行耗时远超默认 15s，timeout 放大至 120s（沿 chatRag 先例）；
 * 流式形态见 utils/sse.ts（本工程对话页主用流式）
 */
export function runAgent(agentId: number, payload: AgentRunPayload): Promise<AgentAnswerVo> {
  return request<AgentAnswerVo>({
    url: `/ai/agent/${agentId}/run`,
    method: 'post',
    data: payload,
    timeout: 120000,
  })
}

/**
 * 创建会话（契约 §4，perms ai:agent:run）：无 body，归属取网关注入的 X-User-Id；
 * 标题默认「新对话」，首问后服务端取前 16 字符更新；智能体不存在或停用 5015
 */
export function createAgentSession(agentId: number): Promise<AgentSessionVo> {
  return request<AgentSessionVo>({ url: `/ai/agent/${agentId}/sessions`, method: 'post' })
}

/**
 * 本人会话分页（契约 §4，perms ai:agent:run）：当前用户在该智能体下的会话
 * （id 倒序、软删不可见）；不校验智能体状态——停用/已删智能体的历史会话仍可
 * 列表/回显/删除（路径 agent_id 不存在时返回空页）
 */
export function pageAgentSessions(agentId: number, query: AgentSessionPageQuery): Promise<AiPageResult<AgentSessionVo>> {
  return request<AiPageResult<AgentSessionVo>>({
    url: `/ai/agent/${agentId}/sessions/list`,
    method: 'get',
    params: query,
  })
}

/**
 * 删除会话（契约 §4，perms ai:agent:run）：Redis 消息键物理删 + MySQL 软删双清；
 * 5019 会话不存在 / 5020 非本人——本页会话列表以本人为界，删除失败统一 toast 即可
 */
export function deleteAgentSession(sessionId: number): Promise<null> {
  return request<null>({ url: `/ai/agent/sessions/${sessionId}`, method: 'delete' })
}

/**
 * 会话消息回显（契约 §4，perms ai:agent:run）：该会话全部保留消息（时间升序 ≤100 条），
 * 切换会话/刷新后重建消息区
 */
export function listAgentSessionMessages(sessionId: number): Promise<AgentSessionMessagesVo> {
  return request<AgentSessionMessagesVo>({ url: `/ai/agent/sessions/${sessionId}/messages`, method: 'get' })
}
