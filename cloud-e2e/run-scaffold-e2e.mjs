/**
 * 脚手架升级 e2e（计划 2026-10-06 T1-T9 + T-VERIFY；harness 复用 lib/harness.mjs）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202 在线；前端 dev 5173 在线（/api 代理 18080）
 * 运行：cd cloud-e2e && npm run e2e（链尾串行含本脚本；单跑 npm run e2e:scaffold）
 *
 * 测试纪律（升级设计 §8 / 计划任务清单 F8）：
 * - admin 会话零后端数据写：弹窗只开不提交（T8 新增用户弹窗点取消），全程 /api/system 无非 GET
 *   ——T-VERIFY 有显式断言；登录/注销 POST /sso/* 属会话操作非数据写
 * - 不碰种子数据（admin 账号/角色/菜单零改动）
 * - 全局态操作后还原：T3 折叠回展开、T4 退出全屏、T5 主题回 light；脚本结束态=浅色+展开（双保险）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitDialogGone, waitTableIdle, login, logoutViaUi } = h

const APP_TITLE = 'CloudAI 企业基座'

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

/** 非 /api/ 资产的 404 清单（favicon 等环境噪音甄别；覆盖登录背景图 login-bg 是否 404） */
const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

/** 读 localStorage.cloud-web:app（容错解析） */
const readAppPrefs = () =>
  page.evaluate(() => {
    try {
      return JSON.parse(localStorage.getItem('cloud-web:app') || 'null')
    } catch {
      return null
    }
  })

/** 本会话 /api/system/user/page 请求计数（T8 缓存断言）——harness 记录的 url 含 query，须 startsWith */
const userPageReqs = () => h.state.apiCalls.filter((c) => c.url.startsWith('/api/system/user/page')).length

/** 轮询等待条件成立（Playwright waitForFunction 的 eval 版薄封装） */
async function waitUntil(fn, timeout = 8000, label = '条件') {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    if (await fn()) return
    await sleep(200)
  }
  throw new Error(`等待超时: ${label}`)
}

/** 打开 .tags-actions 下拉并点命令项 */
async function tagsCommand(text) {
  await page.locator('.tags-actions').click()
  const item = page.locator('.el-dropdown-menu__item', { hasText: text }).first()
  await item.waitFor({ state: 'visible', timeout: 5000 })
  await item.click()
  await sleep(400)
}

const tagCount = () => page.locator('.tags-view-item').count()
const activeTagText = async () =>
  (await page.locator('.tags-view-item.active').innerText()).trim()

try {
  // ================= T1 登录页视觉与标题（未登录态） =================
  await step('T1', `登录页视觉与标题：卡片/标题=${APP_TITLE}/document.title/背景图`, async () => {
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
    await page.locator('.login-card').waitFor({ state: 'visible', timeout: 10000 })
    await assertEq((await page.locator('.login-title').innerText()).trim(), APP_TITLE, '.login-title 文本')
    await assertEq(await page.title(), APP_TITLE, '登录页 document.title（login 无 meta.title → 系统名全称）')
    const bg = await page.evaluate(() => getComputedStyle(document.querySelector('.login-page')).backgroundImage)
    assert(bg.includes('url('), `.login-page 应有背景图，实际 "${bg}"`)
    await shot(page, 't-login.png')
  })

  // ================= T2 品牌区与文档标题 =================
  await step('T2', `登录后品牌区含 "${APP_TITLE}"；/system/user 文档标题联动`, async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功，实际 ${JSON.stringify(r)}`)
    await page.locator('.sidebar-brand').waitFor({ state: 'visible', timeout: 10000 })
    const brand = (await page.locator('.sidebar-brand').innerText()).trim()
    assert(brand.includes(APP_TITLE), `.sidebar-brand 应含 "${APP_TITLE}"，实际 "${brand}"`)
    await waitTableIdle(page)
    await assertEq(await page.title(), `用户管理 - ${APP_TITLE}`, '/system/user 文档标题')
  })

  // ================= T3 侧边栏折叠/展开（收尾还原） =================
  await step('T3', '折叠：aside 64px/品牌文字隐藏/菜单 collapse 类；再点还原', async () => {
    await page.locator('.navbar-collapse').click()
    await sleep(400)
    const w = await page.evaluate(() => getComputedStyle(document.querySelector('.layout-aside')).width)
    assertEq(w, '64px', '折叠后 aside 宽度')
    assert(!(await page.locator('.sidebar-brand span').isVisible()), '折叠后品牌文字应不可见')
    const collapsed = await page.evaluate(() => document.querySelector('.el-menu')?.classList.contains('el-menu--collapse'))
    assert(collapsed === true, '.el-menu 应含 el-menu--collapse 类')
    await shot(page, 't-collapse.png')
    // 还原
    await page.locator('.navbar-collapse').click()
    await sleep(400)
    const w2 = await page.evaluate(() => getComputedStyle(document.querySelector('.layout-aside')).width)
    assertEq(w2, '200px', '展开后 aside 宽度')
    assert(await page.locator('.sidebar-brand span').isVisible(), '展开后品牌文字应恢复可见')
  })

  // ================= T4 全屏（headless 记 SKIP） =================
  if (HEADLESS) {
    log('\n===== [T4] 全屏 =====')
    h.state.results.push({ id: 'T4', name: '全屏进入/退出', status: 'SKIP', detail: '无头模式全屏行为未定（升级设计 §8.5）' })
    log('[T4] SKIP —— 无头模式全屏行为未定（升级设计 §8.5）')
  } else {
    await step('T4', '全屏：进入 fullscreenElement 非空 → 再点退出为 null', async () => {
      await page.locator('.navbar-fullscreen').click()
      await waitUntil(() => page.evaluate(() => document.fullscreenElement !== null), 6000, '进入全屏')
      await page.locator('.navbar-fullscreen').click()
      await waitUntil(() => page.evaluate(() => document.fullscreenElement === null), 6000, '退出全屏')
    })
  }

  // ================= T5 深色模式持久化（收尾还原 light） =================
  await step('T5', '深色：html.dark/落盘/--el-bg-color 变化/reload 仍 dark；切回 light', async () => {
    const bgBefore = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color').trim(),
    )
    await page.locator('.navbar-theme').click()
    await sleep(400)
    assert(
      await page.evaluate(() => document.documentElement.classList.contains('dark')),
      '切深色后 html 应含 dark 类',
    )
    assertEq((await readAppPrefs())?.theme, 'dark', 'cloud-web:app 应落盘 theme=dark')
    const bgAfter = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color').trim(),
    )
    assert(bgAfter !== bgBefore, `--el-bg-color 应随主题变化，light="${bgBefore}" dark="${bgAfter}"`)
    await shot(page, 't-dark.png')
    // reload 持久化
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.locator('.layout').waitFor({ state: 'visible', timeout: 10000 })
    assert(
      await page.evaluate(() => document.documentElement.classList.contains('dark')),
      'reload 后应保持 dark（mount 前同步 + store 初始化）',
    )
    assertEq((await readAppPrefs())?.theme, 'dark', 'reload 后落盘值仍 dark')
    // 还原 light
    await page.locator('.navbar-theme').click()
    await sleep(400)
    assert(
      !(await page.evaluate(() => document.documentElement.classList.contains('dark'))),
      '切回后 html.dark 应移除',
    )
    assertEq((await readAppPrefs())?.theme, 'light', '收尾应落盘 theme=light')
  })

  // ================= T6 菜单搜索（Ctrl+K） =================
  await step('T6', '菜单搜索：初始零 DOM/Ctrl+K 弹出聚焦/过滤/Enter 跳转/无结果 Esc 关闭', async () => {
    // 初始零 DOM（外层 v-if 契约）
    await assertEq(await page.locator('.menu-search-dialog').count(), 0, '初始 .menu-search-dialog 应为 0 个')
    // Ctrl+K 打开（主控裁决：仅 Ctrl/Meta 组合态，e2e 只测 Ctrl+K）
    await page.keyboard.press('Control+k')
    const dlg = page.locator('.menu-search-dialog')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500) // @opened 聚焦回调
    const focusedPh = await page.evaluate(() => document.activeElement?.getAttribute('placeholder') || '')
    assert(focusedPh.includes('搜索菜单'), `弹层输入框应已聚焦，实际 activeElement placeholder "${focusedPh}"`)
    // 过滤：输"角色" → 仅角色管理
    await page.locator('.menu-search-dialog input').fill('角色')
    await sleep(300)
    const items = page.locator('.menu-search-item')
    await assertEq(await items.count(), 1, `过滤"角色"应剩 1 项，实际 ${await items.count()}`)
    const t = (await items.first().innerText()).trim()
    assert(t.includes('角色管理'), `过滤结果应为角色管理，实际 "${t}"`)
    await shot(page, 't-search.png')
    // Enter → 跳转 /system/role 且弹层关闭
    await page.keyboard.press('Enter')
    await page.waitForURL('**/system/role', { timeout: 8000 })
    await waitDialogGone(page, '菜单搜索')
    await assertEq(await page.locator('.menu-search-dialog:visible').count(), 0, 'Enter 跳转后弹层应关闭')
    // 无结果 → el-empty；Esc 关闭
    await page.keyboard.press('Control+k')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(300)
    await page.locator('.menu-search-dialog input').fill('zzz')
    await sleep(300)
    await assertEq(await page.locator('.menu-search-item').count(), 0, '乱码关键词应无结果项')
    assert(await page.locator('.menu-search-dialog .el-empty').isVisible(), '无结果应显示 el-empty')
    await page.keyboard.press('Escape')
    await waitDialogGone(page, '菜单搜索')
  })

  // ================= T7 水印（点击穿透=常驻下正常切页） =================
  await step('T7', '水印：存在/pointer-events none/背景图非 none/常驻下点菜单切页', async () => {
    const wm = page.locator('.app-watermark')
    await assertEq(await wm.count(), 1, '.app-watermark 应存在')
    const style = await page.evaluate(() => {
      const s = getComputedStyle(document.querySelector('.app-watermark'))
      return { pe: s.pointerEvents, bg: s.backgroundImage }
    })
    assertEq(style.pe, 'none', '水印 pointer-events 应为 none（不挡交互）')
    assert(style.bg !== 'none', `水印应有平铺背景，实际 "${style.bg}"`)
    // 点击穿透：水印 z-index 9999 常驻下经侧边菜单切页成功
    await page.locator('.el-menu .el-menu-item', { hasText: '用户管理' }).click()
    await page.waitForURL('**/system/user', { timeout: 8000 })
    await waitTableIdle(page)
  })

  // ================= T8 标签页全套（缓存/刷新/弹窗保持/下拉四命令） =================
  await step('T8', '标签页：3 签/active 跟随/缓存零请求/刷新+1/弹窗保持/下拉四命令', async () => {
    // 基线：只留当前签（后续断言数量可控）
    await tagsCommand('关闭其他')
    await assertEq(await tagCount(), 1, '关闭其他后应仅剩 1 签')
    // 依次点开 菜单/角色 → 共 3 签
    await page.locator('.el-menu .el-menu-item', { hasText: '菜单管理' }).click()
    await page.waitForURL('**/system/menu', { timeout: 8000 })
    await page.locator('.el-menu .el-menu-item', { hasText: '角色管理' }).click()
    await page.waitForURL('**/system/role', { timeout: 8000 })
    await waitTableIdle(page)
    await assertEq(await tagCount(), 3, '点开 用户/菜单/角色 后应 3 签')
    assert((await activeTagText()).includes('角色管理'), `active 应跟随角色管理，实际 "${await activeTagText()}"`)
    await shot(page, 't-tags.png')
    // 点用户签回 /system/user
    await page.locator('.tags-view-item', { hasText: '用户管理' }).click()
    await page.waitForURL('**/system/user', { timeout: 8000 })
    await waitTableIdle(page)
    assert((await activeTagText()).includes('用户管理'), '点签后 active 应为用户管理')
    // 缓存断言：切走再切回，/page 请求数不变（keep-alive 命中）
    const n1 = userPageReqs()
    await page.locator('.tags-view-item', { hasText: '角色管理' }).click()
    await page.waitForURL('**/system/role', { timeout: 8000 })
    await page.locator('.tags-view-item', { hasText: '用户管理' }).click()
    await page.waitForURL('**/system/user', { timeout: 8000 })
    await sleep(600)
    await assertEq(userPageReqs(), n1, '切签往返用户页不应新增 /page 请求（缓存命中）')
    // 刷新断言：刷新当前页 → +1 且仍在 /system/user
    await tagsCommand('刷新当前页')
    await waitUntil(() => userPageReqs() >= n1 + 1, 8000, '刷新触发新 /page 请求')
    await assertEq(userPageReqs(), n1 + 1, '刷新当前页应恰好 +1 请求')
    assert(new URL(page.url()).pathname === '/system/user', `刷新后应仍在 /system/user，实际 ${page.url()}`)
    // 弹窗保持断言：开新增弹窗（不提交）→ 切工作台 → 切回 → 弹窗仍在 → 取消。
    // 实测修正：模态 overlay 盖住全页，侧边菜单/页签点击被拦（真实用户同样点不到），
    // 切走改走 Ctrl+K 菜单搜索（window keydown 不受 overlay 拦截，真实键盘路径）；
    // UserFormDialog 未 append-to-body → 弹窗与 overlay 随 keep-alive 失活组件整体摘除
    await page.locator('button', { hasText: '新增用户' }).click()
    const addDlg = page.locator('.el-dialog', { hasText: '新增用户' })
    await addDlg.waitFor({ state: 'visible', timeout: 8000 })
    await page.keyboard.press('Control+k')
    await page.locator('.menu-search-dialog').waitFor({ state: 'visible', timeout: 8000 })
    await sleep(400)
    await page.locator('.menu-search-dialog input').fill('工作台')
    await sleep(300)
    await page.keyboard.press('Enter')
    await page.waitForURL('**/dashboard', { timeout: 8000 })
    await waitDialogGone(page, '菜单搜索')
    await assertEq(await addDlg.count(), 0, '切走后弹窗 DOM 应随缓存组件整体摘除')
    await page.locator('.tags-view-item', { hasText: '用户管理' }).click()
    await page.waitForURL('**/system/user', { timeout: 8000 })
    assert(await addDlg.isVisible(), '切走再切回，缓存页内弹窗应保持可见')
    await page.locator('.el-dialog__footer button', { hasText: '取' }).click()
    await waitDialogGone(page, '新增用户')
    // 下拉命令逐项：关闭其他
    await tagsCommand('关闭其他')
    await assertEq(await tagCount(), 1, '关闭其他后应 1 签')
    // 关闭右侧：构造 [用户,菜单,工作台]，在菜单签上关右侧 → 掉工作台
    await page.locator('.el-menu .el-menu-item', { hasText: '菜单管理' }).click()
    await page.waitForURL('**/system/menu', { timeout: 8000 })
    await page.locator('.el-menu .el-menu-item', { hasText: '工作台' }).click()
    await page.waitForURL('**/dashboard', { timeout: 8000 })
    await page.locator('.tags-view-item', { hasText: '菜单管理' }).click()
    await page.waitForURL('**/system/menu', { timeout: 8000 })
    await tagsCommand('关闭右侧')
    await assertEq(await tagCount(), 2, '关闭右侧后应剩 用户+菜单 2 签')
    assert((await page.locator('.tags-view-item', { hasText: '工作台' }).count()) === 0, '工作台签应被关闭')
    // 全部关闭 → 落 /dashboard 且仅 1 签
    await tagsCommand('全部关闭')
    await page.waitForURL('**/dashboard', { timeout: 8000 })
    await assertEq(await tagCount(), 1, '全部关闭后落 /dashboard 应仅 1 签')
  })

  // ================= T9 会话清理（login 统一 closeAll） =================
  await step('T9', '退出重登：上一会话页签被清，仅剩当前页 1 签', async () => {
    // 先造 ≥2 签：当前 工作台 1 签 → 开用户页成 2 签
    await page.locator('.el-menu .el-menu-item', { hasText: '用户管理' }).click()
    await page.waitForURL('**/system/user', { timeout: 8000 })
    await waitTableIdle(page)
    const preCount = await tagCount()
    assert(preCount >= 2, `登出前应 ≥2 签（工作台+用户…），实际 ${preCount}`)
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `重新登录应成功，实际 ${JSON.stringify(r)}`)
    await waitTableIdle(page)
    await assertEq(await tagCount(), 1, '重登后应仅 1 签（上一会话页签已清，D4）')
    assert((await activeTagText()).includes('用户管理'), `落点签应为用户管理（/ → redirect），实际 "${await activeTagText()}"`)
  })

  // ================= T-VERIFY 证据核验 =================
  await step('T-VERIFY', '证据核验：零 console error/pageerror/≥400/网络失败/非 favicon 资产 404；零后端写', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（与 M-VERIFY 同口径）
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有 pageerror，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 ≥400 的 /api 响应，实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
    const noiseFree404 = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noiseFree404.length, 0, `非 favicon 资产 404 不应存在（覆盖登录背景图），实际 ${JSON.stringify(asset404)}`)
    // 零后端数据写：全程 /api/system无非 GET（弹窗只开不提交；/sso 登录注销属会话操作）
    const writes = h.state.apiCalls.filter((c) => c.url.startsWith('/api/system') && c.method !== 'GET')
    assertEq(writes.length, 0, `admin 会话不应有任何 /api/system 写操作，实际 ${JSON.stringify(writes)}`)
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({
    extras: [
      '\n种子数据（admin 账号/角色/菜单）零写操作；全局态收尾：浅色 + 展开（T3/T4/T5 已各自还原）',
      'T4 headless 记 SKIP（无头全屏行为未定，升级设计 §8.5）；本脚本无测试数据残留（零造数）',
    ],
  })
  await browser.close()
}
