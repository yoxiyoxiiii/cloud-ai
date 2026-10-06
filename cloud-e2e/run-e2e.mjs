/**
 * cloud-web 试点 T3/T4/T5 浏览器自动化补测（脚本路径，playwright + 本机 Chrome channel）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202 已启动；前端 dev 5173 已启动（/api 代理 18080）
 * 运行：cd cloud-e2e && npm run e2e（默认有头；无头 npm run e2e:headless）
 * 目标入口：E2E_BASE_URL 环境变量覆盖（默认 http://localhost:5173）
 * 截图输出：cloud-e2e/artifacts/*.png
 * 测试数据：账号 e2e+时间戳（仅作用于该测试账号，不改 admin；结束时删除该账号）
 * 公共工具已抽 lib/harness.mjs（设计 D5）——本脚本只保留用户管理场景本体
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, logoutViaUi, findRow, rowCells } = h

// ---------- 测试数据（只作用于测试账号） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const TEST_ACCOUNT = `e2e${stamp}`
const TEST_NICKNAME = 'E2E测试用户'
const TEST_NICKNAME_V2 = 'E2E测试用户v2'
const TEST_PWD = 'e2ePass123'
const TEST_PWD_NEW = 'e2eNew456'

// ---------- 主流程 ----------
// 主控要求：默认有头模式（用户可在本机看到 UI 效果）+ slowMo 300；--headless 或 E2E_HEADLESS=1 可切回无头
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

const loginCallCount = () => h.state.apiCalls.filter((c) => c.url.includes('/sso/auth/login')).length

try {
  // ================= S1 无 token 访问受保护页 → 跳登录（带 redirect） =================
  await step('S1', '无 token 直访 /system/user → 重定向 /login（带 redirect 参数）', async () => {
    await page.goto(`${BASE}/system/user`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForURL('**/login**', { timeout: 10000 })
    const url = new URL(page.url())
    assertEq(url.pathname, '/login', '应跳转到 /login')
    assertEq(url.searchParams.get('redirect'), '/system/user', 'redirect 参数应为 /system/user')
    await page.locator('.login-card').waitFor({ state: 'visible', timeout: 5000 })
    await page.locator('button.login-submit').waitFor({ state: 'visible', timeout: 5000 })
    await shot(page, 's01-login-page.png')
  })

  // ================= S2 空提交必填校验，不发请求 =================
  await step('S2', '登录表单空提交 → 内联必填错误，不发请求', async () => {
    const before = loginCallCount()
    await page.locator('button.login-submit').click()
    await sleep(500)
    const errs = page.locator('.el-form-item__error')
    const texts = []
    const n = await errs.count()
    for (let i = 0; i < n; i++) texts.push((await errs.nth(i).innerText()).trim())
    assert(texts.includes('请输入账号'), `应出现"请输入账号"错误，实际: ${JSON.stringify(texts)}`)
    assert(texts.includes('请输入密码'), `应出现"请输入密码"错误，实际: ${JSON.stringify(texts)}`)
    const after = loginCallCount()
    assertEq(after, before, '空提交不应发出登录请求')
    await shot(page, 's02-login-empty-errors.png')
  })

  // ================= S3 错误密码 → 内联错误，停留登录页 =================
  await step('S3', '错误密码登录 → 内联错误提示，停留 /login', async () => {
    await page.locator('.login-card input[placeholder="请输入账号"]').fill('admin')
    await page.locator('.login-card input[placeholder="请输入密码"]').fill('wrong123x')
    const respP = page.waitForResponse((r) => r.url().includes('/api/sso/auth/login'), { timeout: 15000 })
    await page.locator('button.login-submit').click()
    const resp = await respP
    const body = await resp.json()
    log(`  登录接口: HTTP ${resp.status()}, body.code=${body.code}, msg="${body.msg}"`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 2001, '错密码业务码应为 2001')
    await page.locator('.login-alert').waitFor({ state: 'visible', timeout: 8000 })
    const alertText = (await page.locator('.login-alert').innerText()).trim()
    log(`  内联错误文案: "${alertText}"`)
    assert(alertText.includes('账号或密码错误'), `错误文案应含"账号或密码错误"，实际 "${alertText}"`)
    assert(new URL(page.url()).pathname === '/login', '应停留登录页')
    // 全局 toast 不应出现（skipErrorMessage 设计）
    const toast = page.locator('.el-message')
    assertEq(await toast.count(), 0, '不应弹全局错误 toast')
    await shot(page, 's03-login-failed.png')
  })

  // ================= S4 正确登录 → 回跳 redirect 目标 =================
  await step('S4', 'admin/admin123 登录 → 回跳 /system/user，Layout 渲染，顶栏 admin', async () => {
    await page.locator('.login-card input[placeholder="请输入账号"]').fill('admin')
    await page.locator('.login-card input[placeholder="请输入密码"]').fill('admin123')
    const respP = page.waitForResponse((r) => r.url().includes('/api/sso/auth/login'), { timeout: 15000 })
    await page.locator('button.login-submit').click()
    const resp = await respP
    const body = await resp.json()
    log(`  登录接口: HTTP ${resp.status()}, body.code=${body.code}`)
    assertEq(body.code, 200, '登录业务码应为 200')
    assert(body.data?.accessToken, '登录响应应含 accessToken')
    await page.waitForURL('**/system/user', { timeout: 15000 })
    await page.locator('.el-menu').waitFor({ state: 'visible', timeout: 8000 })
    const accountText = (await page.locator('.navbar-account').innerText()).trim()
    assert(accountText.includes('admin'), `顶栏应显示 admin，实际 "${accountText}"`)
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    const stored = await page.evaluate(() => localStorage.getItem('cloud-web:auth'))
    assert(stored && JSON.parse(stored).accessToken, 'localStorage 应写入 accessToken')
    await shot(page, 's04-layout.png')
  })

  // ================= S5 退出登录 → 清 token 回登录页；无 token 再访受保护页仍被拦 =================
  await step('S5', '顶栏退出登录 → /login 且 token 清除；退出后直访受保护页仍被拦', async () => {
    await logoutViaUi(page)
    const stored = await page.evaluate(() => localStorage.getItem('cloud-web:auth'))
    assertEq(stored, null, '退出后 localStorage cloud-web:auth 应被清除')
    await page.goto(`${BASE}/system/user`, { waitUntil: 'domcontentloaded' })
    await page.waitForURL('**/login**', { timeout: 8000 })
    const url = new URL(page.url())
    assertEq(url.pathname, '/login', '退出后访问受保护页应被拦回 /login')
    assertEq(url.searchParams.get('redirect'), '/system/user', '拦截时应带 redirect')
    await shot(page, 's05-logout-guard.png')
  })

  // ================= S6 侧边菜单渲染/高亮 + 面包屑 =================
  await step('S6', '侧边菜单渲染与点击高亮；面包屑随路由变化', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `登录应成功: ${r.msg || ''}`)
    await page.waitForURL('**/system/user', { timeout: 10000 })
    const menuItems = page.locator('.el-menu .el-menu-item')
    const count = await menuItems.count()
    const labels = []
    for (let i = 0; i < count; i++) labels.push((await menuItems.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assert(labels.includes('用户管理') && labels.includes('工作台'), `菜单应含 用户管理/工作台，实际 ${JSON.stringify(labels)}`)
    let active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '用户管理', '/system/user 下用户管理应高亮')
    let bc = await breadcrumbTexts(page)
    log(`  面包屑(/system/user): ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/用户管理', '用户管理页面包屑应为 首页/用户管理')
    // 切到工作台
    await page.locator('.el-menu-item', { hasText: '工作台' }).click()
    await page.waitForURL('**/dashboard', { timeout: 8000 })
    active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '工作台', '/dashboard 下工作台应高亮')
    bc = await breadcrumbTexts(page)
    log(`  面包屑(/dashboard): ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/工作台', '工作台页面包屑应为 首页/工作台')
    await page.locator('.el-menu-item', { hasText: '用户管理' }).click()
    await page.waitForURL('**/system/user', { timeout: 8000 })
    await shot(page, 's06-menu-breadcrumb.png')
  })

  // ================= S7 F5 刷新保持登录态 =================
  await step('S7', '/system/user 刷新（F5）→ 登录态保持，菜单/面包屑/表格恢复', async () => {
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 15000 })
    const url = new URL(page.url())
    assertEq(url.pathname, '/system/user', '刷新后应仍在 /system/user（不被拦回登录）')
    const active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '用户管理', '刷新后菜单高亮应保持')
    const bc = await breadcrumbTexts(page)
    assertEq(bc.join('/'), '首页/用户管理', '刷新后面包屑应保持')
    const stored = await page.evaluate(() => localStorage.getItem('cloud-web:auth'))
    assert(stored, '刷新后 storage 登录态应存在')
    await shot(page, 's07-after-reload.png')
  })

  // ================= S8 表格加载/列渲染/分页 =================
  let tableTotal = 0
  let bulkUserIds = []
  await step('S8', '表格列渲染、分页 total 与条数、翻页', async () => {
    await waitTableIdle(page)
    const headerCells = page.locator('.el-table__header-wrapper th')
    const hn = await headerCells.count()
    const headers = []
    for (let i = 0; i < hn; i++) {
      headers.push(((await headerCells.nth(i).innerText()) || '').trim())
    }
    log(`  表头: ${JSON.stringify(headers)}`)
    for (const col of ['账号', '昵称', '状态', '创建人', '创建时间', '更新人', '更新时间', '操作']) {
      assert(headers.includes(col), `表头应含"${col}"，实际 ${JSON.stringify(headers)}`)
    }
    const rowCount = await page.locator('.el-table__row').count()
    log(`  当前页行数: ${rowCount}`)
    assert(rowCount >= 1 && rowCount <= 10, `行数应在 1-10，实际 ${rowCount}`)
    // 首行数据形态
    const first = await rowCells(page.locator('.el-table__row').first())
    log(`  首行: ${JSON.stringify(first.slice(0, 5))}`)
    assert(first[0].length > 0, '账号列应有值')
    const tagText = first[2]
    assert(['正常', '停用'].includes(tagText), `状态列应为 正常/停用 tag，实际 "${tagText}"`)
    assert(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(first[4]) || first[4] === '-', `创建时间应为 yyyy-MM-dd HH:mm:ss，实际 "${first[4]}"`)
    // 分页 total
    const totalText = (await page.locator('.el-pagination__total').innerText()).trim()
    tableTotal = parseInt((totalText.match(/\d+/) || ['0'])[0], 10)
    log(`  分页 total 文案: "${totalText}"（解析=${tableTotal}）`)
    assert(tableTotal >= rowCount, `total(${tableTotal}) 应 >= 当前页行数(${rowCount})`)
    const tagClass = await page.locator('.el-table__row').first().locator('.el-tag').getAttribute('class')
    log(`  首行状态 tag class: ${tagClass}`)
    await shot(page, 's08-user-table.png')
    if (tableTotal > 10) {
      await verifyPage2(page, 's08-page2.png')
    } else {
      log('  total<=10，暂无第 2 页——S8b 将构造数据后复验翻页')
    }
  })

  // ---------- S8b：total<=10 时批量构造数据验证翻页（用 admin token 经页面 fetch 造/删，全程 e2e 前缀账号） ----------
  async function verifyPage2(page, shotName) {
    const firstPageAccount = (await rowCells(page.locator('.el-table__row').first()))[0]
    await page.locator('.el-pagination .btn-next').click()
    await waitTableIdle(page)
    await sleep(400)
    const activePage = (await page.locator('.el-pager li.is-active').innerText()).trim()
    assertEq(activePage, '2', '翻页后第 2 页应激活')
    const p2Count = await page.locator('.el-table__row').count()
    const second = await rowCells(page.locator('.el-table__row').first())
    log(`  第2页: 行数=${p2Count}，首行=${second[0]}（第1页首行 ${firstPageAccount}）`)
    assert(second[0] !== firstPageAccount, '翻页后数据应变化')
    if (shotName) await shot(page, shotName)
    // 回第 1 页
    await page.locator('.el-pager li', { hasText: '1' }).first().click()
    await waitTableIdle(page)
    return p2Count
  }

  await step('S8b', '构造 total>10 数据 → 翻页可用（分页控件真实分页）', async () => {
    if (tableTotal > 10) {
      log('  首轮已验过翻页，跳过构造')
      return
    }
    // 用当前 admin 会话经页面内 fetch 批量新增（走同一条 /api 代理链路）
    const created = await page.evaluate(async (args) => {
      const { prefix, pwd } = args
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const out = []
      for (let i = 1; i <= 10; i++) {
        const account = `${prefix}${String(i).padStart(2, '0')}`
        const res = await fetch('/api/system/user', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify({ account, nickname: `E2E分页${String(i).padStart(2, '0')}号`, password: pwd, status: 0 }),
        })
        const body = await res.json()
        out.push({ account, code: body.code, id: body.data })
      }
      return out
    }, { prefix: `e2epg${stamp}`, pwd: 'e2ePage123' })
    for (const c of created) assert(c.code === 200, `批量造数 ${c.account} 应成功，code=${c.code}`)
    bulkUserIds = created.map((c) => c.id)
    log(`  批量造数 10 个账号（${`e2epg${stamp}`}01-10）全部成功，id=${JSON.stringify(bulkUserIds)}`)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const totalText2 = (await page.locator('.el-pagination__total').innerText()).trim()
    const newTotal = parseInt((totalText2.match(/\d+/) || ['0'])[0], 10)
    log(`  造数后 total 文案: "${totalText2}"（解析=${newTotal}）`)
    assert(newTotal === tableTotal + 10, `total 应从 ${tableTotal} 变为 ${tableTotal + 10}，实际 ${newTotal}`)
    const p1Rows = await page.locator('.el-table__row').count()
    assertEq(p1Rows, 10, '第 1 页应满 10 行')
    const p2Rows = await verifyPage2(page, 's08b-page2.png')
    assertEq(p2Rows, newTotal - 10, `第 2 页应剩 ${newTotal - 10} 行`)
  })

  // ================= S9 搜索过滤（视页面实现） =================
  {
    const searchBtn = await page.getByRole('button', { name: '搜索' }).count()
    const resetBtn = await page.getByRole('button', { name: '重置' }).count()
    log(`[S9] 搜索按钮=${searchBtn}，重置按钮=${resetBtn}`)
    h.state.results.push({
      id: 'S9',
      name: '搜索过滤（按账号/昵称）',
      status: 'SKIP',
      detail: `页面未实现搜索 UI（搜索按钮 ${searchBtn}/重置按钮 ${resetBtn}）；与契约现状一致——/system/user/page 无账号/昵称查询参数（api/user.ts 注明"契约现状"），计划 T5 工具栏仅含"新增用户"按钮。属契约缺口而非前端缺陷`,
    })
  }

  // ================= S10 新增用户 =================
  await step('S10', '新增用户：空提交必填错误 → 填表成功 → 列表出现新行', async () => {
    log(`  测试账号: ${TEST_ACCOUNT}`)
    await page.getByRole('button', { name: '新增用户' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增用户' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await shot(page, 's10-add-dialog.png')
    // 空提交
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    const errs = dlg.locator('.el-form-item__error')
    const texts = []
    const n = await errs.count()
    for (let i = 0; i < n; i++) texts.push((await errs.nth(i).innerText()).trim())
    log(`  空提交错误: ${JSON.stringify(texts)}`)
    for (const t of ['请输入账号', '请输入昵称', '请输入初始密码']) {
      assert(texts.includes(t), `空提交应报"${t}"，实际 ${JSON.stringify(texts)}`)
    }
    await shot(page, 's10-add-dialog-errors.png')
    // 填表提交
    await dlg.locator('input[placeholder="请输入账号"]').fill(TEST_ACCOUNT)
    await dlg.locator('input[placeholder="请输入昵称"]').fill(TEST_NICKNAME)
    await dlg.locator('input[placeholder="6-32 位"]').fill(TEST_PWD)
    const respP = page.waitForResponse((r) => r.url().includes('/api/system/user') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    log(`  新增接口: HTTP ${resp.status()} body=${(await resp.text()).slice(0, 120)}`)
    await waitToast(page, '新增成功')
    log('  ElMessage: 新增成功')
    await waitDialogGone(page, '新增用户')
    const row = await findRow(page, TEST_ACCOUNT)
    assert(row, `列表应出现新行 ${TEST_ACCOUNT}`)
    const cells = await rowCells(row)
    log(`  新行: ${JSON.stringify(cells.slice(0, 5))}`)
    assertEq(cells[0], TEST_ACCOUNT, '新行账号应为测试账号')
    assertEq(cells[1], TEST_NICKNAME, '新行昵称应为测试昵称')
    assertEq(cells[2], '正常', '新行状态应为 正常')
    await shot(page, 's10-row-created.png')
  })

  // ================= S11 编辑用户 =================
  await step('S11', '编辑：改昵称+停用生效；account/password 不可改', async () => {
    let row = await findRow(page, TEST_ACCOUNT)
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '编辑' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '编辑用户' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // account 只读
    const accountInput = dlg.locator('.el-form-item', { hasText: '账号' }).locator('input')
    assert(await accountInput.isDisabled(), '编辑弹窗 account 输入框应为 disabled')
    assertEq(await accountInput.inputValue(), TEST_ACCOUNT, '编辑弹窗应回显原账号')
    // 无密码字段
    const pwdLabel = await dlg.locator('.el-form-item__label', { hasText: '密码' }).count()
    assertEq(pwdLabel, 0, '编辑弹窗不应出现密码字段')
    // 改昵称 + 停用
    await dlg.locator('input[placeholder="请输入昵称"]').fill(TEST_NICKNAME_V2)
    await dlg.locator('.el-radio', { hasText: '停用' }).click()
    await shot(page, 's11-edit-dialog.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑用户')
    row = await findRow(page, TEST_ACCOUNT)
    assert(row, '保存后应仍能定位测试行')
    const cells = await rowCells(row)
    log(`  编辑后行: ${JSON.stringify(cells.slice(0, 3))}`)
    assertEq(cells[1], TEST_NICKNAME_V2, '昵称应更新为 v2')
    assertEq(cells[2], '停用', '状态应更新为 停用')
    const tagClass = await row.locator('.el-tag').getAttribute('class')
    assert(tagClass.includes('el-tag--danger'), `停用 tag 应为 danger，实际 ${tagClass}`)
    await shot(page, 's11-row-updated.png')
  })

  // ================= S12 重置密码 =================
  await step('S12', '重置密码弹窗：校验 + 成功；用新密码登录验证', async () => {
    let row = await findRow(page, TEST_ACCOUNT)
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '重置密码' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '重置密码' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    assert((await dlg.locator('.el-dialog__title').innerText()).includes(TEST_ACCOUNT), '弹窗标题应含目标账号')
    // 空提交
    await dlg.locator('.el-dialog__footer button', { hasText: '确定' }).click()
    await sleep(500)
    const errs = dlg.locator('.el-form-item__error')
    const texts = []
    const n = await errs.count()
    for (let i = 0; i < n; i++) texts.push((await errs.nth(i).innerText()).trim())
    log(`  空提交错误: ${JSON.stringify(texts)}`)
    assert(texts.includes('请输入新密码') && texts.includes('请再次输入新密码'), `必填错误应齐全，实际 ${JSON.stringify(texts)}`)
    await shot(page, 's12-resetpwd-dialog.png')
    // 两次不一致
    const inputs = dlg.locator('input[type="password"]')
    await inputs.nth(0).fill(TEST_PWD_NEW)
    await inputs.nth(1).fill('mismatch999')
    await dlg.locator('.el-dialog__footer button', { hasText: '确定' }).click()
    await sleep(500)
    const mismatchErrs = []
    const m = await dlg.locator('.el-form-item__error').count()
    for (let i = 0; i < m; i++) mismatchErrs.push((await dlg.locator('.el-form-item__error').nth(i).innerText()).trim())
    assert(mismatchErrs.some((t) => t.includes('两次输入的密码不一致')), `应报"两次输入的密码不一致"，实际 ${JSON.stringify(mismatchErrs)}`)
    log(`  一致性校验: ${JSON.stringify(mismatchErrs)}`)
    // 修正提交
    await inputs.nth(1).fill(TEST_PWD_NEW)
    const respP = page.waitForResponse((r) => r.url().includes(`/api/system/user/password/`), { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '确定' }).click()
    const resp = await respP
    log(`  重置密码接口: HTTP ${resp.status()}`)
    await waitToast(page, '密码重置成功')
    await waitDialogGone(page, '重置密码')

    // 先把账号改回正常（顺带复测编辑状态切换），否则停用账号无法用于登录验证
    row = await findRow(page, TEST_ACCOUNT)
    await row.getByRole('button', { name: '编辑' }).click()
    const editDlg = page.locator('.el-dialog', { hasText: '编辑用户' }).last()
    await editDlg.waitFor({ state: 'visible', timeout: 8000 })
    await editDlg.locator('.el-radio', { hasText: '正常' }).click()
    await editDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑用户')
    row = await findRow(page, TEST_ACCOUNT)
    assertEq((await rowCells(row))[2], '正常', '改回正常后行内状态应为 正常')

    // 用测试账号新密码登录验证（预期成功进入 Layout；其无角色权限，用户列表接口可能 403——属预期授权行为）
    await logoutViaUi(page)
    const r = await login(page, TEST_ACCOUNT, TEST_PWD_NEW)
    assert(r.ok, `测试账号新密码登录应成功: ${r.msg || ''}`)
    const accountText = (await page.locator('.navbar-account').innerText()).trim()
    assert(accountText.includes(TEST_ACCOUNT), `顶栏应显示 ${TEST_ACCOUNT}，实际 "${accountText}"`)
    await sleep(1200) // 等列表接口的 403 toast（如有）落定，仅记录
    const errToast = page.locator('.el-message--error')
    const errCount = await errToast.count()
    const errTexts = []
    for (let i = 0; i < errCount; i++) errTexts.push((await errToast.nth(i).innerText()).trim())
    log(`  测试账号登录后错误 toast（无角色时的预期授权拒绝）: ${JSON.stringify(errTexts)}`)
    await shot(page, 's12-login-newpwd.png')
    await logoutViaUi(page)
    const back = await login(page, 'admin', 'admin123')
    assert(back.ok, `admin 重新登录应成功: ${back.msg || ''}`)
  })

  // ================= S13 分配角色 =================
  await step('S13', '分配角色：勾选保存成功 + 重开回显；全不勾 → 回显空', async () => {
    await page.waitForURL('**/system/user', { timeout: 8000 })
    let row = await findRow(page, TEST_ACCOUNT)
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '分配角色' }).click()
    let dlg = page.locator('.el-dialog', { hasText: '分配角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // 等候选角色加载完
    const t0 = Date.now()
    while (Date.now() - t0 < 8000) {
      if ((await dlg.locator('.el-checkbox').count()) > 0 || (await dlg.locator('.el-empty').count()) > 0) break
      await sleep(150)
    }
    const boxes = dlg.locator('.el-checkbox')
    const boxCount = await boxes.count()
    const options = []
    for (let i = 0; i < boxCount; i++) options.push((await boxes.nth(i).innerText()).trim())
    log(`  角色候选: ${JSON.stringify(options)}`)
    if (boxCount === 0) throw new Error('角色候选为空（/system/role/list 无数据或加载失败）')
    const initialChecked = await dlg.locator('.el-checkbox.is-checked').count()
    assertEq(initialChecked, 0, '新用户初始应无勾选角色')
    await shot(page, 's13-assign-role.png')
    // 勾选第一个角色保存
    await boxes.first().click()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '分配成功')
    await waitDialogGone(page, '分配角色')
    // 重开验证回显
    row = await findRow(page, TEST_ACCOUNT)
    await row.getByRole('button', { name: '分配角色' }).click()
    dlg = page.locator('.el-dialog', { hasText: '分配角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    const t1 = Date.now()
    while (Date.now() - t1 < 8000) {
      if ((await dlg.locator('.el-checkbox').count()) > 0) break
      await sleep(150)
    }
    await sleep(500) // 等回显接口落定
    let checkedCount = await dlg.locator('.el-checkbox.is-checked').count()
    const firstIsChecked = await dlg.locator('.el-checkbox').first().evaluate((el) => el.classList.contains('is-checked'))
    log(`  重开回显: 已勾选 ${checkedCount} 个，首个角色勾选=${firstIsChecked}`)
    assert(firstIsChecked, '重开弹窗应回显已分配的角色（首个勾选）')
    await shot(page, 's13-assign-role-echo.png')
    // 全不勾保存 → 回显空
    await dlg.locator('.el-checkbox.is-checked').first().click()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '分配成功')
    await waitDialogGone(page, '分配角色')
    row = await findRow(page, TEST_ACCOUNT)
    await row.getByRole('button', { name: '分配角色' }).click()
    dlg = page.locator('.el-dialog', { hasText: '分配角色' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    const t2 = Date.now()
    while (Date.now() - t2 < 8000) {
      if ((await dlg.locator('.el-checkbox').count()) > 0) break
      await sleep(150)
    }
    await sleep(500)
    checkedCount = await dlg.locator('.el-checkbox.is-checked').count()
    assertEq(checkedCount, 0, '清空保存后重开应无勾选')
    log('  清空角色保存 → 回显空 ✔')
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '分配角色')
  })

  // ================= S14 删除测试账号 =================
  await step('S14', '删除测试账号：确认框 → 成功 → 行消失', async () => {
    let row = await findRow(page, TEST_ACCOUNT)
    assert(row, '应能定位测试行')
    await row.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const boxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
    log(`  确认框内容: ${boxText}`)
    assert(boxText.includes(TEST_ACCOUNT), '确认框文案应含目标账号')
    const btns = box.locator('.el-message-box__btns button')
    const btnTexts = []
    const bn = await btns.count()
    for (let i = 0; i < bn; i++) btnTexts.push((await btns.nth(i).innerText()).trim())
    log(`  确认框按钮: ${JSON.stringify(btnTexts)}`)
    assert(btnTexts.includes('确定') && btnTexts.includes('取消'), `确认框按钮应为中文 确定/取消（locale 修复验证），实际 ${JSON.stringify(btnTexts)}`)
    await shot(page, 's14-delete-confirm.png')
    await box.locator('.el-message-box__btns .el-button--primary').click()
    await waitToast(page, '删除成功')
    await sleep(800)
    const gone = await findRow(page, TEST_ACCOUNT)
    assert(gone === null, `删除后全表不应再有 ${TEST_ACCOUNT}`)
    log('  删除后全表检索：行已消失 ✔')
    await shot(page, 's14-after-delete.png')
  })

  // ---------- 清理 S8b 批量造的 10 个分页测试账号（经页面 fetch 逻辑删，admin 会话仍有效） ----------
  await step('CLEANUP', '清理批量造数账号，恢复库里仅剩 admin', async () => {
    if (bulkUserIds.length === 0) {
      log('  无批量数据需清理')
      return
    }
    const codes = await page.evaluate(async (ids) => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const out = []
      for (const id of ids) {
        const res = await fetch(`/api/system/user/${id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } })
        out.push((await res.json()).code)
      }
      return out
    }, bulkUserIds)
    assert(codes.every((c) => c === 200), `批量删除应全部成功，实际 ${JSON.stringify(codes)}`)
    log(`  批量删除 ${bulkUserIds.length} 个账号全部 200`)
    await page.goto(`${BASE}/system/user`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const totalText = (await page.locator('.el-pagination__total').innerText()).trim()
    log(`  清理后 total: "${totalText}"`)
    assertEq(parseInt((totalText.match(/\d+/) || ['0'])[0], 10), tableTotal, `清理后 total 应回到初始值 ${tableTotal}`)
    const anyE2e = await page.locator('.el-table__row', { hasText: 'e2e' }).count()
    assertEq(anyE2e, 0, '清理后表中不应残留 e2e 前缀账号')
  })

  // ================= S15（可选）篡改 token → 401 清态跳登录 =================
  await step('S15', '篡改 localStorage token 为垃圾值 → API 401 → 清态跳 /login', async () => {
    await page.goto(`${BASE}/system/user`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    await page.evaluate(() => {
      localStorage.setItem('cloud-web:auth', JSON.stringify({ accessToken: 'garbage.garbage.sig', refreshToken: 'x', account: 'admin' }))
    })
    const before = h.state.badResponses.length
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForURL('**/login**', { timeout: 15000 })
    const url = new URL(page.url())
    assertEq(url.pathname, '/login', '401 后应跳转 /login')
    assert(url.searchParams.get('redirect') === '/system/user', `应带 redirect=/system/user，实际 "${url.searchParams.get('redirect')}"`)
    const stored = await page.evaluate(() => localStorage.getItem('cloud-web:auth'))
    assertEq(stored, null, '401 后本地 token 应被清除')
    const got401 = h.state.badResponses.slice(before).some((b) => b.status === 401)
    assert(got401, `应捕获到 401 响应，实际 ${JSON.stringify(h.state.badResponses.slice(before))}`)
    log(`  捕获 401: ${JSON.stringify(h.state.badResponses.slice(before))}`)
    await shot(page, 's15-401-redirect.png')
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({ extras: [`\n测试账号: ${TEST_ACCOUNT}（应已在 S14 删除）`] })
  await browser.close()
}
