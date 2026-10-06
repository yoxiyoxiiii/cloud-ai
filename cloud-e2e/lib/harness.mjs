/**
 * cloud-e2e 公共 harness（从 run-e2e.mjs 抽取，设计 D5：第二个功能接入时抽 lib/）
 *
 * 职责：浏览器工具（等待/断言/截图/登录/找行）+ 结果与网络证据收集 + 汇总打印。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 *
 * 用法：
 *   const h = createHarness({ base: 'http://localhost:5173', artDir: '<artifacts 绝对路径>' })
 *   const browser = await chromium.launch(...)   // 启动方式由调用方决定（有头/无头）
 *   h.state.ctx = ctx; h.state.page = page; h.attachListeners(page)
 *   await h.step('S1', '...', async () => { ... })
 *   h.summary({ extras: [`测试账号: xxx`] })
 */
import fs from 'node:fs'
import path from 'node:path'

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 创建 harness 实例
 * @param {{ base: string, artDir: string }} opts base=目标入口（E2E_BASE_URL），artDir=截图输出目录
 */
export function createHarness({ base, artDir }) {
  fs.mkdirSync(artDir, { recursive: true })

  /** 跨场景共享状态（网络/console 证据、结果、浏览器引用） */
  const state = {
    results: [],
    consoleErrors: [], // { scenario, text }
    pageErrors: [], // 未捕获异常
    apiCalls: [], // { method, url, status }
    badResponses: [], // status >= 400
    requestFailures: [], // 网络层失败
    currentScenario: 'init',
    ctx: null, // BrowserContext（step 失败截图用，主脚本注入）
    page: null, // 主 page（主脚本注入）
  }

  const log = (...a) => console.log(...a)

  async function step(id, name, fn) {
    state.currentScenario = id
    log(`\n===== [${id}] ${name} =====`)
    const t0 = Date.now()
    try {
      await fn()
      state.results.push({ id, name, status: 'PASS', ms: Date.now() - t0 })
      log(`[${id}] PASS (${Date.now() - t0}ms)`)
    } catch (e) {
      state.results.push({ id, name, status: 'FAIL', detail: e.message, ms: Date.now() - t0 })
      log(`[${id}] FAIL: ${e.message}`)
      try {
        const page = state.ctx?.pages()?.[0]
        if (page) await page.screenshot({ path: path.join(artDir, `${id}-fail.png`) })
        log(`[${id}] 失败截图: ${id}-fail.png`)
      } catch {
        /* 忽略截图失败 */
      }
    }
  }

  function assert(cond, msg) {
    if (!cond) throw new Error(`断言失败: ${msg}`)
  }

  function assertEq(actual, expected, msg) {
    if (actual !== expected) throw new Error(`断言失败: ${msg}，期望 "${expected}"，实际 "${actual}"`)
  }

  async function shot(page, name) {
    await page.screenshot({ path: path.join(artDir, name) })
    log(`  [shot] ${name}`)
  }

  async function waitToast(page, text, type = 'success', timeout = 8000) {
    const t0 = Date.now()
    while (Date.now() - t0 < timeout) {
      const msgs = page.locator('.el-message')
      const n = await msgs.count()
      for (let i = 0; i < n; i++) {
        const t = (await msgs.nth(i).innerText()).trim()
        if (t.includes(text) && (await msgs.nth(i).getAttribute('class')).includes(`el-message--${type}`)) {
          return t
        }
      }
      await sleep(150)
    }
    throw new Error(`等待 ElMessage(${type}) "${text}" 超时`)
  }

  async function waitDialogGone(page, titlePart, timeout = 8000) {
    // Element Plus 关闭弹窗是 display:none 隐藏而非移除节点，须等 hidden 而非等节点消失
    const dlg = page.locator('.el-dialog', { hasText: titlePart }).last()
    if ((await dlg.count()) === 0) return
    await dlg.waitFor({ state: 'hidden', timeout })
  }

  async function waitTableIdle(page, timeout = 10000) {
    const t0 = Date.now()
    while (Date.now() - t0 < timeout) {
      const mask = page.locator('.el-table .el-loading-mask')
      const visible = (await mask.count()) > 0 && (await mask.first().isVisible())
      if (!visible) return
      await sleep(150)
    }
    log('  [warn] 表格 loading 未在超时内消失（继续执行）')
  }

  async function breadcrumbTexts(page) {
    const items = page.locator('.el-breadcrumb .el-breadcrumb__item')
    const n = await items.count()
    const out = []
    for (let i = 0; i < n; i++) {
      const t = (await items.nth(i).innerText()).trim()
      out.push(t.replace(/\s*\/\s*$/, ''))
    }
    return out
  }

  async function login(page, account, password) {
    if (!page.url().includes('/login')) {
      await page.goto(`${base}/login`, { waitUntil: 'domcontentloaded' })
    }
    await page.locator('.login-card input[placeholder="请输入账号"]').fill(account)
    await page.locator('.login-card input[placeholder="请输入密码"]').fill(password)
    await page.locator('button.login-submit').click()
    try {
      await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 })
      return { ok: true, url: page.url() }
    } catch {
      const alert = page.locator('.login-alert')
      const msg = (await alert.count()) ? await alert.innerText() : '(无错误提示)'
      return { ok: false, msg: msg.trim() }
    }
  }

  async function logoutViaUi(page) {
    // hover 触发的 EP dropdown 在 headed+slowMo 下偶发自动收起（popper 动画/leave 计时竞争），
    // 重试至多 3 轮：重新 hover → 等菜单可见 → 先移入菜单项维持 hover 链再点
    const item = page.locator('.el-dropdown-menu__item', { hasText: '退出登录' })
    for (let attempt = 0; attempt < 3 && !page.url().includes('/login'); attempt++) {
      await page.locator('.navbar-account').hover()
      await item.waitFor({ state: 'visible', timeout: 5000 })
      await item.hover().catch(() => {})
      await item.click({ timeout: 5000 }).catch(() => {})
      await sleep(600)
    }
    await page.waitForURL('**/login', { timeout: 15000 })
    await sleep(300)
  }

  /**
   * 全表翻页找行：先回第 1 页再向后翻（新行可能在任意页）
   * @param {import('playwright').Page} page
   * @param {string} text 行定位文本（唯一性锚点，如账号/roleKey）
   * @param {{ path?: string, reload?: boolean }} opts path=目标页路由（默认用户管理页，向后兼容）
   */
  async function findRow(page, text, { path = '/system/user', reload = true } = {}) {
    if (reload) {
      await page.goto(`${base}${path}`, { waitUntil: 'domcontentloaded' })
    }
    await waitTableIdle(page)
    for (let guard = 0; guard < 30; guard++) {
      const row = page.locator('.el-table__row', { hasText: text }).first()
      if ((await row.count()) > 0 && (await row.isVisible())) {
        await waitTableIdle(page)
        return row
      }
      const next = page.locator('.el-pagination .btn-next')
      if ((await next.count()) === 0 || !(await next.isEnabled())) return null
      await next.click()
      await waitTableIdle(page)
      await sleep(300)
    }
    return null
  }

  async function rowCells(row) {
    const tds = row.locator('td')
    const n = await tds.count()
    const out = []
    for (let i = 0; i < n; i++) out.push(((await tds.nth(i).innerText()) || '').trim())
    return out
  }

  /** 挂载网络/console 证据采集（主 page 创建后调用一次） */
  function attachListeners(page) {
    page.on('console', (m) => {
      if (m.type() === 'error') state.consoleErrors.push({ scenario: state.currentScenario, text: m.text() })
    })
    page.on('pageerror', (e) => state.pageErrors.push({ scenario: state.currentScenario, text: String(e) }))
    page.on('response', (r) => {
      if (!r.url().includes('/api/')) return
      const short = r.url().replace(/^https?:\/\/[^/]+\/api/, '/api')
      state.apiCalls.push({ method: r.request().method(), url: short, status: r.status() })
      if (r.status() >= 400) state.badResponses.push({ scenario: state.currentScenario, method: r.request().method(), url: short, status: r.status() })
    })
    page.on('requestfailed', (r) => {
      if (r.url().includes('/api/') || r.url().includes(':5173')) {
        state.requestFailures.push({ scenario: state.currentScenario, url: r.url(), err: r.failure()?.errorText })
      }
    })
  }

  /** 汇总打印（finally 中调用）；extras=附加说明行（如测试账号说明） */
  function summary({ extras = [] } = {}) {
    log('\n================= 场景结果 =================')
    for (const r of state.results) {
      log(`[${r.status}] ${r.id} ${r.name}${r.detail ? ' —— ' + r.detail : ''}`)
    }
    log('\n================= API 调用（/api/**） =================')
    for (const c of state.apiCalls) log(`${c.status} ${c.method} ${c.url}`)
    log('\n================= >=400 响应 =================')
    for (const b of state.badResponses) log(`[${b.scenario}] ${b.status} ${b.method} ${b.url}`)
    log('\n================= 网络失败 =================')
    for (const f of state.requestFailures) log(`[${f.scenario}] ${f.url} ${f.err}`)
    log('\n================= console error =================')
    const uniq = [...new Set(state.consoleErrors.map((e) => e.text))]
    for (const t of uniq) log(`  ${t}`)
    if (state.pageErrors.length) {
      log('================= pageerror（未捕获异常） =================')
      for (const e of state.pageErrors) log(`[${e.scenario}] ${e.text}`)
    }
    for (const line of extras) log(line)
  }

  return {
    state,
    log,
    sleep,
    step,
    assert,
    assertEq,
    shot,
    waitToast,
    waitDialogGone,
    waitTableIdle,
    breadcrumbTexts,
    login,
    logoutViaUi,
    findRow,
    rowCells,
    attachListeners,
    summary,
  }
}
