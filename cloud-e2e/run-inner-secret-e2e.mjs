/**
 * inner 密钥管理页 e2e（契约 2026-11-09-inner-secret-admin-api §0-§4 / 计划 2026-11-09-inner-secret-rotation F2）
 *
 * 运行前提：**仅前端 dev 5173**（后端 inner-secret 域由并行轨道实现，本脚本零后端依赖）——
 * 登录链（sso/system 菜单权限）、工作台首屏与 inner-secret 四端点全部在浏览器网络层
 * route mock（沿 run-notify-e2e.mjs 先例；mock 态在脚本内存，结束即清）。
 * 运行：cd cloud-e2e && npm run e2e:innersecret（单跑，不进 run-e2e.mjs 聚合，沿 aichat/notify 先例）；
 * E2E_HEADLESS=1 或 --headless 无头。黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：全部在 route mock 内存态（零真实后端写）；admin 种子账号只登录不改。
 *
 * 场景（计划 F2 五段）：
 * - S1 登录+菜单+页面渲染：侧边「系统管理/密钥管理」（菜单 22，icon Key）→ 状态卡回显
 *   （三参数只读/当前代数/活跃数/下次轮换/分发正常 tag）+ 流水四状态行（1/2/3/4 tag 色板映射
 *   由文案断言）+ 作废按钮仅活跃集行可见 + 表头/分页 total
 * - S2 立即轮换：新行（seq+1 生效中/手动操作）+ 前任转重叠期 + 更早重叠行转过期 + 状态卡刷新
 * - S3 频控 3053：重叠窗内再轮换 → toast 透出后端 msg（含剩余秒数）
 * - S4 作废流：确认弹窗文案含契约 §4「最迟约 30 秒内生效」原文语义 → 重叠期行作废成功；
 *   发布失败 3052（mock publishFail 分支：新钥已落库 + 状态卡转「等待自动重发」）；
 *   最后一把生效密钥 → 3051 toast
 * - S5 掩码红线：全部掩码列 `****`+4 hex 形态、页面无明文形态字段（状态卡零编辑控件）
 *   + status 过滤下拉（已作废 → 恰 3 行）
 * - S-VERIFY 环境卫生（console/pageerror/>=400/网络失败——favicon 404 噪音滤除）
 *
 * 不在本面：3050（已过期/已作废行无作废按钮——UI 面防线，后端兜底）与 revoke 3052 分支
 * （发布失败语义同 rotate 3052 已覆盖）；真后端联动场景归主控集成段。
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login, waitToast, waitTableIdle, rowCells } = h

const PAGE_PATH = '/system/inner-secret'

/** 管理页表头 9 列（实现面 index.vue 列序） */
const HEADERS = ['代数', '状态', '密钥掩码', '来源', '生效时间', '过期时间', '退场时间', '创建人', '操作']

// ---------- mock 数据（分层：密钥流水 4 行四状态 / 状态卡派生 / 轮换与发布 mock 态） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const ERR = (code, msg) => JSON.stringify({ code, msg, data: null })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

const mkSecret = (over) => ({
  id: '0',
  seq: 0,
  status: 1,
  secretMask: '****0000',
  source: 1,
  activateTime: '2026-11-09 16:00:00',
  expireTime: '2026-11-16 16:00:00',
  retireTime: null,
  createBy: 'innerSecretRotateJobHandler',
  createTime: '2026-11-09 16:00:00',
  ...over,
})

/** 密钥流水（mock 唯一真相源：page/status/rotate/revoke 全部派生或迁移此表） */
const secrets = [
  mkSecret({ id: '12', seq: 7, status: 1, secretMask: '****ab3f' }),
  mkSecret({ id: '11', seq: 6, status: 2, secretMask: '****c2d1', activateTime: '2026-11-02 16:00:00', expireTime: '2026-11-09 16:00:00', retireTime: '2026-11-09 16:03:00', createTime: '2026-11-02 16:00:00' }),
  mkSecret({ id: '10', seq: 5, status: 3, secretMask: '****9e77', activateTime: '2026-10-26 16:00:00', expireTime: '2026-11-02 16:00:00', retireTime: '2026-11-02 16:03:00', createTime: '2026-10-26 16:00:00' }),
  mkSecret({ id: '9', seq: 4, status: 4, secretMask: '****31cc', source: 2, activateTime: '2026-10-19 16:00:00', expireTime: '2026-10-26 16:00:00', retireTime: '2026-10-20 09:30:00', createBy: 'admin', createTime: '2026-10-19 16:00:00' }),
]

/** 预计下次轮换（seq → expireTime − lead 24h；mock 静态预计算，不做时钟运算） */
const NEXT_ROTATION = { 7: '2026-11-15 16:00:00', 8: '2026-11-15 18:00:00', 9: '2026-11-15 18:00:00' }

let nextId = 100
let maskSeq = 0
/** 最近一次轮换生成的掩码（测试锚点——行唯一性定位用） */
let lastMask = '****e200'
/** Redis 活跃集 version：仅发布成功才前进（3052 分支停旧值 → redisConsistent=false） */
let redisSeq = 7
/** 发布失败模拟开关（S4b 置 true：落库照常、发布 ERR 3052） */
let publishFail = false
/** 当前重叠窗截止时刻（ms epoch）：轮换成功置 now+重叠窗；窗口内再轮换 → 3053（契约 §3）。
 *  作废重叠期行即清窗（该钥已离活跃集，窗口随之中止）。种子 seq6 的重叠窗早已耗尽
 *  （sweep 待清扫的显示残留），不阻塞轮换——不以「存在 status=2 行」做频控哨兵 */
let overlapUntil = 0

const activeRows = () => secrets.filter((s) => s.status === 1 || s.status === 2)
const latestActive = () => secrets.filter((s) => s.status === 1).sort((a, b) => b.seq - a.seq)[0]

/** 状态卡派生（契约 §2）：currentSeq=最新生效代数 / activeCount=活跃集行数 / redisConsistent=版本对齐 */
function statusVo() {
  const latest = latestActive()
  return {
    validityHours: 168,
    overlapSeconds: 180,
    leadHours: 24,
    currentSeq: latest ? latest.seq : 0,
    activeCount: activeRows().length,
    nextRotationTime: latest ? NEXT_ROTATION[latest.seq] ?? null : null,
    redisVersion: redisSeq,
    redisConsistent: Boolean(latest) && redisSeq === latest.seq,
  }
}

// ---------- route mock（登录链 + 工作台首屏 + 铃铛轮询 + inner-secret 四端点） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}；mock 流水 4 行四状态`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

await page.route('**/api/**', async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  // 只拦网关代理前缀 /api/**（防误拦 Vite dev 模块 URL——如 /src/api/xxx.ts 含 "/api/" 字样）
  if (!u.pathname.startsWith('/api/')) {
    return route.fallback()
  }
  const p = u.pathname.replace(/^\/api/, '')
  const q = u.searchParams

  // ---- 登录链（sso/system：登录、权限快照、导航树、注销）----
  if (p === '/sso/auth/login' && req.method() === 'POST') {
    const ts = Date.now()
    return route.fulfill(json(R({ accessToken: `e2e-token-${ts}`, refreshToken: `e2e-refresh-${ts}`, expiresIn: '3600' })))
  }
  if (p === '/sso/auth/me' && req.method() === 'GET') {
    return route.fulfill(json(R({ account: 'admin', permissions: ['system:innerSecret:list', 'system:innerSecret:rotate', 'system:innerSecret:revoke'] })))
  }
  if (p === '/sso/auth/logout' && req.method() === 'POST') {
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/menu/user-nav' && req.method() === 'GET') {
    // 最小导航树：系统管理(M, sort 1) > 密钥管理(C, /system/inner-secret, icon Key, sort 9)
    return route.fulfill(json(R([
      { id: '10', parentId: '0', name: '系统管理', type: 'M', path: '', icon: 'Setting', sort: 1, children: [
        { id: '22', parentId: '10', name: '密钥管理', type: 'C', path: '/system/inner-secret', icon: 'Key', sort: 9, children: [] },
      ] },
    ])))
  }
  // 工作台首屏聚合单请求（登录落首页触发——mock 空段防后端未起报网络失败；段结构见契约 2026-11-14-aggregate-workbench-api §1.2）
  if (p === '/aggregate/workbench' && req.method() === 'GET') {
    return route.fulfill(json(R({
      todo: { status: 200, msg: '操作成功', data: { total: '0', rows: [] } },
      approval: { status: 200, msg: '操作成功', data: { total: '0', rows: [] } },
      notify: { status: 200, msg: '操作成功', data: { unreadCount: '0', latestId: '0' } },
    })))
  }
  // Navbar 铃铛 30s 轮询（mock 零未读——不触发弹框链路）
  if (p === '/system/notify/summary' && req.method() === 'GET') {
    return route.fulfill(json(R({ unreadCount: '0', latestId: '0' })))
  }

  // ---- inner-secret 域（契约 §1-§4）----
  if (p === '/system/inner-secret/page' && req.method() === 'GET') {
    let rows = [...secrets].sort((a, b) => Number(b.id) - Number(a.id))
    if (q.get('status')) rows = rows.filter((s) => s.status === Number(q.get('status')))
    const pageNum = Math.max(1, Number(q.get('pageNum') ?? 1))
    const pageSize = Math.max(1, Number(q.get('pageSize') ?? 10))
    return route.fulfill(json(R({ total: String(rows.length), rows: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize) })))
  }
  if (p === '/system/inner-secret/status' && req.method() === 'GET') {
    return route.fulfill(json(R(statusVo())))
  }
  if (p === '/system/inner-secret/rotate' && req.method() === 'POST') {
    // 频控先于一切（契约 §3：上一轮重叠窗口未结束 → 3053，msg 带剩余秒数）
    if (Date.now() < overlapUntil) {
      const remainSec = Math.ceil((overlapUntil - Date.now()) / 1000)
      return route.fulfill(json(ERR(3053, `轮换请求过于频繁：上一轮重叠窗口未结束，剩余 ${remainSec} 秒`)))
    }
    const newSeq = secrets.reduce((m, s) => Math.max(m, s.seq), 0) + 1
    lastMask = `****e2${(maskSeq++).toString(16).padStart(2, '0')}`
    const row = mkSecret({ id: String(nextId++), seq: newSeq, status: 1, secretMask: lastMask, source: 2, createBy: 'admin', expireTime: '2026-11-16 18:00:00' })
    if (newSeq >= 8) row.activateTime = '2026-11-09 18:00:00'
    // 前任（status 1）转重叠期；更早重叠行转过期（契约 §3 与定时任务同路径的代际推进）
    const prev = secrets.find((s) => s.status === 1)
    if (prev) {
      prev.status = 2
      prev.retireTime = '2026-11-09 18:03:00'
    }
    for (const s of secrets) {
      if (s.status === 2 && s !== prev) s.status = 3
    }
    secrets.push(row)
    overlapUntil = Date.now() + 180_000
    if (publishFail) {
      // 契约 §3 3052：新钥已落库生效，Redis 未更新（redisSeq 不前进 → 状态卡 redisConsistent=false）
      return route.fulfill(json(ERR(3052, '活跃集发布失败：变更已落库，定时任务一分钟内自愈重发')))
    }
    redisSeq = newSeq
    return route.fulfill(json(R(row)))
  }
  if (p === '/system/inner-secret/revoke' && req.method() === 'PUT') {
    const body = req.postDataJSON()
    const row = secrets.find((s) => s.id === String(body.id))
    if (!row || row.status === 3 || row.status === 4) {
      return route.fulfill(json(ERR(3050, '密钥记录不存在或已退场')))
    }
    // 契约 §4 3051：作废后活跃集为空（inner 面将全体拒绝）——按活跃集口径判最后一把
    const active = activeRows()
    if (active.length <= 1) {
      return route.fulfill(json(ERR(3051, '禁止作废最后一把生效密钥（请先轮换）')))
    }
    if (row.status === 2) {
      overlapUntil = 0
    }
    row.status = 4
    row.retireTime = '2026-11-09 19:00:00'
    return route.fulfill(json(R(null)))
  }
  return route.fulfill(json(R(null)))
})

/** 查询区 status 下拉（容器定位，沿 notify 脚本 pickTypeFilter 形态） */
async function pickStatusFilter(label) {
  await page.locator('.filter-bar .el-select').first().click()
  const dd = page.locator('.el-select-dropdown:visible').last()
  await dd.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  await dd.locator('.el-select-dropdown__item', { hasText: label }).first().click()
  await sleep(300)
}

/** 行状态 tag 文案（锚定列：密钥掩码唯一） */
async function rowTagText(mask) {
  const row = page.locator('.el-table__row', { hasText: mask }).first()
  return (await row.locator('.el-tag').innerText()).trim()
}

/** seq8 行掩码（S2 轮换产物，S4 锚点复用——从 mock 真相源取，避免测试局部变量跨场景传递） */
const mask8Of = () => secrets.find((s) => s.seq === 8).secretMask

try {
  // ================= S1 登录 + 菜单 + 状态卡 + 流水渲染 =================
  await step('S1', '登录 + 菜单「密钥管理」+ 状态卡回显 + 流水四状态行（作废按钮仅活跃集行）', async () => {
    const lr = await login(page, 'admin', 'admin123')
    assert(lr.ok, `admin 登录应成功（route mock 链）: ${lr.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const menuTexts = await page.evaluate(() => Array.from(document.querySelectorAll('.el-menu .el-menu-item, .el-menu .el-sub-menu__title')).map((el) => (el.textContent || '').trim()).filter(Boolean))
    assert(menuTexts.includes('密钥管理'), `侧边栏应含「密钥管理」（mock user-nav 菜单 22），实际 ${JSON.stringify(menuTexts)}`)
    assert(menuTexts.includes('系统管理'), '侧边栏应含「系统管理」分组')
    await shot(page, 's1-menu.png')
    await page.goto(`${BASE}${PAGE_PATH}`, { waitUntil: 'domcontentloaded' })
    await waitTableIdle(page)
    await sleep(500)
    assertEq(new URL(page.url()).pathname, PAGE_PATH, '应停留在密钥管理页')
    const crumbs = (await h.breadcrumbTexts(page)).join('>')
    assert(crumbs.includes('密钥管理'), `面包屑应含密钥管理，实际 "${crumbs}"`)
    // 状态卡回显：三参数只读 + 派生值 + 分发正常 tag
    const card = page.locator('.status-card')
    await card.locator('.el-descriptions').waitFor({ state: 'visible', timeout: 8000 })
    const cardText = await card.innerText()
    for (const expect of ['密钥有效期', '168 小时', '重叠窗', '180 秒', '提前轮换预算', '24 小时', '当前生效代数', '第 7 代', '活跃密钥数', '2 把', '预计下次轮换', '2026-11-15 16:00:00', '分发状态', '分发正常']) {
      assert(cardText.includes(expect), `状态卡应含「${expect}」，实际 "${cardText.replace(/\n/g, '|')}"`)
    }
    assertEq(await card.locator('.el-descriptions input, .el-descriptions select, .el-descriptions textarea').count(), 0, '状态卡为只读回显，不应含任何编辑控件')
    // 流水：表头 9 列 + 四状态行（tag 文案断言）+ 掩码列 + 分页 total
    const headers = await page.evaluate(() => Array.from(document.querySelectorAll('.el-table__header th .cell')).map((el) => el.textContent.trim()).filter(Boolean))
    for (const hd of HEADERS) assert(headers.includes(hd), `表头缺列「${hd}」，实际 ${JSON.stringify(headers)}`)
    assertEq(await page.locator('.el-table__row').count(), 4, '初始应 4 行流水（四状态各一）')
    assertEq(await rowTagText('****ab3f'), '生效中', 'seq7 行应为「生效中」')
    assertEq(await rowTagText('****c2d1'), '重叠期', 'seq6 行应为「重叠期」')
    assertEq(await rowTagText('****9e77'), '已过期', 'seq5 行应为「已过期」')
    assertEq(await rowTagText('****31cc'), '已作废', 'seq4 行应为「已作废」')
    const seq7Cells = await rowCells(page.locator('.el-table__row', { hasText: '****ab3f' }).first())
    assertEq(seq7Cells[0], '7', '代数列应为 7')
    assertEq(seq7Cells[3], '定时任务', 'seq7 来源应为「定时任务」（createBy=handler 名）')
    assert(seq7Cells[6].includes('-'), 'seq7 未被顶替，退场时间应为 -')
    // 作废按钮仅活跃集（1/2）行可见——3/4 行无按钮（UI 面防线，后端 3050 兜底）
    let revokeButtons = 0
    for (let i = 0; i < 4; i++) {
      revokeButtons += await page.locator('.el-table__row').nth(i).getByRole('button', { name: '作废', exact: true }).count()
    }
    assertEq(revokeButtons, 2, '作废按钮应仅在 status∈{1,2} 两行出现')
    assert((await page.locator('.el-pagination__total').innerText()).includes('4'), '分页 total 应为 4')
    await shot(page, 's1-list.png')
  })

  // ================= S2 立即轮换 =================
  await step('S2', '立即轮换：新行 seq8 生效中/手动操作 + 前任转重叠期 + 更早重叠行转过期 + 状态卡刷新', async () => {
    await page.getByRole('button', { name: '立即轮换' }).click()
    await waitToast(page, '已轮换至第 8 代', 'success')
    await waitTableIdle(page)
    await sleep(500)
    const mask8 = lastMask
    assertEq(await rowTagText(mask8), '生效中', '轮换新行应为「生效中」')
    const cells8 = await rowCells(page.locator('.el-table__row', { hasText: mask8 }).first())
    assertEq(cells8[0], '8', '新行代数应为 8')
    assertEq(cells8[3], '手动操作', '新行来源应为「手动操作」')
    assertEq(cells8[7], 'admin', '新行创建人应为 admin')
    assertEq(await rowTagText('****ab3f'), '重叠期', '前任 seq7 应转「重叠期」')
    assertEq(await rowTagText('****c2d1'), '已过期', '更早重叠行 seq6 应转「已过期」')
    assertEq(await page.locator('.el-table__row').count(), 5, '轮换后应 5 行')
    const cardText = await page.locator('.status-card').innerText()
    assert(cardText.includes('第 8 代'), `状态卡应刷新到第 8 代，实际 "${cardText.replace(/\n/g, '|')}"`)
    assert(cardText.includes('2 把') && cardText.includes('分发正常'), '状态卡活跃数 2 把且分发正常')
    await shot(page, 's2-rotated.png')
  })

  // ================= S3 频控 3053 =================
  await step('S3', '频控 3053：重叠窗内再轮换 → toast 透出后端 msg（含剩余秒数）', async () => {
    await page.getByRole('button', { name: '立即轮换' }).click()
    const toast = await waitToast(page, '轮换请求过于频繁', 'error')
    assert(/剩余 \d+ 秒/.test(toast), `3053 msg 应含剩余秒数，实际 "${toast}"`)
    await waitTableIdle(page)
    await sleep(400)
    assertEq(await page.locator('.el-table__row').count(), 5, '3053 被拒后行数不变（未落库）')
    await shot(page, 's3-freq-3053.png')
  })

  // ================= S4 作废流（确认弹窗 / 3052 / 3051） =================
  await step('S4', '作废流：确认弹窗含「最迟约 30 秒内生效」→ 重叠期行作废成功；3052 发布失败（已落库+等待自动重发）；最后一把 → 3051', async () => {
    // a. 作废重叠期行 seq7：确认弹窗契约 §4 文案 → 行变已作废 + 状态卡活跃数回 1
    const row7 = page.locator('.el-table__row', { hasText: '****ab3f' })
    await row7.getByRole('button', { name: '作废', exact: true }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const boxText = await box.innerText()
    assert(boxText.includes('将立即断开重叠窗内的在途服务间调用，最迟约 30 秒内生效'), `作废确认框必须明示契约 §4 非瞬时语义文案，实际 "${boxText.replace(/\n/g, '|')}"`)
    assert(boxText.includes('第 7 代') && boxText.includes('****ab3f'), '确认框文案应含目标代数与掩码')
    await shot(page, 's4a-revoke-confirm.png')
    await box.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '第 7 代已作废', 'success')
    await waitTableIdle(page)
    await sleep(500)
    assertEq(await rowTagText('****ab3f'), '已作废', '作废后行应为「已作废」终态')
    assert((await page.locator('.status-card').innerText()).includes('1 把'), '作废重叠期行后活跃数应回 1 把')

    // b. 发布失败 3052（mock publishFail：新钥已落库生效，Redis 版本停旧值）
    publishFail = true
    await page.getByRole('button', { name: '立即轮换' }).click()
    await waitToast(page, '活跃集发布失败', 'error')
    await waitTableIdle(page)
    await sleep(500)
    const mask9 = lastMask
    assertEq(await rowTagText(mask9), '生效中', '3052 是软失败：新钥 seq9 已落库生效')
    assertEq(await rowTagText(mask8Of()), '重叠期', '3052 下前任 seq8 仍转「重叠期」（变更已落库）')
    const cardText = await page.locator('.status-card').innerText()
    assert(cardText.includes('第 9 代'), `3052 后状态卡应显示第 9 代（DB 真态），实际 "${cardText.replace(/\n/g, '|')}"`)
    const warnTag = page.locator('.status-card .el-tag', { hasText: '等待自动重发' })
    assertEq(await warnTag.count(), 1, 'redisConsistent=false 应显示「等待自动重发」warning tag')
    await shot(page, 's4b-publish-fail-3052.png')

    // c. 作废 seq8（重叠期，仍有继任者——放行）
    publishFail = false
    const row8 = page.locator('.el-table__row', { hasText: mask8Of() })
    await row8.getByRole('button', { name: '作废', exact: true }).click()
    await box.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '第 8 代已作废', 'success')
    await waitTableIdle(page)
    await sleep(400)

    // d. 最后一把生效密钥 seq9 → 3051
    const row9 = page.locator('.el-table__row', { hasText: mask9 })
    await row9.getByRole('button', { name: '作废', exact: true }).click()
    await box.waitFor({ state: 'visible', timeout: 8000 })
    await box.getByRole('button', { name: '确定' }).click()
    const toast = await waitToast(page, '禁止作废最后一把生效密钥', 'error')
    assert(toast.includes('请先轮换'), `3051 msg 应含处置指引，实际 "${toast}"`)
    await waitTableIdle(page)
    await sleep(400)
    assertEq(await rowTagText(mask9), '生效中', '3051 被拒后最后一把应仍「生效中」')
    await shot(page, 's4d-last-key-3051.png')
  })

  // ================= S5 掩码红线 + status 过滤 =================
  await step('S5', '掩码红线：全部掩码 ****+4hex、无明文形态字段 + status 过滤（已作废 → 恰 3 行）', async () => {
    const masks = page.locator('.el-table__row .mono-text')
    const n = await masks.count()
    assertEq(n, 6, `作废/轮换流转后应 6 行流水，实际 ${n}`)
    for (let i = 0; i < n; i++) {
      const t = ((await masks.nth(i).innerText()) || '').trim()
      assert(/^\*{4}[0-9a-f]{4}$/.test(t), `掩码列应为 ****+尾4 形态，第 ${i} 行实际 "${t}"`)
    }
    // 页面无明文形态字段：无 16+ 位连续十六进制串（密钥真值形态）
    const pageText = await page.locator('.inner-secret-page').innerText()
    assert(!/[0-9a-f]{16,}/i.test(pageText), '页面不应出现密钥明文形态的十六进制长串')
    assertEq(await page.locator('.inner-secret-page input[type=password]').count(), 0, '页面不应有密码框形态的密钥展示位')
    // status 过滤：已作废 → 恰 3 行（seq4/7/8）且每行 tag=已作废
    await pickStatusFilter('已作废')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await waitTableIdle(page)
    await sleep(400)
    const revokedRows = page.locator('.el-table__row')
    assertEq(await revokedRows.count(), 3, 'status=已作废 过滤后应恰 3 行')
    for (let i = 0; i < 3; i++) {
      const cells = await rowCells(revokedRows.nth(i))
      assertEq(cells[1], '已作废', `过滤后第 ${i} 行状态应为已作废，实际 "${cells[1]}"`)
    }
    await page.getByRole('button', { name: '重置', exact: true }).click()
    await waitTableIdle(page)
    await sleep(400)
    assertEq(await page.locator('.el-table__row').count(), 6, '重置后应回 6 行')
    await shot(page, 's5-filter-done.png')
  })

  // ================= 证据核验 =================
  await step('S-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（route mock 恒 200），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  h.summary({
    extras: [
      '测试数据: 全部在 route mock 内存态（secrets 数组流转），零真实后端写；admin 只登录不改',
      'mock 面: page.route(**/api/**)——登录链(sso/system user-nav+me+logout) + 工作台空态 + 铃铛 summary + inner-secret 四端点（3053/3052/3051 分支按 mock 态机触发）；后端 gateway/sso/system 未启动亦可跑（联调归主控集成段）',
      '断言口径: 契约 §0 枚举映射/§1 流水四状态行/§2 状态卡派生与只读/§3 轮换代际推进+3053/§4 确认弹窗 30 秒文案+3051/掩码红线',
      '注记: 3050（已退场行无作废按钮，UI 面防线）与 revoke 3052 分支不在 UI 面——rotate 3052 已覆盖发布失败语义',
    ],
  })
  await browser.close()
}
