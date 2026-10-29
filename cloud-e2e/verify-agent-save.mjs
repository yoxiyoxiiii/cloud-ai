/**
 * 一次性复现脚本：定位「POST /api/ai/agent 已 fulfill 200 但页面 await 不结算」问题。
 * 全 mock 面（me/user-nav/list/tool/create），XHR 原型打补丁把 readyState/事件序列打到 console，
 * Playwright 侧同时监听 response/requestfinished/console 三源对照。使命完成即删。
 */
import { chromium } from 'playwright'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5174'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const R = (data) => ({ code: 200, msg: '操作成功', data })
const json = (body) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

const PERMS = ['ai:agent:list', 'ai:agent:add', 'ai:agent:update', 'ai:agent:delete', 'ai:agent:run', 'system:user:list']

// 复刻业务脚本的菜单树形态（AI 助手分组含两个 agent 子项）
const NAV = {
  code: 200,
  msg: '操作成功',
  data: [
    { id: '2', name: '系统管理', path: '/system', sort: 1, children: [
      { id: '21', name: '用户管理', path: '/system/user', sort: 1, children: [] },
    ] },
    { id: '18', name: 'AI 助手', path: '/ai', sort: 2, children: [
      { id: '181', name: '对话', path: '/rag/chat', sort: 1, children: [] },
      { id: '186', name: '智能体管理', path: '/agent/manage', sort: 3, children: [] },
      { id: '190', name: '智能体对话', path: '/agent/chat', sort: 4, children: [] },
    ] },
  ],
}

const launch = async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: false, slowMo: 300 })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  page.on('console', (m) => {
    const t = m.text()
    if (t.includes('[xhr]') || t.includes('[save]')) console.log(`CONSOLE: ${t}`)
  })
  page.on('response', (r) => {
    if (r.url().includes('/api/ai/agent') && !r.url().includes('.ts')) {
      console.log(`PW-RESPONSE: ${r.status()} ${r.request().method()} ${r.url()}`)
    }
  })
  page.on('requestfinished', (r) => {
    if (r.method() === 'POST' && r.url().includes('/api/ai/agent')) {
      console.log(`PW-REQFINISHED: ${r.method()} ${r.url()}`)
    }
  })
  page.on('requestfailed', (r) => {
    if (r.url().includes('/api/')) console.log(`PW-REQFAILED: ${r.method()} ${r.url()} :: ${r.failure()?.errorText}`)
  })
  return { browser, page }
}

const main = async () => {
  const { browser, page } = await launch()

  await page.route('**/api/**', async (route) => {
    const req = route.request()
    const u = new URL(req.url())
    const p = u.pathname.replace(/^\/api/, '')
    const method = req.method()
    if (p === '/sso/auth/me' && method === 'GET') {
      return route.fulfill(json(R({ id: 1, account: 'admin', name: '管理员', permissions: PERMS })))
    }
    if (p === '/system/menu/user-nav' && method === 'GET') {
      return route.fulfill(json(NAV))
    }
    if (p === '/ai/agent/list' && method === 'GET') {
      return route.fulfill(json(R({ total: 0, rows: [] })))
    }
    if (p === '/ai/agent/tool/list' && method === 'GET') {
      return route.fulfill(json(R([
        { id: 1, tool_key: 'weather_query', name: '天气查询', description: '查天气' },
        { id: 2, tool_key: 'db_query', name: '数据库查询', description: '查库' },
      ])))
    }
    if (p === '/ai/agent' && method === 'POST') {
      console.log('MOCK: create handler entered')
      const body = req.postDataJSON()
      console.log(`MOCK: body=${JSON.stringify(body)}`)
      const result = route.fulfill(json(R(5001)))
      console.log('MOCK: fulfill() called')
      return result
    }
    // 其余 /api 一律 200 空 R（防 401 跳登录干扰）
    return route.fulfill(json(R(null)))
  })

  // 伪登录态：token 存在即过路由守卫（me/user-nav 均 mock）
  await page.goto(`${BASE}/login`)
  await page.evaluate(() => {
    localStorage.setItem('cloud-web:auth', JSON.stringify({ accessToken: 'fake', refreshToken: 'fake', account: 'admin' }))
  })

  // XHR 打补丁：记录 agent POST 的全事件序列（axios 走 XHR adapter）
  await page.evaluate(() => {
    const origOpen = XMLHttpRequest.prototype.open
    XMLHttpRequest.prototype.open = function (...args) {
      const url = String(args[1] ?? '')
      if (url.includes('/ai/agent') && !url.includes('/list') && !url.includes('/tool')) {
        console.log(`[xhr] open ${args[0]} ${url}`)
        this.addEventListener('loadstart', () => console.log('[xhr] loadstart'))
        this.addEventListener('readystatechange', () => console.log(`[xhr] readyState=${this.readyState} status=${this.status}`))
        this.addEventListener('load', () => console.log(`[xhr] load status=${this.status} len=${this.responseText?.length}`))
        this.addEventListener('error', () => console.log('[xhr] error'))
        this.addEventListener('timeout', () => console.log('[xhr] timeout'))
        this.addEventListener('loadend', () => console.log(`[xhr] loadend status=${this.status}`))
      }
      return origOpen.apply(this, args)
    }
  })

  await page.goto(`${BASE}/agent/manage`)
  await page.locator('.el-table__empty-text').waitFor({ state: 'visible', timeout: 10000 })
  console.log('PAGE: manage loaded, empty state visible')

  await page.getByRole('button', { name: '新增智能体' }).click()
  const dlg = page.locator('.el-dialog', { hasText: '新建智能体' }).last()
  await dlg.waitFor({ state: 'visible', timeout: 5000 })
  await dlg.locator('input[placeholder="请输入智能体名称"]').fill('verify_probe')
  await dlg.locator('textarea[placeholder="定义智能体的角色、能力与输出要求"]').fill('探针')
  await shot(page, 'verify-dialog-filled.png')

  console.log('ACTION: clicking save')
  await dlg.getByRole('button', { name: '保存' }).click()

  // 观察窗：toast / 弹窗关闭 / list 刷新 三信号
  const t0 = Date.now()
  let sawToast = false
  let dialogGone = false
  let listRefetched = false
  while (Date.now() - t0 < 12000) {
    if (!sawToast && (await page.locator('.el-message--success').count()) > 0) {
      sawToast = true
      console.log(`SIGNAL: toast visible @+${Date.now() - t0}ms`)
    }
    if (!dialogGone && (await page.locator('.el-dialog:visible').count()) === 0) {
      dialogGone = true
      console.log(`SIGNAL: dialog closed @+${Date.now() - t0}ms`)
    }
    if (!listRefetched && (await page.locator('.el-table__row').count()) > 0) {
      listRefetched = true
      console.log(`SIGNAL: list row appeared @+${Date.now() - t0}ms`)
    }
    if (sawToast && dialogGone && listRefetched) break
    await sleep(200)
  }
  console.log(`RESULT: toast=${sawToast} dialogGone=${dialogGone} listRow=${listRefetched}`)
  await shot(page, 'verify-after-save.png')
  await browser.close()
  const ok = sawToast && dialogGone && listRefetched
  console.log(ok ? 'VERDICT: PASS — 复现失败（保存链路本身正常）' : 'VERDICT: REPRODUCED — 保存链路未闭环')
  process.exit(ok ? 0 : 1)
}

async function shot(page, name) {
  await page.screenshot({ path: `artifacts/${name}`, fullPage: false })
  console.log(`SHOT: artifacts/${name}`)
}

main().catch((e) => {
  console.error('SCRIPT ERROR:', e)
  process.exit(2)
})
