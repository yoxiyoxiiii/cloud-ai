/**
 * MCP Server 配置化管理 e2e（契约 2026-10-30-mcp-server-mgmt-api §0/§1/§2 管理面九端点 + §5 种子）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202 已启动（登录链走真实网关）；前端 dev 已启动。
 * **cloud-base-ai 8010 与菜单种子 191-195 入库均不需要**——mcp 域九端点全在浏览器网络层
 * route mock（内存态，沿 run-agent-e2e.mjs 分层先例）；菜单 191 与 ai:mcp:* 权限经
 * user-nav / auth/me 响应增广注入（route.fetch 真实透传后补种，幂等——种子入库后注入自动跳过，
 * 集成期真跑同一脚本不红；none 模式做权威摘除，侧边形态只由 grantMode 决定）。
 * 运行：cd cloud-e2e && npm run e2e:mcp（不进 run-e2e.mjs 聚合）；E2E_HEADLESS=1 无头；
 * dev 端口非默认时 E2E_BASE_URL 覆盖（如 http://localhost:5174）。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：MCP 服务/工具全在 route mock 内存态（零后端写，脚本结束即清）；admin 只登录不改。
 *
 * 场景：
 * - MC1 管理页全链路：菜单可达（17 项侧边序）→ 内置 SYSTEM 行形态（内置 tag/删除禁用/工具计数）
 *   → JSON 导入新增（坏 JSON 报错提示 → 整档解析 + 键位大写化 github→GITHUB 口径 → 保存前测试连接
 *   成功/失败两态）→ 创建 body 形态 → 名称搜索 → 同步（added=2 报告 + 计数刷新）→ 工具抽屉
 *   （草稿 tag/展开描述与 schema/5028 审核失败 toast/配 perms 发布成功状态 tag 变化）→
 *   编辑改描述（PUT 全量）→ 状态开关（详情合并提交）→ 删除（确认框文案含名称）
 * - MC2 权限收敛：无任何 ai:mcp:* → 「MCP服务管理」菜单不可见 → 查询接口 403（R body code，HTTP 恒 200）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, breadcrumbTexts, login, waitToast, waitDialogGone, waitTableIdle } = h

const MANAGE_PATH = '/mcp/manage'

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

/** 五枚权限（契约 §0 分段表）；grantMode 控制增广注入与 mock 端点执法：
 *  full = 五枚全注（MC1）；none = 零注（MC2 权限收敛） */
const MCP_PERMS = ['ai:mcp:list', 'ai:mcp:add', 'ai:mcp:update', 'ai:mcp:delete', 'ai:mcp:sync']
let grantMode = 'full'
const hasPerm = () => grantMode === 'full'

/** 契约 §1 校验口径（mock 与后端同值）：name 大写蛇形 / 凭据键名命名空间 */
const NAME_PATTERN = /^[A-Z][A-Z0-9_]{1,29}$/
const CRED_KEY_PATTERN = /^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$/

/** 工具状态（契约 §2.8）：0=草稿 1=已发布 2=已失效 */
const TOOL_DRAFT = 0
const TOOL_PUBLISHED = 1

/** 工具内存表（ai_tool 形态）：预置内置 SYSTEM 两枚已发布（契约 2026-10-17-mcp-tools-api §2 两工具） */
const mockTools = [
  {
    id: 101, server_id: 1, tool_key: 'system_query_users', name: 'system_query_users',
    description: '查询系统用户列表', input_schema: { type: 'object', properties: { page: { type: 'integer' } } },
    required_perms: ['system:user:list'], status: TOOL_PUBLISHED, server_name: 'SYSTEM',
    create_time: '2026-10-30 09:00:00', update_time: '2026-10-30 09:00:00',
  },
  {
    id: 102, server_id: 1, tool_key: 'system_query_dict_items', name: 'system_query_dict_items',
    description: '查询字典项', input_schema: { type: 'object', properties: { dictKey: { type: 'string' } } },
    required_perms: [], status: TOOL_PUBLISHED, server_name: 'SYSTEM',
    create_time: '2026-10-30 09:00:00', update_time: '2026-10-30 09:00:00',
  },
]

/** MCP 服务内存表（§2 全端点数据源）：预置内置行（契约 §5.1 种子形态）；软删行过滤后不可见 */
let mockServers = [
  {
    id: 1, name: 'SYSTEM', description: '平台内置 cloud-system MCP 服务', url: 'http://127.0.0.1:9202/mcp',
    transport: 'http', headers: {}, credential_headers: {}, timeout_ms: 30000,
    is_builtin: 1, status: 0, deleted: false, create_time: '2026-10-30 09:00:00', update_time: null,
  },
]
let nextServerId = 5001
let nextToolId = 5001

/** 请求 body 记录（断言锚） */
let lastCreateBody = null
let lastUpdateBody = null
let lastUpdateId = 0
let lastTestBody = null
let lastSyncId = 0
let lastReviewId = 0
let lastReviewBody = null

/** 同步为 E2E 服务拉取的远端工具面（test-config 的 tool_count/sample 同源） */
const REMOTE_TOOLS = [
  {
    tool_key: 'e2e_weather_query', description: 'e2e 天气查询工具',
    input_schema: { type: 'object', properties: { city: { type: 'string' } }, required: ['city'] },
    bad_schema: false,
  },
  {
    tool_key: 'e2e_bad_schema_tool', description: 'e2e schema 不可构建工具',
    input_schema: { type: 'banana' },
    bad_schema: true,
  },
]

/** 工具计数摘要（契约 §2.1 tool_counts）「草稿/已发布/失效」实时聚合 */
function toolCounts(serverId) {
  const ts = mockTools.filter((t) => t.server_id === serverId)
  return `${ts.filter((t) => t.status === 0).length}/${ts.filter((t) => t.status === 1).length}/${ts.filter((t) => t.status === 2).length}`
}

/** 服务列表行投影（契约 §2.1 summary 形态） */
const summaryOf = (s) => ({
  id: s.id,
  name: s.name,
  description: s.description,
  url: s.url,
  transport: s.transport,
  is_builtin: s.is_builtin,
  status: s.status,
  tool_counts: toolCounts(s.id),
  create_time: s.create_time,
  update_time: s.update_time,
})

const aliveServer = (id) => mockServers.find((s) => s.id === id && !s.deleted)

/** 凭据键名护栏（契约 §1：违例 1001）——mock 与后端同值执法 */
function credentialKeyViolation(headers) {
  for (const v of Object.values(headers || {})) {
    if (!CRED_KEY_PATTERN.test(v)) return v
  }
  return null
}

// ---------- 菜单/权限增广（真实响应透传 + 补种注入；种子入库后幂等跳过） ----------
await page.route('**/api/system/menu/user-nav', async (route) => {
  const resp = await route.fetch()
  const body = await resp.json()
  const tree = Array.isArray(body.data) ? body.data : []
  const ai = tree.find((n) => String(n.id) === '18')
  if (ai && Array.isArray(ai.children)) {
    // 契约 §5.2 种子形态（191 C 页面节点挂 18 下，icon Connection sort 5）；
    // none 模式对真实响应做权威摘除（种子已入库时侧边形态仍只由 grantMode 决定）
    if (grantMode !== 'full') {
      ai.children = ai.children.filter((c) => String(c.id) !== '191')
    } else if (!ai.children.some((c) => String(c.id) === '191')) {
      ai.children.push({ id: '191', parentId: '18', name: 'MCP服务管理', type: 'C', path: '/mcp/manage', icon: 'Connection', sort: 5, children: [] })
      ai.children.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
    }
  }
  body.data = tree
  // no-store：防浏览器缓存命中绕过 handler（透传响应无缓存头时可能被启发式缓存）
  await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(body) })
})

await page.route('**/api/sso/auth/me', async (route) => {
  const resp = await route.fetch()
  const body = await resp.json()
  if (body.data && Array.isArray(body.data.permissions)) {
    // 权威收敛：真实 permissions 可能已含全部 ai:mcp:*（后端种子入库后）——先整体摘除 mcp 域
    // 五枚再按 grantMode 注入，none 场景不残留任何一枚
    const perms = body.data.permissions.filter((p) => !MCP_PERMS.includes(p))
    if (grantMode === 'full') perms.push(...MCP_PERMS)
    body.data.permissions = perms
  }
  // no-store：防浏览器缓存命中绕过 handler
  await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(body) })
})

// ---------- mcp 域九端点 route mock（契约 §0/§2） ----------
// 裸 URL POST /api/ai/mcp/server 须与 /** 同注册（Playwright glob 的 ** 其后至少一个字符），漏注册会经 dev proxy 打到真实后端
const mcpRouteHandler = async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  const p = u.pathname.replace(/^\/api/, '')
  const method = req.method()

  // §2.1 分页列表：name 包含匹配、id 倒序、软删不可见
  if (p === '/ai/mcp/server/list' && method === 'GET') {
    if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
    const pageNum = Math.max(1, Number(u.searchParams.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(u.searchParams.get('size') ?? 10)))
    const name = (u.searchParams.get('name') ?? '').trim()
    const filtered = mockServers.filter((s) => !s.deleted && (!name || s.name.includes(name)))
    const sorted = [...filtered].sort((a, b) => b.id - a.id)
    return route.fulfill(json(R({ total: sorted.length, rows: sorted.slice((pageNum - 1) * size, pageNum * size).map(summaryOf) })))
  }
  // §2.2 创建：§1 对象校验（name 格式/凭据护栏 1001、stdio 5022、重名 5023），返回新 id
  if (p === '/ai/mcp/server' && method === 'POST') {
    if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
    const body = req.postDataJSON()
    if (!NAME_PATTERN.test(body.name || '')) return route.fulfill(json(ERR(1001, '参数校验失败：name 格式')))
    if (body.transport && !['http', 'sse'].includes(body.transport)) return route.fulfill(json(ERR(5022, 'MCP服务操作受限：不支持的传输类型')))
    const badKey = credentialKeyViolation(body.credential_headers)
    if (badKey) return route.fulfill(json(ERR(1001, `参数校验失败：凭据键名 ${badKey} 不在命名空间`)))
    if (mockServers.some((s) => !s.deleted && s.name === body.name)) return route.fulfill(json(ERR(5023, 'MCP服务名已存在')))
    lastCreateBody = body
    const transport = body.transport ?? (String(body.url || '').includes('/sse') ? 'sse' : 'http')
    const now = '2026-10-30 11:00:00'
    mockServers.push({
      id: nextServerId++, name: body.name, description: body.description ?? null, url: body.url,
      transport, headers: body.headers ?? {}, credential_headers: body.credential_headers ?? {},
      timeout_ms: body.timeout_ms ?? 30000, is_builtin: 0, status: 0, deleted: false,
      create_time: now, update_time: now,
    })
    return route.fulfill(json(R(nextServerId - 1)))
  }
  // §2.6 保存前连通测试（无 DB 写）：url 含 unreachable 模拟握手失败 5025，否则按远端工具面返回
  if (p === '/ai/mcp/server/test-config' && method === 'POST') {
    if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
    lastTestBody = req.postDataJSON()
    if (String(lastTestBody.url || '').includes('unreachable')) {
      return route.fulfill(json(ERR(5025, 'MCP服务连接失败：握手超时（模拟）')))
    }
    return route.fulfill(json(R({ ok: true, tool_count: REMOTE_TOOLS.length, sample_tools: REMOTE_TOOLS.map((t) => t.tool_key), error: null })))
  }
  // §2.3 详情 / §2.4 全量编辑 / §2.5 软删（内置行保护 5022）
  const serverMatch = p.match(/^\/ai\/mcp\/server\/(\d+)$/)
  if (serverMatch) {
    const id = Number(serverMatch[1])
    const row = aliveServer(id)
    if (method === 'GET') {
      if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
      if (!row) return route.fulfill(json(ERR(5021, 'MCP服务不存在')))
      return route.fulfill(json(R({ ...summaryOf(row), headers: row.headers, credential_headers: row.credential_headers, timeout_ms: row.timeout_ms })))
    }
    if (method === 'PUT') {
      if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
      if (!row) return route.fulfill(json(ERR(5021, 'MCP服务不存在')))
      const body = req.postDataJSON()
      if (row.is_builtin === 1 && body.name !== row.name) return route.fulfill(json(ERR(5022, 'MCP服务操作受限：内置服务不可改名')))
      if (mockServers.some((s) => !s.deleted && s.id !== id && s.name === body.name)) return route.fulfill(json(ERR(5023, 'MCP服务名已存在')))
      lastUpdateBody = body
      lastUpdateId = id
      row.name = body.name
      row.description = body.description ?? null
      row.url = body.url
      row.transport = body.transport ?? (String(body.url).includes('/sse') ? 'sse' : 'http')
      row.headers = body.headers ?? {}
      row.credential_headers = body.credential_headers ?? {}
      row.timeout_ms = body.timeout_ms ?? 30000
      row.status = body.status
      row.update_time = '2026-10-30 11:30:00'
      return route.fulfill(json(R(null)))
    }
    if (method === 'DELETE') {
      if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
      if (!row) return route.fulfill(json(ERR(5021, 'MCP服务不存在')))
      if (row.is_builtin === 1) return route.fulfill(json(ERR(5022, 'MCP服务操作受限：内置服务不可删除')))
      row.deleted = true
      return route.fulfill(json(R(null)))
    }
  }
  // §2.7 工具同步：校验 5021/5024 → upsert 草稿（bad_schema 工具同步入库但审核 5028）
  const syncMatch = p.match(/^\/ai\/mcp\/server\/(\d+)\/sync$/)
  if (syncMatch && method === 'POST') {
    if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
    const row = aliveServer(Number(syncMatch[1]))
    if (!row) return route.fulfill(json(ERR(5021, 'MCP服务不存在')))
    if (row.status !== 0) return route.fulfill(json(ERR(5024, 'MCP服务未启用')))
    lastSyncId = row.id
    let added = 0
    let updated = 0
    for (const rt of REMOTE_TOOLS) {
      const exist = mockTools.find((t) => t.server_id === row.id && t.tool_key === rt.tool_key)
      if (!exist) {
        mockTools.push({
          id: nextToolId++, server_id: row.id, tool_key: rt.tool_key, name: rt.tool_key,
          description: rt.description, input_schema: rt.input_schema, required_perms: [],
          status: TOOL_DRAFT, server_name: row.name, create_time: '2026-10-30 11:40:00', update_time: null,
        })
        added += 1
      } else {
        exist.description = rt.description
        exist.input_schema = rt.input_schema
        exist.status = TOOL_DRAFT
        exist.update_time = '2026-10-30 11:40:00'
        updated += 1
      }
    }
    return route.fulfill(json(R({ added, updated, missing: 0, conflicts: [] })))
  }
  // §2.8 工具清单（server_id/status 可选过滤，server_id、id 升序）
  if (p === '/ai/mcp/tool/list' && method === 'GET') {
    if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
    const serverId = u.searchParams.get('server_id')
    const status = u.searchParams.get('status')
    const rows = mockTools
      .filter((t) => (serverId ? t.server_id === Number(serverId) : true))
      .filter((t) => (status !== null && status !== '' ? t.status === Number(status) : true))
      .sort((a, b) => a.server_id - b.server_id || a.id - b.id)
      .map((t) => ({ ...t }))
    return route.fulfill(json(R(rows)))
  }
  // §2.9 工具审核：bad_schema 工具 5028（停留草稿），成功置已发布
  const reviewMatch = p.match(/^\/ai\/mcp\/tool\/(\d+)\/review$/)
  if (reviewMatch && method === 'PUT') {
    if (!hasPerm()) return route.fulfill(json(ERR(403, '无操作权限')))
    const tool = mockTools.find((t) => t.id === Number(reviewMatch[1]))
    if (!tool) return route.fulfill(json(ERR(5027, 'MCP工具不存在')))
    lastReviewId = tool.id
    lastReviewBody = req.postDataJSON()
    if (tool.tool_key.startsWith('e2e_bad_schema')) {
      return route.fulfill(json(ERR(5028, 'MCP工具schema不可构建：input_schema 无法构造入参模型')))
    }
    tool.required_perms = lastReviewBody.required_perms ?? []
    if (typeof lastReviewBody.name === 'string' && lastReviewBody.name) tool.name = lastReviewBody.name
    tool.status = TOOL_PUBLISHED
    tool.update_time = '2026-10-30 12:00:00'
    return route.fulfill(json(R(null)))
  }
  return route.fulfill(json(ERR(404, '未匹配的 mock 端点: ' + method + ' ' + p)))
}
await page.route('**/api/ai/mcp/server/**', mcpRouteHandler)
await page.route('**/api/ai/mcp/server', mcpRouteHandler)
await page.route('**/api/ai/mcp/tool/**', mcpRouteHandler)

// ---------- 通用等待/断言小件（沿 run-agent-e2e 形态） ----------
/** 侧边全部叶子/子项菜单文本（el-menu-item，不含目录标题） */
async function menuLabels() {
  const items = page.locator('.el-menu .el-menu-item')
  const n = await items.count()
  const out = []
  for (let i = 0; i < n; i++) out.push((await items.nth(i).innerText()).trim())
  return out
}

const SIDEBAR_17 = '用户管理,角色管理,菜单管理,字典管理,部门管理,数据权限,死信治理,请假申请,我的审批,待办任务,流程定义,对话,知识库,智能体管理,智能体对话,MCP服务管理,工作台'
const SIDEBAR_16 = '用户管理,角色管理,菜单管理,字典管理,部门管理,数据权限,死信治理,请假申请,我的审批,待办任务,流程定义,对话,知识库,智能体管理,智能体对话,工作台'

/** 等编辑弹窗回显完成（详情端点异步，防空表单覆盖后误断言） */
async function waitEcho(inputLocator, expected, timeout = 5000) {
  const t0 = Date.now()
  while ((await inputLocator.inputValue()) !== expected && Date.now() - t0 < timeout) await sleep(150)
  return (await inputLocator.inputValue()) === expected
}

const ts = Date.now()
const SRV = `E2E_${ts}` // 服务名（契约 §1 大写蛇形；e2e_ 前缀纪律的大写形态）

try {
  // ================= MC1 管理页全链路 =================
  await step('MC1', '管理页：菜单可达(17项) → 内置行形态 → JSON 导入新增(键位大写化+测试连接两态) → 搜索 → 同步 → 工具审核(5028+发布) → 编辑 → 状态开关 → 删除', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（真实 sso/system 链路）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })

    // a. 侧边可达性：注入种子入序（MCP服务管理 在智能体对话后、工作台前——契约 §6 17 项串）
    const labels = await menuLabels()
    log(`  侧边菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), SIDEBAR_17, `侧边菜单顺序应为 17 项形态（MCP服务管理入 AI 助手序），实际 ${JSON.stringify(labels)}`)
    await page.locator('.el-menu .el-menu-item', { hasText: 'MCP服务管理' }).click()
    await page.waitForURL(`**${MANAGE_PATH}`, { timeout: 8000 })
    assertEq((await breadcrumbTexts(page)).join('/'), '首页/MCP服务管理', '面包屑应为 首页/MCP服务管理')
    assertEq((await page.locator('.el-menu-item.is-active').innerText()).trim(), 'MCP服务管理', '「MCP服务管理」应高亮')
    await waitTableIdle(page)

    // b. 内置 SYSTEM 行：内置 tag / 工具计数 0/2/0 / 删除按钮禁用（is_builtin 前端禁用）
    const sysRow = page.locator('.el-table__row', { hasText: 'SYSTEM' }).first()
    await sysRow.waitFor({ state: 'visible', timeout: 5000 })
    const sysCells = await h.rowCells(sysRow)
    assert(sysCells[0].includes('SYSTEM') && sysCells[0].includes('内置'), `内置行名称列应含 SYSTEM 与「内置」tag，实际 "${sysCells[0]}"`)
    assertEq(sysCells[4], '0/2/0', `内置行工具计数应为 0/2/0（两枚已发布平台工具），实际 "${sysCells[4]}"`)
    assert(await sysRow.getByRole('button', { name: '删除' }).isDisabled(), '内置行「删除」按钮应禁用（后端 5022 拦，前端先行禁用）')
    await shot(page, 'mcp1-list.png')

    // c. 新增：JSON 导入（坏 JSON 报错 → 整档解析 + 键位大写化 e2e_xxx→E2E_XXX → 测试连接两态）
    await page.getByRole('button', { name: '新增服务' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增 MCP 服务' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    const jsonArea = dlg.locator('textarea[placeholder^="粘贴单条"]')
    // c1. 坏 JSON：解析错误给表单区提示（不进 console，不落表单）
    await jsonArea.fill('{bad json')
    await dlg.getByRole('button', { name: '解析导入' }).click()
    const badAlert = dlg.locator('.el-alert--error', { hasText: 'JSON 解析失败' })
    await badAlert.waitFor({ state: 'visible', timeout: 5000 })
    // c2. canonical 整档：键位小写 e2e_xxx → 自动大写化填入 name（契约 §1 转换口径）
    const doc = {
      mcpServers: {
        [`e2e_${ts}`]: {
          url: 'https://mcp.e2e.example.com/mcp',
          transport: 'http',
          headers: { 'X-Org-Id': 'cloudai' },
          credential_headers: { Authorization: `CLOUD_BASE_AI_MCP__SERVERS__${SRV}__TOKEN` },
          timeout_ms: 15000,
        },
      },
    }
    await jsonArea.fill(JSON.stringify(doc))
    await dlg.getByRole('button', { name: '解析导入' }).click()
    await dlg.locator('.el-alert--success', { hasText: '大写化为' }).waitFor({ state: 'visible', timeout: 5000 })
    const nameInput = dlg.locator('input[placeholder^="大写字母开头"]')
    assertEq(await nameInput.inputValue(), SRV, `导入应将键名大写化填入 name（e2e_${ts}→${SRV}），实际 "${await nameInput.inputValue()}"`)
    assertEq(await dlg.locator('input[placeholder^="https://mcp.example.com"]').inputValue(), 'https://mcp.e2e.example.com/mcp', '导入应填充 url')
    await shot(page, 'mcp1-create-json-import.png')

    // c3. 保存前测试连接（§2.6）：成功态（tool_count + sample_tools tags）
    await dlg.getByRole('button', { name: '测试连接' }).click()
    await dlg.locator('.el-alert--success', { hasText: '连接成功：发现 2 个工具' }).waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('.el-alert--success .el-tag', { hasText: 'e2e_weather_query' }).waitFor({ state: 'visible', timeout: 5000 })
    assert(lastTestBody && typeof lastTestBody === 'object', 'route 层应记录到测试请求 body')
    assertEq(lastTestBody.name, SRV, '测试 body.name 应为导入的大写化名称')
    await shot(page, 'mcp1-test-result.png')
    // c4. 测试连接失败态：不可达地址 → 5025 内联错误留档（拦截器另 toast）
    await dlg.locator('input[placeholder^="https://mcp.example.com"]').fill('https://unreachable.example.com/mcp')
    await dlg.getByRole('button', { name: '测试连接' }).click()
    await dlg.locator('.el-alert--error', { hasText: '连接失败：MCP服务连接失败' }).waitFor({ state: 'visible', timeout: 8000 })
    // 失败不落库不阻塞：改回可达地址后保存
    await dlg.locator('input[placeholder^="https://mcp.example.com"]').fill('https://mcp.e2e.example.com/mcp')
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '新建成功')
    await waitDialogGone(page, '新增 MCP 服务')
    await waitTableIdle(page)

    // c5. 创建 body 形态断言（§1 对象：键位大写化/headers/凭据键名/超时）
    assert(lastCreateBody && typeof lastCreateBody === 'object', 'route 层应记录到创建请求 body')
    assertEq(lastCreateBody.name, SRV, `创建 body.name 应为键位大写化名称 ${SRV}`)
    assertEq(lastCreateBody.url, 'https://mcp.e2e.example.com/mcp', '创建 body.url 应为导入地址')
    assertEq(lastCreateBody.timeout_ms, 15000, '创建 body.timeout_ms 应为导入的 15000')
    assertEq(JSON.stringify(lastCreateBody.headers), '{"X-Org-Id":"cloudai"}', '创建 body.headers 应为导入静态头')
    assertEq(JSON.stringify(lastCreateBody.credential_headers), `{"Authorization":"CLOUD_BASE_AI_MCP__SERVERS__${SRV}__TOKEN"}`, '创建 body.credential_headers 应只含 .env 键名')
    assert(!('status' in lastCreateBody), '创建 body 不应带 status（§2.2 可选默认 0，前端不传）')

    // d. 列表行出现（id 倒序居首）+ 名称搜索（§2.1 包含匹配）
    const row = page.locator('.el-table__row', { hasText: SRV }).first()
    await row.waitFor({ state: 'visible', timeout: 5000 })
    assertEq((await h.rowCells(row))[4], '0/0/0', '新服务工具计数应为 0/0/0（未同步）')
    await page.locator('input[placeholder="服务名（模糊）"]').fill(SRV)
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    assert(h.state.apiCalls.some((c) => c.method === 'GET' && c.url.includes('/api/ai/mcp/server/list') && c.url.includes('name=')), '搜索应携带 name 查询参数')
    assertEq(await page.locator('.el-table__row').count(), 1, '名称过滤后应恰 1 行')
    await page.getByRole('button', { name: '重置' }).click()
    await waitTableIdle(page)
    assertEq(await page.locator('.el-table__row').count(), 2, '重置后应恢复 2 行（SYSTEM + 新服务）')

    // e. 同步（§2.7）：确认框 → 报告 toast（added=2）→ 工具计数刷新 2/0/0
    await row.getByRole('button', { name: '同步' }).click()
    const syncBox = page.locator('.el-message-box')
    await syncBox.waitFor({ state: 'visible', timeout: 5000 })
    assert((await syncBox.innerText()).includes(SRV), `同步确认框文案应含服务名称，实际 "${await syncBox.innerText()}"`)
    await syncBox.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '同步完成：新增 2 / 更新 0 / 失效 0')
    await waitTableIdle(page)
    assertEq(lastSyncId, 5001, '同步应作用于新服务 id')
    assertEq((await h.rowCells(page.locator('.el-table__row', { hasText: SRV }).first()))[4], '2/0/0', '同步后工具计数应为 2/0/0（两枚草稿）')
    await shot(page, 'mcp1-sync-report.png')

    // f. 工具抽屉（§2.8/§2.9）：草稿 tag → 展开 description/schema → 5028 审核失败 → 配 perms 发布成功
    await row.getByRole('button', { name: '工具' }).click()
    const drawer = page.locator('.el-drawer', { hasText: '工具清单' }).last()
    await drawer.waitFor({ state: 'visible', timeout: 5000 })
    assert((await drawer.innerText()).includes(SRV), '抽屉标题应含服务名称')
    assert(h.state.apiCalls.some((c) => c.method === 'GET' && c.url.includes('/api/ai/mcp/tool/list') && c.url.includes('server_id=')), '抽屉应按 server_id 拉取工具清单')
    const goodRow = drawer.locator('.el-table__row', { hasText: 'e2e_weather_query' }).first()
    const badRow = drawer.locator('.el-table__row', { hasText: 'e2e_bad_schema_tool' }).first()
    await goodRow.waitFor({ state: 'visible', timeout: 5000 })
    assert((await goodRow.locator('.el-tag', { hasText: '草稿' }).count()) === 1, '新工具应为「草稿」tag')
    // f1. 展开行：description 与 input_schema（el-collapse 两面板逐一点开）。
    //     EP 表格展开内容渲染在行后的兄弟 tr（不在 .el-table__row 内），展开区定位器挂在抽屉层
    await goodRow.locator('.el-table__expand-icon').click()
    const drawerTable = drawer.locator('.el-table')
    await drawerTable.locator('.el-collapse-item__header', { hasText: '工具描述' }).waitFor({ state: 'visible', timeout: 5000 })
    await drawerTable.locator('.el-collapse-item__header', { hasText: '工具描述' }).click()
    await drawerTable.locator('.desc-text').waitFor({ state: 'visible', timeout: 5000 })
    assert((await drawerTable.locator('.desc-text').innerText()).includes('e2e 天气查询工具'), '展开行应显示工具描述')
    await drawerTable.locator('.el-collapse-item__header', { hasText: '入参 Schema' }).click()
    const schemaPre = drawerTable.locator('.schema-pre')
    await schemaPre.waitFor({ state: 'visible', timeout: 5000 })
    assert((await schemaPre.innerText()).includes('"city"'), '展开行应显示入参 JSON Schema')
    // f2. 5028：schema 不可构建工具审核失败（错误 toast，停留草稿）
    await badRow.getByRole('button', { name: '审核' }).click()
    const badDlg = page.locator('.el-dialog', { hasText: '审核工具' }).last()
    await badDlg.waitFor({ state: 'visible', timeout: 5000 })
    await badDlg.getByRole('button', { name: '发布' }).click()
    await waitToast(page, 'MCP工具schema不可构建', 'error')
    await badDlg.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '审核工具')
    // f3. 发布成功：自由输入 perms（allow-create）→ 状态 tag 变「已发布」+ perms tag 回显
    await goodRow.getByRole('button', { name: '审核' }).click()
    const reviewDlg = page.locator('.el-dialog', { hasText: '审核工具' }).last()
    await reviewDlg.waitFor({ state: 'visible', timeout: 5000 })
    await reviewDlg.locator('.el-select').click()
    // fill 会触发 input 事件驱动 EP select 过滤（insertText 不派发键盘事件，勿用）
    await reviewDlg.locator('.el-select input').fill('eemcp:demo:run')
    const createdOpt = page.locator('.el-select-dropdown__item', { hasText: 'eemcp:demo:run' }).first()
    await createdOpt.waitFor({ state: 'visible', timeout: 5000 })
    await createdOpt.click()
    await reviewDlg.locator('.el-select .el-tag', { hasText: 'eemcp:demo:run' }).waitFor({ state: 'visible', timeout: 5000 })
    // 多选下拉选中后仍保持展开，popper 遮罩会拦截发布按钮点击——Esc 收起后再提交
    await page.keyboard.press('Escape')
    await createdOpt.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})
    await shot(page, 'mcp1-review-dialog.png')
    await reviewDlg.getByRole('button', { name: '发布' }).click()
    await waitToast(page, '审核完成，工具已发布')
    await waitDialogGone(page, '审核工具')
    await drawer.locator('.el-table__row', { hasText: 'e2e_weather_query' }).first().locator('.el-tag', { hasText: '已发布' }).waitFor({ state: 'visible', timeout: 8000 })
    // 审核后抽屉表与主表两路并发刷新，计数断言前等两处 loading 都收敛
    await waitTableIdle(page)
    const goodAfter = drawer.locator('.el-table__row', { hasText: 'e2e_weather_query' }).first()
    assert((await goodAfter.locator('.el-tag', { hasText: '已发布' }).count()) === 1, '发布后状态 tag 应变「已发布」')
    assert((await goodAfter.locator('.el-tag', { hasText: 'eemcp:demo:run' }).count()) === 1, '发布后所需权限 tag 应回显')
    assert(lastReviewBody && Array.isArray(lastReviewBody.required_perms), 'route 层应记录到审核请求 body')
    assertEq(JSON.stringify(lastReviewBody.required_perms), '["eemcp:demo:run"]', '审核 body.required_perms 应为自由输入项')
    assertEq((await h.rowCells(page.locator('.el-table__row', { hasText: SRV }).first()))[4], '1/1/0', '审核后列表工具计数应为 1/1/0（抽屉外主表随 reviewed 刷新）')
    await shot(page, 'mcp1-tool-drawer.png')
    await drawer.locator('.el-drawer__close-btn').click()
    await drawer.waitFor({ state: 'hidden', timeout: 5000 })

    // g. 编辑改描述（§2.4 全量）：回显（详情端点）→ 改描述保存 → PUT body 全量（含 status/headers）
    await row.getByRole('button', { name: '编辑' }).click()
    const editDlg = page.locator('.el-dialog', { hasText: '编辑 MCP 服务' }).last()
    await editDlg.waitFor({ state: 'visible', timeout: 5000 })
    assert(await waitEcho(editDlg.locator('input[placeholder^="大写字母开头"]'), SRV), '编辑弹窗应回显原名称（详情端点数据）')
    const descArea = editDlg.locator('textarea[placeholder="管理备注（可选）"]')
    await waitEcho(descArea, '', 2000).catch(() => {})
    await descArea.fill(`e2e_${ts}_描述更新`)
    await editDlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑 MCP 服务')
    await waitTableIdle(page)
    assert(lastUpdateBody && typeof lastUpdateBody === 'object', 'route 层应记录到编辑请求 body')
    assertEq(lastUpdateBody.description, `e2e_${ts}_描述更新`, '编辑 body.description 应为更新值')
    assertEq(lastUpdateBody.status, 0, '编辑 body 应带 status（PUT 全量覆盖语义，弹窗不改状态取行当前值）')
    assertEq(JSON.stringify(lastUpdateBody.headers), '{"X-Org-Id":"cloudai"}', '编辑 body.headers 应原样带回（全量，详情合并）')
    assert((await page.locator('.el-table__row', { hasText: SRV }).first().innerText()).includes(`e2e_${ts}_描述更新`), '列表描述列应显示更新后的描述')

    // h. 状态开关：停用 = 先拉详情再 PUT（列表行无 headers/凭据/超时，不能只发 status）
    const detailGetsBefore = h.state.apiCalls.filter((c) => c.method === 'GET' && /^\/api\/ai\/mcp\/server\/\d+$/.test(c.url.split('?')[0])).length
    await page.locator('.el-table__row', { hasText: SRV }).first().locator('.el-switch').click()
    await waitToast(page, '已停用')
    assertEq(lastUpdateBody.status, 1, '开关停用提交 body.status 应为 1')
    assertEq(
      h.state.apiCalls.filter((c) => c.method === 'GET' && /^\/api\/ai\/mcp\/server\/\d+$/.test(c.url.split('?')[0])).length,
      detailGetsBefore + 1,
      '开关变更前应先拉详情（GET /{id} 合并提交）',
    )
    assert(!((await page.locator('.el-table__row', { hasText: SRV }).first().locator('.el-switch').getAttribute('class')) || '').includes('is-checked'), '停用后开关应处于关闭态')

    // i. 删除：确认框文案含名称 → 行消失（SYSTEM 保留）
    await page.locator('.el-table__row', { hasText: SRV }).first().getByRole('button', { name: '删除' }).click()
    const delBox = page.locator('.el-message-box')
    await delBox.waitFor({ state: 'visible', timeout: 5000 })
    assert((await delBox.innerText()).includes(SRV), `删除确认框文案应含服务名称，实际 "${await delBox.innerText()}"`)
    await delBox.getByRole('button', { name: '确定' }).click()
    await delBox.waitFor({ state: 'hidden', timeout: 5000 })
    await waitToast(page, '删除成功')
    await waitTableIdle(page)
    // 已关闭工具抽屉仍留 DOM（display:none）且内含自己的表格——行断言只认可见行
    assertEq(await page.locator('.el-table__row:visible').count(), 1, '删除后应仅剩内置 SYSTEM 行（不含隐藏抽屉表）')
    assert((await page.locator('.el-table__row:visible').first().innerText()).includes('SYSTEM'), '剩余行应为内置 SYSTEM')
  })

  // ================= MC2 权限收敛（无任何 ai:mcp:*） =================
  await step('MC2', '权限收敛：无 ai:mcp:* → 「MCP服务管理」菜单不可见（16 项形态）→ 查询接口 403（R body code）', async () => {
    grantMode = 'none'
    // 整页重载触发权限快照与导航树按新授权重建（me/user-nav 增广读 grantMode）；
    // /dashboard 为静态路由恒可达，作侧边形态观察点
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' })
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const labels = await menuLabels()
    assertEq(labels.join(','), SIDEBAR_16, `无 ai:mcp:list 时侧边应回 16 项形态（MCP服务管理 不可见），实际 ${JSON.stringify(labels)}`)
    await shot(page, 'mcp2-no-perm.png')
    // 接口执法：mock 层 403（R 约定 HTTP 恒 200、code 在 body）
    const r403 = await page.evaluate(async () => {
      const resp = await fetch('/api/ai/mcp/server/list')
      return await resp.json()
    })
    assertEq(r403.code, 403, `无权限调用服务列表应得 body code 403（HTTP 恒 200），实际 ${JSON.stringify(r403)}`)
    log(`  403 响应体: ${JSON.stringify(r403)}`)
  })

  // ================= 证据核验 =================
  await step('MC-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
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
      '\n测试数据: MCP 服务/工具全在 route mock 内存态（零后端写，脚本结束即清）；admin 种子账号只登录不改，种子菜单零触碰',
      'mock 面: /api/ai/mcp/server/** + /ai/mcp/tool/** 九端点内存态 + user-nav/auth/me 真实透传增广（191 与 ai:mcp:* 注入，种子入库后幂等跳过）；登录链走真实 gateway/sso/system，cloud-base-ai 8010 未依赖',
      '注记: mock 层只验前端行为（请求形态/渲染/键位大写化/权限收敛/403/5028 消费）；真实落库/同步状态机/服务端权限执法由后端章与集成期真跑覆盖',
    ],
  })
  await browser.close()
}
