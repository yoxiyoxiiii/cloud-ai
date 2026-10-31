/**
 * MCP Server 用户授权（mcp-perm）e2e（契约：docs/superpowers/contracts/2026-10-30-mcp-server-permission-api.md）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202 已启动（admin 登录链走真实网关，
 * 同 run-ai-kb-perm-e2e.mjs 分层先例）；前端 dev 5173 已启动。
 * **cloud-base-ai 8010 不需要起**——授权两端点与三数据源端点全部在浏览器网络层
 * route mock（fulfill 假 R JSON）；菜单 191 与 ai:mcp:* 权限经 user-nav / auth/me
 * 响应增广注入（route.fetch 真实透传后补种，幂等——种子入库后注入自动跳过，
 * 同 run-mcp-e2e.mjs 先例）。
 * 运行：cd cloud-e2e && npm run e2e:mcpperm（单跑，不进 e2e 聚合——沿 e2e:kbperm 口径）；
 * E2E_HEADLESS=1 无头。黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：零后端写（全部 mock 内存态）；admin 种子账号只登录不改，种子菜单零触碰。
 *
 * 场景（契约 §2/§3 授权两端点 + §1 id 换轨/弱引用失效占位/默认拒绝语义 + §6 分工——
 * 运行面三态不进 e2e，由后端测试矩阵覆盖）：
 * - MP1 弹窗回显：admin 登录 → MCP 管理页行内「授权」按钮 → 弹窗三块拉源+回显
 *   （角色/用户 select 预选 tag、部门树勾选态；id 换轨 number→string 后命中选项；
 *   回显含重复 id 验证前端去重呈现）
 * - MP2 勾选保存：三块各增选一项 → 保存 → route 层 body 断言（number 数组/去重/恰三键）
 *   → toast「保存成功」→ 弹窗关
 * - MP3 已失效占位：server2 回显含选项外 id（999/9999）→「id:N（已失效）」占位断言
 *   （select 占位选项 + 部门占位 tag）→ 直接保存 body 保留 999/9999（弱引用最小惊讶语义）
 * - MP4 错误分支：mock PUT 返回 5021（服务不存在）→ 错误 toast 且弹窗不关（可重试）
 * - MP5 按钮权限：mock 登录链（me 仅 ai:mcp:list）→「授权/编辑」按钮 DOM 移除
 *   （v-perms 同挂 ai:mcp:update）、状态列降级只读 tag（canUpdate 二态渲染）、「工具」可见
 *
 * 选择器注意：el-select 下拉 teleport 到 body——选项定位用 .el-select-dropdown:visible；
 * el-tree 节点行文本在 .el-tree-node__content（不含子树文本，hasText 不串父节点）；
 * EP 弹窗关闭是 display:none——消失等待用 waitDialogGone（hidden 语义）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
const TS = Date.now()

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login, waitToast, waitDialogGone, waitTableIdle } = h

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

// ---------- route mock 公共数据（契约 §2/§3 的假 R JSON） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const ERR = (code, msg) => JSON.stringify({ code, msg, data: null })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

// MCP 服务行（§2.1 summary 形态；id 为 AI 域 number；SYSTEM=内置行，E2E 行作 MP3 载体）
const servers = [
  {
    id: 1, name: 'SYSTEM', description: '平台内置 cloud-system MCP 服务', url: 'http://127.0.0.1:9202/mcp',
    transport: 'http', is_builtin: 1, status: 0, tool_counts: '0/2/0',
    create_time: '2026-10-30 09:00:00', update_time: null,
  },
  {
    id: 2, name: 'E2E_PERM_SRV', description: 'e2e 授权场景外部服务', url: 'https://mcp.e2e.example.com/mcp',
    transport: 'http', is_builtin: 0, status: 0, tool_counts: '1/0/0',
    create_time: '2026-10-30 10:00:00', update_time: null,
  },
]

// 授权内存态（GET/PUT /perms 同步维护）
// server1 回显含重复 2（MP1 验证前端 Set 去重呈现 + MP2 提交去重）；server2 含选项外 id（MP3 已失效占位）
const permsByServer = new Map([
  [1, { role_ids: [2, 2, 5], dept_ids: [3], user_ids: [952] }],
  [2, { role_ids: [2, 999], dept_ids: [9999], user_ids: [] }],
])

// 主体选项（system 域口径：id 为 string——Java Long→String，契约 §1 换轨前提）
const roleOptions = [
  { id: '2', label: '管理员角色' },
  { id: '5', label: '运营角色' },
  { id: '7', label: '客服角色' },
]
const userOptions = [
  { id: '952', label: '张三(zhangsan)' },
  { id: '953', label: '李四(lisi)' },
]
// 部门树（system 域口径：id 为 string；两根三叶）
const deptTree = [
  {
    id: '1', parentId: '0', name: '总公司', sort: 0, status: 0, builtin: true, createTime: '2026-10-30 08:00:00',
    children: [
      { id: '3', parentId: '1', name: '研发部', sort: 0, status: 0, builtin: false, createTime: '2026-10-30 08:00:00', children: [] },
      { id: '4', parentId: '1', name: '市场部', sort: 1, status: 0, builtin: false, createTime: '2026-10-30 08:00:00', children: [] },
    ],
  },
  {
    id: '10', parentId: '0', name: '华东分部', sort: 1, status: 0, builtin: false, createTime: '2026-10-30 08:00:00',
    children: [],
  },
]

let lastPutUrl = '' // 最近一次 PUT /perms 请求 URL（server_id 断言锚）
let lastPutBody = null // 最近一次 PUT /perms 请求 body（MP2/MP3/MP4 断言锚）
let putErrorCode = 0 // 非零时 PUT 返回该错误码（MP4 剧本开关）

/** 授权两端点 + 三数据源端点 + 管理面列表的 mock 装配（返回 fulfill 结果=已处理，null=未匹配） */
function fulfillMcpPermMock(route, req, u) {
  const p = u.pathname.replace(/^\/api/, '')
  const q = u.searchParams
  if (p === '/ai/mcp/server/list' && req.method() === 'GET') {
    const pageNum = Math.max(1, Number(q.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(q.get('size') ?? 10)))
    return route.fulfill(json(R({ total: servers.length, rows: servers.slice((pageNum - 1) * size, pageNum * size) })))
  }
  const permsMatch = p.match(/^\/ai\/mcp\/server\/(\d+)\/perms$/)
  if (permsMatch) {
    const serverId = Number(permsMatch[1])
    if (req.method() === 'GET') {
      return route.fulfill(json(R(permsByServer.get(serverId) ?? { role_ids: [], dept_ids: [], user_ids: [] })))
    }
    if (req.method() === 'PUT') {
      lastPutUrl = u.pathname
      lastPutBody = req.postDataJSON()
      if (putErrorCode !== 0) {
        // MP4 剧本：服务端 5021（契约 §3 错误分支——服务不存在/软删）
        return route.fulfill(json(ERR(putErrorCode, 'MCP服务不存在')))
      }
      permsByServer.set(serverId, {
        role_ids: lastPutBody.role_ids ?? [],
        dept_ids: lastPutBody.dept_ids ?? [],
        user_ids: lastPutBody.user_ids ?? [],
      })
      return route.fulfill(json(R(null)))
    }
  }
  if (p === '/system/data-perm/subject-options' && req.method() === 'GET') {
    return route.fulfill(json(R(Number(q.get('type')) === 1 ? userOptions : roleOptions)))
  }
  if (p === '/system/dept/tree' && req.method() === 'GET') {
    return route.fulfill(json(R(deptTree)))
  }
  return null
}

// ---------- catch-all：登录链走真实网关，业务端点 mock（沿 run-ai-kb-perm 分层） ----------
await page.route('**/api/**', async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  if (!u.pathname.startsWith('/api/')) {
    return route.fallback() // 防误拦 Vite dev 模块 URL（如 /src/api/mcp.ts）
  }
  const done = fulfillMcpPermMock(route, req, u)
  if (done) return done
  return route.fallback()
})

// ---------- 菜单/权限增广（真实响应透传 + 补种注入；种子入库后幂等跳过，同 run-mcp-e2e 先例） ----------
await page.route('**/api/system/menu/user-nav', async (route) => {
  const resp = await route.fetch()
  const body = await resp.json()
  const tree = Array.isArray(body.data) ? body.data : []
  const ai = tree.find((n) => String(n.id) === '18')
  if (ai && Array.isArray(ai.children) && !ai.children.some((c) => String(c.id) === '191')) {
    ai.children.push({ id: '191', parentId: '18', name: 'MCP服务管理', type: 'C', path: '/mcp/manage', icon: 'Connection', sort: 5, children: [] })
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
    // 授权面板按钮挂 ai:mcp:update（契约 §0）；admin 权限快照缺该枚时补种（种子未入环境时兜底）
    for (const perm of ['ai:mcp:list', 'ai:mcp:update']) {
      if (!body.data.permissions.includes(perm)) body.data.permissions.push(perm)
    }
  }
  await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(body) })
})

// 页面表格经 ProTable 包裹（div.pro-table > el-table），el-table 非卡片体直接子节点——用后代选择器（沿 run-mcp-e2e.mjs 同页验证过的形态）
const mainRow = (name) => page.locator('.el-card__body .el-table__row', { hasText: name }).first()

try {
  // ================= MP1 弹窗回显 =================
  await step('MP1', 'admin 登录 → MCP 管理页「授权」按钮 → 弹窗三块拉源+回显（select 预选 tag / 树勾选态 / 重复 id 去重呈现）', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（真实网关链）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    await page.locator('.el-menu .el-menu-item', { hasText: 'MCP服务管理' }).click()
    await page.waitForURL('**/mcp/manage', { timeout: 8000 })
    await waitTableIdle(page)
    await mainRow('SYSTEM').waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await page.locator('.el-card__body .el-table__row:visible').count(), 2, '表格应渲染 mock 2 服务')
    // 操作列「授权」按钮存在（与「编辑」同款位置风格，v-perms ai:mcp:update——admin 全权限可见）
    assertEq(await mainRow('SYSTEM').getByRole('button', { name: '授权' }).count(), 1, '操作列应有「授权」按钮')
    await mainRow('SYSTEM').getByRole('button', { name: '授权' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '授权（SYSTEM）' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    assert((await dlg.locator('.el-dialog__title').innerText()).includes('SYSTEM'), '弹窗标题应含服务名')
    await dlg.locator('.el-select').first().waitFor({ state: 'visible', timeout: 3000 })
    // 回显等待：三源+perms 并行请求后勾选态落定（loading 遮罩消失锚）
    await page.waitForTimeout(600)
    // 角色 select 预选 tag（回显 [2,2,5] → Set 去重呈现 2/5 两枚 tag；id 换轨 number→string 后命中选项）
    const roleTags = await dlg.locator('.el-select').nth(0).locator('.el-tag').allInnerTexts()
    assertEq(roleTags.length, 2, `角色回显应去重为 2 枚 tag（回显 [2,2,5]），实际 ${JSON.stringify(roleTags)}`)
    assert(roleTags.join(',').includes('管理员角色') && roleTags.join(',').includes('运营角色'), `角色回显应含 2/5 的 label tag，实际 ${JSON.stringify(roleTags)}`)
    // 用户 select 预选 tag（回显 952）
    const userTags = await dlg.locator('.el-select').nth(1).locator('.el-tag').allInnerTexts()
    assert(userTags.join(',').includes('张三(zhangsan)'), `用户回显应含 952 的 label tag，实际 ${JSON.stringify(userTags)}`)
    // 部门树勾选态（回显 3=研发部 checked；strict 模式父节点 1 不联动勾选）
    const treeOf = (name) => dlg.locator('.el-tree-node__content', { hasText: name })
    assert((await treeOf('研发部').locator('.el-checkbox').first().getAttribute('class')).includes('is-checked'), '研发部应勾选（回显 dept_ids [3]）')
    assert(!(await treeOf('市场部').locator('.el-checkbox').first().getAttribute('class')).includes('is-checked'), '市场部不应勾选')
    assert(!(await treeOf('总公司').locator('.el-checkbox').first().getAttribute('class')).includes('is-checked'), 'check-strictly：父节点不应因子节点勾选而勾选')
    await shot(page, 'mcpperm-mp1-dialog.png')
  })

  // ================= MP2 勾选保存 =================
  await step('MP2', '三块各增选一项 → 保存 → body 断言（number 数组/去重/恰三键）→ toast → 弹窗关', async () => {
    const dlg = page.locator('.el-dialog', { hasText: '授权（SYSTEM）' }).last()
    // 角色 select 增选「客服角色」（下拉 teleport 到 body，:visible 锁当前展开面板）
    await dlg.locator('.el-select').nth(0).click()
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '客服角色' }).click()
    await page.keyboard.press('Escape')
    // 用户 select 增选「李四(lisi)」
    await dlg.locator('.el-select').nth(1).click()
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '李四(lisi)' }).click()
    await page.keyboard.press('Escape')
    // 部门树增勾「市场部」
    await dlg.locator('.el-tree-node__content', { hasText: '市场部' }).locator('.el-checkbox').click()
    await sleep(300)
    await shot(page, 'mcpperm-mp2-selected.png')
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await waitDialogGone(page, '授权（SYSTEM）')
    // route 层 body 断言：server 1、number 数组、去重（回显重复 2 只落一次）、恰三键（契约 §3）
    assert(lastPutUrl === '/api/ai/mcp/server/1/perms', `PUT 应指向 server 1（回显后保存），实际 ${lastPutUrl}`)
    assert(lastPutBody != null, 'route 层应记录到 PUT body')
    assertEq(JSON.stringify(lastPutBody.role_ids), JSON.stringify([2, 5, 7]), `role_ids 应为去重后的 [2,5,7]，实际 ${JSON.stringify(lastPutBody.role_ids)}`)
    assertEq(JSON.stringify(lastPutBody.dept_ids), JSON.stringify([3, 4]), `dept_ids 应为 [3,4]，实际 ${JSON.stringify(lastPutBody.dept_ids)}`)
    assertEq(JSON.stringify(lastPutBody.user_ids), JSON.stringify([952, 953]), `user_ids 应为 [952,953]，实际 ${JSON.stringify(lastPutBody.user_ids)}`)
    for (const key of ['role_ids', 'dept_ids', 'user_ids']) {
      assert(lastPutBody[key].every((v) => Number.isInteger(v)), `${key} 应全为 number（AI 域入参，契约 §1 换轨）`)
      assertEq(new Set(lastPutBody[key]).size, lastPutBody[key].length, `${key} 应无重复（前端 Set 去重）`)
    }
    assertEq(Object.keys(lastPutBody).sort().join(','), 'dept_ids,role_ids,user_ids', 'body 应恰含三键（无多余字段）')
  })

  // ================= MP3 已失效占位 =================
  await step('MP3', 'server2 回显含选项外 id → 「id:N（已失效）」占位 tag → 保存 body 保留已失效 id（弱引用最小惊讶）', async () => {
    await mainRow('E2E_PERM_SRV').getByRole('button', { name: '授权' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '授权（E2E_PERM_SRV）' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await dlg.locator('.el-select').first().waitFor({ state: 'visible', timeout: 3000 })
    await page.waitForTimeout(600)
    // 角色 select：2 有效 + 999 已失效占位（禁选占位选项承载 tag 文案）
    const roleTags = await dlg.locator('.el-select').nth(0).locator('.el-tag').allInnerTexts()
    const roleText = roleTags.join(',')
    assert(roleText.includes('管理员角色'), `角色占位场景仍应回显有效 2，实际 ${JSON.stringify(roleTags)}`)
    assert(roleText.includes('id:999（已失效）'), `角色 select 应显示「id:999（已失效）」占位 tag，实际 ${JSON.stringify(roleTags)}`)
    // 部门树：9999 不在树中 → 树下占位 tag
    const invalidTags = await dlg.locator('.invalid-tags .el-tag').allInnerTexts()
    assert(invalidTags.join(',').includes('id:9999（已失效）'), `部门占位应显示「id:9999（已失效）」tag，实际 ${JSON.stringify(invalidTags)}`)
    await shot(page, 'mcpperm-mp3-invalid.png')
    // 直接保存（未做任何修改）：已失效 id 随集保留提交——打开即保存授权不变（契约 §1 弱引用自愈、最小惊讶语义）
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await waitDialogGone(page, '授权（E2E_PERM_SRV）')
    assert(lastPutUrl === '/api/ai/mcp/server/2/perms', `PUT 应指向 server 2，实际 ${lastPutUrl}`)
    assertEq(JSON.stringify(lastPutBody.role_ids), JSON.stringify([2, 999]), `已失效 999 应保留提交，实际 ${JSON.stringify(lastPutBody.role_ids)}`)
    assertEq(JSON.stringify(lastPutBody.dept_ids), JSON.stringify([9999]), `已失效 9999 应保留提交，实际 ${JSON.stringify(lastPutBody.dept_ids)}`)
    assert(lastPutBody.role_ids.every((v) => Number.isInteger(v)), '占位 id 提交亦为 number（换轨一致）')
  })

  // ================= MP4 错误分支（5021 弹窗不关） =================
  await step('MP4', 'mock PUT 返回 5021 → 错误 toast（拦截器统一提示）→ 弹窗不关可重试 → 取消关闭', async () => {
    await mainRow('SYSTEM').getByRole('button', { name: '授权' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '授权（SYSTEM）' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await dlg.locator('.el-select').first().waitFor({ state: 'visible', timeout: 3000 })
    await page.waitForTimeout(600)
    putErrorCode = 5021 // 剧本：服务端判服务不存在（契约 §3 错误 5021）
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, 'MCP服务不存在', 'error')
    assert(await dlg.isVisible(), '5021 保存失败后弹窗应保持打开（可调整重试）')
    assert(lastPutBody != null && Array.isArray(lastPutBody.role_ids), '失败的 PUT 亦应有合法 body（前端提交形态不变）')
    await shot(page, 'mcpperm-mp4-error.png')
    // 收尾：恢复剧本并经取消关闭弹窗（本场景零成功写）
    putErrorCode = 0
    await dlg.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '授权（SYSTEM）')
  })

  // ================= MP5 按钮权限（v-perms 移除语义） =================
  await step('MP5', 'mock 登录链（me 仅 ai:mcp:list）→「授权/编辑」不可见、「工具」可见、状态列降级只读 tag', async () => {
    // 独立 ctx 全 mock 登录链（list-only 剧本账号真实环境不存在，同 kbperm-AC5 先例）
    const ctx2 = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page2 = await ctx2.newPage()
    h.attachListeners(page2)
    await page2.route('**/api/**', async (route) => {
      const req = route.request()
      const u = new URL(req.url())
      if (!u.pathname.startsWith('/api/')) {
        return route.fallback()
      }
      const p = u.pathname.replace(/^\/api/, '')
      if (p === '/sso/auth/login' && req.method() === 'POST') {
        return route.fulfill(json(R({ accessToken: 'e2e-mock-at', refreshToken: 'e2e-mock-rt', expiresIn: '7200' })))
      }
      if (p === '/sso/auth/me' && req.method() === 'GET') {
        return route.fulfill(json(R({ account: `e2e_perm_viewer_${TS}`, permissions: ['ai:mcp:list'] })))
      }
      if (p === '/system/menu/user-nav' && req.method() === 'GET') {
        return route.fulfill(
          json(
            R([
              {
                id: '18', parentId: '0', name: 'AI 助手', type: 'M', path: '', icon: 'ChatDotRound', sort: 1,
                children: [{ id: '191', parentId: '18', name: 'MCP服务管理', type: 'C', path: '/mcp/manage', icon: 'Connection', sort: 5, children: [] }],
              },
            ]),
          ),
        )
      }
      const done = fulfillMcpPermMock(route, req, u)
      if (done) return done
      return route.fallback()
    })
    const r = await login(page2, `e2e_perm_viewer_${TS}`, 'irrelevant-mock-pass')
    assert(r.ok, `mock 登录应成功: ${r.msg || ''}`)
    await page2.goto(`${BASE}/mcp/manage`, { waitUntil: 'domcontentloaded' })
    await waitTableIdle(page2)
    await page2.locator('.el-card__body .el-table__row').first().waitFor({ state: 'visible', timeout: 8000 })
    const row2 = page2.locator('.el-card__body .el-table__row').first()
    // v-perms 移除语义：无 ai:mcp:update →「授权/编辑」DOM 移除；「测试/同步/删除」同因无权限不可见
    for (const label of ['授权', '编辑', '测试', '同步', '删除']) {
      assertEq(await row2.getByRole('button', { name: label }).count(), 0, `无对应管理权限快照：「${label}」按钮应不可见`)
    }
    assertEq(await page2.getByRole('button', { name: '新增服务' }).count(), 0, '无 ai:mcp:add：「新增服务」应不可见')
    assertEq(await row2.getByRole('button', { name: '工具' }).count(), 1, '「工具」应可见（查询类动作不挂按钮级权限）')
    // canUpdate 二态渲染：无 update 权限时状态列降级为只读 tag（无开关）
    assertEq(await row2.locator('.el-switch').count(), 0, '无 ai:mcp:update：状态列应为只读 tag 而非开关')
    assert((await row2.innerText()).includes('启用'), '状态 tag 应回显「启用」')
    await shot(page2, 'mcpperm-mp5-noperm.png')
    await ctx2.close()
  })

  // ================= 证据核验 =================
  await step('MP-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（R 约定 HTTP 恒 200，5021 走 body code），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  h.summary({
    extras: [
      `\n测试数据: 零后端写（授权两端点/管理面列表/三数据源端点全 mock 内存态）；admin 种子账号只登录不改，种子菜单零触碰`,
      'mock 面: admin ctx 拦 /api/ai/mcp/server/** + subject-options + dept/tree（登录链真实网关）；user-nav/auth/me 真实透传增广（191 与 ai:mcp:list/update 补种，种子入库后幂等跳过）——cloud-base-ai 8010 未依赖',
      '注记: mock 层只验前端行为（回显换轨/勾选/占位/提交 payload/错误分支消费）；真实授权落库与运行面三态由后端章测试矩阵覆盖（契约 §6 分工）',
    ],
  })
  await browser.close()
}
