/**
 * 任务中心（异步任务）e2e（契约 2026-11-10-task-center-api §12 / 计划 2026-11-10-task-center F2）
 *
 * 运行前提：**仅前端 dev 5173**（cloud-task 后端由并行轨道实现，本脚本零后端依赖）——
 * 登录链（sso/system 菜单权限）、工作台首屏、任务中心四端点与演示提交端点全部在浏览器
 * 网络层 route mock（沿 run-inner-secret-e2e.mjs 先例；mock 态在脚本内存，结束即清）。
 * 运行：cd cloud-e2e && npm run e2e:taskcenter（单跑，不进 run-e2e.mjs 聚合，沿 aichat/notify 先例）；
 * E2E_HEADLESS=1 或 --headless 无头。黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：全部在 route mock 内存态（零真实后端写）；admin 种子账号只登录不改。
 *
 * 场景（契约 §12 六段 + 卫生）：
 * - S1 登录+菜单「任务中心/异步任务」→ 列表渲染：五状态行（tag 色板文案映射）+ 进度列
 *   （仅执行中行渲染 el-progress，终态行纯文本）+ 失败行 title 悬浮 error_msg + 表头 + 分页 total
 * - S2 提交演示任务（默认参数）→ toast → 排队新行出现 + 行数 5→6
 * - S3 进度轮询：mock 分次返回 progress 30→70→100、状态 1→2（2s 轮询驱动）→
 *   断言行内进度文本流转 + 状态 tag 转「成功」+ 终态后进度条让位纯文本
 * - S4 取消两态：排队行 → 确认文案「取消立即生效」→ 状态变已取消、按钮消失；
 *   执行中行 → 确认文案必含「将在任务下一检查点生效」（契约 §5 协作式语义）→
 *   toast 后状态仍执行中（生效前不变）
 * - S5 重试：失败行 → toast → 状态回排队、errorMsg 清空（title 属性消失）、retryCount +1；
 *   成功行无重试/取消按钮（按钮显隐断言——6005 的 UI 面防线）
 * - S6 详情弹窗：全字段回显 + params/result JSON 格式化 pre 展示 + 底部动作按钮显隐同列表口径
 * - S-VERIFY 环境卫生（console/pageerror/>=400/网络失败——favicon 404 噪音滤除）
 *
 * 不在本面：6004/6005/6006 toast 分支（按钮显隐已把不可达态挡在 UI 外，业务码 toast 由
 * 拦截器结构保证——route mock 下无真实后端可构造并发竞争窗口）；真后端联动场景归主控集成段。
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login, waitToast, waitDialogGone, waitTableIdle, rowCells } = h

const PAGE_PATH = '/task/async'

/** 管理页表头 9 列（实现面 index.vue 列序） */
const HEADERS = ['任务ID', '类型', '状态', '进度', '重试', '提交人', '来源', '提交时间', '操作']

/** 状态列下标（rowCells 锚点）：0 任务ID / 2 状态 / 3 进度 / 4 重试 */
const CELL_STATUS = 2
const CELL_PROGRESS = 3
const CELL_RETRY = 4

// ---------- mock 数据（分层：五状态种子行 / 轮询 tick 序列 / 取消重试状态机迁移） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const ERR = (code, msg) => JSON.stringify({ code, msg, data: null })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

const STATUS_TEXT = { 0: '排队', 1: '执行中', 2: '成功', 3: '失败', 4: '已取消' }

const mkTask = (over) => ({
  taskId: '0',
  taskType: 'demo-batch',
  bizKey: null,
  status: 0,
  progress: 0,
  phase: null,
  retryCount: 0,
  maxRetries: 3,
  errorMsg: null,
  submitBy: 'admin',
  submitSource: 'cloud-system',
  createTime: '2026-11-12 10:00:00',
  startTime: null,
  finishTime: null,
  ...over,
})

/** 任务表（mock 唯一真相源：page/detail/cancel/retry/submit 全部读改此表）。
 *  六行覆盖全五状态（契约 §12「五状态 mock 行」逐状态可断言）：两执行中行分工——
 *  9001…01 是 S3 轮询演示行（tick 流转）、9001…02 是 S4 执行中取消靶行（须恒留执行中） */
const tasks = [
  mkTask({ taskId: '9001000000000001', status: 1, progress: 30, phase: '批处理中 3/10', createTime: '2026-11-12 10:00:06', startTime: '2026-11-12 10:00:07' }),
  mkTask({ taskId: '9001000000000002', status: 1, progress: 45, phase: '批处理中 5/10', createTime: '2026-11-12 10:00:05', startTime: '2026-11-12 10:00:06' }),
  mkTask({ taskId: '9001000000000003', status: 0, createTime: '2026-11-12 10:00:04' }),
  mkTask({ taskId: '9001000000000004', status: 2, progress: 100, createTime: '2026-11-12 10:00:03', startTime: '2026-11-12 10:00:04', finishTime: '2026-11-12 10:00:20' }),
  mkTask({ taskId: '9001000000000005', status: 3, progress: 60, phase: null, retryCount: 2, errorMsg: '模拟失败：注入失败百分比 100%', createTime: '2026-11-12 10:00:02', startTime: '2026-11-12 10:00:03', finishTime: '2026-11-12 10:00:30' }),
  mkTask({ taskId: '9001000000000006', status: 4, createTime: '2026-11-12 10:00:01', finishTime: '2026-11-12 10:00:15' }),
]

/**
 * 轮询演示任务（9001…01）的 tick 序列：每次 /task/page 响应后步进（仅 pollArmed 时）。
 * 30%(执行中) → 70%(执行中) → 100%(成功终态，停进)——S3 armed 前恒 30%，场景间互不抢拍。
 */
const POLL_TASK_ID = '9001000000000001'
const POLL_TICKS = [
  { status: 1, progress: 30, phase: '批处理中 3/10' },
  { status: 1, progress: 70, phase: '批处理中 7/10' },
  { status: 2, progress: 100, phase: null },
]
let pollArmed = false
let pollTick = 0

let nextDemoId = 9001000000000100

const rowById = (id) => tasks.find((t) => t.taskId === String(id))

// ---------- route mock（登录链 + 工作台首屏 + 铃铛轮询 + 任务中心五端点） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}；mock 种子六行覆盖全五状态 + 轮询/取消/重试状态机`)
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
    return route.fulfill(json(R({ account: 'admin', permissions: ['task:asyncTask:list', 'task:asyncTask:cancel', 'task:asyncTask:retry', 'task:demo:submit'] })))
  }
  if (p === '/sso/auth/logout' && req.method() === 'POST') {
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/menu/user-nav' && req.method() === 'GET') {
    // 最小导航树：任务中心(M 40, sort 5, icon Timer) > 异步任务(C 401, /task/async, icon List)
    return route.fulfill(json(R([
      { id: '40', parentId: '0', name: '任务中心', type: 'M', path: '', icon: 'Timer', sort: 5, children: [
        { id: '401', parentId: '40', name: '异步任务', type: 'C', path: '/task/async', icon: 'List', sort: 1, children: [] },
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

  // ---- 任务中心域（契约 §3-§7）----
  if (p === '/task/page' && req.method() === 'GET') {
    // 轮询 tick 步进：响应前迁移到下一态（30→30→70→100，tick 走满即停——终态恒定返回）
    if (pollArmed && pollTick < POLL_TICKS.length) {
      const apply = () => {
        const row = rowById(POLL_TASK_ID)
        const tick = POLL_TICKS[pollTick]
        Object.assign(row, tick)
        if (tick.status === 2) {
          row.finishTime = '2026-11-12 10:01:00'
          row.startTime = row.startTime ?? '2026-11-12 10:00:06'
        }
      }
      apply()
      pollTick += 1
    }
    let rows = [...tasks].sort((a, b) => (a.createTime < b.createTime ? 1 : -1))
    if (q.get('status')) rows = rows.filter((t) => t.status === Number(q.get('status')))
    if (q.get('taskType')) rows = rows.filter((t) => t.taskType === q.get('taskType'))
    if (q.get('submitBy')) rows = rows.filter((t) => t.submitBy === q.get('submitBy'))
    const pageNum = Math.max(1, Number(q.get('pageNum') ?? 1))
    const pageSize = Math.max(1, Number(q.get('pageSize') ?? 10))
    return route.fulfill(json(R({ total: String(rows.length), rows: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize) })))
  }
  // 详情（/task/{id}）：列表行字段 + params/result/timeoutSeconds/cancelRequested/executorId
  if (/^\/task\/\d+$/.test(p) && req.method() === 'GET') {
    const row = rowById(p.split('/')[2])
    if (!row) return route.fulfill(json(ERR(6006, '任务不存在')))
    return route.fulfill(json(R({
      ...row,
      params: '{"total":100,"failPercent":0}',
      result: row.status === 2 ? '{"processed": 100}' : null,
      timeoutSeconds: 600,
      cancelRequested: row.cancelRequested ?? 0,
      executorId: row.status === 1 ? 'task-host:9204' : null,
    })))
  }
  // 取消（契约 §5 两态语义）：排队直接置终态；执行中协作式（仅置标志，状态不变）
  if (/^\/task\/\d+\/cancel$/.test(p) && req.method() === 'POST') {
    const row = rowById(p.split('/')[2])
    if (!row) return route.fulfill(json(ERR(6006, '任务不存在')))
    if (row.status === 0) {
      row.status = 4
      row.finishTime = '2026-11-12 10:02:00'
      return route.fulfill(json(R(null)))
    }
    if (row.status === 1) {
      row.cancelRequested = 1
      return route.fulfill(json(R(null)))
    }
    return route.fulfill(json(ERR(6004, '当前状态不允许取消')))
  }
  // 重试（契约 §6）：仅失败/已取消 → 回排队（retryCount+1、进度/错误/结果清空）
  if (/^\/task\/\d+\/retry$/.test(p) && req.method() === 'POST') {
    const row = rowById(p.split('/')[2])
    if (!row) return route.fulfill(json(ERR(6006, '任务不存在')))
    if (row.status === 3 || row.status === 4) {
      row.status = 0
      row.progress = 0
      row.phase = null
      row.errorMsg = null
      row.result = null
      row.retryCount += 1
      row.startTime = null
      row.finishTime = null
      row.cancelRequested = 0
      row.executorId = null
      return route.fulfill(json(R(null)))
    }
    return route.fulfill(json(ERR(6005, '当前状态不允许重试')))
  }
  // 演示提交（契约 §7，端点在 cloud-system——不是 /task 前缀）
  if (p === '/system/task-demo/submit' && req.method() === 'POST') {
    const body = req.postDataJSON() ?? {}
    const id = String(nextDemoId++)
    tasks.push(mkTask({ taskId: id, createTime: '2026-11-12 10:03:30' }))
    log(`  [mock] task-demo/submit total=${body.total ?? 100} failPercent=${body.failPercent ?? 0} → taskId=${id}`)
    return route.fulfill(json(R({ taskId: id })))
  }
  return route.fulfill(json(R(null)))
})

/** 行定位（任务ID 列锚定唯一） */
const rowOf = (taskId) => page.locator('.el-table__row', { hasText: taskId }).first()

/** 行状态 tag 文案 */
async function rowTagText(taskId) {
  const row = rowOf(taskId)
  return (await row.locator('.el-tag').first().innerText()).trim()
}

/**
 * 轮询等待行的进度单元格出现目标百分比（2s 轮询驱动，最多 15s）。
 * @param expected 如 '70%' / '100%'
 */
async function waitRowProgress(taskId, expected, timeoutMs = 15000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const text = (await rowOf(taskId).locator('td').nth(CELL_PROGRESS).innerText()).trim()
    if (text.includes(expected)) return text
    await sleep(400)
  }
  throw new Error(`等待行 ${taskId} 进度含 "${expected}" 超时`)
}

try {
  // ================= S1 登录 + 菜单 + 列表渲染 =================
  await step('S1', '登录 + 菜单「任务中心/异步任务」→ 五状态行 + 进度列（仅执行中 el-progress）+ 表头 + 分页', async () => {
    const lr = await login(page, 'admin', 'admin123')
    assert(lr.ok, `admin 登录应成功（route mock 链）: ${lr.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const menuTexts = await page.evaluate(() => Array.from(document.querySelectorAll('.el-menu .el-menu-item, .el-menu .el-sub-menu__title')).map((el) => (el.textContent || '').trim()).filter(Boolean))
    assert(menuTexts.includes('任务中心'), `侧边栏应含顶级目录「任务中心」（mock user-nav 菜单 40），实际 ${JSON.stringify(menuTexts)}`)
    assert(menuTexts.includes('异步任务'), `侧边栏应含「异步任务」（mock user-nav 菜单 401），实际 ${JSON.stringify(menuTexts)}`)
    await shot(page, 'tc-s1-menu.png')
    // 菜单点击导航（el-menu router 模式）
    await page.locator('.el-menu .el-menu-item', { hasText: '异步任务' }).first().click()
    await page.waitForURL(`**${PAGE_PATH}`, { timeout: 10000 })
    await waitTableIdle(page)
    await sleep(500)
    assertEq(new URL(page.url()).pathname, PAGE_PATH, '应停留在异步任务页')
    const crumbs = (await h.breadcrumbTexts(page)).join('>')
    assert(crumbs.includes('异步任务'), `面包屑应含异步任务，实际 "${crumbs}"`)
    // 表头 9 列
    const headers = await page.evaluate(() => Array.from(document.querySelectorAll('.el-table__header th .cell')).map((el) => el.textContent.trim()).filter(Boolean))
    for (const hd of HEADERS) assert(headers.includes(hd), `表头缺列「${hd}」，实际 ${JSON.stringify(headers)}`)
    // 六行覆盖全五状态（tag 文案映射契约 §8 枚举；执行中两行分别服务轮询/取消场景）
    assertEq(await page.locator('.el-table__row').count(), 6, '初始应 6 行（五状态全覆盖，执行中两行）')
    assertEq(await rowTagText('9001000000000001'), '执行中', '9001…01 行应为「执行中」')
    assertEq(await rowTagText('9001000000000003'), '排队', '9001…03 行应为「排队」')
    assertEq(await rowTagText('9001000000000004'), '成功', '9001…04 行应为「成功」')
    assertEq(await rowTagText('9001000000000005'), '失败', '9001…05 行应为「失败」')
    assertEq(await rowTagText('9001000000000006'), '已取消', '9001…06 行应为「已取消」')
    // 进度列形态：执行中行渲染 el-progress（+阶段文本），成功终态行纯文本 100%、无进度条
    assertEq(await rowOf('9001000000000002').locator('.el-progress').count(), 1, '执行中行（9001…02）应渲染 el-progress')
    const runningProgress = (await rowOf('9001000000000002').locator('td').nth(CELL_PROGRESS).innerText()).trim()
    assert(runningProgress.includes('45%') && runningProgress.includes('批处理中'), `执行中行进度列应含 45% 与阶段文本，实际 "${runningProgress.replace(/\n/g, '|')}"`)
    const successProgress = (await rowOf('9001000000000004').locator('td').nth(CELL_PROGRESS).innerText()).trim()
    assertEq(successProgress, '100%', '成功终态行进度列应为纯文本 100%')
    assertEq(await rowOf('9001000000000004').locator('.el-progress').count(), 0, '终态行不应渲染 el-progress')
    // 失败行 error_msg 悬浮（原生 title 属性——列表面的失败原因审计线索）
    const failedTitle = await rowOf('9001000000000005').locator(`td:nth-child(${CELL_STATUS + 1}) span[title]`).first().getAttribute('title')
    assert(failedTitle && failedTitle.includes('模拟失败'), `失败行状态列 title 应含 error_msg，实际 "${failedTitle}"`)
    // 提交人/来源列
    const queuedCells = await rowCells(rowOf('9001000000000003'))
    assertEq(queuedCells[5], 'admin', '提交人列应为 admin')
    assertEq(queuedCells[6], 'cloud-system', '来源列应为 cloud-system')
    assert((await page.locator('.el-pagination__total').innerText()).includes('6'), '分页 total 应为 6')
    await shot(page, 'tc-s1-list.png')
  })

  // ================= S2 提交演示任务（默认参数） =================
  await step('S2', '提交演示任务（弹窗默认参数）→ mock 200 → toast → 排队新行出现（6→7 行）', async () => {
    await page.getByRole('button', { name: '提交演示任务' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '提交演示任务' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await shot(page, 'tc-s2-dialog.png')
    // 默认参数直接提交（total=100 / failPercent=0 是契约 §7 默认值）
    await dlg.getByRole('button', { name: '提交', exact: true }).click()
    await waitToast(page, '任务已提交', 'success')
    await waitDialogGone(page, '提交演示任务')
    await waitTableIdle(page)
    await sleep(500)
    assertEq(await page.locator('.el-table__row').count(), 7, '提交后应 7 行（+1 排队行）')
    const demoRow = rowOf('9001000000000100')
    assert(await demoRow.count() > 0, '新提交任务行（taskId 9001000000000100）应出现')
    assertEq(await rowTagText('9001000000000100'), '排队', '新任务行应为「排队」')
    assert((await page.locator('.el-pagination__total').innerText()).includes('7'), '分页 total 应为 7')
    await shot(page, 'tc-s2-submitted.png')
  })

  // ================= S3 进度轮询（2s 驱动 tick 30→70→100，状态 1→2） =================
  await step('S3', '进度轮询：mock 30%→70%→100%、状态 执行中→成功 → 行内进度与状态 tag 流转', async () => {
    // armed 前 tick 恒 0（30% 执行中）——先固化起点
    assertEq(await rowTagText(POLL_TASK_ID), '执行中', '轮询演示行起点应为「执行中」')
    const startProgress = (await rowOf(POLL_TASK_ID).locator('td').nth(CELL_PROGRESS).innerText()).trim()
    assert(startProgress.includes('30%'), `轮询演示行起点进度应为 30%，实际 "${startProgress.replace(/\n/g, '|')}"`)
    pollArmed = true
    // 2s 轮询驱动：等待 70%（执行中）→ 100%（成功）
    const midProgress = await waitRowProgress(POLL_TASK_ID, '70%')
    assert(midProgress.includes('70%'), `中间态应见 70%，实际 "${midProgress.replace(/\n/g, '|')}"`)
    assertEq(await rowTagText(POLL_TASK_ID), '执行中', '70% 时状态应仍「执行中」')
    await shot(page, 'tc-s3-progress-70.png')
    await waitRowProgress(POLL_TASK_ID, '100%')
    assertEq(await rowTagText(POLL_TASK_ID), '成功', '进度到 100% 后状态 tag 应转「成功」')
    assertEq(await rowOf(POLL_TASK_ID).locator('.el-progress').count(), 0, '转终态后进度条应让位纯文本')
    await shot(page, 'tc-s3-terminal.png')
  })

  // ================= S4 取消两态（排队立即 / 执行中协作式） =================
  await step('S4', '取消两态：排队行 → 「取消立即生效」→ 已取消+按钮消失；执行中行 → 确认文案含「下一检查点」→ 状态仍执行中', async () => {
    const box = page.locator('.el-message-box')
    // a. 排队行取消（立即生效语义）
    await rowOf('9001000000000003').getByRole('button', { name: '取消', exact: true }).click()
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const queuedBoxText = await box.innerText()
    assert(queuedBoxText.includes('取消立即生效'), `排队行确认框应明示立即生效语义，实际 "${queuedBoxText.replace(/\n/g, '|')}"`)
    await box.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '已取消', 'success')
    await waitTableIdle(page)
    await sleep(500)
    assertEq(await rowTagText('9001000000000003'), '已取消', '排队行取消后应转「已取消」')
    assertEq(await rowOf('9001000000000003').getByRole('button', { name: '取消', exact: true }).count(), 0, '已取消行不应再有取消按钮')

    // b. 执行中行取消（协作式——契约 §5 非瞬时语义文案）
    await rowOf('9001000000000002').getByRole('button', { name: '取消', exact: true }).click()
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const runningBoxText = await box.innerText()
    assert(runningBoxText.includes('将在任务下一检查点生效'), `执行中行确认框必须明示契约 §5 协作式语义文案，实际 "${runningBoxText.replace(/\n/g, '|')}"`)
    await shot(page, 'tc-s4-cancel-confirm.png')
    await box.getByRole('button', { name: '确定' }).click()
    const toast = await waitToast(page, '已请求取消', 'success')
    assert(toast.includes('下一检查点'), `执行中取消 toast 应含检查点语义，实际 "${toast}"`)
    await waitTableIdle(page)
    await sleep(500)
    assertEq(await rowTagText('9001000000000002'), '执行中', '执行中取消请求后状态应仍「执行中」（检查点生效前不变）')
    await shot(page, 'tc-s4-after.png')
  })

  // ================= S5 重试（失败行）+ 成功行按钮显隐 =================
  await step('S5', '重试：失败行 → toast → 状态回排队 + errorMsg 清空 + retryCount+1；成功行无重试/取消按钮', async () => {
    // a. 失败行重试
    const beforeCells = await rowCells(rowOf('9001000000000005'))
    assertEq(beforeCells[CELL_RETRY], '2/3', '重试前 retryCount 应为 2/3')
    await rowOf('9001000000000005').getByRole('button', { name: '重试', exact: true }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    await box.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '已重新排队', 'success')
    await waitTableIdle(page)
    await sleep(500)
    assertEq(await rowTagText('9001000000000005'), '排队', '重试后状态应回「排队」')
    const afterCells = await rowCells(rowOf('9001000000000005'))
    assertEq(afterCells[CELL_RETRY], '3/3', '重试后 retryCount 应 +1 至 3/3')
    const failedTitle = await rowOf('9001000000000005').locator(`td:nth-child(${CELL_STATUS + 1}) span[title]`).count()
    assertEq(failedTitle, 0, '重试后 errorMsg 已清空，状态列 title 属性应消失')
    // b. 成功行按钮显隐（6005 的 UI 面防线——终态行不给动作入口）
    assertEq(await rowOf('9001000000000004').getByRole('button', { name: '重试', exact: true }).count(), 0, '成功行不应有重试按钮')
    assertEq(await rowOf('9001000000000004').getByRole('button', { name: '取消', exact: true }).count(), 0, '成功行不应有取消按钮')
    await shot(page, 'tc-s5-after-retry.png')
  })

  // ================= S6 详情弹窗（字段回显 + JSON pre + 按钮口径） =================
  await step('S6', '详情弹窗：全字段回显 + params/result JSON 格式化 pre + 动作按钮显隐同列表口径', async () => {
    // a. 成功行详情：params/result JSON 展示 + 终态无动作按钮
    await rowOf('9001000000000004').getByRole('button', { name: '详情', exact: true }).click()
    let dlg = page.locator('.el-dialog', { hasText: '任务详情' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(400)
    let text = await dlg.innerText()
    for (const expect of ['9001000000000004', 'demo-batch', '成功', '100%', 'admin', 'cloud-system', '600 秒', '"total": 100', '"failPercent": 0', '"processed": 100']) {
      assert(text.includes(expect), `成功行详情应含「${expect}」，实际 "${text.replace(/\n/g, '|').slice(0, 400)}"`)
    }
    assertEq(await dlg.locator('pre').count(), 2, 'params/result 应各一个 pre 展示位')
    assertEq(await dlg.getByRole('button', { name: '取消任务' }).count(), 0, '终态详情不应有取消按钮')
    assertEq(await dlg.getByRole('button', { name: '重试任务' }).count(), 0, '终态详情不应有重试按钮')
    await shot(page, 'tc-s6-detail-success.png')
    // exact 防子串撞弹窗头部关闭钮（aria-label「关闭此对话框」——getByRole name 默认子串匹配）
    await dlg.getByRole('button', { name: '关闭', exact: true }).click()
    await waitDialogGone(page, '任务详情')

    // b. 执行中行详情：取消任务按钮在场（显隐同列表口径）
    await rowOf('9001000000000002').getByRole('button', { name: '详情', exact: true }).click()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(400)
    text = await dlg.innerText()
    assert(text.includes('task-host:9204'), `执行中详情应回显执行器标识，实际 "${text.replace(/\n/g, '|').slice(0, 400)}"`)
    assertEq(await dlg.getByRole('button', { name: '取消任务' }).count(), 1, '执行中详情应有取消任务按钮')
    await shot(page, 'tc-s6-detail-running.png')
    await dlg.getByRole('button', { name: '关闭', exact: true }).click()
    await waitDialogGone(page, '任务详情')
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
      '测试数据: 全部在 route mock 内存态（tasks 数组状态机流转），零真实后端写；admin 只登录不改',
      'mock 面: page.route(**/api/**)——登录链(sso/system user-nav+me+logout) + 工作台空态 + 铃铛 summary + /task/page|{id}|cancel|retry + /system/task-demo/submit（演示端点在 cloud-system 非 /task 前缀）；后端 gateway/cloud-task 未启动亦可跑（联调归主控集成段）',
      '断言口径: 契约 §3 五状态行/§4 详情全字段/§5 取消两态（执行中确认框必含「将在任务下一检查点生效」）/§6 重试回排队/§7 演示提交默认参数/§8 仅执行中行 el-progress + 2s 轮询驱动 tick 流转',
      '注记: 6004/6005/6006 toast 分支不在 UI 面——按钮显隐已把不可达态挡在 UI 外（业务码 toast 由拦截器结构保证）；轮询停止分支（全终态停轮询）因 mock 恒留执行中行不可断言，归真后端回归',
    ],
  })
  await browser.close()
}
