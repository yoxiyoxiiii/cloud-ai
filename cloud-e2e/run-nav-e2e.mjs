/**
 * 业务动态路由 e2e（计划 2026-10-07 E2：N0-N6 + CLEANUP + N-VERIFY；harness 复用 lib/harness.mjs）
 * 2026-10-07 按钮级权限改造（计划 E1 / 设计 D7）：N5 重设计 + N2f/N4/N6/N1 增补 + N3 微适配
 *
 * 运行前提：后端 gateway 18080 / sso 9201（须为含 /sso/auth/me 版本，契约 2026-10-07-perms-api）/ system 9202（v2 契约版含 user-nav）已启动；前端 dev 5173 已启动
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 npm run e2e:nav）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码
 * 测试数据（删净纪律最高优先）：
 * - 角色/用户/角色标识全部 e2e 前缀+时间戳；绝不改 admin/种子账号；种子菜单（10/11/12/13/111…20/21/211）零触碰
 * - 结束删除测试用户与测试角色并断言删净（含 N5 意外落库兜底删除）
 * 核心断言（设计 D10 / 契约 §2 §3 + perms-api §4 §6）：
 * - N1 admin user-nav 形状逐字段（根 M 系统管理 + 3 个 C 带 path/icon；无 F；20/21 被剪）+ me 形状与恰 1 次
 * - N2 RBAC 闭环：角色仅绑"角色管理"分支 → 新用户侧边恰 '角色管理,工作台' 且角色页可加载
 * - N2f 按钮粒度：受限用户（快照 list+edit）表头新增/行内分配权限·删除隐藏、编辑可见
 * - N4 F5 刷新：路由/菜单/高亮保持 + user-nav 与 me 各恰 +1 + 按钮隐藏保持
 * - N5 两维时效：按钮已随登录快照隐藏（v-perms，DOM 移除）+ page.request 直连仍 body 403
 *   （服务层最终防线，双证据并存——防"前端隐藏"被绕过后无人兜底的回归）
 * - N6 admin 重登恢复全量菜单 + 角色页按钮全显（全量快照回归零影响）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, logoutViaUi, findRow, rowCells } = h

// ---------- 测试数据（只作用于 e2e 前缀测试数据，不碰 admin/种子） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const TEST_ROLE_KEY = `e2enav${stamp}`
const TEST_ROLE_NAME = `e2e导航${stamp}`
const TEST_ACCOUNT = `e2enav${stamp}`
const TEST_NICKNAME = 'E2E导航用户'
const TEST_PWD = 'e2ePass123' // 沿用既有 e2e 口令风格（run-e2e S10）
/** N5 两维时效探针角色（正常路径不落库；意外落库时 CLEANUP 兜底删除） */
const TEST_403_ROLE_KEY = `e2enav403${stamp}`
const TEST_403_ROLE_NAME = `e2e导航403${stamp}`

const ROLE_PATH = '/system/role'
const USER_PATH = '/system/user'
/** 种子菜单 id（契约 §2 示例/基线 SQL）：10 系统管理 M / 12 角色管理 C / 121 角色新增 F / 122 角色修改 F */
const SEED = { ROOT: '10', ROLE_C: '12', ROLE_ADD: '121', ROLE_EDIT: '122' }
/** 勾选目标 F 节点显示名（种子 122 角色修改——system:role:edit，非 add 保 N5 前提） */
const SEED_ROLE_EDIT_LABEL = '角色修改'

// ---------- 主流程（默认有头 + slowMo 300，与其余四脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

/** 非 /api/ 资产的 404 清单（favicon 环境噪音甄别用，M-VERIFY 同款） */
const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

/** waitForResponse 的 URL 匹配：r.url() 是含 origin 的完整地址，须比 pathname */
const apiPath = (url, pathname) => new URL(url).pathname === pathname

/** user-nav 调用计数（N4 增量断言用） */
const navCount = () => h.state.apiCalls.filter((c) => c.url === '/api/system/menu/user-nav').length

/** /sso/auth/me 调用计数（N1/N4 增量断言用——守卫并行原子门，每次全新引导恰 1 次） */
const meCount = () => h.state.apiCalls.filter((c) => c.url === '/api/sso/auth/me').length

/** 读取侧边全部菜单项文本（el-menu-item；M 目录标题在 el-sub-menu__title 不入此域）。
 *  等待用 .sidebar-menu：el-sub-menu 的内层 ul.el-menu--inline 也带 .el-menu 类，
 *  裸 .el-menu 命中 2 元素会触发 strict mode violation */
async function menuLabels() {
  await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
  const items = page.locator('.el-menu .el-menu-item')
  const n = await items.count()
  const labels = []
  for (let i = 0; i < n; i++) labels.push((await items.nth(i).innerText()).trim())
  return labels
}

/** 按权限标识列精确找角色行（roleKey 唯一锚点；跨页全量检索——run-role 同款） */
async function findRoleRowByKey(roleKey) {
  await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
  await waitTableIdle(page)
  for (let guard = 0; guard < 30; guard++) {
    const rows = page.locator('.el-table__row')
    const n = await rows.count()
    for (let i = 0; i < n; i++) {
      const cells = await rowCells(rows.nth(i))
      if (cells[1] === roleKey) return rows.nth(i)
    }
    const next = page.locator('.el-pagination .btn-next')
    if ((await next.count()) === 0 || !(await next.isEnabled())) return null
    await next.click()
    await waitTableIdle(page)
    await sleep(300)
  }
  return null
}

// ---------- 分配权限弹窗助手（run-role 同款子集：本脚本只点 F 与三态断言） ----------

/** 等分配权限弹窗分组布局渲染并回显落定（勾选设置发生在 loading 遮罩撤下前） */
async function waitTreeReady(dlg) {
  const t0 = Date.now()
  while (Date.now() - t0 < 10000) {
    if ((await dlg.locator('.perm-menu-row').count()) > 0) break
    await sleep(150)
  }
  const mask = dlg.locator('.el-loading-mask')
  const t1 = Date.now()
  while (Date.now() - t1 < 8000) {
    if ((await mask.count()) === 0 || !(await mask.first().isVisible())) break
    await sleep(150)
  }
  await sleep(400)
}

/** 按文本定位弹窗内权限 checkbox（F 的 label 内含 perms 灰字，故取精确或前缀匹配） */
async function findPermCheckBox(dlg, nodeText) {
  const boxes = dlg.locator('.perm-panel .el-checkbox')
  const n = await boxes.count()
  for (let i = 0; i < n; i++) {
    const box = boxes.nth(i)
    const label = ((await box.innerText()) || '').trim()
    if (label === nodeText || label.startsWith(nodeText)) return box
  }
  throw new Error(`未找到文本为 "${nodeText}" 的权限 checkbox`)
}

/** 权限 checkbox 三态（原生 input DOM 属性：checked / indeterminate） */
async function checkBoxState(dlg, nodeText) {
  const box = await findPermCheckBox(dlg, nodeText)
  return await box.locator('input.el-checkbox__original').evaluate((el) => ({ checked: el.checked, half: el.indeterminate }))
}

/** 点击权限 checkbox（点 label 根元素整体） */
async function clickPermCheckBox(dlg, nodeText) {
  const box = await findPermCheckBox(dlg, nodeText)
  await box.click()
}

try {
  // ================= N0 前置：admin 登录 =================
  await step('N0', 'admin 登录（前置）', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
    // 裸 .el-menu 会命中根 ul + el-sub-menu 内层 ul（strict violation），用 .sidebar-menu
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    log(`  登录落点: ${page.url()}`)
  })

  // ================= N1 admin 全量可见 + user-nav API 形状（契约 §2）+ me 形状（perms-api §2） =================
  await step('N1', 'admin user-nav 形状逐字段 + me 形状 + 侧边全量导航（M1 精确串复核动态源）', async () => {
    // 重载触发全新守卫流程：内存态清空 → 守卫并行 ensureLoaded → user-nav + me（模拟刷新/直链路径）
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/menu/user-nav'), { timeout: 15000 })
    const meRespP = page.waitForResponse((r) => apiPath(r.url(), '/api/sso/auth/me'), { timeout: 15000 })
    const meBefore = meCount()
    await page.reload({ waitUntil: 'domcontentloaded' })
    const resp = await respP
    const meResp = await meRespP
    const body = await resp.json()
    log(`  user-nav 接口: HTTP ${resp.status()} code=${body.code}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, 'user-nav 业务码应为 200')
    // me 形状（契约 perms-api §2）：account=admin + 全量快照（17 项：user6+role5+menu4+sso2）
    const meBody = await meResp.json()
    log(`  me 接口: HTTP ${meResp.status()} code=${meBody.code} account=${meBody.data.account} perms=${meBody.data.permissions.length} 项`)
    assertEq(meResp.status(), 200, 'me：HTTP 恒 200')
    assertEq(meBody.code, 200, 'me 业务码应为 200')
    assertEq(meBody.data.account, 'admin', 'me account 应为 admin')
    assert(Array.isArray(meBody.data.permissions), `me permissions 应为数组，实际 ${typeof meBody.data.permissions}`)
    assert(meBody.data.permissions.includes('system:role:add'), 'admin 快照应含 system:role:add')
    assert(meBody.data.permissions.includes('sso:online:list'), 'admin 快照应含 sso:online:list')
    const nav = body.data
    assert(Array.isArray(nav), `data 应为数组，实际 ${typeof nav}`)
    // 根级恰 1 节点：M 系统管理（icon Setting——契约 §2 示例）
    assertEq(nav.length, 1, `admin 根级应恰 1 节点，实际 ${nav.length}`)
    const root = nav[0]
    assertEq(root.type, 'M', `根节点 type 应为 M，实际 "${root.type}"`)
    assertEq(root.name, '系统管理', `根节点名称应为 系统管理，实际 "${root.name}"`)
    assertEq(root.icon, 'Setting', `根节点 icon 应为 Setting，实际 "${root.icon}"`)
    assertEq(root.parentId, '0', '根节点 parentId 应为 "0"')
    assert(typeof root.sort === 'number', 'sort 字段应为数值')
    // 3 个 C 子级：path 依次 /system/user /system/role /system/menu（种子 11/12/13 顺序）
    assertEq(root.children.length, 3, `系统管理子级应恰 3 个，实际 ${root.children.length}`)
    const cPaths = root.children.map((c) => c.path)
    const cNames = root.children.map((c) => c.name)
    log(`  C 子级: ${JSON.stringify(root.children.map((c) => ({ name: c.name, type: c.type, path: c.path, icon: c.icon })))}`)
    assertEq(cPaths.join(','), '/system/user,/system/role,/system/menu', `C 子级 path 应依次三页，实际 ${JSON.stringify(cPaths)}`)
    assertEq(cNames.join(','), '用户管理,角色管理,菜单管理', `C 子级名称应依次，实际 ${JSON.stringify(cNames)}`)
    for (const c of root.children) {
      assertEq(c.type, 'C', `子级 type 应为 C，实际 "${c.type}"`)
      assert(typeof c.icon === 'string' && c.icon.length > 0, `C 节点 icon 应非空，实际 "${c.icon}"`)
      assert(Array.isArray(c.children) && c.children.length === 0, 'C 叶子节点 children 应为空数组（契约 §2）')
    }
    // 递归全树：无 F 节点（nav 只出 M/C）；无 认证管理/在线用户（C21 无 path 不进导航 → M20 剪空）
    const allTypes = []
    const allNames = []
    const walk = (nodes) => {
      for (const n of nodes) {
        allTypes.push(n.type)
        allNames.push(n.name)
        if (n.children && n.children.length) walk(n.children)
      }
    }
    walk(nav)
    assert(allTypes.every((t) => t === 'M' || t === 'C'), `导航树不应含 F 节点，实际 ${JSON.stringify([...new Set(allTypes)])}`)
    assert(!allNames.includes('认证管理') && !allNames.includes('在线用户'), `无 path 的 C 与剪空 M 不应出现（20/21），实际 ${JSON.stringify(allNames)}`)
    // 侧边精确串（M1 同款断言复核动态源：sub-menu 子项 + 工作台尾挂 + default-openeds 展开）
    const labels = await menuLabels()
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,工作台', '侧边菜单顺序应为 用户管理→角色管理→菜单管理→工作台')
    // 嵌套结构证据：根 M 渲染为 el-sub-menu（侧边栏嵌套布局）
    assert((await page.locator('.el-menu .el-sub-menu').count()) >= 1, '根目录 M 应渲染为 el-sub-menu')
    await sleep(800) // 落定窗口内不应有额外 me（守卫并行门只拉一次，无重复请求）
    assertEq(meCount() - meBefore, 1, `本轮刷新 me 请求应恰 +1（与 user-nav 同批并行），实际 +${meCount() - meBefore}`)
    await shot(page, 'n1-admin-menu.png')
  })

  // ================= N2 RBAC 闭环（核心场景） =================
  await step('N2', 'RBAC 闭环：建角色仅绑角色管理 → 建用户绑角色 → 新用户侧边恰 角色管理,工作台 → 角色页加载', async () => {
    // ---- 2a. UI 建角色 ----
    log(`  测试角色: ${TEST_ROLE_NAME}（roleKey ${TEST_ROLE_KEY}）`)
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    await page.getByRole('button', { name: '新增角色' }).click()
    let dlg = page.locator('.el-dialog', { hasText: '新增角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('input[placeholder="请输入角色名称"]').fill(TEST_ROLE_NAME)
    await dlg.locator('input[placeholder="字母开头，如 ops"]').fill(TEST_ROLE_KEY)
    const respRole = page.waitForResponse((r) => apiPath(r.url(), '/api/system/role') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const r1 = await respRole
    const b1 = await r1.json()
    assertEq(b1.code, 200, `新增角色业务码应为 200，实际 ${b1.code}（${b1.msg || ''}）`)
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增角色')

    // ---- 2b. 分配权限：仅勾"角色管理"分支 ----
    // 语义说明（计划 E2 意图 × 分配弹窗 D2 实现的组合约束）：点 C 行=全选其全部 F
    // （AssignMenuDialog toggleC），会把 121(system:role:add) 一并授予，使 N5 的 403 前提失效；
    // 改点其 F"角色修改"(122)——C12 半选、M10 半选，两 id 随半选父语义入提交集合，
    // 效果 = 计划"仅勾选角色管理（半选自动带上系统管理）"且不含 add 权限
    let roleRow = await findRoleRowByKey(TEST_ROLE_KEY)
    assert(roleRow, '应能定位测试角色行')
    await roleRow.getByRole('button', { name: '分配权限' }).click()
    let permDlg = page.locator('.el-dialog', { hasText: '分配权限' }).last()
    await permDlg.waitFor({ state: 'visible', timeout: 8000 })
    await waitTreeReady(permDlg)
    await clickPermCheckBox(permDlg, SEED_ROLE_EDIT_LABEL)
    const cState = await checkBoxState(permDlg, '角色管理')
    const mState = await checkBoxState(permDlg, '系统管理')
    log(`  勾选后三态: 角色管理(C)=${JSON.stringify(cState)} 系统管理(M)=${JSON.stringify(mState)}`)
    assert(!cState.checked && cState.half, '角色管理（C）应半选（1/4 按钮）')
    assert(!mState.checked && mState.half, '系统管理（M）应半选（半选自动带上）')
    const respAssign = page.waitForResponse((r) => apiPath(r.url(), '/api/system/role/menu') && r.request().method() === 'PUT', { timeout: 15000 })
    await permDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const r2 = await respAssign
    const b2 = await r2.json()
    const menuIds = r2.request().postDataJSON().menuIds
    log(`  分配接口: HTTP ${r2.status()} code=${b2.code}，menuIds=${JSON.stringify(menuIds)}`)
    assertEq(b2.code, 200, '分配业务码应为 200')
    assertEq(menuIds.length, 3, `提交应恰 3 项（半选父 10/12 + 叶子 122），实际 ${JSON.stringify(menuIds)}`)
    assert([SEED.ROOT, SEED.ROLE_C, SEED.ROLE_EDIT].every((id) => menuIds.includes(id)), `提交应含 ${SEED.ROOT}/${SEED.ROLE_C}/${SEED.ROLE_EDIT}`)
    assert(!menuIds.includes(SEED.ROLE_ADD), `提交不应含 角色新增 ${SEED.ROLE_ADD}（N5 的 403 前提）`)
    await waitToast(page, '分配成功')
    await waitDialogGone(page, '分配权限')

    // ---- 2c. UI 建用户并分配该角色（绑定先于登录——权限快照在登录时生成，契约 §3） ----
    log(`  测试用户: ${TEST_ACCOUNT}`)
    await page.goto(`${BASE}${USER_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    await page.getByRole('button', { name: '新增用户' }).click()
    dlg = page.locator('.el-dialog', { hasText: '新增用户' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('input[placeholder="请输入账号"]').fill(TEST_ACCOUNT)
    await dlg.locator('input[placeholder="请输入昵称"]').fill(TEST_NICKNAME)
    await dlg.locator('input[placeholder="6-32 位"]').fill(TEST_PWD)
    const respUser = page.waitForResponse((r) => apiPath(r.url(), '/api/system/user') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const r3 = await respUser
    const b3 = await r3.json()
    assertEq(b3.code, 200, `新增用户业务码应为 200，实际 ${b3.code}（${b3.msg || ''}）`)
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增用户')
    let userRow = await findRow(page, TEST_ACCOUNT, { path: USER_PATH })
    assert(userRow, '列表应出现测试用户行')
    await userRow.getByRole('button', { name: '分配角色' }).click()
    dlg = page.locator('.el-dialog', { hasText: '分配角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    const t0 = Date.now()
    while (Date.now() - t0 < 8000) {
      if ((await dlg.locator('.el-checkbox').count()) > 0) break
      await sleep(150)
    }
    const roleBox = dlg.locator('.el-checkbox', { hasText: TEST_ROLE_NAME })
    assert((await roleBox.count()) >= 1, `角色候选应含 ${TEST_ROLE_NAME}`)
    await roleBox.first().click()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '分配成功')
    await waitDialogGone(page, '分配角色')

    // ---- 2d. 登出 → 新用户登录：侧边恰 角色管理,工作台 ----
    await logoutViaUi(page)
    const r = await login(page, TEST_ACCOUNT, TEST_PWD)
    assert(r.ok, `新用户登录应成功: ${r.msg || ''}`)
    const labels = await menuLabels()
    log(`  新用户菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '角色管理,工作台', `受限用户侧边应恰 角色管理,工作台，实际 ${JSON.stringify(labels)}`)
    assert(!labels.includes('用户管理') && !labels.includes('菜单管理'), '不应出现未授权菜单')
    await shot(page, 'n2-nav-user.png')

    // ---- 2e. 点角色管理 → /system/role 表格加载成功（system:role:list 在登录快照内） ----
    await page.locator('.el-menu-item', { hasText: '角色管理' }).click()
    await page.waitForURL('**/system/role', { timeout: 8000 })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '角色管理', '/system/role 下角色管理应高亮')
    const bc = await breadcrumbTexts(page)
    assertEq(bc.join('/'), '首页/角色管理', '面包屑应为 首页/角色管理')
    log('  受限用户角色页加载 ✔（表格行可见）')

    // ---- 2f. 按钮级权限粒度（契约 perms-api §4/§6；设计 D7 N2f）：快照=list+edit ----
    // 同页不同按钮不同显隐 = 粒度到按钮而非页面；绑定先于登录（两维同设，契约 §5 e2e 纪律允许断言）
    await sleep(500) // 指令 mounted 移除落定
    assertEq(await page.getByRole('button', { name: '新增角色' }).count(), 0, '表头"新增角色"应隐藏（system:role:add 不在快照，DOM 移除）')
    assert((await page.locator('.el-table__row button', { hasText: '编辑' }).count()) > 0, '行内"编辑"应可见（system:role:edit 在快照）')
    assertEq(await page.locator('.el-table__row button', { hasText: '分配权限' }).count(), 0, '行内"分配权限"应隐藏（system:role:assignMenu 不在快照）')
    assertEq(await page.locator('.el-table__row button', { hasText: '删除' }).count(), 0, '行内"删除"应隐藏（system:role:remove 不在快照）')
    await shot(page, 'n2f-perm-granularity.png')
  })

  // ================= N3 直链无权限兜底 =================
  await step('N3', '直链无权限：新用户 goto /system/menu → NotFound 兜底 → 返回工作台', async () => {
    await page.goto(`${BASE}/system/menu`, { waitUntil: 'domcontentloaded' })
    // 未注册路由 → catchAll NotFound（Layout 内渲染；非 menu-error——导航加载是成功的）
    const sub = page.locator('.el-result__subtitle')
    await sub.waitFor({ state: 'visible', timeout: 10000 })
    const subText = (await sub.innerText()).trim()
    log(`  兜底页文案: "${subText}"`)
    assert(subText.includes('页面不存在或无访问权限'), `兜底文案应含"页面不存在或无访问权限"，实际 "${subText}"`)
    // MenuError 文案改"菜单或权限加载失败"后原文本断言已失效（设计 D7 微适配）——语义更直接的 URL 判断
    assert(!page.url().includes('menu-error'), `不应落 menu-error（导航加载成功，只是无权限），实际 ${page.url()}`)
    assertEq(new URL(page.url()).pathname, '/system/menu', 'URL 应保持 /system/menu（不重定向）')
    // Layout 兜底渲染证据：侧边仍是受限菜单
    const labels = await menuLabels()
    assertEq(labels.join(','), '角色管理,工作台', 'NotFound 页侧边应保持受限菜单（Layout 内渲染）')
    await shot(page, 'n3-notfound.png')
    await page.getByRole('button', { name: '返回工作台' }).click()
    await page.waitForURL('**/dashboard', { timeout: 8000 })
    log(`  返回工作台: ${page.url()}`)
  })

  // ================= N4 F5 刷新保持 =================
  await step('N4', 'F5 刷新保持：/system/role reload → 路由/菜单/高亮保持 + user-nav/me 各恰 +1 + 按钮隐藏保持', async () => {
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const before = navCount()
    const meBefore = meCount()
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/menu/user-nav'), { timeout: 15000 })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await respP
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 15000 })
    await waitTableIdle(page)
    assertEq(new URL(page.url()).pathname, ROLE_PATH, '刷新后应仍在 /system/role（动态路由重注册）')
    const active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '角色管理', '刷新后菜单高亮应保持')
    const bc = await breadcrumbTexts(page)
    assertEq(bc.join('/'), '首页/角色管理', '刷新后面包屑应保持')
    const labels = await menuLabels()
    assertEq(labels.join(','), '角色管理,工作台', '刷新后侧边菜单应保持')
    await sleep(800) // 落定窗口内不应有额外 user-nav / me（无重复拉取）
    assertEq(navCount() - before, 1, `本轮刷新 user-nav 请求应恰 +1（内存态重建），实际 +${navCount() - before}`)
    assertEq(meCount() - meBefore, 1, `本轮刷新 me 请求应恰 +1（快照重取，与 user-nav 同批并行），实际 +${meCount() - meBefore}`)
    // 按钮隐藏保持（perms-api §4：F5 守卫重拉，快照语义不变——显隐不受刷新影响）
    assertEq(await page.getByRole('button', { name: '新增角色' }).count(), 0, '刷新后表头"新增角色"应保持隐藏')
    await shot(page, 'n4-after-reload.png')
  })

  // ================= N5 两维时效语义（按钮随快照隐藏 + 后端 403 兜底，双证据） =================
  await step('N5', '两维时效：受限用户"新增角色"按钮已隐藏（v-perms）+ 直连 API 仍 body 403（最终防线）', async () => {
    // 前端证据：按钮随登录快照隐藏（perms-api §4：显隐=快照，DOM 移除；原"可见可点提交 403"路径已删）
    await sleep(500) // 指令 mounted 移除落定
    assertEq(await page.getByRole('button', { name: '新增角色' }).count(), 0, '表头"新增角色"应随快照隐藏（system:role:add 不在快照）')
    // 后端证据：绕过 UI 直连 API（page.request 不经 page 网络事件，不污染 N-VERIFY 统计）——
    // 前端隐藏不是防线本身，@PreAuthorize 403 兜底证据必须并存（缺一即回归，设计 D7）
    const auth = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth') || 'null'))
    assert(auth?.accessToken, '应能从 localStorage 取到受限用户 accessToken（浏览器态读取，黑盒纪律允许）')
    const resp = await page.request.post(`${BASE}/api/system/role`, {
      headers: { Authorization: `Bearer ${auth.accessToken}` },
      data: { name: TEST_403_ROLE_NAME, roleKey: TEST_403_ROLE_KEY },
    })
    const body = await resp.json()
    log(`  直连新增接口: HTTP ${resp.status()} body.code=${body.code} msg="${body.msg}"`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200（403 在 body；page.request 流量不入 page 网络统计）')
    assertEq(body.code, 403, '无 system:role:add 权限直连提交应 body 403（@PreAuthorize 最终防线）')
    assert(typeof body.msg === 'string' && body.msg.length > 0, `403 msg 应非空，实际 "${body.msg}"`)
    // 列表不新增该行（全表检索；N5 若意外成功落库由 CLEANUP 兜底删净）
    const leaked = await findRoleRowByKey(TEST_403_ROLE_KEY)
    assert(leaked === null, `403 后角色列表不应新增 ${TEST_403_ROLE_KEY}`)
    await shot(page, 'n5-hidden-and-403.png')
  })

  // ================= N6 登出清态 + admin 恢复 =================
  await step('N6', '登出清态：新用户登出 → admin 登录侧边恢复全量四项 + 角色页按钮全显（三件套清态重建）', async () => {
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 重新登录应成功: ${r.msg || ''}`)
    const labels = await menuLabels()
    log(`  admin 恢复菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,工作台', 'admin 重登应恢复全量四项（登录页 reset 清态 + 守卫按新账号重建）')
    // 按钮级权限回归（perms-api §4：admin 全量快照 → 全显；受限快照零残留——三件套 reset 生效证据）
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    await sleep(500)
    assertEq(await page.getByRole('button', { name: '新增角色' }).count(), 1, 'admin 重登后表头"新增角色"应可见')
    assert((await page.locator('.el-table__row button', { hasText: '编辑' }).count()) > 0, 'admin 行内"编辑"应可见')
    assert((await page.locator('.el-table__row button', { hasText: '分配权限' }).count()) > 0, 'admin 行内"分配权限"应可见')
    assert((await page.locator('.el-table__row button', { hasText: '删除' }).count()) > 0, 'admin 行内"删除"应可见')
    await shot(page, 'n6-admin-restored.png')
  })

  // ================= CLEANUP 删净（含 N5 意外落库兜底） =================
  await step('CLEANUP', '删净：测试用户 → 测试角色（确认框含解绑）→ 断言删净 + 无 e2e 残留', async () => {
    // ---- 删测试用户 ----
    let userRow = await findRow(page, TEST_ACCOUNT, { path: USER_PATH })
    assert(userRow, '应能定位测试用户行')
    await userRow.getByRole('button', { name: '删除' }).click()
    let box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const userBoxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
    assert(userBoxText.includes(TEST_ACCOUNT), `删除确认框应含账号 ${TEST_ACCOUNT}，实际 "${userBoxText}"`)
    await box.locator('.el-message-box__btns .el-button--primary').click()
    await waitToast(page, '删除成功')
    await sleep(800)
    assert((await findRow(page, TEST_ACCOUNT)) === null, `删除后全表不应再有 ${TEST_ACCOUNT}`)
    // ---- 删测试角色（确认框含解绑提示——用户已删，关系级联解除文案仍应出现） ----
    let roleRow = await findRoleRowByKey(TEST_ROLE_KEY)
    assert(roleRow, '应能定位测试角色行')
    await roleRow.getByRole('button', { name: '删除' }).click()
    box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const roleBoxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
    assert(roleBoxText.includes(TEST_ROLE_NAME), `删除确认框应含角色名 ${TEST_ROLE_NAME}，实际 "${roleBoxText}"`)
    assert(roleBoxText.includes('解除'), `确认框文案应含"解除"绑定提示，实际 "${roleBoxText}"`)
    await box.locator('.el-message-box__btns .el-button--primary').click()
    await waitToast(page, '删除成功')
    await sleep(800)
    assert((await findRoleRowByKey(TEST_ROLE_KEY)) === null, `删除后全表不应再有 ${TEST_ROLE_KEY}`)
    // ---- N5 意外落库兜底（正常路径不存在；存在即删，保证复跑两轮均绿） ----
    const leak403 = await findRoleRowByKey(TEST_403_ROLE_KEY)
    if (leak403) {
      log(`  [兜底] N5 探针角色 ${TEST_403_ROLE_KEY} 意外存在，执行删除`)
      await leak403.getByRole('button', { name: '删除' }).click()
      box = page.locator('.el-message-box')
      await box.waitFor({ state: 'visible', timeout: 8000 })
      await box.locator('.el-message-box__btns .el-button--primary').click()
      await waitToast(page, '删除成功')
      await sleep(800)
      assert((await findRoleRowByKey(TEST_403_ROLE_KEY)) === null, '兜底删除后 403 探针角色应删净')
    }
    // ---- 残留核验：用户表/角色表无 e2e 残留 ----
    await page.goto(`${BASE}${USER_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    let residue = 0
    for (let guard = 0; guard < 30; guard++) {
      residue += await page.locator('.el-table__row', { hasText: 'e2e' }).count()
      const next = page.locator('.el-pagination .btn-next')
      if ((await next.count()) === 0 || !(await next.isEnabled())) break
      await next.click()
      await waitTableIdle(page)
      await sleep(300)
    }
    assertEq(residue, 0, `用户表不应残留 e2e 前缀行，实际 ${residue}`)
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    residue = await page.locator('.el-table__row', { hasText: 'e2e' }).count()
    assertEq(residue, 0, `角色表不应残留 e2e 前缀行，实际 ${residue}`)
    await shot(page, 'nav-cleanup-final.png')
  })

  // ================= 证据核验：无 console error / pageerror / >=400 / 网络失败 =================
  await step('N-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败（403 在 body 不入统计）', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（M-VERIFY 同款白名单）
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    const noiseFree404 = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noiseFree404.length, 0, `非 favicon 的资产 404 不应存在，实际 ${JSON.stringify(asset404)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（N5 的 403 为 page.request 直连，不进 page 网络事件），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({
    extras: [
      `\n测试数据: 角色 ${TEST_ROLE_KEY}（${TEST_ROLE_NAME}）/ 用户 ${TEST_ACCOUNT} / N5 探针 ${TEST_403_ROLE_KEY}（应均已在 CLEANUP 删净）`,
      '种子账号 admin 与种子菜单（10/11/12/13/111…20/21/211）零触碰；权限快照时效语义（绑定先于登录，契约 §3）',
    ],
  })
  await browser.close()
}
