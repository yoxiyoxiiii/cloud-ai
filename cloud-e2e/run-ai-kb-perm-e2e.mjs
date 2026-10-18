/**
 * AI 知识库授权（kb-perm）e2e（契约：docs/superpowers/contracts/2026-10-18-kb-permission-api.md）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202 已启动（admin 登录链走真实网关，
 * 同 run-ai-kb-e2e.mjs 分层先例）；前端 dev 5173 已启动。
 * **cloud-base-ai 8010 不需要起**——授权两端点与三数据源端点全部在浏览器网络层
 * route mock（fulfill 假 R JSON）；AC5/AC6 连登录链也 mock（list-only 用户为剧本态，
 * 真实环境无此账号）。
 * 运行：cd cloud-e2e && npm run e2e:kbperm（单跑，不进 e2e 聚合——同 e2e:aikb 口径）；
 * E2E_HEADLESS=1 无头。黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：零后端写（全部 mock 内存态）；admin 种子账号只登录不改，种子菜单零触碰。
 *
 * 场景（契约 §2/§3 授权两端点 + §1 id 换轨/已失效占位 + §7 数据源 + 按钮权限/列表可见性）：
 * - AC1 弹窗回显：admin 登录 → 知识库页行内「权限」按钮 → 弹窗三块拉源+回显
 *   （角色/用户 select 预选 tag、部门树勾选态；id 换轨 number→string 后勾选）
 * - AC2 勾选保存：三块各增选一项 → 保存 → route 层 body 断言（number 数组/去重/恰三键）
 *   → toast「保存成功」→ 弹窗关
 * - AC3 已失效占位：kb2 回显含选项外 id（999/9999）→「id:N（已失效）」占位 tag 断言
 *   （select 占位选项 + 部门占位 tag）→ 直接保存 body 保留 999/9999（最小惊讶语义）
 * - AC4 清空保存：移除全部勾选与占位 → 保存 body 三数组全空（契约 §3 清空授权语义）
 * - AC5 按钮权限：mock 登录链（me 不含 ai:kb:update）→「权限/编辑/删除/新增」按钮
 *   不可见、「文件管理」可见（v-perms 快照语义）
 * - AC6 列表可见性：mock 层模拟契约 §4 修订——list-only 用户仅见 own+granted 行
 *   （第三方库行不可见，服务端过滤行为的 mock 剧本）
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
const { log, sleep, step, assert, assertEq, shot, login, waitToast, waitDialogGone, waitTableIdle, rowCells } = h

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

// ---------- route mock 公共数据（契约 §2/§3/§7 的假 R JSON） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

// 知识库行（AC1-AC4 主表数据；id 为 AI 域 number）
const kbs = [
  { id: 1, name: '产品手册库', description: '产品使用手册', create_time: '2026-10-18 09:00:00' },
  { id: 2, name: '规章制度库', description: null, create_time: '2026-10-18 10:00:00' },
]

// 授权内存态（GET/PUT /perms 同步维护；AC4 清空 kb1）
const permsByKb = new Map([
  [1, { role_ids: [2, 5], dept_ids: [3], user_ids: [952] }], // AC1 回显（全部在选项内）
  [2, { role_ids: [2, 999], dept_ids: [9999], user_ids: [] }], // AC3 回显（999/9999 不在选项=已失效）
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
    id: '1', parentId: '0', name: '总公司', sort: 0, status: 0, builtin: true, createTime: '2026-10-18 08:00:00',
    children: [
      { id: '3', parentId: '1', name: '研发部', sort: 0, status: 0, builtin: false, createTime: '2026-10-18 08:00:00', children: [] },
      { id: '4', parentId: '1', name: '市场部', sort: 1, status: 0, builtin: false, createTime: '2026-10-18 08:00:00', children: [] },
    ],
  },
  {
    id: '10', parentId: '0', name: '华东分部', sort: 1, status: 0, builtin: false, createTime: '2026-10-18 08:00:00',
    children: [],
  },
]

let lastPutUrl = '' // 最近一次 PUT /perms 请求 URL（kb_id 断言锚）
let lastPutBody = null // 最近一次 PUT /perms 请求 body（AC2/AC3/AC4 route 层断言锚）

/** 授权两端点 + 三数据源端点的 mock 装配（admin ctx 与 mock ctx 共用；返回 true=已处理） */
function fulfillKbPermMock(route, req, u) {
  const p = u.pathname.replace(/^\/api/, '')
  const q = u.searchParams
  if (p === '/ai/rag/kb/list' && req.method() === 'GET') {
    const pageNum = Math.max(1, Number(q.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(q.get('size') ?? 10)))
    return route.fulfill(json(R({ total: kbs.length, rows: kbs.slice((pageNum - 1) * size, pageNum * size) })))
  }
  const permsMatch = p.match(/^\/ai\/rag\/kb\/(\d+)\/perms$/)
  if (permsMatch) {
    const kbId = Number(permsMatch[1])
    if (req.method() === 'GET') {
      return route.fulfill(json(R(permsByKb.get(kbId) ?? { role_ids: [], dept_ids: [], user_ids: [] })))
    }
    if (req.method() === 'PUT') {
      lastPutUrl = u.pathname
      lastPutBody = req.postDataJSON()
      permsByKb.set(kbId, {
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

// ---------- admin ctx：登录链走真实网关，业务端点 mock（沿 run-ai-kb-e2e 分层） ----------
await page.route('**/api/**', async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  if (!u.pathname.startsWith('/api/')) {
    return route.fallback() // 防误拦 Vite dev 模块 URL（如 /src/api/xxx.ts）
  }
  const done = fulfillKbPermMock(route, req, u)
  if (done) return done
  return route.fallback()
})

// 主表格 scope（沿 aikb 脚本口径）
const mainRow = (n) => page.locator('.el-card__body > .el-table .el-table__row').nth(n)
const mainRowCount = () => page.locator('.el-card__body > .el-table .el-table__row').count()

try {
  // ================= AC1 弹窗回显 =================
  await step('AC1', 'admin 登录 → 知识库页「权限」按钮 → 弹窗三块拉源+回显（select 预选 tag / 树勾选态）', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（真实网关链）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    await page.locator('.el-menu .el-menu-item', { hasText: '知识库' }).click()
    await page.waitForURL('**/rag/kb', { timeout: 8000 })
    await waitTableIdle(page)
    await mainRow(0).waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await mainRowCount(), 2, '表格应渲染 mock 2 库')
    // 操作列四按钮（admin 含 ai:kb:update → 「权限」可见性锚）
    for (const label of ['编辑', '权限', '删除', '文件管理']) {
      assertEq(await mainRow(0).getByRole('button', { name: label }).count(), 1, `操作列应有「${label}」按钮`)
    }
    // 打开 kb2（id 倒序居首=规章制度库？id 2 > 1 → 首行规章制度库）——AC1 用 kb1（产品手册库，次行）
    const rowKb1 = page.locator('.el-card__body > .el-table .el-table__row', { hasText: '产品手册库' }).first()
    await rowKb1.getByRole('button', { name: '权限' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '权限分配' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    assert((await dlg.locator('.el-dialog__title').innerText()).includes('产品手册库'), '弹窗标题应含库名')
    await dlg.locator('.el-select').first().waitFor({ state: 'visible', timeout: 3000 })
    // 回显等待：三源+perms 并行请求后勾选态落定（loading 遮罩消失锚）
    await page.waitForTimeout(600)
    // 角色 select 预选 tag（回显 2/5 → label tag；id 换轨 number→string 后命中选项）
    const roleTags = await dlg.locator('.el-select').nth(0).locator('.el-tag').allInnerTexts()
    assert(roleTags.join(',').includes('管理员角色') && roleTags.join(',').includes('运营角色'), `角色回显应含 2/5 的 label tag，实际 ${JSON.stringify(roleTags)}`)
    // 用户 select 预选 tag（回显 952）
    const userTags = await dlg.locator('.el-select').nth(1).locator('.el-tag').allInnerTexts()
    assert(userTags.join(',').includes('张三(zhangsan)'), `用户回显应含 952 的 label tag，实际 ${JSON.stringify(userTags)}`)
    // 部门树勾选态（回显 3=研发部 checked；strict 模式父节点 1 不联动勾选）
    const treeOf = (name) => dlg.locator('.el-tree-node__content', { hasText: name })
    assert((await treeOf('研发部').locator('.el-checkbox').first().getAttribute('class')).includes('is-checked'), '研发部应勾选（回显 dept_ids [3]）')
    assert(!(await treeOf('市场部').locator('.el-checkbox').first().getAttribute('class')).includes('is-checked'), '市场部不应勾选')
    assert(!(await treeOf('总公司').locator('.el-checkbox').first().getAttribute('class')).includes('is-checked'), 'check-strictly：父节点不应因子节点勾选而勾选')
    await shot(page, 'kbperm-ac1-dialog.png')
  })

  // ================= AC2 勾选保存 =================
  await step('AC2', '三块各增选一项 → 保存 → body 断言（number 数组/去重/恰三键）→ toast → 弹窗关', async () => {
    const dlg = page.locator('.el-dialog', { hasText: '权限分配' }).last()
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
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await waitDialogGone(page, '权限分配')
    // route 层 body 断言：kb1、number 数组、去重、恰三键（契约 §3）
    assert(lastPutUrl === '/api/ai/rag/kb/1/perms', `PUT 应指向 kb1（回显后保存），实际 ${lastPutUrl}`)
    assert(lastPutBody != null, 'route 层应记录到 PUT body')
    assertEq(JSON.stringify(lastPutBody.role_ids), JSON.stringify([2, 5, 7]), `role_ids 应为 [2,5,7]，实际 ${JSON.stringify(lastPutBody.role_ids)}`)
    assertEq(JSON.stringify(lastPutBody.dept_ids), JSON.stringify([3, 4]), `dept_ids 应为 [3,4]，实际 ${JSON.stringify(lastPutBody.dept_ids)}`)
    assertEq(JSON.stringify(lastPutBody.user_ids), JSON.stringify([952, 953]), `user_ids 应为 [952,953]，实际 ${JSON.stringify(lastPutBody.user_ids)}`)
    for (const key of ['role_ids', 'dept_ids', 'user_ids']) {
      assert(lastPutBody[key].every((v) => Number.isInteger(v)), `${key} 应全为 number（AI 域入参，契约 §1 换轨）`)
      assertEq(new Set(lastPutBody[key]).size, lastPutBody[key].length, `${key} 应无重复（前端 Set 去重）`)
    }
    assertEq(Object.keys(lastPutBody).sort().join(','), 'dept_ids,role_ids,user_ids', 'body 应恰含三键（无多余字段）')
  })

  // ================= AC3 已失效占位 =================
  await step('AC3', 'kb2 回显含选项外 id → 「id:N（已失效）」占位 tag → 保存 body 保留已失效 id（最小惊讶）', async () => {
    const rowKb2 = page.locator('.el-card__body > .el-table .el-table__row', { hasText: '规章制度库' }).first()
    await rowKb2.getByRole('button', { name: '权限' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '权限分配' }).last()
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
    await shot(page, 'kbperm-ac3-invalid.png')
    // 直接保存（未做任何修改）：已失效 id 随集保留提交——打开即保存授权不变（最小惊讶语义）
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await waitDialogGone(page, '权限分配')
    assert(lastPutUrl === '/api/ai/rag/kb/2/perms', `PUT 应指向 kb2，实际 ${lastPutUrl}`)
    assertEq(JSON.stringify(lastPutBody.role_ids), JSON.stringify([2, 999]), `已失效 999 应保留提交，实际 ${JSON.stringify(lastPutBody.role_ids)}`)
    assertEq(JSON.stringify(lastPutBody.dept_ids), JSON.stringify([9999]), `已失效 9999 应保留提交，实际 ${JSON.stringify(lastPutBody.dept_ids)}`)
    assert(lastPutBody.role_ids.every((v) => Number.isInteger(v)), '占位 id 提交亦为 number（换轨一致）')
  })

  // ================= AC4 清空保存 =================
  await step('AC4', '移除全部勾选与占位 → 保存 body 三数组全空（契约 §3 清空授权语义）', async () => {
    const rowKb1 = page.locator('.el-card__body > .el-table .el-table__row', { hasText: '产品手册库' }).first()
    await rowKb1.getByRole('button', { name: '权限' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '权限分配' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await dlg.locator('.el-select').first().waitFor({ state: 'visible', timeout: 3000 })
    await page.waitForTimeout(600)
    // 清空两个 select 的全部 tag（close 后列表收缩，恒点 first）
    for (const idx of [0, 1]) {
      const closes = dlg.locator('.el-select').nth(idx).locator('.el-tag .el-tag__close')
      while ((await closes.count()) > 0) {
        await closes.first().click()
        await sleep(150)
      }
    }
    // 取消树全部勾选（AC2 后 kb1 勾选 3/4——checkbox 点击为翻转语义，checked 态点击即取消）
    for (const name of ['研发部', '市场部']) {
      const cb = dlg.locator('.el-tree-node__content', { hasText: name }).locator('.el-checkbox').first()
      if ((await cb.getAttribute('class')).includes('is-checked')) {
        await cb.click()
        await sleep(200)
      }
    }
    await shot(page, 'kbperm-ac4-cleared.png')
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await waitDialogGone(page, '权限分配')
    assert(lastPutUrl === '/api/ai/rag/kb/1/perms', `PUT 应指向 kb1，实际 ${lastPutUrl}`)
    assertEq(JSON.stringify(lastPutBody), JSON.stringify({ role_ids: [], dept_ids: [], user_ids: [] }), `清空保存 body 应三数组全空，实际 ${JSON.stringify(lastPutBody)}`)
    // 重开验证内存态回写（清空后回显三空集）
    await rowKb1.getByRole('button', { name: '权限' }).click()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await page.waitForTimeout(600)
    assertEq(await dlg.locator('.el-select').nth(0).locator('.el-tag').count(), 0, '清空后重开角色 select 应无 tag')
    assertEq(await dlg.locator('.invalid-tags .el-tag').count(), 0, '清空后重开应无占位 tag')
    await dlg.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '权限分配')
  })

  // ================= AC5 按钮权限 + AC6 列表可见性（mock 登录链，两 step 共享 ctx） =================
  // AC6 行集剧本：list-only 用户仅见 own+granted（契约 §4 服务端过滤行为的 mock 侧演绎）
  const viewerKbs = [
    { id: 21, name: `e2e_kb_perm_own_${TS}`, description: '自己创建', create_time: '2026-10-18 11:00:00' },
    { id: 22, name: 'e2e_kb_perm_granted', description: '角色授权命中', create_time: '2026-10-18 11:10:00' },
  ]
  const otherKb = { id: 23, name: 'e2e_kb_perm_other', description: '第三方库（不应可见）', create_time: '2026-10-18 11:20:00' }
  let listMode = 'plain' // plain=全量 2 库（AC5）；viewer=own+granted（AC6，第三方不可见）
  let ctx2 = null
  let page2 = null

  await step('AC5', 'mock 登录链（me 不含 ai:kb:update）→「权限/编辑/删除/新增」不可见、「文件管理」可见', async () => {
    ctx2 = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    page2 = await ctx2.newPage()
    h.attachListeners(page2)
    await page2.route('**/api/**', async (route) => {
      const req = route.request()
      const u = new URL(req.url())
      if (!u.pathname.startsWith('/api/')) {
        return route.fallback()
      }
      const p = u.pathname.replace(/^\/api/, '')
      // 登录链 mock：list-only 用户为剧本账号（真实环境不存在）
      if (p === '/sso/auth/login' && req.method() === 'POST') {
        return route.fulfill(json(R({ accessToken: 'e2e-mock-at', refreshToken: 'e2e-mock-rt', expiresIn: '7200' })))
      }
      if (p === '/sso/auth/me' && req.method() === 'GET') {
        return route.fulfill(json(R({ account: `e2e_perm_viewer_${TS}`, permissions: ['ai:kb:list'] })))
      }
      if (p === '/system/menu/user-nav' && req.method() === 'GET') {
        return route.fulfill(
          json(
            R([
              {
                id: '180', parentId: '0', name: 'AI 助手', type: 'M', path: '', icon: 'ChatDotRound', sort: 1,
                children: [{ id: '182', parentId: '180', name: '知识库', type: 'C', path: '/rag/kb', icon: 'Collection', sort: 1, children: [] }],
              },
            ]),
          ),
        )
      }
      if (p === '/ai/rag/kb/list' && req.method() === 'GET') {
        const rows = listMode === 'viewer' ? [...viewerKbs] : [...kbs]
        return route.fulfill(json(R({ total: rows.length, rows })))
      }
      const done = fulfillKbPermMock(route, req, u)
      if (done) return done
      return route.fulfill(json(R(null)))
    })
    const r = await login(page2, `e2e_perm_viewer_${TS}`, 'irrelevant-mock-pass')
    assert(r.ok, `mock 登录应成功: ${r.msg || ''}`)
    await page2.goto(`${BASE}/rag/kb`, { waitUntil: 'domcontentloaded' })
    await waitTableIdle(page2)
    await page2.locator('.el-card__body > .el-table .el-table__row').first().waitFor({ state: 'visible', timeout: 8000 })
    // v-perms 快照语义：me 无管理权限 → 四个管理按钮全部 DOM 移除
    const row2 = page2.locator('.el-card__body > .el-table .el-table__row').first()
    for (const label of ['权限', '编辑', '删除']) {
      assertEq(await row2.getByRole('button', { name: label }).count(), 0, `无 ai:kb:update/delete 快照：「${label}」按钮应不可见`)
    }
    assertEq(await page2.getByRole('button', { name: '新增知识库' }).count(), 0, '无 ai:kb:add：「新增知识库」应不可见')
    assertEq(await row2.getByRole('button', { name: '文件管理' }).count(), 1, '「文件管理」应可见（查询类不挂按钮权限）')
    await shot(page2, 'kbperm-ac5-no-update.png')
  })

  await step('AC6', '列表可见性（mock 层模拟 §4 修订）：list-only 用户仅见 own+granted 行，第三方库不可见', async () => {
    if (page2 === null) {
      throw new Error('AC5 前置失败：mock 登录页未建立')
    }
    // 同 ctx 换剧本：list 返回 §4 过滤后的行集（服务端过滤行为的 mock 侧演绎）
    listMode = 'viewer'
    await page2.reload({ waitUntil: 'domcontentloaded' })
    await waitTableIdle(page2)
    await page2.locator('.el-card__body > .el-table .el-table__row').first().waitFor({ state: 'visible', timeout: 8000 })
    const rowCount = await page2.locator('.el-card__body > .el-table .el-table__row').count()
    assertEq(rowCount, 2, `list-only 用户应仅见 own+granted 2 行（契约 §4），实际 ${rowCount}`)
    const names = (await page2.locator('.el-card__body > .el-table .el-table__row').allInnerTexts()).join('\n')
    assert(names.includes('e2e_kb_perm_own'), `应含 own 库行，实际:\n${names}`)
    assert(names.includes('e2e_kb_perm_granted'), `应含 granted 库行，实际:\n${names}`)
    assert(!names.includes(otherKb.name), `第三方库「${otherKb.name}」不应可见（§4 过滤）`)
    const totalText = (await page2.locator('.el-card .el-pagination__total').innerText()).trim()
    assert(totalText.includes('2'), `分页 total 应显示 2，实际 "${totalText}"`)
    await shot(page2, 'kbperm-ac6-viewer-list.png')
    await ctx2.close()
  })

  // ================= 证据核验 =================
  await step('AC-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应，实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  h.summary({
    extras: [
      `\n测试数据: 零后端写（kb perms 端点与三数据源端点全 mock 内存态；AC5/AC6 登录链 mock 剧本账号 e2e_perm_viewer_${TS}）；admin 种子账号只登录不改，种子菜单零触碰`,
      'mock 面: admin ctx 拦 /api/ai/** + subject-options + dept/tree（登录链真实网关）；AC5/AC6 ctx 拦 /api/**（含 sso 登录链）——cloud-base-ai 8010 未依赖',
    ],
  })
  await browser.close()
}
