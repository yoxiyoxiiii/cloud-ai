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
 * 表格列配置基线复位（tableconfig 轮补强）：列头断言与 admin 个人列偏好解耦。
 * 按人按表持久化的列配置（显隐/列序/列宽）会让同一页面在不同登录态下渲染不同列集，
 * 回归断言须以页面默认列为基线——本函数对给定 tableKey 逐个 GET 存档，有个人配置则
 * DELETE（幂等，回默认列）；返回恢复句柄交 restoreTableConfigs 在脚本收尾原样回写，
 * 个人偏好不因跑回归而丢失。
 * 复位/恢复走 Node fetch + 登录后捕获的 token：不依赖脚本结束时的页面登录态
 * （防中途切换受限用户后把配置恢复到错误账号），也不入 page 网络统计（不污染 VERIFY 场景）。
 * @param {import('playwright').Page} page 已登录 admin 的页面（取 localStorage token）
 * @param {string} apiBase 直连基座（dev 代理传 `${BASE}/api`，网关直连传 GATEWAY）
 * @param {string[]} tableKeys 如 ['system:user', 'system:dict']
 */
export async function resetTableConfigs(page, apiBase, tableKeys) {
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken)
  const saved = new Map()
  for (const key of tableKeys) {
    const res = await fetch(`${apiBase}/system/table-config/${key}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const body = await res.json().catch(() => null)
    // HTTP 失败或 R.code!=200 时基线状态未知——告警而非静默当"无个人配置"（body 级失败 fetch 不抛）
    if (!body || body.code !== 200) {
      console.log(`  [table-config] ${key} 读取失败（HTTP ${res.status}${body ? ` code=${body.code}` : ' 非 JSON'}），按无个人配置处理`)
      continue
    }
    if (body.data) {
      saved.set(key, body.data)
      const del = await fetch(`${apiBase}/system/table-config/${key}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
      const delBody = await del.json().catch(() => null)
      if (!delBody || delBody.code !== 200) {
        console.log(`  [table-config] ${key} 复位 DELETE 失败（HTTP ${del.status}），列断言可能受个人配置干扰`)
      }
    }
  }
  if (saved.size) console.log(`  [table-config] 基线复位 ${[...saved.keys()].join(',')}（收尾恢复）`)
  return { apiBase, token, saved }
}

/** 复位句柄回写（脚本 finally 调用）：原样 PUT 回存档配置；失败仅告警不抛（不掩盖场景结果） */
export async function restoreTableConfigs(handle) {
  if (!handle || handle.saved.size === 0) return
  const restored = []
  for (const [key, cfg] of handle.saved) {
    try {
      const res = await fetch(`${handle.apiBase}/system/table-config/${key}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${handle.token}` },
        body: JSON.stringify(cfg),
      })
      // R 约定 HTTP 恒 200、成败在 body.code——fetch 对 HTTP 4xx/5xx 不抛，须查 body 才知真假
      const body = await res.json().catch(() => null)
      if (body && body.code === 200) {
        restored.push(key)
      } else {
        console.log(`  [table-config] 恢复 ${key} 失败（HTTP ${res.status}${body ? ` code=${body.code}` : ' 非 JSON'}），个人列偏好需手动重设`)
      }
    } catch (e) {
      console.log(`  [table-config] 恢复 ${key} 失败（个人列偏好需手动重设）: ${e.message}`)
    }
  }
  if (restored.length) console.log(`  [table-config] 已恢复个人列配置: ${restored.join(',')}`)
}

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
    logoutViaUiStats: [], // { clicks, posts }——logoutViaUi 每次调用记档（设计 D7 常驻回归守卫）
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
        // toast 淡出动画中元素瞬时 not visible，innerText 默认等 30s 必卡——短超时失败即跳过该条
        let t = ''
        try {
          t = (await msgs.nth(i).innerText({ timeout: 1000 })).trim()
        } catch {
          continue
        }
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
    // 双守卫（设计 D7，根治「重定向未完成时二击 → 第二个 POST 撞已失效会话得 401」flake）：
    // 1) 请求级守卫：函数生命周期内挂一次性 logout POST 响应等待，sawLogout 置位后循环条件含
    //    !sawLogout —— 永不再点（守卫判定在 click 之前）
    // 2) 时序修：每轮点击后以 waitForURL(4s) 取代盲等 600ms——URL 已落定则循环条件自然退出
    // 另：hover 触发的 EP dropdown 在 headed+slowMo 下偶发自动收起（popper 动画/leave 计时竞争），
    // 重试至多 3 轮：重新 hover → 等菜单可见 → 先移入菜单项维持 hover 链再点
    const postsBefore = state.apiCalls.filter((c) => c.url === '/api/sso/auth/logout' && c.method === 'POST').length
    let sawLogout = false
    page.waitForResponse((r) => new URL(r.url()).pathname === '/api/sso/auth/logout', { timeout: 15000 })
      .then(() => { sawLogout = true })
      .catch(() => { /* 未发生即不置位；最终 waitForURL 兜底报错 */ })
    const item = page.locator('.el-dropdown-menu__item', { hasText: '退出登录' })
    let clicks = 0
    for (let attempt = 0; attempt < 3 && !sawLogout && !page.url().includes('/login'); attempt++) {
      await page.locator('.navbar-account').hover()
      // 菜单本轮没开出（popper 竞态）不抛——下一轮重新 hover 再试（重试设计原本意图，waitFor 逃逸会击穿三轮循环）
      try {
        await item.waitFor({ state: 'visible', timeout: 5000 })
      } catch {
        continue
      }
      await item.hover().catch(() => {})
      clicks += 1
      await item.click({ timeout: 5000 }).catch(() => {})
      await page.waitForURL('**/login', { timeout: 4000 }).catch(() => {})
    }
    await page.waitForURL('**/login', { timeout: 15000 })
    await sleep(300)
    const postsAfter = state.apiCalls.filter((c) => c.url === '/api/sso/auth/logout' && c.method === 'POST').length
    state.logoutViaUiStats.push({ clicks, posts: postsAfter - postsBefore })
  }

  /**
   * 全表翻页找行：先回第 1 页再向后翻（新行可能在任意页）
   * @param {import('playwright').Page} page
   * @param {string} text 行定位文本（唯一性锚点，如账号/roleKey）
   * @param {{ path?: string, reload?: boolean }} opts path=目标页路由（默认用户管理页，向后兼容）
   */
  async function findRow(page, text, { path = '/system/user', reload = true } = {}) {
    if (reload) {
      // 首屏竞态修复：goto 后表格/分页可能尚未首渲染（waitTableIdle 对"mask 未出现"直接放行），
      // 空表 DOM 会让下方"btn-next 不存在"分支提前 return null（S10 偶发误判根因）——
      // 先等本次列表接口（*/page）响应落定，再等首行或分页控件可见，才进入扫页循环
      const listP = page.waitForResponse((r) => new URL(r.url()).pathname.endsWith('/page'), { timeout: 15000 }).catch(() => null)
      await page.goto(`${base}${path}`, { waitUntil: 'domcontentloaded' })
      await listP
      await page.locator('.el-table__row, .el-pagination').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
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

  /**
   * 全表翻页按单元格精确找行（以 run-role 已修版 findRoleRowByKey 为体抽共享——副本漂移根除，
   * run-role/run-nav 统一改调本函数；设计 D7）
   * 精确比对（===）而非 hasText：hasText 是子串且不区分大小写——"admin" 会撞上
   * 创建人列同为 admin 的其他行（run-role R6b 曾误判的根因）
   * @param {import('playwright').Page} page
   * @param {{ path: string, cellIndex: number, value: string, reload?: boolean }} opts
   *   path=目标页路由；cellIndex=锚定列下标；value=精确比对值（唯一性锚点，如 roleKey/dictKey）
   */
  async function findRowByCell(page, { path, cellIndex, value, reload = true } = {}) {
    if (reload) {
      // 首渲染竞态守卫（同 findRow 的 S10 修复）：goto 后表格/分页可能尚未首渲染
      // （waitTableIdle 对"mask 未出现"直接放行），空表 DOM 会让下方"btn-next 不存在"分支提前
      // return null——先等本次列表接口（*/page）响应落定，再等首行或分页控件可见
      const listP = page.waitForResponse((r) => new URL(r.url()).pathname.endsWith('/page'), { timeout: 15000 }).catch(() => null)
      await page.goto(`${base}${path}`, { waitUntil: 'domcontentloaded' })
      await listP
      await page.locator('.el-table__row, .el-pagination').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
    }
    await waitTableIdle(page)
    for (let guard = 0; guard < 30; guard++) {
      const rows = page.locator('.el-table__row')
      const n = await rows.count()
      for (let i = 0; i < n; i++) {
        const cells = await rowCells(rows.nth(i))
        if (cells[cellIndex] === value) return rows.nth(i)
      }
      const next = page.locator('.el-pagination .btn-next')
      if ((await next.count()) === 0 || !(await next.isEnabled())) return null
      await next.click()
      await waitTableIdle(page)
      await sleep(300)
    }
    return null
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
    findRowByCell,
    rowCells,
    attachListeners,
    summary,
  }
}
