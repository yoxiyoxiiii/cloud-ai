/**
 * 聚合服务工作台 e2e（契约 2026-11-14-aggregate-workbench-api §8-§9 / 计划 2026-11-14-aggregate-service §3 F3）
 *
 * 运行前提：**仅前端 dev 5173**（cloud-aggregate 后端由并行轨道实现，本脚本零后端依赖）——
 * 登录链（sso/system 菜单权限）、工作台聚合端点、铃铛摘要全部在浏览器网络层 route mock
 * （沿 run-task-center-e2e.mjs 先例；mock 态在脚本内存，结束即清）。
 * 运行：cd cloud-e2e && npm run e2e:aggregate（单跑，不进 run-e2e.mjs 聚合，沿 taskcenter/aichat 分层先例）；
 * E2E_HEADLESS=1 或 --headless 无头。判定口径：**只认输出里 [FAIL] 计数为 0（退出码不可信）**。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：全部在 route mock 内存态（零真实后端写）；admin 种子账号只登录不改。
 *
 * 场景（契约 §1.2 段结构 status/msg/data + §2 段状态枚举的三态消费口径）：
 * - S1 全成功（三段 status=200）：两卡渲染——待办卡行（标题/业务类型 tag/申请人/时间，含昵称 null
 *   降级显示 account）+ 审批卡行（状态 tag 四态色/译文 null 降级本地映射/业务类型名）+
 *   「查看全部」按钮在场 + 铃铛未读数渲染（notify 段界面消费归顶栏）+
 *   **旧直调端点零请求**（/bpmn/task/todo 与 /bpmn/approval/page 前端不再发，契约 §8.4）
 * - S2 bpmn 两段 7001（无权限）：两卡整卡隐藏、页面零错误 toast，铃铛摘要不受段失败影响（契约 §2 段间隔离）
 * - S3 bpmn 两段 7002（下游失败）：卡片保留 + 空态区展示段 msg（「cloud-bpmn 服务不可用」），
 *   默认空态文案（暂无待办任务/暂无审批记录）不得出现
 * - S-VERIFY 环境卫生（console/pageerror/>=400/网络失败——favicon 404 噪音滤除）
 *
 * 不在本面：真链路三段 200 断言（依赖真实 cloud-aggregate :9205 + 网关路由，归主控集成段——
 * run-bpmn-e2e.mjs BP12 同批迁移）；401/500 整体失败由拦截器结构保证（route mock 无真实会话竞争窗口）。
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login } = h

const PAGE_PATH = '/dashboard'

// ---------- mock 数据（段结构契约 §1.2：status/msg/data，status≠200 时 data 恒 null） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

const segOk = (data) => ({ status: 200, msg: '操作成功', data })
const segErr = (status, msg) => ({ status, msg, data: null })

/** 待办行（契约 §1.3 todo.data.rows 形态，TaskVo 镜像字段） */
const mkTodo = (over) => ({
  taskId: 'task-101',
  approvalId: '88',
  businessType: 'leave',
  businessTypeName: '请假申请',
  title: '张三的请假申请',
  detailPath: '/system/leave?approval=88',
  applyUser: 'zhangsan',
  applyUserName: '张三',
  createTime: '2026-11-14 10:30:00',
  ...over,
})

/** 审批行（契约 §1.3 approval.data.rows 形态，ApprovalVo 镜像字段） */
const mkApproval = (over) => ({
  id: '92',
  businessType: 'leave',
  businessTypeName: '请假申请',
  businessKey: '456',
  title: '王五的请假申请',
  status: '0',
  statusLabel: '审批中',
  applyUser: 'wangwu',
  applyUserName: '王五',
  approver: 'admin',
  approverName: '管理员',
  detailPath: '/system/leave?approval=92',
  processInstanceId: '7f3a-mock-pid',
  createTime: '2026-11-14 09:12:00',
  updateTime: null,
  ...over,
})

/** 三态工作台响应（wbMode 场景开关）：notify 段三态恒 200（段间隔离——bpmn 段失败不传染，契约 §2） */
const wbByMode = {
  ok: {
    todo: segOk({
      total: '12',
      rows: [
        mkTodo({}),
        // 昵称译文 null → 前端降级显示 applyUser（审批平台契约 §9 降级链）
        mkTodo({ taskId: 'task-102', approvalId: '93', title: '李四的请假申请', applyUser: 'lisi', applyUserName: null, createTime: '2026-11-14 09:00:00' }),
      ],
    }),
    approval: segOk({
      total: '31',
      rows: [
        mkApproval({}),
        // 译文 null → 前端降级本地映射（APPROVAL_STATUS_MAP['2']='已拒绝'），tag 颜色仍按原字段 status
        mkApproval({ id: '90', businessKey: '450', title: '赵六的报销单', businessType: 'expense', businessTypeName: '报销申请', status: '2', statusLabel: null, createTime: '2026-11-13 18:40:00' }),
      ],
    }),
    notify: segOk({ unreadCount: '3', latestId: '128' }),
  },
  noPerm: {
    todo: segErr(7001, '无操作权限'),
    approval: segErr(7001, '无操作权限'),
    notify: segOk({ unreadCount: '3', latestId: '128' }),
  },
  downFail: {
    todo: segErr(7002, 'cloud-bpmn 服务不可用'),
    approval: segErr(7002, 'cloud-bpmn 服务不可用'),
    notify: segOk({ unreadCount: '3', latestId: '128' }),
  },
}

/** 场景开关：S1 ok → S2 noPerm → S3 downFail（每次 goto /dashboard 前拨动） */
let wbMode = 'ok'

/** 契约 §8.4 退役红线：前端不再直调这两个 bpmn 端点——本脚本内存态留痕，S1 断言零请求 */
const legacyCalls = []

// ---------- route mock（登录链 + 工作台聚合端点 + 铃铛摘要） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}；mock 三态工作台（ok/noPerm/downFail）+ 登录链 + 铃铛摘要`)
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

  // ---- 登录链（sso/system：登录、权限快照、导航树、注销）----
  if (p === '/sso/auth/login' && req.method() === 'POST') {
    const ts = Date.now()
    return route.fulfill(json(R({ accessToken: `e2e-token-${ts}`, refreshToken: `e2e-refresh-${ts}`, expiresIn: '3600' })))
  }
  if (p === '/sso/auth/me' && req.method() === 'GET') {
    // 聚合端点登录即可（无 perms）——权限快照给空集即工作台合法形态（段准入预判在后端，契约 §5）
    return route.fulfill(json(R({ account: 'admin', permissions: [] })))
  }
  if (p === '/sso/auth/logout' && req.method() === 'POST') {
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/menu/user-nav' && req.method() === 'GET') {
    // 最小导航树：工作台不经菜单进入（直接 goto URL），给一个占位目录保侧边栏渲染形态
    return route.fulfill(json(R([
      { id: '40', parentId: '0', name: '任务中心', type: 'M', path: '', icon: 'Timer', sort: 5, children: [
        { id: '401', parentId: '40', name: '异步任务', type: 'C', path: '/task/async', icon: 'List', sort: 1, children: [] },
      ] },
    ])))
  }
  // 铃铛 30s 轮询（§3.1 summary，界面消费归顶栏——本轮不动，notify 段数据与它无耦合）
  if (p === '/system/notify/summary' && req.method() === 'GET') {
    return route.fulfill(json(R({ unreadCount: '3', latestId: '128' })))
  }

  // ---- 聚合工作台端点（契约 §1）：三态场景开关 + 旧直调端点留痕 ----
  if (p === '/aggregate/workbench' && req.method() === 'GET') {
    log(`  [mock] /aggregate/workbench → ${wbMode}`)
    return route.fulfill(json(R(wbByMode[wbMode])))
  }
  if ((p === '/bpmn/task/todo' || p === '/bpmn/approval/page') && req.method() === 'GET') {
    legacyCalls.push(p)
    return route.fulfill(json(R({ total: '0', rows: [] })))
  }
  return route.fulfill(json(R(null)))
})

/** 铃铛未读数文本（el-badge content，未读 0 时 v-show 隐藏——innerText 为空串） */
async function badgeText() {
  return (await page.locator('.navbar-bell .el-badge__content').first().innerText().catch(() => '')).trim()
}

/** 轮询等待铃铛未读数出现目标值（首拉在 Navbar 挂载即发，最多 8s） */
async function waitBadge(expected, timeoutMs = 8000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if ((await badgeText()) === expected) return
    await sleep(300)
  }
  throw new Error(`等待铃铛未读数 "${expected}" 超时（实际 "${await badgeText()}"）`)
}

/** 等工作台聚合响应落定并返回段结构（goto 前注册等待，防 onMounted 请求竞态） */
async function gotoDashboardAndWait() {
  const wbP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/aggregate/workbench', { timeout: 15000 })
  await page.goto(`${BASE}${PAGE_PATH}`, { waitUntil: 'domcontentloaded' })
  const res = await wbP
  const body = await res.json()
  assertEq(body.code, 200, '聚合端点业务码应为 200（段级失败不改变整体码，契约 §2）')
  await sleep(500) // 段状态 → computed 派生 → DOM 渲染留一拍
  return body.data
}

try {
  // ================= S1 三段全成功：两卡渲染 + 铃铛摘要 + 旧端点零请求 =================
  await step('S1', '三段 200：待办卡两行（含昵称 null 降级 account）+ 审批卡两行（tag 色/译文 null 降级本地映射）+ 查看全部在场 + 铃铛=3 + 旧直调端点零请求', async () => {
    const lr = await login(page, 'admin', 'admin123')
    assert(lr.ok, `admin 登录应成功（route mock 链）: ${lr.msg || ''}`)
    const wb = await gotoDashboardAndWait()
    assertEq(wb.todo.status, 200, 'todo 段 status 应为 200')
    assertEq(wb.approval.status, 200, 'approval 段 status 应为 200')
    assertEq(wb.notify.status, 200, 'notify 段 status 应为 200')
    // 两卡在场（mock admin 权限空集——工作台登录即可，两卡显隐由段 200 驱动而非前端权限判断）
    const todoCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的待办' }) })
    const approvalCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的审批' }) })
    assertEq(await todoCard.count(), 1, '「我的待办」卡应恰 1 张（段 200）')
    assertEq(await approvalCard.count(), 1, '「我的审批」卡应恰 1 张（段 200）')
    // 待办卡：两行——首行昵称译文渲染、次行昵称 null 降级显示 account
    assertEq(await todoCard.locator('.dash-row').count(), 2, '待办卡应 2 行')
    const todoRow1 = todoCard.locator('.dash-row').first()
    assert((await todoRow1.innerText()).includes('张三的请假申请'), '待办首行应含标题「张三的请假申请」')
    assertEq((await todoRow1.locator('.el-tag').first().innerText()).trim(), '请假申请', '待办行业务类型 tag 应为「请假申请」')
    const todoRow1Text = (await todoRow1.innerText()).replace(/\n/g, ' | ')
    assert(todoRow1Text.includes('张三') && todoRow1Text.includes('2026-11-14 10:30:00'), `待办首行应含昵称「张三」与创建时间，实际 "${todoRow1Text}"`)
    const todoRow2Text = (await todoCard.locator('.dash-row').nth(1).innerText()).replace(/\n/g, ' | ')
    assert(todoRow2Text.includes('李四的请假申请') && todoRow2Text.includes('lisi'), `待办次行昵称 null 应降级显示 applyUser「lisi」，实际 "${todoRow2Text}"`)
    // 审批卡：两行——tag 色 + 文案降级链（statusLabel null → APPROVAL_STATUS_MAP）
    assertEq(await approvalCard.locator('.dash-row').count(), 2, '审批卡应 2 行')
    const approvalRow1 = approvalCard.locator('.dash-row').first()
    const tag1 = approvalRow1.locator('.el-tag').first()
    assertEq((await tag1.innerText()).trim(), '审批中', '审批首行 tag 文案应为「审批中」（statusLabel 译文）')
    assert(((await tag1.getAttribute('class')) || '').includes('el-tag--warning'), 'status=0 行 tag 应为 warning 色')
    const approvalRow2 = approvalCard.locator('.dash-row').nth(1)
    const tag2 = approvalRow2.locator('.el-tag').first()
    assertEq((await tag2.innerText()).trim(), '已拒绝', '审批次行 tag 文案应降级本地映射为「已拒绝」（statusLabel=null）')
    assert(((await tag2.getAttribute('class')) || '').includes('el-tag--danger'), 'status=2 行 tag 应为 danger 色（颜色永远按原字段 status）')
    assert((await approvalRow2.innerText()).includes('报销申请'), '审批次行应含业务类型名「报销申请」')
    // 「查看全部」两处（跳转真断言依赖 /bpmn 动态菜单路由，归 BP12 真链路——本面只断言按钮在场）
    assertEq(await page.getByRole('button', { name: '查看全部' }).count(), 2, '两卡应各有「查看全部」按钮')
    // notify 段界面消费归顶栏：铃铛未读数渲染 3
    await waitBadge('3')
    // 契约 §8.4 退役红线：前端不再直调 bpmn 两端点
    assertEq(legacyCalls.length, 0, `前端不应再直调旧工作台端点（契约 §8.4），实际请求了 ${JSON.stringify(legacyCalls)}`)
    await shot(page, 'agg-s1-all-ok.png')
  })

  // ================= S2 bpmn 两段 7001：两卡整卡隐藏 + 通知不受影响 =================
  await step('S2', 'bpmn 两段 7001：两卡整卡隐藏（无空态残影）+ 零错误 toast + 铃铛未读数不受段失败影响', async () => {
    wbMode = 'noPerm'
    const wb = await gotoDashboardAndWait()
    assertEq(wb.todo.status, 7001, 'todo 段 status 应为 7001')
    assertEq(wb.todo.data, null, '7001 段 data 应恒为 null（契约 §1.2）')
    assertEq(wb.approval.status, 7001, 'approval 段 status 应为 7001')
    // 两卡整卡隐藏（卡片级消失，非空态占位）
    assertEq(await page.locator('.dash-card').count(), 0, '7001 段两卡应整卡隐藏')
    assertEq(await page.locator('.dash-card .el-empty').count(), 0, '隐藏卡不应残留空态占位')
    // 整体 code=200 → 拦截器不 toast——段级失败零错误提示（契约 §2 段间隔离）
    assertEq(await page.locator('.el-message--error').count(), 0, '段级失败不应出现错误 toast（整体 code=200）')
    // notify 段正常：铃铛未读数不受 bpmn 段失败影响
    await waitBadge('3')
    await shot(page, 'agg-s2-no-perm.png')
  })

  // ================= S3 bpmn 两段 7002：卡片保留 + 空态区展示段 msg =================
  await step('S3', 'bpmn 两段 7002：卡片保留 + 空态区展示段 msg「cloud-bpmn 服务不可用」+ 默认空态文案不出现', async () => {
    wbMode = 'downFail'
    const wb = await gotoDashboardAndWait()
    assertEq(wb.todo.status, 7002, 'todo 段 status 应为 7002')
    assertEq(wb.todo.msg, 'cloud-bpmn 服务不可用', '7002 段 msg 应为下游降级文案')
    // 卡片保留（与 7001 整卡隐藏相区分）
    assertEq(await page.locator('.dash-card').count(), 2, '7002 段两卡应保留在场')
    const todoCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的待办' }) })
    const approvalCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的审批' }) })
    // 空态区展示段 msg（msg 是给人看的文案，可直接展示在空态区——契约 §2）
    const todoEmpty = todoCard.locator('.el-empty')
    assert(await todoEmpty.isVisible(), '待办卡应渲染空态区')
    assert((await todoEmpty.innerText()).includes('cloud-bpmn 服务不可用'), `待办卡空态应展示段 msg，实际 "${(await todoEmpty.innerText()).replace(/\n/g, '|')}"`)
    const approvalEmpty = approvalCard.locator('.el-empty')
    assert(await approvalEmpty.isVisible(), '审批卡应渲染空态区')
    assert((await approvalEmpty.innerText()).includes('cloud-bpmn 服务不可用'), `审批卡空态应展示段 msg，实际 "${(await approvalEmpty.innerText()).replace(/\n/g, '|')}"`)
    // 失败态不是"无数据"态：默认空态文案不得出现
    assert(!(await todoEmpty.innerText()).includes('暂无待办任务'), '失败态不得显示默认文案「暂无待办任务」')
    assert(!(await approvalEmpty.innerText()).includes('暂无审批记录'), '失败态不得显示默认文案「暂无审批记录」')
    assertEq(await page.locator('.el-message--error').count(), 0, '段级失败不应出现错误 toast（整体 code=200）')
    await shot(page, 'agg-s3-down-fail.png')
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
      '测试数据: 全部在 route mock 内存态（wbMode 三态开关：ok/noPerm/downFail），零真实后端写；admin 只登录不改',
      'mock 面: page.route(**/api/**)——登录链(sso/system user-nav+me+logout) + /aggregate/workbench 三态 + 铃铛 summary + 旧直调端点留痕（/bpmn/task/todo、/bpmn/approval/page，S1 断言零请求）；后端 gateway/cloud-aggregate 未启动亦可跑（真链路断言归主控集成段 BP12 同批）',
      '断言口径: 契约 §1.2 段结构（7001 段 data 恒 null）/§2 段状态三态消费（200 渲染、7001 整卡隐藏、7002 空态+msg）/§8.4 旧端点零直调',
      '判定口径: 本脚本退出码不可信，只认上方场景结果的 FAIL 行计数 = 0（grep 时注意本行不含方括号字面量）',
    ],
  })
  await browser.close()
}
