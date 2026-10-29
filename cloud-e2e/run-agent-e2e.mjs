/**
 * 平台 Agent 管理 e2e（契约 2026-10-29-agent-platform-api §2 管理面 + §3 运行 + §4 会话 + §5 菜单种子）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202 已启动（登录链走真实网关）；前端 dev 已启动。
 * **cloud-base-ai 8010 与菜单种子 186-190 入库均不需要**——agent 域 13 端点全在浏览器网络层
 * route mock（内存态，沿 run-ai-chat-e2e.mjs 分层先例）；菜单 186/190 与 ai:agent:* 权限经
 * user-nav / auth/me 响应增广注入（route.fetch 真实透传后补种，幂等——种子入库后注入自动跳过，
 * 集成期真跑同一脚本不红）。
 * 运行：cd cloud-e2e && npm run e2e:agent（不进 run-e2e.mjs 聚合）；E2E_HEADLESS=1 无头；
 * dev 端口非默认时 E2E_BASE_URL 覆盖（如 http://localhost:5174）。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：智能体/会话全在 route mock 内存态（零后端写，脚本结束即清）；admin 只登录不改。
 *
 * 场景：
 * - AG1 管理页 CRUD：菜单可达 → 空态 → 新增（工具多选）→ 创建 body 形态 → 列表行 → 名称搜索 →
 *   编辑改名（PUT 全量含 status）→ 状态开关（详情合并提交）→ 运行历史空态 → 试跑（弹窗内嵌
 *   对话面板流式）→ 删除（确认框文案含名称）
 * - AG2 对话页：选智能体自动激活 → 新建会话 → 流式问答（3 content 帧 + [DONE] 气泡拼接）→
 *   乐观标题（问句前 16 字）→ 刷新回显（messages 端点）→ 删除会话回空态
 * - AG3 权限收敛：仅 ai:agent:list（无 run/add/update/delete）→ 侧边「智能体对话」不可见、
 *   操作按钮不可见、状态列降级 tag、运行接口 403（R body code，HTTP 恒 200）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, breadcrumbTexts, login, waitToast, waitDialogGone, waitTableIdle } = h

const MANAGE_PATH = '/agent/manage'
const CHAT_PATH = '/agent/chat'

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

// ---------- mock 层共享态 ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const ERR = (code, msg) => JSON.stringify({ code, msg, data: null })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

/** 五枚权限（契约 §1）；grantMode 控制增广注入与 mock 端点执法：
 *  full = 五枚全注（AG1/AG2）；listOnly = 仅 list（AG3 权限收敛） */
const AGENT_PERMS = ['ai:agent:list', 'ai:agent:add', 'ai:agent:update', 'ai:agent:delete', 'ai:agent:run']
let grantMode = 'full'
const hasPerm = (p) => grantMode === 'full' || p === 'ai:agent:list'

/** 工具注册表（契约 §2.6 只读；工具显示名/标识供管理页多选与列表 tag 消费） */
const mockTools = [
  { id: 1, tool_key: 'weather_query', name: '天气查询', description: '查询指定城市天气', required_perms: [] },
  { id: 2, tool_key: 'db_query', name: '数据库查询', description: '受控 SQL 查询', required_perms: [] },
]

/** 智能体内存表（§2 全端点数据源）：行含 detail 字段，list 出参现场投影 */
let mockAgents = []
const mockRunsByAgent = new Map() // agentId -> 运行日志数组（本轮恒空，历史空态用）
const mockSessions = []
const mockMsgs = new Map()
let nextAgentId = 5001
let nextSessionId = 6001

/** 请求 body 记录（断言锚） */
let lastCreateBody = null
let lastUpdateBody = null
let lastUpdateId = 0
let lastDetailId = 0
let lastStreamBody = null

/** SSE 假流体（契约 §3.2 帧协议沿 RAG 母契约）：3 content 帧 + [DONE] */
const frame = (payload) => `data: ${JSON.stringify(payload)}\n\n`
const STREAM_OK = frame({ content: '第一段' }) + frame({ content: '第二段' }) + frame({ content: '第三段' }) + 'data: [DONE]\n\n'

/** 工具 id → 显示名（列表 tool_names 投影） */
const toolNames = (ids) => mockTools.filter((t) => ids.includes(t.id)).map((t) => t.name)

/** 智能体列表行投影（契约 §2.1 summary 形态） */
const summaryOf = (a) => ({
  id: a.id,
  name: a.name,
  description: a.description,
  temperature: a.temperature,
  status: a.status,
  tool_names: toolNames(a.tool_ids),
  create_time: a.create_time,
  update_time: a.update_time,
})

// ---------- 菜单/权限增广（真实响应透传 + 补种注入；种子入库后幂等跳过） ----------
await page.route('**/api/system/menu/user-nav', async (route) => {
  const resp = await route.fetch()
  const body = await resp.json()
  const tree = Array.isArray(body.data) ? body.data : []
  const ai = tree.find((n) => String(n.id) === '18')
  if (ai && Array.isArray(ai.children)) {
    // 契约 §5 种子形态（186/190 挂 18 下，C 页面节点带 icon 与 sort）；listOnly 不注 190（菜单 perms 过滤的后端语义模拟）
    const defs = [
      { id: '186', parentId: '18', name: '智能体管理', type: 'C', path: '/agent/manage', icon: 'Monitor', sort: 3, children: [] },
      { id: '190', parentId: '18', name: '智能体对话', type: 'C', path: '/agent/chat', icon: 'ChatLineSquare', sort: 4, children: [] },
    ]
    // 后端种子可能已入库（真实 user-nav 已含 186/190）——对 agent 域节点做权威收敛而非只增：
    // listOnly 先摘除真实响应里的 190 再按 defs 注入，侧边形态只由 grantMode 决定，不受实时后端状态影响
    if (grantMode !== 'full') {
      ai.children = ai.children.filter((c) => String(c.id) !== '190')
    }
    for (const d of defs) {
      if (d.id === '190' && grantMode !== 'full') continue
      if (!ai.children.some((c) => String(c.id) === d.id)) ai.children.push(d)
    }
    ai.children.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
  }
  body.data = tree
  // no-store：防浏览器缓存命中绕过 handler（透传响应无缓存头时可能被启发式缓存）
  await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(body) })
})

await page.route('**/api/sso/auth/me', async (route) => {
  const resp = await route.fetch()
  const body = await resp.json()
  if (body.data && Array.isArray(body.data.permissions)) {
    // 权威收敛：真实 permissions 可能已含全部 ai:agent:*（后端种子入库后）——先整体摘除 agent 域
    // 五枚再按 grantMode 注入，listOnly 场景不残留 run/update 等权限
    const perms = body.data.permissions.filter((p) => !AGENT_PERMS.includes(p))
    for (const p of grantMode === 'full' ? AGENT_PERMS : ['ai:agent:list']) perms.push(p)
    body.data.permissions = perms
  }
  // no-store：防浏览器缓存命中绕过 handler（透传响应无缓存头时可能被启发式缓存）
  await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(body) })
})

// ---------- agent 域 13 端点 route mock（契约 §2/§3/§4） ----------
// 创建端点 POST /api/ai/agent 是裸 URL：Playwright glob 的 ** 要求其后至少一个字符，
// '**/api/ai/agent/**' 匹配不到它——漏注册会经 dev proxy 打到真实后端（联调期后端正在
// 迭代返回非 200 业务码），必须两个 pattern 成对注册同一 handler
const agentRouteHandler = async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  const p = u.pathname.replace(/^\/api/, '')
  const method = req.method()

  // §2.1 分页列表：name 包含匹配、id 倒序、软删不可见（内存表无软删行）
  if (p === '/ai/agent/list' && method === 'GET') {
    const pageNum = Math.max(1, Number(u.searchParams.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(u.searchParams.get('size') ?? 10)))
    const name = (u.searchParams.get('name') ?? '').trim()
    const filtered = mockAgents.filter((a) => !name || a.name.includes(name))
    const sorted = [...filtered].sort((a, b) => b.id - a.id)
    return route.fulfill(json(R({ total: sorted.length, rows: sorted.slice((pageNum - 1) * size, pageNum * size).map(summaryOf) })))
  }
  // §2.6 工具注册表（只读不分页）
  if (p === '/ai/agent/tool/list' && method === 'GET') {
    return route.fulfill(json(R(mockTools)))
  }
  // §2.2 创建：入参 name/system_prompt/description?/temperature?/tool_ids?，返回新 id
  if (p === '/ai/agent' && method === 'POST') {
    if (!hasPerm('ai:agent:add')) return route.fulfill(json(ERR(403, '无操作权限')))
    lastCreateBody = req.postDataJSON()
    const a = {
      id: nextAgentId++,
      name: lastCreateBody.name,
      description: lastCreateBody.description ?? null,
      temperature: lastCreateBody.temperature ?? 0.7,
      status: 0,
      tool_ids: lastCreateBody.tool_ids ?? [],
      system_prompt: lastCreateBody.system_prompt,
      config_version: 1,
      create_time: '2026-10-29 11:00:00',
      update_time: '2026-10-29 11:00:00',
    }
    mockAgents.push(a)
    return route.fulfill(json(R(a.id)))
  }
  // §2.7 运行历史分页（本轮 mock 恒空集——历史空态场景）
  const runsMatch = p.match(/^\/ai\/agent\/(\d+)\/runs$/)
  if (runsMatch && method === 'GET') {
    if (!hasPerm('ai:agent:list')) return route.fulfill(json(ERR(403, '无操作权限')))
    return route.fulfill(json(R({ total: 0, rows: [] })))
  }
  // §4 会话四端点（perms ai:agent:run）
  const sessCreateMatch = p.match(/^\/ai\/agent\/(\d+)\/sessions$/)
  if (sessCreateMatch && method === 'POST') {
    if (!hasPerm('ai:agent:run')) return route.fulfill(json(ERR(403, '无操作权限')))
    const s = { id: nextSessionId++, agent_id: Number(sessCreateMatch[1]), title: '新对话', create_time: '2026-10-29 11:10:00' }
    mockSessions.push(s)
    mockMsgs.set(s.id, [])
    return route.fulfill(json(R(s)))
  }
  const sessListMatch = p.match(/^\/ai\/agent\/(\d+)\/sessions\/list$/)
  if (sessListMatch && method === 'GET') {
    if (!hasPerm('ai:agent:run')) return route.fulfill(json(ERR(403, '无操作权限')))
    const mine = mockSessions.filter((s) => s.agent_id === Number(sessListMatch[1])).sort((a, b) => b.id - a.id)
    return route.fulfill(json(R({ total: mine.length, rows: mine })))
  }
  const sessDelMatch = p.match(/^\/ai\/agent\/sessions\/(\d+)$/)
  if (sessDelMatch && method === 'DELETE') {
    if (!hasPerm('ai:agent:run')) return route.fulfill(json(ERR(403, '无操作权限')))
    const id = Number(sessDelMatch[1])
    const idx = mockSessions.findIndex((s) => s.id === id)
    if (idx < 0) return route.fulfill(json(ERR(5019, '会话不存在')))
    mockSessions.splice(idx, 1)
    mockMsgs.delete(id)
    return route.fulfill(json(R(null)))
  }
  const sessMsgMatch = p.match(/^\/ai\/agent\/sessions\/(\d+)\/messages$/)
  if (sessMsgMatch && method === 'GET') {
    if (!hasPerm('ai:agent:run')) return route.fulfill(json(ERR(403, '无操作权限')))
    const id = Number(sessMsgMatch[1])
    if (!mockSessions.some((s) => s.id === id)) return route.fulfill(json(ERR(5019, '会话不存在')))
    return route.fulfill(json(R({ messages: mockMsgs.get(id) ?? [] })))
  }
  // §3.2 流式运行：问先落档 + 首问改题（服务端行为模拟，AG2 刷新回显数据源）→ 假 SSE
  const streamMatch = p.match(/^\/ai\/agent\/(\d+)\/run\/stream$/)
  if (streamMatch && method === 'POST') {
    if (!hasPerm('ai:agent:run')) return route.fulfill(json(ERR(403, '无操作权限')))
    lastStreamBody = req.postDataJSON()
    const sid = lastStreamBody.session_id
    const s = mockSessions.find((x) => x.id === sid)
    if (!s) return route.fulfill(json(ERR(5019, '会话不存在')))
    mockMsgs.get(sid)?.push({ role: 'user', content: lastStreamBody.question })
    if (s.title === '新对话') s.title = String(lastStreamBody.question).slice(0, 16)
    await route.fulfill({ status: 200, contentType: 'text/event-stream; charset=utf-8', body: STREAM_OK })
    mockMsgs.get(sid)?.push({ role: 'assistant', content: '第一段第二段第三段' })
    return
  }
  // §2.3 详情（编辑回显与状态开关合并提交的数据源）
  const detailMatch = p.match(/^\/ai\/agent\/(\d+)$/)
  if (detailMatch && method === 'GET') {
    if (!hasPerm('ai:agent:list')) return route.fulfill(json(ERR(403, '无操作权限')))
    const a = mockAgents.find((x) => x.id === Number(detailMatch[1]))
    if (!a) return route.fulfill(json(ERR(5015, '智能体不存在')))
    lastDetailId = a.id
    return route.fulfill(json(R({ ...summaryOf(a), system_prompt: a.system_prompt, tool_ids: a.tool_ids, config_version: a.config_version })))
  }
  // §2.4 全量编辑（status 必填、config_version 自增）
  if (detailMatch && method === 'PUT') {
    if (!hasPerm('ai:agent:update')) return route.fulfill(json(ERR(403, '无操作权限')))
    const a = mockAgents.find((x) => x.id === Number(detailMatch[1]))
    if (!a) return route.fulfill(json(ERR(5015, '智能体不存在')))
    lastUpdateBody = req.postDataJSON()
    lastUpdateId = a.id
    a.name = lastUpdateBody.name
    a.description = lastUpdateBody.description ?? null
    a.temperature = lastUpdateBody.temperature ?? a.temperature
    a.tool_ids = lastUpdateBody.tool_ids ?? a.tool_ids
    a.system_prompt = lastUpdateBody.system_prompt
    a.status = lastUpdateBody.status
    a.config_version += 1
    return route.fulfill(json(R(null)))
  }
  // §2.5 软删（会话与审计保留——mock 同步移出行即可见性归零）
  if (detailMatch && method === 'DELETE') {
    if (!hasPerm('ai:agent:delete')) return route.fulfill(json(ERR(403, '无操作权限')))
    const idx = mockAgents.findIndex((x) => x.id === Number(detailMatch[1]))
    if (idx < 0) return route.fulfill(json(ERR(5015, '智能体不存在')))
    mockAgents.splice(idx, 1)
    return route.fulfill(json(R(null)))
  }
  return route.fulfill(json(ERR(404, '未匹配的 mock 端点: ' + method + ' ' + p)))
}
await page.route('**/api/ai/agent/**', agentRouteHandler)
await page.route('**/api/ai/agent', agentRouteHandler)

// ---------- 通用等待/断言小件（沿 run-ai-chat 形态） ----------
/** 等流式结束回稳态：输入框恢复可用 + 发送按钮回归 */
async function waitStreamSettled(timeout = 10000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    const ta = page.locator('.input-bar textarea')
    const sendBtn = page.getByRole('button', { name: '发送' })
    if ((await ta.count()) > 0 && !(await ta.isDisabled()) && (await sendBtn.count()) > 0 && (await sendBtn.isVisible())) return
    await sleep(150)
  }
  throw new Error('等待流式结束（输入框恢复可用）超时')
}

/** 发送问题（textarea + Enter） */
async function sendQuestion(q) {
  const ta = page.locator('.input-bar textarea')
  await ta.fill(q)
  await ta.press('Enter')
}

/** 侧边全部叶子/子项菜单文本（el-menu-item，不含目录标题） */
async function menuLabels() {
  const items = page.locator('.el-menu .el-menu-item')
  const n = await items.count()
  const out = []
  for (let i = 0; i < n; i++) out.push((await items.nth(i).innerText()).trim())
  return out
}

/** 前置守卫：场景须已在目标页（杜绝前序失败后的无效通过） */
function ensurePath(path) {
  assertEq(new URL(page.url()).pathname, path, `前置守卫：本场景须已在 ${path}，实际 ${page.url()}`)
}

const ts = Date.now()
const AGENT_A = `e2e_${ts}_智能体A`
const AGENT_A_RENAMED = `e2e_${ts}_智能体A改`

try {
  // ================= AG1 管理页 CRUD 全链路 =================
  await step('AG1', '管理页 CRUD：菜单可达 → 空态 → 新增(工具多选) → 搜索 → 编辑改名(PUT 全量) → 状态开关(详情合并) → 历史空态 → 试跑流式 → 删除', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（真实 sso/system 链路）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })

    // a. 侧边可达性：注入种子入序（对话,知识库,智能体管理,智能体对话——契约 §5 sort 1/2/3/4）
    const labels = await menuLabels()
    log(`  侧边菜单项: ${JSON.stringify(labels)}`)
    assertEq(
      labels.join(','),
      '用户管理,角色管理,菜单管理,字典管理,部门管理,数据权限,死信治理,请假申请,我的审批,待办任务,流程定义,对话,知识库,智能体管理,智能体对话,工作台',
      `侧边菜单顺序应为 16 项形态（智能体管理/智能体对话入 AI 助手序），实际 ${JSON.stringify(labels)}`,
    )
    await page.locator('.el-menu .el-menu-item', { hasText: '智能体管理' }).click()
    await page.waitForURL(`**${MANAGE_PATH}`, { timeout: 8000 })
    assertEq((await breadcrumbTexts(page)).join('/'), '首页/智能体管理', '面包屑应为 首页/智能体管理')
    assertEq((await page.locator('.el-menu-item.is-active').innerText()).trim(), '智能体管理', '「智能体管理」应高亮')
    await waitTableIdle(page)
    // 空态文案锚（mock 内存表初始为空）
    assertEq((await page.locator('.el-table__empty-text').innerText()).trim(), '暂无智能体', '初始应为空态「暂无智能体」')
    await shot(page, 'ag1-manage-empty.png')

    // b. 新增：工具多选勾一项 + 必填项 → 创建 body 形态断言（§2.2）
    await page.getByRole('button', { name: '新增智能体' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新建智能体' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    // 工具选项数据源 = 工具注册表端点（§2.6）
    assert(h.state.apiCalls.some((c) => c.url.includes('/api/ai/agent/tool/list')), '打开弹窗应拉取工具注册表 GET /tool/list')
    await dlg.locator('input[placeholder="请输入智能体名称"]').fill(AGENT_A)
    await dlg.locator('textarea[placeholder="定义智能体的角色、能力与输出要求"]').fill('你是 e2e 验证助手')
    // el-select multiple：点开下拉勾「天气查询」→ Escape 收起
    await dlg.locator('.el-select').click()
    const opt = page.locator('.el-select-dropdown__item', { hasText: '天气查询' }).first()
    await opt.waitFor({ state: 'visible', timeout: 5000 })
    await opt.click()
    await page.keyboard.press('Escape')
    await dlg.locator('.el-select .el-tag', { hasText: '天气查询' }).waitFor({ state: 'visible', timeout: 5000 })
    await shot(page, 'ag1-create-dialog.png')
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '新建成功')
    await waitDialogGone(page, '新建智能体')
    await waitTableIdle(page)
    assert(lastCreateBody && typeof lastCreateBody === 'object', 'route 层应记录到创建请求 body')
    assertEq(
      Object.keys(lastCreateBody).sort().join(','),
      'name,system_prompt,temperature,tool_ids',
      `创建 body 应恰含四键（description 留空不传），实际 [${Object.keys(lastCreateBody)}]`,
    )
    assertEq(lastCreateBody.name, AGENT_A, `创建 body.name 应为 "${AGENT_A}"`)
    assertEq(lastCreateBody.temperature, 0.7, '未改温度时创建 body.temperature 应为默认 0.7')
    assertEq(JSON.stringify(lastCreateBody.tool_ids), '[1]', '创建 body.tool_ids 应为勾选工具 id 数组')
    // c. 列表行：名称/工具 tag/温度/状态开关/创建时间（§2.1 投影）
    const rowA = page.locator('.el-table__row', { hasText: AGENT_A }).first()
    await rowA.waitFor({ state: 'visible', timeout: 5000 })
    const cells = await h.rowCells(rowA)
    assertEq(cells[0], AGENT_A, '名称列应为新建名称')
    assert(cells[1].includes('天气查询'), `工具列应含「天气查询」tag（tool_names 投影），实际 "${cells[1]}"`)
    assertEq(cells[2], '0.7', '温度列应为 0.7')
    assert((await rowA.locator('.el-switch').count()) === 1, '状态列应为开关（有 update 权限）')
    assert(cells[4].length > 0, '创建时间列应非空')
    await shot(page, 'ag1-list-created.png')

    // d. 名称搜索（§2.1 name 包含匹配）：命中 1 行 / 无命中回空态 / 重置恢复
    await page.locator('input[placeholder="名称（模糊）"]').fill(`e2e_${ts}`)
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    assert(h.state.apiCalls.some((c) => c.method === 'GET' && c.url.includes('/api/ai/agent/list') && c.url.includes('name=')), '搜索应携带 name 查询参数')
    assertEq(await page.locator('.el-table__row').count(), 1, '名称过滤后应恰 1 行')
    await page.getByRole('button', { name: '重置' }).click()
    await waitTableIdle(page)
    assertEq(await page.locator('.el-table__row').count(), 1, '重置后应恢复 1 行')

    // e. 编辑改名：回显（详情端点）→ 改名保存 → PUT 全量（含 status，§2.4）
    await rowA.getByRole('button', { name: '编辑' }).click()
    const editDlg = page.locator('.el-dialog', { hasText: '编辑智能体' }).last()
    await editDlg.waitFor({ state: 'visible', timeout: 5000 })
    const nameInput = editDlg.locator('input[placeholder="请输入智能体名称"]')
    // 回显异步（详情端点）：轮询至回显完成再改名，防空表单覆盖
    const t0 = Date.now()
    while ((await nameInput.inputValue()) !== AGENT_A && Date.now() - t0 < 5000) await sleep(150)
    assertEq(await nameInput.inputValue(), AGENT_A, '编辑弹窗应回显原名称（详情端点数据）')
    await nameInput.fill(AGENT_A_RENAMED)
    await editDlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑智能体')
    await waitTableIdle(page)
    assert(lastUpdateBody && typeof lastUpdateBody === 'object', 'route 层应记录到编辑请求 body')
    assertEq(lastUpdateBody.name, AGENT_A_RENAMED, '编辑 body.name 应为改名后名称')
    assertEq(lastUpdateBody.status, 0, '编辑 body 应带 status（PUT 全量覆盖语义，弹窗不改状态取行当前值）')
    assertEq(lastUpdateBody.system_prompt, '你是 e2e 验证助手', '编辑 body.system_prompt 应原样带回（全量）')
    await page.locator('.el-table__row', { hasText: AGENT_A_RENAMED }).first().waitFor({ state: 'visible', timeout: 5000 })

    // f. 状态开关：停用 = 先拉详情再 PUT（列表行无提示词/工具绑定，不能只发 status）
    const rowB = page.locator('.el-table__row', { hasText: AGENT_A_RENAMED }).first()
    const detailGetsBefore = h.state.apiCalls.filter((c) => c.method === 'GET' && /^\/api\/ai\/agent\/\d+$/.test(c.url.split('?')[0])).length
    await rowB.locator('.el-switch').click()
    await waitToast(page, '已停用')
    assertEq(lastUpdateBody.status, 1, '开关停用提交 body.status 应为 1')
    assertEq(
      h.state.apiCalls.filter((c) => c.method === 'GET' && /^\/api\/ai\/agent\/\d+$/.test(c.url.split('?')[0])).length,
      detailGetsBefore + 1,
      '开关变更前应先拉详情（GET /{id} 合并提交）',
    )
    assert(!((await rowB.locator('.el-switch').getAttribute('class')) || '').includes('is-checked'), '停用后开关应处于关闭态')
    await shot(page, 'ag1-status-off.png')

    // g. 运行历史抽屉：空态（§2.7 mock 恒空集）
    await rowB.getByRole('button', { name: '历史' }).click()
    const drawer = page.locator('.el-drawer', { hasText: '运行历史' }).last()
    await drawer.waitFor({ state: 'visible', timeout: 5000 })
    assert((await drawer.innerText()).includes(AGENT_A_RENAMED), '抽屉标题应含智能体名称')
    assertEq((await drawer.locator('.el-table__empty-text').innerText()).trim(), '暂无运行记录', '运行历史应为空态')
    await shot(page, 'ag1-run-drawer.png')
    await drawer.locator('.el-drawer__close-btn').click()
    await drawer.waitFor({ state: 'hidden', timeout: 5000 })

    // h. 试跑弹窗（内嵌对话面板）：首问自动建会话 + 3 content 帧流式拼接（§3.2 + §4）
    await rowB.getByRole('button', { name: '试跑' }).click()
    const tryDlg = page.locator('.el-dialog', { hasText: '试跑' }).last()
    await tryDlg.waitFor({ state: 'visible', timeout: 5000 })
    assert((await tryDlg.innerText()).includes(AGENT_A_RENAMED), '试跑弹窗标题应含智能体名称')
    const sessPostsBefore = h.state.apiCalls.filter((c) => c.method === 'POST' && /\/api\/ai\/agent\/\d+\/sessions$/.test(c.url)).length
    const q1 = `e2e_${ts}_试跑提问`
    await tryDlg.locator('.input-bar textarea').fill(q1)
    await tryDlg.getByRole('button', { name: '发送' }).click()
    await waitStreamSettled()
    assertEq(
      h.state.apiCalls.filter((c) => c.method === 'POST' && /\/api\/ai\/agent\/\d+\/sessions$/.test(c.url)).length,
      sessPostsBefore + 1,
      '试跑首问应自动创建会话（POST /{id}/sessions）',
    )
    assert(lastStreamBody && typeof lastStreamBody === 'object', 'route 层应记录到流式请求 body')
    assertEq(Object.keys(lastStreamBody).sort().join(','), 'question,session_id', `流式 body 应恰含两键，实际 [${Object.keys(lastStreamBody)}]`)
    assertEq((await tryDlg.locator('.msg-row.user .bubble').innerText()).trim(), q1, '试跑用户气泡应为问句原文')
    assertEq((await tryDlg.locator('.msg-row.assistant .bubble').last().innerText()).trim(), '第一段第二段第三段', '试跑回答气泡应为 3 帧拼接文本')
    await shot(page, 'ag1-try-dialog.png')
    await tryDlg.locator('.el-dialog__headerbtn').click()
    await waitDialogGone(page, '试跑')

    // i. 删除：确认框文案含名称 → 行消失回空态（§2.5）
    await rowB.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 5000 })
    assert((await box.innerText()).includes(AGENT_A_RENAMED), `删除确认框文案应含智能体名称，实际 "${await box.innerText()}"`)
    await box.getByRole('button', { name: '确定' }).click()
    await box.waitFor({ state: 'hidden', timeout: 5000 })
    await waitToast(page, '删除成功')
    await waitTableIdle(page)
    // 历史抽屉 append-to-body 关闭后不销毁，其空态文本仍在 DOM——按文案过滤只认管理表
    assertEq(
      (await page.locator('.el-table__empty-text', { hasText: '暂无智能体' }).innerText()).trim(),
      '暂无智能体',
      '删除后应回空态',
    )
  })

  // ================= AG2 对话页 =================
  await step('AG2', '对话页：选智能体自动激活 → 新建会话 → 流式问答(3帧+[DONE]) → 乐观标题 → 刷新回显 → 删除会话', async () => {
    // mock 层直种一个启用智能体（对话页数据源；UI 创建路径已由 AG1 覆盖）
    mockAgents.push({
      id: nextAgentId++,
      name: `e2e_${ts}_对话助手`,
      description: null,
      temperature: 0.7,
      status: 0,
      tool_ids: [],
      system_prompt: '纯对话智能体',
      config_version: 1,
      create_time: '2026-10-29 11:20:00',
      update_time: '2026-10-29 11:20:00',
    })

    // a. 菜单可达
    await page.locator('.el-menu .el-menu-item', { hasText: '智能体对话' }).click()
    await page.waitForURL(`**${CHAT_PATH}`, { timeout: 8000 })
    assertEq((await breadcrumbTexts(page)).join('/'), '首页/智能体对话', '面包屑应为 首页/智能体对话')
    // 选择器自动选中首个启用项 → 会话列表加载（§4 list）
    // waitForResponse 注册晚于页面内请求发出即漏拍超时——改全局响应采集轮询（无竞态）
    const sessListCalled = () => h.state.apiCalls.some((c) => c.method === 'GET' && /\/api\/ai\/agent\/\d+\/sessions\/list/.test(c.url))
    const t1 = Date.now()
    while (!sessListCalled() && Date.now() - t1 < 8000) await sleep(150)
    assert(sessListCalled(), '对话页选择器应自动选中并拉取会话列表 GET /{id}/sessions/list')
    await page.locator('.session-sidebar .empty').waitFor({ state: 'visible', timeout: 5000 })
    assertEq((await page.locator('.session-sidebar .empty').innerText()).trim(), '暂无对话', '初始会话列表应为空态「暂无对话」')

    // b. 新建会话：POST → 置顶激活 + 初始标题「新对话」（§4）
    const postsBefore = h.state.apiCalls.filter((c) => c.method === 'POST' && /\/api\/ai\/agent\/\d+\/sessions$/.test(c.url)).length
    await page.getByRole('button', { name: '新建对话' }).click()
    const item = page.locator('.session-item').first()
    await item.waitFor({ state: 'visible', timeout: 5000 })
    assert(((await item.getAttribute('class')) || '').includes('active'), '新会话应即时激活')
    assertEq((await item.locator('.name').innerText()).trim(), '新对话', '新会话初始标题应为「新对话」')
    assertEq(
      h.state.apiCalls.filter((c) => c.method === 'POST' && /\/api\/ai\/agent\/\d+\/sessions$/.test(c.url)).length,
      postsBefore + 1,
      '新建对话应发出恰 1 次 POST /{id}/sessions',
    )

    // c. 流式问答：3 content 帧 + [DONE] → 气泡拼接渲染；乐观标题 = 问句前 16 字
    const longQ = `e2e_${ts}_对话页提问标题十六字验证一二三四五六七八`
    await sendQuestion(longQ)
    await waitStreamSettled()
    assert(lastStreamBody && typeof lastStreamBody.session_id === 'number', '流式 body.session_id 应为 JSON number')
    assertEq((await page.locator('.msg-row.user .bubble').innerText()).trim(), longQ, '用户气泡应为问句原文')
    assertEq((await page.locator('.msg-row.assistant .bubble').last().innerText()).trim(), '第一段第二段第三段', '回答气泡应为 3 帧拼接文本')
    assertEq((await item.locator('.name').innerText()).trim(), longQ.slice(0, 16), `首问后侧栏标题应为前 16 字（乐观更新），实际 "${await item.locator('.name').innerText()}"`)
    await shot(page, 'ag2-chat-stream.png')

    // d. 刷新回显：list（服务端已改题）+ messages（问/答已落档）→ 气泡重建（§4）
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.locator('.session-item').first().waitFor({ state: 'visible', timeout: 8000 })
    const sid = lastStreamBody.session_id
    await page.locator('.msg-row.user .bubble').first().waitFor({ state: 'visible', timeout: 5000 })
    assertEq((await page.locator('.msg-row.user .bubble').first().innerText()).trim(), longQ, '刷新后用户气泡应回显问句')
    assertEq((await page.locator('.msg-row.assistant .bubble').first().innerText()).trim(), '第一段第二段第三段', '刷新后回答气泡应回显 mock 落档答案')
    assertEq((await page.locator('.session-item .name').first().innerText()).trim(), longQ.slice(0, 16), '刷新后标题应来自服务端列表（mock 按服务端口径改题）')
    // 回显证据经全局响应采集断言（waitForResponse 注册晚于请求发出会漏拍，计数无竞态）
    const msgsGets = h.state.apiCalls.filter((c) => c.method === 'GET' && c.url === `/api/ai/agent/sessions/${sid}/messages`).length
    assert(msgsGets >= 1, `刷新后应发生 GET /sessions/${sid}/messages 回显请求`)
    await shot(page, 'ag2-session-restore.png')

    // e. 删除会话：确认框（文案含标题）→ DELETE → 列表移除回空态（§4 双清）
    await page.locator('.session-item .del').first().click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 5000 })
    assert((await box.innerText()).includes(longQ.slice(0, 16)), `删除确认框文案应含会话标题，实际 "${await box.innerText()}"`)
    await box.getByRole('button', { name: '确定' }).click()
    await box.waitFor({ state: 'hidden', timeout: 5000 })
    await page.locator('.session-sidebar .empty').waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await page.locator('.session-item').count(), 0, '删除后会话列表应回空态')
  })

  // ================= AG3 权限收敛（仅 ai:agent:list） =================
  await step('AG3', '权限收敛：仅 list → 「智能体对话」菜单不可见 → 操作按钮不可见 → 状态列降级 tag → 运行接口 403', async () => {
    grantMode = 'listOnly'
    // 整页重载触发权限快照与导航树按新授权重建（me/user-nav 增广读 grantMode）
    await page.goto(`${BASE}${MANAGE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    ensurePath(MANAGE_PATH)
    await waitTableIdle(page)

    // a. 菜单收敛：186（list）保留、190（run）不可见
    const labels = await menuLabels()
    assert(labels.includes('智能体管理'), `有 list 权限时「智能体管理」应可见，实际 ${JSON.stringify(labels)}`)
    assert(!labels.includes('智能体对话'), `无 run 权限时「智能体对话」应不可见，实际 ${JSON.stringify(labels)}`)

    // b. 按钮收敛：add/update/delete/run 四枚按钮全隐（v-perms 移除语义）；历史（查询类）保留
    assertEq(await page.getByRole('button', { name: '新增智能体' }).count(), 0, '无 add 权限时「新增智能体」按钮应不可见')
    const row = page.locator('.el-table__row').first()
    await row.waitFor({ state: 'visible', timeout: 5000 })
    for (const btn of ['编辑', '试跑', '删除']) {
      assertEq(await row.getByRole('button', { name: btn }).count(), 0, `无对应权限时行内「${btn}」按钮应不可见`)
    }
    assertEq(await row.getByRole('button', { name: '历史' }).count(), 1, '「历史」为查询类动作应保留')
    // c. 状态列降级：开关（update 入口）→ 只读 tag
    assertEq(await row.locator('.el-switch').count(), 0, '无 update 权限时状态列应为只读形态（无开关）')
    assert((await row.locator('.el-tag', { hasText: '启用' }).count()) === 1, '状态列应显示「启用」tag')
    await shot(page, 'ag3-list-only.png')

    // d. 运行接口 403：mock 层执法（契约 §1 未发放者运行面 403 无特例；R 约定 HTTP 恒 200、code 在 body）
    const r403 = await page.evaluate(async () => {
      const resp = await fetch('/api/ai/agent/5001/run/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: 1, question: 'x' }),
      })
      return await resp.json()
    })
    assertEq(r403.code, 403, `无 run 权限调用运行接口应得 body code 403（HTTP 恒 200），实际 ${JSON.stringify(r403)}`)
    log(`  403 响应体: ${JSON.stringify(r403)}`)
  })

  // ================= 证据核验 =================
  await step('AG-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（R 约定 HTTP 恒 200，403 走 body code），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  h.summary({
    extras: [
      '\n测试数据: 智能体/会话全在 route mock 内存态（零后端写，脚本结束即清）；admin 种子账号只登录不改，种子菜单零触碰',
      'mock 面: /api/ai/agent/** 13 端点内存态 + user-nav/auth/me 真实透传增广（186/190 与 ai:agent:* 注入，种子入库后幂等跳过）；登录链走真实 gateway/sso/system，cloud-base-ai 8010 未依赖',
      '注记: mock 层只验前端行为（请求形态/渲染/权限收敛/403 消费）；真实落库/DeepAgent 执行/服务端权限执法由后端章与集成期真跑覆盖',
    ],
  })
  await browser.close()
}
