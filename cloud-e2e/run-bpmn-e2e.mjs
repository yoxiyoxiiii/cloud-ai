/**
 * 请假工作流 e2e 第七脚本（契约 2026-10-07-bpmn-leave-api §2/§3/§4；设计 D10/D12；计划 E2；harness 复用 lib/harness.mjs）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202 / bpmn 9203 已启动；前端 dev 5173 已启动
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 npm run e2e:bpmn）
 * 前置：9203 健康探测（无浏览器 Node fetch 经网关——登录取 token 后 GET /bpmn/definition/page；
 *   首请求失败即打印 SKIP 原因退出，勿硬跑——卡点纪律同 B9 先例，设计 D12）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码
 *
 * 清扫纪律新形态（设计 D12 拍板——与 CRUD 域「清零+无残留」的差异，本脚本验收口径）：
 * - bpmn 域无删除端点（留档语义）→ 不做业务表清零；CLEANUP 断言「本轮 stamp 单全部终态 + 下一轮时间戳天然隔离」
 * - ACT_HI 历史表允许 e2e 前缀残留（引擎表不可控，不清理）
 * - 业务表 e2ebpmn 前缀行允许保留但必须终态（历史 e2ebpmncurl/e2ebpmnf6 单亦为终态——不做全局计数断言，stamp 精确锚定）
 *
 * 测试数据（title 前缀 e2ebpmn${stamp}；admin 双角色 = 申请人 + 审批人，单人闭环——e2e 环境最小依赖）：
 * - A 同意路径 / R 拒绝路径 / C 撤销路径 / D 4002 探针（后台审批后对已终态单再撤销——BP8 附产，终态=已通过）
 * - E 审批中高亮探针（BP9 造单断言 approval 主高亮后即后台办结，终态=已通过）
 * - X 4004 探针（approver=nobody，被校验拦截永不落库）
 *
 * 场景（契约 §11 验收口径）：
 * - BP0 admin 登录 + 我的申请页骨架（侧边 8 项/高亮/面包屑/表头 8 列/发起按钮=权限快照证据）
 * - BP1 发起弹窗形态：类型下拉恰 3 项（字典 bpmn_leave_type 消费）+ 审批人下拉含 admin（跨服务 Feign 实证，inner-api §7 欠账兑现）
 * - BP2 发起（A）→ 我的申请行：审批中 tag + 类型译文 + 审批人昵称（UI 层）
 *   + 页内 fetch /bpmn/leave/page 原值与译文字段并存（API 层——E2 双层模式）
 * - BP3 待办出现 → 办理弹窗（同意默认选中 + 意见）→ 待办消行 + 我的申请变已通过 + 已办 tab 1 行同意
 * - BP4 拒绝路径（R）：办理选拒绝 → 已拒绝 + 已办 tab 拒绝 tag（与 A 行累积断言）
 * - BP5 撤销路径（C）：ElMessageBox 二段确认（含标题与不可恢复提示）→ 已撤销 + 待办无此单
 * - BP6 流程定义页：leave_approval 行（key/名称/版本/部署时间格式）+ 表头 5 列（末位操作列——Round H 设计器迁移，设计 D8）
 * - BP7 详情时间线：已通过单三步骤（发起申请/审批意见含文案/流程结束 result=已通过）
 * - BP8 防御面：无 token 直调 /bpmn/leave/page → HTTP 401（网关层）；D 单后台审批后 stale DOM 撤销 → toast 4002；
 *   approver=nobody 直连发起 body 4004（msg 含 审批人无效——契约 §5 为含义列，后端实参拼接账号，措辞差异已回报主控）；
 *   定义页写面反转（Round H 迁移，设计 D8：种子 331 bpmn:definition:deploy 绑 admin，本脚本 BP0 全新登录取新快照）
 *   ——admin 可见「新建流程」按钮与操作列（查看图/设计），原「无写按钮（4 列无操作列+零按钮）」断言反转
 * - BP9 详情流程图（契约 2026-10-08-bpmn-diagram-designer-api §3/§8）：已通过 A 单 svg 渲染 + 主高亮恰 [endApprove]
 *   （active=[] + endActivityId 并入——三态矩阵终态列）+ completed 含 start/approval + 时间线并存（BP7 互证）；
 *   再造 E 单审批中主高亮恰 [approval]（三态矩阵在途列）→ 后台办结归终态
 * - BP10 定义页「查看图」弹窗（契约 §2.1/§8）：xml 端点 200 → svg 渲染 leave_approval 语义元素封闭集恰 9 id
 *   （5 节点 + 4 连线；bpmn-js 外置标签元素 *_label 不计）+ 零高亮 marker → 关闭
 * - BP11 设计器（契约 §2.2/§8）：「设计」打开 leave_approval → 画布元素 ≥1（F7 booting 修复后画布常驻）+
 *   属性面板挂载 + XML 源码含 leave_approval → 不改动「保存部署」→ 部署响应/toast/页内 fetch 三证 version+1；
 *   部署产生的 v2+ 定义允许残留（latestVersion 过滤下 UI 恒显最新版——设计 D9 纪律）
 * - BP12 工作台两卡（契约 §6/§8）：两卡标题 + 待办行数 = min(5, todo 接口长度) + 申请卡含本轮 stamp 行 +
 *   两「查看全部」跳转 URL；无权限整卡隐藏分支不建第二账号（契约 §6 记档——快照权限单账号环境无法构造无权限态）
 * - CLEANUP/VERIFY：本轮 stamp 五单各自终态（A/D/E=已通过 R=已拒绝 C=已撤销，C 实例已删 processInstanceId=null）
 *   + console/badResponses/网络失败零污染
 *
 * B5 留档红线：e2ebpmnb5114833 前缀单/角色与受限账号 e2ebpmnb5114833u 绝不办理/触碰——
 * BP12 待办 min 公式允许 B5 留档待办计入行数（只断言行数与 stamp 行存在，不做封闭集断言）
 *
 * 实操注意（F6 联调结论）：el-date-picker 走日历面板点击（键盘输入会触发 EP Invalid user input 警告污染 console 断言）；
 * 办理弹窗同意 radio 默认选中；撤销有 ElMessageBox 二段确认
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（BP8 无 token 401 探测 + 4002 后台审批 + 4004 直连——page.request 不入 page 网络统计，不污染 BP-VERIFY） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, findRow, rowCells } = h

// ---------- 测试数据（e2ebpmn 前缀 + 时间戳；title 是全链路唯一锚点） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const T_A = `e2ebpmn${stamp}A` // 同意路径
const T_R = `e2ebpmn${stamp}R` // 拒绝路径
const T_C = `e2ebpmn${stamp}C` // 撤销路径
const T_D = `e2ebpmn${stamp}D` // 4002 探针（审批后再撤销）
const T_E = `e2ebpmn${stamp}E` // 审批中高亮探针（BP9 造单断言后即后台办结，终态=已通过）
const T_X = `e2ebpmn${stamp}X` // 4004 探针（approver=nobody，永不落库）
const REASON_A = `E2E事由同意${stamp}`
const COMMENT_A = `E2E同意意见${stamp}`
const COMMENT_R = `E2E拒绝意见${stamp}`
const COMMENT_D = `E2E后台同意${stamp}`
const COMMENT_E = `E2E通过意见${stamp}`

const LEAVE_PATH = '/bpmn/leave'
const TASK_PATH = '/bpmn/task'
const DEF_PATH = '/bpmn/definition'

/** 类型下拉选项 → leaveType 原值（字典 bpmn_leave_type：1 事假 / 2 病假 / 3 年假——契约 §6） */
const TYPE_LABELS = { 事假: '1', 病假: '2', 年假: '3' }

// ---------- 前置健康探测（无浏览器；失败即 SKIP，勿硬跑） ----------
async function preflight() {
  try {
    const lr = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    })
    const lb = await lr.json().catch(() => null)
    if (!lb || lb.code !== 200 || !lb.data?.accessToken) {
      return { ok: false, reason: `登录探测失败（sso 链路）: HTTP ${lr.status} body=${JSON.stringify(lb).slice(0, 200)}` }
    }
    const dr = await fetch(`${GATEWAY}/bpmn/definition/page?pageNum=1&pageSize=1`, {
      headers: { Authorization: `Bearer ${lb.data.accessToken}` },
    })
    const db = await dr.json().catch(() => null)
    if (!db || db.code !== 200) {
      return { ok: false, reason: `bpmn 域探测失败（9203 经网关不可达或未就绪）: HTTP ${dr.status} body=${JSON.stringify(db).slice(0, 200)}` }
    }
    return { ok: true, defTotal: db.data?.total }
  } catch (e) {
    return { ok: false, reason: `网络层异常: ${String(e)}` }
  }
}

const pre = await preflight()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按设计 D12 卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / bpmn 9203 已启动且经网关可达')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
log(`前置健康探测通过（经网关 /bpmn/definition/page，流程定义 total=${pre.defTotal}）`)

// ---------- 主流程（默认有头 + slowMo 300，与其余六脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

/** 非 /api/ 资产的 404 清单（favicon 环境噪音甄别用，D-VERIFY 同款） */
const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

/** waitForResponse 的 URL 匹配：r.url() 是含 origin 的完整地址，须比 pathname */
const apiPath = (url, pathname) => new URL(url).pathname === pathname

/** 弹窗定位（el-dialog 关闭是 display:none 留存 DOM——每次重建定位器取 last） */
const dialogByTitle = (title) => page.locator('.el-dialog', { hasText: title }).last()

/** ElMessageBox 二段确认：等可见 → 文案包含断言 → 点主按钮 */
async function confirmBox(expected) {
  const box = page.locator('.el-message-box')
  await box.waitFor({ state: 'visible', timeout: 8000 })
  const text = (await box.innerText()).trim().replace(/\n/g, ' | ')
  assert(text.includes(expected), `确认框文案应含 "${expected}"，实际 "${text}"`)
  await box.locator('.el-message-box__btns .el-button--primary').click()
  return text
}

/** 直连网关小助手（E2 通用模式）：page.evaluate 取 localStorage token + page.request + Bearer——
 *  不入 page 网络统计（不污染 BP-VERIFY，N5/D5c 先例）；请求体中文经 Node UTF-8 无 GBK 陷阱 */
async function directApi(method, path, data) {
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken)
  const res = await page.request.fetch(`${GATEWAY}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    data: data === undefined ? undefined : JSON.stringify(data),
  })
  return { httpStatus: res.status(), body: await res.json() }
}

/** 页内 fetch（E2 双层模式的 API 层——dict 审计断言同款先例）：走 /api 代理链路，中文无 GBK 陷阱 */
async function pageFetch(pathWithQuery) {
  return page.evaluate(async (p) => {
    const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
    const res = await fetch(p, { headers: { Authorization: `Bearer ${token}` } })
    return await res.json()
  }, pathWithQuery)
}

/** BpmnViewer 高亮态提取（Round H BP9/BP10）：canvas.addMarker 把 marker 类挂在 g.djs-element 上、
 *  data-element-id 即流程节点 id（BpmnViewer 双类：bpmn-highlight-active 主高亮 / -completed 已执行路径） */
async function viewerMarkers(scope) {
  return scope.evaluate((el) => {
    const ids = (cls) =>
      Array.from(el.querySelectorAll(`.djs-element.${cls}`)).map((g) => g.getAttribute('data-element-id'))
    return { active: ids('bpmn-highlight-active'), completed: ids('bpmn-highlight-completed') }
  })
}

/** 进入我的申请页并等首屏分页落定 */
async function loadLeavePage() {
  const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/leave/page'), { timeout: 15000 })
  await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded' })
  await respP
  await waitTableIdle(page)
  await sleep(300)
}

/** 我的申请行定位（title 唯一锚点；新单 id 倒序置顶但残留可能在任意页——全表翻页） */
async function leaveRow(title, { reload = true } = {}) {
  return findRow(page, title, { path: LEAVE_PATH, reload })
}

/** 状态列 tag（状态列是全表唯一 el-tag 宿主） */
async function statusTag(row) {
  const tag = row.locator('td .el-tag').first()
  return { text: ((await tag.innerText()) || '').trim(), cls: (await tag.getAttribute('class')) || '' }
}

/** 打开下拉并返回可见下拉容器（teleport 到 body；同一时刻仅一个可见） */
async function openSelect(dlg, placeholder) {
  await dlg.locator('.el-select', { hasText: placeholder }).click()
  const dd = page.locator('.el-select-dropdown:visible')
  await dd.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  return dd
}

/** 可见下拉的选项文本清单 */
async function optionTexts(dd) {
  const items = dd.locator('.el-select-dropdown__item')
  const n = await items.count()
  const out = []
  for (let i = 0; i < n; i++) out.push(((await items.nth(i).innerText()) || '').trim())
  return out
}

/** 在可见下拉中点选指定文本选项 */
async function pickOption(dd, text) {
  await dd.locator('.el-select-dropdown__item', { hasText: text }).first().click()
  await sleep(300)
}

/**
 * 日历面板点选起止日期（F6 联调结论：禁键盘输入——EP Invalid user input 警告会污染 console 断言）
 * daterange 双月面板取左侧（当前月）；未来日期必 available
 */
async function pickDateRange(dlg, startDay, endDay) {
  await dlg.locator('.el-date-editor').click()
  const panel = page.locator('.el-picker__popper')
  await panel.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  const table = panel.locator('.el-date-table').first()
  const day = (n) =>
    table.locator('td.available:not(.prev-month):not(.next-month)').filter({ hasText: new RegExp(`^${n}$`) })
  await day(startDay).first().click()
  await sleep(400)
  await day(endDay).first().click()
  await sleep(400)
  // 面板自动关闭 + 双输入框落值断言（YYYY-MM-DD，当月）
  const mm = `${pad(ts.getMonth() + 1)}`
  const inputs = dlg.locator('.el-range-input')
  const sv = await inputs.nth(0).inputValue()
  const ev = await inputs.nth(1).inputValue()
  assertEq(sv, `2026-${mm}-${pad(startDay)}`, `开始日期输入框应为 2026-${mm}-${pad(startDay)}，实际 "${sv}"`)
  assertEq(ev, `2026-${mm}-${pad(endDay)}`, `结束日期输入框应为 2026-${mm}-${pad(endDay)}，实际 "${ev}"`)
  return { sv, ev }
}

/** 发起请假全流程（开弹窗 → 填五字段 → 提交 → 发起成功 toast + 弹窗关闭 + 列表刷新落定）；返回新单 id */
async function createLeaveViaUi({ title, typeLabel, reason }) {
  // 发起按钮只在 /bpmn/leave（调用方可能在任务/定义页——先归位再开弹窗）
  if (!page.url().includes(LEAVE_PATH)) await loadLeavePage()
  await page.getByRole('button', { name: '发起请假' }).click()
  const dlg = dialogByTitle('发起请假')
  await dlg.waitFor({ state: 'visible', timeout: 8000 })
  await dlg.locator('input[placeholder="请输入标题"]').fill(title)
  const typeDd = await openSelect(dlg, '请选择请假类型')
  await pickOption(typeDd, typeLabel)
  const { sv, ev } = await pickDateRange(dlg, 25, 26)
  if (reason) await dlg.locator('textarea[placeholder="选填，不超过 500 字"]').fill(reason)
  const apprDd = await openSelect(dlg, '请选择审批人')
  await pickOption(apprDd, 'admin')
  // 响应等待须在触发点击之前注册（页面 success 处理即刻发刷新请求——注册晚了会错过响应，waitForResponse 只捕注册后事件）
  const postP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/leave') && r.request().method() === 'POST', { timeout: 15000 })
  const pageP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/leave/page'), { timeout: 15000 })
  await dlg.locator('.el-dialog__footer button', { hasText: '提交' }).click()
  const res = await postP
  const body = await res.json()
  assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
  assertEq(body.code, 200, `发起请假应 body 200，实际 ${body.code} msg="${body.msg}"`)
  assert(typeof body.data === 'string' && body.data.length > 0, `发起应返回新单 id 字符串，实际 ${JSON.stringify(body.data)}`)
  await waitToast(page, '发起成功')
  await waitDialogGone(page, '发起请假')
  // success 事件触发的列表刷新（pageP 已预注册）：响应落定后行才可见
  await pageP
  await waitTableIdle(page)
  await sleep(300)
  log(`  发起成功: title=${title} type=${typeLabel}(${TYPE_LABELS[typeLabel]}) ${sv}~${ev} id=${body.data}`)
  return body.data
}

/** 进入待办任务页并等 todo 首屏落定 */
async function loadTaskTodo() {
  const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/task/todo'), { timeout: 15000 })
  await page.goto(`${BASE}${TASK_PATH}`, { waitUntil: 'domcontentloaded' })
  await respP
  await waitTableIdle(page)
  await sleep(300)
}

/** 当前可见页签内的行（el-tabs 双 pane 同 DOM，inactive 是 display:none——必须 :visible 作用域） */
const visibleRows = () => page.locator('.el-table__row:visible')
const visibleRowByText = (text) => page.locator('.el-table__row:visible').filter({ hasText: text }).first()

/** 切到已办页签并等已办表面板可见落定。
 *  不依赖新网络事件：done 数据在办理成功时已被 handleCompleteSuccess 双页签刷新拉取（内存已就绪），
 *  tab-change 即使再发 done 请求也无妨（waitTableIdle 兜住）——run2 实测点击后偶发无新请求，
 *  waitForResponse 硬等会假红；以「页签激活 + 已办表面板可见」为落定准绳，未激活重点（run2 BP3/BP4 教训） */
async function switchDoneTab() {
  const tab = page.getByRole('tab', { name: '已办任务' })
  let active = false
  for (let attempt = 0; attempt < 3 && !active; attempt++) {
    await tab.click()
    for (let i = 0; i < 15; i++) {
      active = ((await tab.getAttribute('class')) || '').includes('is-active')
      if (active) break
      await sleep(200)
    }
  }
  assert(active, '点击（含重点重试）后 已办任务 页签应激活')
  // 已办表面板可见（办理时间 列头与待办表 到达时间 区分）+ 表落定
  const donePane = page.locator('.el-tabs__content .el-tab-pane:visible', { hasText: '办理时间' }).first()
  await donePane.waitFor({ state: 'visible', timeout: 8000 })
  await waitTableIdle(page)
  await sleep(300)
}

/** 办理任务全流程（开弹窗 → 选结果 → 填意见 → 提交 → 办理成功 + 弹窗关闭 + 双页签刷新落定） */
async function completeTaskViaUi({ title, approveLabel, comment }) {
  const row = visibleRowByText(title)
  assert(await row.isVisible(), `待办应含 "${title}" 行`)
  await row.getByRole('button', { name: '办理' }).click()
  const dlg = dialogByTitle('办理任务')
  await dlg.waitFor({ state: 'visible', timeout: 8000 })
  assert((await dlg.innerText()).includes(title), `办理弹窗应展示请假标题 "${title}"`)
  const checked = dlg.locator('.el-radio.is-checked')
  assertEq(((await checked.innerText()) || '').trim(), '同意', '办理弹窗同意 radio 应默认选中')
  if (approveLabel !== '同意') {
    await dlg.locator('.el-radio', { hasText: approveLabel }).first().click()
    await sleep(200)
    assertEq(((await dlg.locator('.el-radio.is-checked').innerText()) || '').trim(), approveLabel, `选后 ${approveLabel} 应为选中态`)
  }
  if (comment) await dlg.locator('textarea[placeholder="选填，不超过 200 字"]').fill(comment)
  // 响应等待须在触发点击之前注册（handleCompleteSuccess 即刻双发 todo+done 刷新——晚了会错过响应）
  // 路径核对：办理端点是 /api/bpmn/task/complete（run2/3 假红根因——曾误写 /api/bpmn/complete 永不匹配）
  const postP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/task/complete') && r.request().method() === 'POST', { timeout: 15000 })
  const todoP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/task/todo'), { timeout: 15000 })
  const doneP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/task/done'), { timeout: 15000 })
  await dlg.locator('.el-dialog__footer button', { hasText: '提交' }).click()
  const res = await postP
  const body = await res.json()
  assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
  assertEq(body.code, 200, `办理任务应 body 200，实际 ${body.code} msg="${body.msg}"`)
  await waitToast(page, '办理成功')
  await waitDialogGone(page, '办理任务')
  // handleCompleteSuccess 双页签刷新（todoP/doneP 已预注册）：待办消行证据在此之后断言
  await Promise.all([todoP, doneP])
  await sleep(300)
  log(`  办理成功: title=${title} 结果=${approveLabel} 意见="${comment || '(空)'}"`)
}

try {
  // ================= BP0 登录 + 我的申请页骨架 =================
  await step('BP0', '无 token 直访被拦 → admin 登录回跳 + 侧边 8 项（我的申请高亮）+ 面包屑 + 表头 8 列 + 发起按钮', async () => {
    await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForURL('**/login**', { timeout: 10000 })
    const url = new URL(page.url())
    assertEq(url.searchParams.get('redirect'), LEAVE_PATH, 'redirect 参数应为 /bpmn/leave')
    // admin 双角色登录 = 全新权限快照（含 bpmn:leave:*/bpmn:task:*/bpmn:definition:* 24 权限）
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
    await page.waitForURL(`**${LEAVE_PATH}`, { timeout: 15000 })
    await waitTableIdle(page)
    log(`  登录回跳: ${page.url()}`)
    const items = page.locator('.el-menu .el-menu-item')
    const n = await items.count()
    const labels = []
    for (let i = 0; i < n; i++) labels.push((await items.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,字典管理,我的申请,待办任务,流程定义,工作台', '侧边菜单顺序（B7 30 段种子后 8 项形态）')
    assertEq(((await page.locator('.el-menu-item.is-active').innerText()) || '').trim(), '我的申请', '/bpmn/leave 下我的申请应高亮')
    const bc = await breadcrumbTexts(page)
    log(`  面包屑: ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/我的申请', '面包屑应为 首页/我的申请')
    const ths = page.locator('.el-table__header-wrapper th')
    const tn = await ths.count()
    const headers = []
    for (let i = 0; i < tn; i++) headers.push(((await ths.nth(i).innerText()) || '').trim())
    log(`  表头(${tn}): ${JSON.stringify(headers)}`)
    assertEq(headers.join(','), '标题,请假类型,起止日期,状态,申请人,审批人,发起时间,操作', '我的申请表头应为 8 列精确序')
    assertEq(await page.getByRole('button', { name: '发起请假' }).count(), 1, '"发起请假"按钮应可见（admin 快照含 bpmn:leave:add）')
    await shot(page, 'bp0-skeleton.png')
  })

  // ================= BP1 发起弹窗形态：字典消费 + 跨服务 Feign 实证 =================
  await step('BP1', '发起弹窗：类型下拉恰 3 项（bpmn_leave_type 字典消费）+ 审批人下拉含 admin（跨服务 Feign 实证，inner-api §7 欠账兑现）', async () => {
    await page.getByRole('button', { name: '发起请假' }).click()
    const dlg = dialogByTitle('发起请假')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    assertEq(((await dlg.locator('.el-dialog__title').innerText()) || '').trim(), '发起请假', '弹窗标题应为 发起请假')
    // 类型下拉（字典 bpmn_leave_type：1 事假 / 2 病假 / 3 年假——契约 §6 恰 3 项 sort 升序）
    const typeDd = await openSelect(dlg, '请选择请假类型')
    const types = await optionTexts(typeDd)
    log(`  类型下拉选项: ${JSON.stringify(types)}`)
    assertEq(types.join(','), '事假,病假,年假', `类型下拉应恰 3 项（字典消费），实际 ${JSON.stringify(types)}`)
    await pickOption(typeDd, '事假')
    await sleep(300)
    // 审批人下拉（GET /bpmn/leave/approvers → Feign system /inner/user/all 投影直通）
    const apprDd = await openSelect(dlg, '请选择审批人')
    const approvers = await optionTexts(apprDd)
    log(`  审批人下拉选项: ${JSON.stringify(approvers)}`)
    assert(approvers.length >= 1, `审批人下拉应至少 1 项，实际 ${JSON.stringify(approvers)}`)
    assert(
      approvers.some((t) => t.includes('admin') && t.includes('管理员')),
      `审批人下拉应含 admin（管理员）——跨服务 Feign 链路实证，实际 ${JSON.stringify(approvers)}`,
    )
    await pickOption(apprDd, 'admin')
    await shot(page, 'bp1-dialog.png')
    // 本场景仅验形态：取消关闭，不落库
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '发起请假')
  })

  // ================= BP2 发起（同意路径 A）→ 行出现（UI 层）+ 页内 fetch 双层断言 =================
  await step('BP2', '发起 A（事假）→ 我的申请行：审批中 tag + 类型译文 + 审批人昵称 + 撤销按钮（UI）；页内 fetch 原值与译文并存（API 双层）', async () => {
    const id = await createLeaveViaUi({ title: T_A, typeLabel: '事假', reason: REASON_A })
    const row = await leaveRow(T_A, { reload: false })
    assert(row, `我的申请应出现 "${T_A}" 行`)
    const cells = await rowCells(row)
    log(`  A 行: ${JSON.stringify(cells)}`)
    assertEq(cells[0], T_A, '标题列应为提交值')
    assertEq(cells[1], '事假', '请假类型列应为译文 事假（leaveTypeLabel）')
    assert(cells[2].includes('2026-10-25') && cells[2].includes('2026-10-26'), `起止日期列应含所选两日，实际 "${cells[2]}"`)
    assertEq(cells[3], '审批中', '状态列应为 审批中（statusLabel）')
    assertEq(cells[4], '管理员', '申请人列应为昵称译文 管理员')
    assertEq(cells[5], '管理员', '审批人列应为昵称译文 管理员（approverName 非空）')
    const tag = await statusTag(row)
    assert(tag.cls.includes('el-tag--warning'), `审批中 tag 应为 warning 色，实际 class="${tag.cls}"`)
    assertEq(await row.getByRole('button', { name: '撤销' }).count(), 1, '审批中且本人行应有 撤销 按钮（v-if + v-perms 双闸放行）')
    // ---- API 层（E2 双层模式）：页内 fetch /bpmn/leave/page 原值与译文字段并存 ----
    const fetched = await pageFetch('/api/bpmn/leave/page?pageNum=1&pageSize=10')
    assertEq(fetched.code, 200, '页内 fetch 业务码应为 200')
    const frow = fetched.data.rows.find((r) => r.id === id)
    assert(frow, `fetch 应含 id=${id} 行`)
    log(`  fetch 行原值: status=${frow.status} leaveType=${frow.leaveType} applyUser=${frow.applyUser} approver=${frow.approver} processInstanceId=${frow.processInstanceId?.slice(0, 8)}...`)
    log(`  fetch 行译文: statusLabel=${frow.statusLabel} leaveTypeLabel=${frow.leaveTypeLabel} applyUserName=${frow.applyUserName} approverName=${frow.approverName}`)
    assertEq(frow.status, '0', '原值 status 应为 "0"（审批中）')
    assertEq(frow.leaveType, '1', '原值 leaveType 应为 "1"（事假）')
    assertEq(frow.applyUser, 'admin', '原值 applyUser 应为 admin')
    assertEq(frow.approver, 'admin', '原值 approver 应为 admin')
    assert(frow.processInstanceId, '审批中单应有在途流程实例 id（processInstanceId 非 null）')
    assertEq(frow.statusLabel, '审批中', '译文 statusLabel 应为 审批中（与原值并存）')
    assertEq(frow.leaveTypeLabel, '事假', '译文 leaveTypeLabel 应为 事假（与原值并存）')
    assertEq(frow.applyUserName, '管理员', '译文 applyUserName 应为 管理员')
    assertEq(frow.approverName, '管理员', '译文 approverName 应为 管理员')
    assertEq(frow.reason, REASON_A, '事由应为提交值')
    await shot(page, 'bp2-row.png')
  })

  // ================= BP3 待办办理（同意）→ 已通过 + 已办 1 行同意 =================
  await step('BP3', '待办出现 A → 办理弹窗（同意默认选中+意见）→ 待办消行 + 我的申请变已通过 + 已办 tab 1 行同意', async () => {
    await loadTaskTodo()
    // 待办出现（admin 双角色：自己是自己的审批人）
    const todoRow = visibleRowByText(T_A)
    assert(await todoRow.isVisible(), `待办应含 "${T_A}" 行`)
    const todoCells = await rowCells(todoRow)
    log(`  待办 A 行: ${JSON.stringify(todoCells)}`)
    assertEq(todoCells[1], '事假', '待办类型列应为译文 事假（TaskVo leaveTypeLabel）')
    assertEq(todoCells[2], '管理员', '待办申请人列应为 管理员')
    await completeTaskViaUi({ title: T_A, approveLabel: '同意', comment: COMMENT_A })
    // 待办消行
    assert(!(await visibleRowByText(T_A).isVisible()), `办理后待办应消行（无 "${T_A}"）`)
    // 已办 tab：1 行同意
    await switchDoneTab()
    const doneRow = visibleRowByText(T_A)
    assert(await doneRow.isVisible(), `已办应含 "${T_A}" 行`)
    const doneCells = await rowCells(doneRow)
    log(`  已办 A 行: ${JSON.stringify(doneCells)}`)
    assertEq(doneCells[4], '同意', '办理结果列应为 同意（approve="true"）')
    assertEq(doneCells[5], COMMENT_A, '审批意见列应为提交意见')
    assertEq(doneCells[6], '已通过', '当前单状态列应为 已通过（leaveStatusLabel）')
    const doneTag = doneRow.locator('td .el-tag').first()
    assert(((await doneTag.getAttribute('class')) || '').includes('el-tag--success'), '同意结果 tag 应为 success 色')
    // 我的申请变已通过（撤销按钮随终态消失）
    const row = await leaveRow(T_A)
    assert(row, `我的申请应仍含 "${T_A}" 行`)
    const cells = await rowCells(row)
    assertEq(cells[3], '已通过', '状态列应变 已通过')
    const tag = await statusTag(row)
    assert(tag.cls.includes('el-tag--success'), `已通过 tag 应为 success 色，实际 class="${tag.cls}"`)
    assertEq(await row.getByRole('button', { name: '撤销' }).count(), 0, '终态行不应再有 撤销 按钮（v-if 关闸）')
    await shot(page, 'bp3-approved.png')
  })

  // ================= BP4 拒绝路径（R）→ 已拒绝 + 已办累积断言 =================
  await step('BP4', '发起 R（病假）→ 办理选拒绝 → 我的申请变已拒绝 + 已办 tab 拒绝 tag（与 A 行累积）', async () => {
    const id = await createLeaveViaUi({ title: T_R, typeLabel: '病假', reason: '' })
    assert(typeof id === 'string', '发起 R 应返回 id')
    await loadTaskTodo()
    await completeTaskViaUi({ title: T_R, approveLabel: '拒绝', comment: COMMENT_R })
    // 已办累积：A=同意 / R=拒绝 同屏
    await switchDoneTab()
    const rRow = visibleRowByText(T_R)
    assert(await rRow.isVisible(), `已办应含 "${T_R}" 行`)
    const rCells = await rowCells(rRow)
    log(`  已办 R 行: ${JSON.stringify(rCells)}`)
    assertEq(rCells[4], '拒绝', '办理结果列应为 拒绝（approve="false"）')
    assertEq(rCells[5], COMMENT_R, '审批意见列应为提交意见')
    assertEq(rCells[6], '已拒绝', '当前单状态列应为 已拒绝')
    const rTag = rRow.locator('td .el-tag').first()
    assert(((await rTag.getAttribute('class')) || '').includes('el-tag--danger'), '拒绝结果 tag 应为 danger 色')
    assert(await visibleRowByText(T_A).isVisible(), '已办应仍含 A 行（累积断言）')
    // 我的申请变已拒绝
    const row = await leaveRow(T_R)
    const cells = await rowCells(row)
    assertEq(cells[1], '病假', '类型列应为译文 病假')
    assertEq(cells[3], '已拒绝', '状态列应变 已拒绝')
    const tag = await statusTag(row)
    assert(tag.cls.includes('el-tag--danger'), `已拒绝 tag 应为 danger 色，实际 class="${tag.cls}"`)
    await shot(page, 'bp4-rejected.png')
  })

  // ================= BP5 撤销路径（C）：二段确认 → 已撤销 + 待办无此单 =================
  await step('BP5', '发起 C（年假）→ 本人撤销（ElMessageBox 二段确认含标题）→ 已撤销 + 待办无此单', async () => {
    const id = await createLeaveViaUi({ title: T_C, typeLabel: '年假', reason: '' })
    assert(typeof id === 'string', '发起 C 应返回 id')
    const row = await leaveRow(T_C, { reload: false })
    assert(row, `我的申请应含 "${T_C}" 行`)
    assertEq(await row.getByRole('button', { name: '撤销' }).count(), 1, '审批中且本人行应有 撤销 按钮')
    // 二段确认：文案含标题 + 不可恢复提示
    const putP = page.waitForResponse(
      (r) => apiPath(r.url(), `/api/bpmn/leave/cancel/${id}`) && r.request().method() === 'PUT',
      { timeout: 15000 },
    )
    await row.getByRole('button', { name: '撤销' }).click()
    const boxText = await confirmBox(T_C)
    log(`  撤销确认框: "${boxText}"`)
    assert(boxText.includes('撤销后不可恢复'), '确认框应含不可恢复提示')
    const res = await putP
    const body = await res.json()
    assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(body.code, 200, `撤销应 body 200，实际 ${body.code} msg="${body.msg}"`)
    await waitToast(page, '撤销成功')
    await waitTableIdle(page)
    await sleep(300)
    // 行内状态变已撤销（success 事件已刷新列表）
    const rowAfter = await leaveRow(T_C, { reload: false })
    const cells = await rowCells(rowAfter)
    log(`  C 行（撤销后）: ${JSON.stringify(cells)}`)
    assertEq(cells[1], '年假', '类型列应为译文 年假')
    assertEq(cells[3], '已撤销', '状态列应变 已撤销')
    const tag = await statusTag(rowAfter)
    assert(tag.cls.includes('el-tag--info'), `已撤销 tag 应为 info 色，实际 class="${tag.cls}"`)
    assertEq(await rowAfter.getByRole('button', { name: '撤销' }).count(), 0, '终态行不应再有 撤销 按钮')
    // 待办无此单（撤销同事务删流程实例 → 任务随实例消失）
    await loadTaskTodo()
    assert(!(await visibleRowByText(T_C).isVisible()), `撤销后待办不应含 "${T_C}"`)
    assertEq(await visibleRows().filter({ hasText: T_C }).count(), 0, '待办全表无 C 行（双保险）')
    await shot(page, 'bp5-canceled.png')
  })

  // ================= BP6 流程定义页：leave_approval 行 =================
  await step('BP6', '流程定义页：leave_approval 行（key/名称=请假审批/版本/部署时间格式）+ 表头 5 列（末位操作列——Round H 迁移）', async () => {
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/definition/page'), { timeout: 15000 })
    await page.goto(`${BASE}${DEF_PATH}`, { waitUntil: 'domcontentloaded' })
    const resp = await respP
    const body = await resp.json()
    await waitTableIdle(page)
    await sleep(300)
    assertEq(body.code, 200, '流程定义分页业务码应为 200')
    const ths = page.locator('.el-table__header-wrapper th')
    const tn = await ths.count()
    const headers = []
    for (let i = 0; i < tn; i++) headers.push(((await ths.nth(i).innerText()) || '').trim())
    log(`  表头(${tn}): ${JSON.stringify(headers)}`)
    assertEq(headers.join(','), '定义标识,定义名称,版本,部署时间,操作', '定义表头应为 5 列精确序（末位操作列——Round H 设计器迁移，设计 D8）')
    // leave_approval 行（classpath 自动部署唯一种子；latestVersion 过滤取最新版）
    const defRow = body.data.rows.find((r) => r.key === 'leave_approval')
    assert(defRow, `定义分页应含 leave_approval 行，实际 ${JSON.stringify(body.data.rows)}`)
    const row = page.locator('.el-table__row', { hasText: 'leave_approval' }).first()
    assert(await row.isVisible(), '页面应渲染 leave_approval 行')
    const cells = await rowCells(row)
    log(`  定义行: ${JSON.stringify(cells)}（分页 total=${body.data.total}——不锁全局计数）`)
    assertEq(cells[0], 'leave_approval', '定义标识列应为 leave_approval')
    assertEq(cells[1], '请假审批', '定义名称列应为 请假审批')
    assert(/^\d+$/.test(cells[2]), `版本列应为数字（Long→String），实际 "${cells[2]}"`)
    assert(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(cells[3]), `部署时间应为 yyyy-MM-dd HH:mm:ss，实际 "${cells[3]}"`)
    await shot(page, 'bp6-definition.png')
  })

  // ================= BP7 详情时间线：已通过单三步骤 =================
  await step('BP7', '详情时间线：A 单详情弹窗 el-steps 恰 3 步（发起申请/审批意见含文案/流程结束 result=已通过）', async () => {
    const row = await leaveRow(T_A)
    assert(row, `我的申请应含 "${T_A}" 行`)
    await row.getByRole('button', { name: '详情' }).click()
    const dlg = dialogByTitle('请假单详情')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(400)
    // 主体平铺（弹窗内新拉 detail，非行数据）
    const bodyText = (await dlg.innerText()).replace(/\n/g, ' | ')
    log(`  详情主体: ${bodyText.slice(0, 260)}`)
    for (const expected of [T_A, '事假', '已通过', '管理员', REASON_A, '2026-10-25 ~ 2026-10-26']) {
      assert(bodyText.includes(expected), `详情主体应含 "${expected}"`)
    }
    // el-steps 时间线（steps 升序三源拼装：apply/approval/end——契约 §2.3）
    const steps = dlg.locator('.el-step')
    assertEq(await steps.count(), 3, `时间线应恰 3 步，实际 ${await steps.count()}`)
    const titles = []
    for (let i = 0; i < 3; i++) titles.push(((await steps.nth(i).locator('.el-step__title').innerText()) || '').trim())
    log(`  步骤标题: ${JSON.stringify(titles)}`)
    assertEq(titles.join(','), '发起申请,审批意见,流程结束', '三步标题应为 发起申请/审批意见/流程结束')
    const d0 = (await steps.nth(0).locator('.el-step__description').innerText()).trim()
    const d1 = (await steps.nth(1).locator('.el-step__description').innerText()).trim()
    const d2 = (await steps.nth(2).locator('.el-step__description').innerText()).trim()
    log(`  步1描述: "${d0.replace(/\n/g, ' | ')}"`)
    log(`  步2描述: "${d1.replace(/\n/g, ' | ')}"`)
    log(`  步3描述: "${d2.replace(/\n/g, ' | ')}"`)
    assert(d0.includes('管理员'), '发起步操作人应为昵称译文 管理员')
    assert(d1.includes(`意见：${COMMENT_A}`), `审批意见步应含提交文案 "${COMMENT_A}"`)
    assert(d2.includes('结果：已通过'), '流程结束步 result 应为 已通过')
    await shot(page, 'bp7-timeline.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    await waitDialogGone(page, '请假单详情')
  })

  // ================= BP8 防御面：401 / 4002 / 4004 / 定义页写面反转（Round H 迁移） =================
  await step('BP8', '防御面：无 token 直调 401 → D 单后台审批后 stale 撤销 toast 4002 → nobody 直连发起 body 4004（msg 含 审批人无效）→ 定义页写面反转（新建流程按钮/操作列可见）', async () => {
    // ---- a. 无 token 直调网关 → HTTP 401（网关层真实状态码 + R JSON body——认证链路契约） ----
    const noToken = await page.request.get(`${GATEWAY}/bpmn/leave/page`)
    log(`  无 token 直调 ${GATEWAY}/bpmn/leave/page: HTTP ${noToken.status()}`)
    assertEq(noToken.status(), 401, '无 token 直调应 HTTP 401（网关验签拒绝）')
    const noTokenBody = await noToken.json().catch(() => null)
    assert(noTokenBody && noTokenBody.code, '401 body 应为 R JSON（含 code）')
    // ---- b. 4002：D 单发起（页面呈现审批中）→ 直连后台审批 → stale DOM 撤销 → toast 4002 ----
    const dId = await createLeaveViaUi({ title: T_D, typeLabel: '事假', reason: '' })
    const rowD = await leaveRow(T_D, { reload: false })
    assert(rowD, `我的申请应含 "${T_D}" 行`)
    assertEq(await rowD.getByRole('button', { name: '撤销' }).count(), 1, 'D 审批中行应有 撤销 按钮（stale DOM 锚点）')
    // 直连后台审批（不经 UI——构造「页面数据已过期」窗口）
    const todo = await directApi('GET', '/bpmn/task/todo')
    assertEq(todo.body.code, 200, '直连待办业务码应为 200')
    const dTask = todo.body.data.find((t) => t.leaveTitle === T_D)
    assert(dTask, `直连待办应含 "${T_D}"（taskId 办理锚点）`)
    const done = await directApi('POST', '/bpmn/task/complete', { taskId: dTask.taskId, approve: 'true', comment: COMMENT_D })
    log(`  直连后台审批 D: HTTP ${done.httpStatus} code=${done.body.code}`)
    assertEq(done.body.code, 200, '后台审批应 body 200')
    // 页面未刷新：撤销按钮仍在 → 点击走 UI 撤销 → 后端 4002 → 拦截器 toast（HTTP 200 + body 错误码）
    const putP = page.waitForResponse(
      (r) => apiPath(r.url(), `/api/bpmn/leave/cancel/${dId}`) && r.request().method() === 'PUT',
      { timeout: 15000 },
    )
    await rowD.getByRole('button', { name: '撤销' }).click()
    await confirmBox(T_D)
    const res = await putP
    const body = await res.json()
    log(`  stale 撤销 D: HTTP ${res.status()} code=${body.code} msg="${body.msg}"`)
    assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(body.code, 4002, `对已通过单撤销应 body 4002，实际 ${body.code}`)
    const toastText = await waitToast(page, '请假单已终态', 'error')
    log(`  4002 toast: "${toastText}"`)
    assert(toastText.includes('请假单已终态，不可撤销'), `toast 应为契约 msg 逐字，实际 "${toastText}"`)
    // 刷新后 D 呈终态（BP8 附产单的清扫锚点）
    const rowD2 = await leaveRow(T_D)
    const dCells = await rowCells(rowD2)
    assertEq(dCells[3], '已通过', '刷新后 D 应为 已通过（后台审批生效）')
    // ---- c. 4004：approver=nobody 直连发起（UI 下拉无法选出无效人——直连是唯一路径；永不落库） ----
    const bad = await directApi('POST', '/bpmn/leave', {
      title: T_X,
      leaveType: '1',
      startDate: '2026-10-25',
      endDate: '2026-10-26',
      approver: 'nobody',
    })
    log(`  nobody 发起: HTTP ${bad.httpStatus} code=${bad.body.code} msg="${bad.body.msg}"`)
    assertEq(bad.httpStatus, 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(bad.body.code, 4004, `审批人无效应 body 4004，实际 ${bad.body.code}`)
    // 契约 §5 表中「审批人无效（不在用户投影中）」是含义列非 msg 逐字规范；后端实参为 "审批人无效: nobody"
    // （LeaveWorkflowService 拼接 approver 账号）——语义断言 + 全文留档，措辞差异已回报主控（E4 文档核对裁决项）
    assert(
      typeof bad.body.msg === 'string' && bad.body.msg.includes('审批人无效'),
      `4004 msg 应含 审批人无效，实际 "${bad.body.msg}"`,
    )
    // ---- d. 定义页写面反转（Round H 迁移，设计 D8：种子 331 bpmn:definition:deploy 绑 admin——
    //      本脚本 BP0 无 token 直访后 admin 全新登录，快照必含 deploy 权限；断言按可见形态写，
    //      原「无写按钮（只读域）」三断言（零按钮/无操作列/零行内按钮）反转） ----
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/definition/page'), { timeout: 15000 })
    await page.goto(`${BASE}${DEF_PATH}`, { waitUntil: 'domcontentloaded' })
    await respP
    await waitTableIdle(page)
    await sleep(300)
    const createBtn = page.getByRole('button', { name: '新建流程' })
    assertEq(await createBtn.count(), 1, '定义页应可见「新建流程」按钮且唯一（admin 快照含 bpmn:definition:deploy——331 绑定 + BP0 全新登录取新快照）')
    assert(await createBtn.isVisible(), '「新建流程」按钮应可见（v-perm bpmn:definition:deploy 放行形态）')
    assertEq(await page.locator('.el-table__header-wrapper th', { hasText: '操作' }).count(), 1, '定义表头应有操作列（5 列，与 BP6 表头断言互证）')
    assert((await page.locator('.el-table__row button', { hasText: '查看图' }).count()) >= 1, '定义行内应有「查看图」按钮（perms bpmn:definition:list 可见）')
    assert((await page.locator('.el-table__row button', { hasText: '设计' }).count()) >= 1, '定义行内应有「设计」按钮（v-perm bpmn:definition:deploy）')
    await shot(page, 'bp8-defense.png')
  })

  // ================= BP9 详情流程图：三态矩阵两态实证（已通过/审批中主高亮分治） =================
  await step('BP9', '已通过 A 单详情：svg 渲染 + 主高亮恰 [endApprove] + completed 含 start/approval + 时间线并存；再造 E 单审批中主高亮恰 [approval] → 后台办结（终态=已通过）', async () => {
    // ---- a. 已通过单（A）：active=[] + endActivityId 并入主高亮（契约 §3 三态矩阵终态列） ----
    const rowA = await leaveRow(T_A)
    assert(rowA, `我的申请应含 "${T_A}" 行`)
    await rowA.getByRole('button', { name: '详情' }).click()
    const dlg = dialogByTitle('请假单详情')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // 两段式数据链（detail → diagram → xml）+ BpmnViewer async chunk：等首个 djs 元素可见（importXML 落定标志）
    const viewerA = dlg.locator('.bpmn-viewer')
    await viewerA.locator('.djs-element').first().waitFor({ state: 'visible', timeout: 20000 })
    await sleep(500)
    const mA = await viewerMarkers(viewerA)
    log(`  A 单 markers: active=${JSON.stringify(mA.active)} completed=${JSON.stringify(mA.completed)}`)
    assertEq([...mA.active].sort().join(','), 'endApprove', `已通过单主高亮应恰 [endApprove]（契约 §3 终态列），实际 ${JSON.stringify(mA.active)}`)
    assert(mA.completed.includes('start') && mA.completed.includes('approval'), `已通过单 completed 应含 start+approval（已执行路径），实际 ${JSON.stringify(mA.completed)}`)
    // 流程图与时间线并存（F3：图区不挤占时间线——与 BP7 三步断言互证）
    assertEq(await dlg.locator('.el-step').count(), 3, '详情弹窗时间线应仍恰 3 步（图与时间线并存）')
    await shot(page, 'bp9-approved-highlight.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    await waitDialogGone(page, '请假单详情')
    // ---- b. 审批中单（E 造单）：activeActivityIds 主高亮当前节点 ----
    const eId = await createLeaveViaUi({ title: T_E, typeLabel: '年假', reason: '' })
    assert(typeof eId === 'string', '发起 E 应返回 id')
    const rowE = await leaveRow(T_E, { reload: false })
    assert(rowE, `我的申请应含 "${T_E}" 行`)
    await rowE.getByRole('button', { name: '详情' }).click()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    const viewerE = dlg.locator('.bpmn-viewer')
    await viewerE.locator('.djs-element').first().waitFor({ state: 'visible', timeout: 20000 })
    await sleep(500)
    const mE = await viewerMarkers(viewerE)
    log(`  E 单 markers: active=${JSON.stringify(mE.active)} completed=${JSON.stringify(mE.completed)}`)
    assertEq([...mE.active].sort().join(','), 'approval', `审批中单主高亮应恰 [approval]（契约 §3 在途列），实际 ${JSON.stringify(mE.active)}`)
    assert(mE.completed.includes('start'), `审批中单 completed 应含 start，实际 ${JSON.stringify(mE.completed)}`)
    assert(!mE.active.includes('endApprove') && !mE.active.includes('endReject'), '审批中单主高亮不应含任何 end 节点')
    await shot(page, 'bp9-active-highlight.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    await waitDialogGone(page, '请假单详情')
    // ---- c. E 后台办结（BP8-b 同款直连手法）：归终态 已通过（保 CLEANUP 五单全终态） ----
    const todo = await directApi('GET', '/bpmn/task/todo')
    assertEq(todo.body.code, 200, '直连待办业务码应为 200')
    const eTask = todo.body.data.find((t) => t.leaveTitle === T_E)
    assert(eTask, `直连待办应含 "${T_E}"（taskId 办理锚点）`)
    const done = await directApi('POST', '/bpmn/task/complete', { taskId: eTask.taskId, approve: 'true', comment: COMMENT_E })
    log(`  直连后台办结 E: HTTP ${done.httpStatus} code=${done.body.code}`)
    assertEq(done.body.code, 200, '后台办结 E 应 body 200')
  })

  // ================= BP10 定义页「查看图」弹窗：五节点四连线零高亮 =================
  await step('BP10', '定义页 leave_approval 行「查看图」→ 弹窗 svg 渲染恰 5 节点 4 连线 + 零高亮 marker → 关闭', async () => {
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/definition/page'), { timeout: 15000 })
    await page.goto(`${BASE}${DEF_PATH}`, { waitUntil: 'domcontentloaded' })
    await respP
    await waitTableIdle(page)
    await sleep(300)
    const defRow = page.locator('.el-table__row', { hasText: 'leave_approval' }).first()
    assert(await defRow.isVisible(), '定义页应含 leave_approval 行')
    // xml 端点（契约 §2.1 /api/bpmn/definition/{definitionId}/xml——响应等待先注册后点击）
    const xmlP = page.waitForResponse(
      (r) => /^\/api\/bpmn\/definition\/.+\/xml$/.test(new URL(r.url()).pathname),
      { timeout: 15000 },
    )
    await defRow.getByRole('button', { name: '查看图' }).click()
    const xmlRes = await xmlP
    const xmlBody = await xmlRes.json()
    assertEq(xmlBody.code, 200, `取定义 xml 应 body 200，实际 ${xmlBody.code} msg="${xmlBody.msg}"`)
    assert(typeof xmlBody.data?.xml === 'string' && xmlBody.data.xml.includes('leave_approval'), 'xml 端点 data.xml 应含 leave_approval')
    log(`  xml 端点: HTTP ${xmlRes.status()} code=${xmlBody.code} xml 长度=${xmlBody.data.xml.length}`)
    const dlg = dialogByTitle('流程图 - 请假审批')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    const viewer = dlg.locator('.bpmn-viewer')
    await viewer.locator('.djs-element').first().waitFor({ state: 'visible', timeout: 20000 })
    await sleep(500)
    // 元素清点（run1 实测：bpmn-js 为 start/网关/end 的事件名另建外置标签元素（id 后缀 _label）——
    // .djs-shape 实计 9 含 4 标签；语义断言改走 data-element-id 封闭集（5 节点 + 4 连线恰 9 id），标签不计入）
    try {
      const allIds = await viewer.evaluate((el) =>
        Array.from(el.querySelectorAll('.djs-element')).map((g) => g.getAttribute('data-element-id')),
      )
      const nodeIds = allIds.filter((id) => !id.endsWith('_label')).sort()
      const labelN = allIds.length - nodeIds.length
      const connN = await viewer.locator('.djs-connection').count()
      log(`  查看图画布: 语义元素=${nodeIds.length}（其中连线 ${connN}）外置标签=${labelN} 全量 ids=${JSON.stringify(allIds)}`)
      assertEq(
        nodeIds.join(','),
        'approval,decision,endApprove,endReject,flowApproval,flowApprove,flowReject,flowStart,start',
        `leave_approval 应恰 9 语义元素（5 节点 + 4 连线封闭集），实际 ${JSON.stringify(nodeIds)}`,
      )
      assertEq(connN, 4, `连线元素应恰 4（flowStart/flowApproval/flowApprove/flowReject），实际 ${connN}`)
      const m = await viewerMarkers(viewer)
      assertEq(m.active.length + m.completed.length, 0, `定义页查看图应零高亮 marker（无高亮入参），实际 active=${JSON.stringify(m.active)} completed=${JSON.stringify(m.completed)}`)
      await shot(page, 'bp10-definition-diagram.png')
    } finally {
      // 关闭收尾必须兜底（run1 教训：断言中途抛出会遗留弹窗 overlay，拦截后续步骤行内点击）
      await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
      await waitDialogGone(page, '流程图 - 请假审批')
    }
  })

  // ================= BP11 设计器：不改动原样重部署 version+1（三证：响应/toast/页内 fetch 前后对比） =================
  await step('BP11', '「设计」打开 leave_approval → 画布元素 ≥1 + 属性面板挂载 + XML 源码含 leave_approval → 不改动「保存部署」→ version+1', async () => {
    // 步骤自归位（run1 教训：上步若遗留弹窗 overlay 会拦截行内按钮点击——goto 全量重载天然清场）
    const navP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/definition/page'), { timeout: 15000 })
    await page.goto(`${BASE}${DEF_PATH}`, { waitUntil: 'domcontentloaded' })
    await navP
    await waitTableIdle(page)
    await sleep(300)
    // 部署前版本（页内 fetch API 层——latestVersion 过滤下行即最新版）
    const before = await pageFetch('/api/bpmn/definition/page?pageNum=1&pageSize=50')
    assertEq(before.code, 200, '部署前定义分页业务码应为 200')
    const beforeRow = before.data.rows.find((r) => r.key === 'leave_approval')
    assert(beforeRow, `部署前分页应含 leave_approval，实际 ${JSON.stringify(before.data.rows.map((r) => r.key))}`)
    const vBefore = Number(beforeRow.version)
    assert(Number.isInteger(vBefore) && vBefore >= 1, `部署前版本应为正整数，实际 "${beforeRow.version}"`)
    log(`  部署前版本: leave_approval v${vBefore}`)
    const defRow = page.locator('.el-table__row', { hasText: 'leave_approval' }).first()
    await defRow.getByRole('button', { name: '设计' }).click()
    const dlg = dialogByTitle('编辑流程')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // 版本语义防呆 alert（设计 D5 常驻）
    const alertText = (await dlg.locator('.el-alert').innerText()).trim()
    assert(alertText.includes('新版本仅对新发起的流程生效'), `防呆 alert 应含版本语义文案，实际 "${alertText}"`)
    // 画布（Modeler async chunk + getDefinitionXml 回填 + importXML——F7 修复后容器常驻，等 djs 元素即可）
    await dlg.locator('.designer-canvas .djs-element').first().waitFor({ state: 'visible', timeout: 20000 })
    await sleep(500)
    const elN = await dlg.locator('.designer-canvas .djs-element').count()
    log(`  设计器画布元素: ${elN}`)
    assert(elN >= 1, `设计器画布应渲染元素 ≥1（F7 booting v-if 修复后画布常驻），实际 ${elN}`)
    const panelN = await dlg.locator('.designer-panel').evaluate((el) => el.childElementCount)
    log(`  属性面板挂载子节点: ${panelN}`)
    assert(panelN > 0, `properties-panel 应已挂载（designer-panel 子节点 >0），实际 ${panelN}`)
    await shot(page, 'bp11-designer-canvas.png')
    // XML 源码 tab（切 tab 触发 saveXML 同步——契约 §2.1 消费位）
    await dlg.getByRole('tab', { name: 'XML 源码' }).click()
    await sleep(800)
    const xmlText = ((await dlg.locator('.designer-xml').innerText()) || '').trim()
    assert(!xmlText.includes('暂无内容'), 'XML 源码不应为空态 暂无内容')
    assert(xmlText.includes('leave_approval'), `XML 源码应含 leave_approval，实际前 200 字 "${xmlText.slice(0, 200)}"`)
    log(`  XML 源码: ${xmlText.length} 字符（含 leave_approval）`)
    // 不改动保存部署（契约 §2.2 multipart——响应等待先注册后点击）
    const deployP = page.waitForResponse(
      (r) => apiPath(r.url(), '/api/bpmn/definition/deploy') && r.request().method() === 'POST',
      { timeout: 30000 },
    )
    await dlg.locator('.el-dialog__footer button', { hasText: '保存部署' }).click()
    const res = await deployP
    const body = await res.json()
    assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(body.code, 200, `保存部署应 body 200，实际 ${body.code} msg="${body.msg}"`)
    const defs = body.data?.definitions ?? []
    log(`  部署响应 definitions: ${JSON.stringify(defs)}`)
    const deployed = defs.find((d) => d.key === 'leave_approval')
    assert(deployed && Number(deployed.version) === vBefore + 1, `部署响应应含 leave_approval v${vBefore + 1}，实际 ${JSON.stringify(defs)}`)
    const toastText = await waitToast(page, '部署成功')
    assert(toastText.includes(`leave_approval v${vBefore + 1}`), `toast 应含 "leave_approval v${vBefore + 1}"，实际 "${toastText}"`)
    await waitDialogGone(page, '编辑流程')
    await waitTableIdle(page)
    await sleep(300)
    // 部署后版本（页内 fetch 前后对比 + UI 行版本互证——emit success 已刷新列表）
    const after = await pageFetch('/api/bpmn/definition/page?pageNum=1&pageSize=50')
    assertEq(after.code, 200, '部署后定义分页业务码应为 200')
    const afterRow = after.data.rows.find((r) => r.key === 'leave_approval')
    assert(afterRow, '部署后分页应含 leave_approval')
    assertEq(Number(afterRow.version), vBefore + 1, `部署后版本应为 ${vBefore + 1}（同 key 自动 +1），实际 "${afterRow.version}"`)
    const uiVer = (await rowCells(page.locator('.el-table__row', { hasText: 'leave_approval' }).first()))[2]
    assertEq(uiVer, String(vBefore + 1), `UI 行版本列应变 ${vBefore + 1}，实际 "${uiVer}"`)
    await shot(page, 'bp11-deployed.png')
  })

  // ================= BP12 工作台两卡：行数公式 + stamp 行 + 查看全部跳转 =================
  await step('BP12', '/dashboard 两卡标题 + 待办行数 = min(5, todo 接口长度) + 申请卡含本轮 stamp 行 + 两「查看全部」跳转 URL（无权限整卡隐藏分支记档不测——单账号环境）', async () => {
    // 数据链在 onMounted（keep-alive 组件 Dashboard——首访即挂载即拉取；响应等待先注册）
    const todoP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/task/todo'), { timeout: 15000 })
    const leaveP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/leave/page'), { timeout: 15000 })
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' })
    const todoBody = await (await todoP).json()
    const leaveBody = await (await leaveP).json()
    assertEq(todoBody.code, 200, 'todo 接口业务码应为 200')
    assertEq(leaveBody.code, 200, 'leave 分页业务码应为 200')
    const todoLen = Array.isArray(todoBody.data) ? todoBody.data.length : 0
    // B5 留档红线：todo 列表可能含 e2ebpmnb5114833 前缀留档待办——min 公式天然计入，只断言行数不点名触碰
    const todoCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的待办' }) })
    const leaveCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的申请' }) })
    assertEq(await todoCard.count(), 1, '「我的待办」卡应恰 1 张（admin 快照含 bpmn:task:list）')
    assertEq(await leaveCard.count(), 1, '「我的申请」卡应恰 1 张（admin 快照含 bpmn:leave:list）')
    await todoCard.waitFor({ state: 'visible', timeout: 8000 })
    await leaveCard.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500)
    const todoRowN = await todoCard.locator('.dash-row').count()
    log(`  待办卡: 行数=${todoRowN}（todo 接口长度=${todoLen} → min(5, ${todoLen})=${Math.min(5, todoLen)}）`)
    assertEq(todoRowN, Math.min(5, todoLen), `待办卡行数应为 min(5, todo 接口长度)=${Math.min(5, todoLen)}，实际 ${todoRowN}`)
    const leaveRowN = await leaveCard.locator('.dash-row').count()
    const stampRows = await leaveCard.locator('.dash-row', { hasText: `e2ebpmn${stamp}` }).count()
    log(`  申请卡: 行数=${leaveRowN} 其中本轮 stamp 行=${stampRows}（leave 接口 total=${leaveBody.data.total}）`)
    assert(leaveRowN > 0, `申请卡应有数据行（本轮五单置顶），实际 ${leaveRowN}`)
    assert(leaveRowN <= 5, `申请卡行数应 ≤5（pageSize=5），实际 ${leaveRowN}`)
    assert(stampRows >= 1, `申请卡应含本轮 stamp（e2ebpmn${stamp}）行，实际 ${stampRows}`)
    await shot(page, 'bp12-dashboard.png')
    // 两「查看全部」跳转（keep-alive：二次 goto /dashboard 不重挂载，卡片仍在 DOM 可定位 footer）
    await todoCard.getByRole('button', { name: '查看全部' }).click()
    await page.waitForURL('**/bpmn/task', { timeout: 10000 })
    assertEq(new URL(page.url()).pathname, TASK_PATH, `待办卡「查看全部」应跳 ${TASK_PATH}，实际 ${page.url()}`)
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' })
    await todoCard.waitFor({ state: 'visible', timeout: 8000 })
    await leaveCard.getByRole('button', { name: '查看全部' }).click()
    await page.waitForURL('**/bpmn/leave', { timeout: 10000 })
    assertEq(new URL(page.url()).pathname, LEAVE_PATH, `申请卡「查看全部」应跳 ${LEAVE_PATH}，实际 ${page.url()}`)
  })

  // ================= CLEANUP 清扫纪律新形态：本轮 stamp 单全部终态（不清零、允许留档） =================
  await step('CLEANUP', '清扫新形态：本轮 stamp 五单各自终态（A/D/E=已通过 R=已拒绝 C=已撤销）+ C 实例已删 + 无审批中残留（不清零，允许留档）', async () => {
    // ---- API 层权威断言（页内 fetch，数据面以接口为准） ----
    const fetched = await pageFetch('/api/bpmn/leave/page?pageNum=1&pageSize=50')
    assertEq(fetched.code, 200, '页内 fetch 业务码应为 200')
    const mine = fetched.data.rows.filter((r) => r.title.startsWith(`e2ebpmn${stamp}`))
    log(`  本轮 stamp 单: ${mine.length} 行`)
    assertEq(mine.length, 5, `本轮 stamp 应恰 5 单（A/R/C/D/E），实际 ${JSON.stringify(mine.map((r) => [r.title, r.status]))}`)
    const expected = { [T_A]: '1', [T_R]: '2', [T_C]: '3', [T_D]: '1', [T_E]: '1' }
    const expectedLabel = { [T_A]: '已通过', [T_R]: '已拒绝', [T_C]: '已撤销', [T_D]: '已通过', [T_E]: '已通过' }
    for (const row of mine) {
      assertEq(row.status, expected[row.title], `${row.title} 终态应为 ${expected[row.title]}（${expectedLabel[row.title]}），实际 ${row.status}`)
      assertEq(row.statusLabel, expectedLabel[row.title], `${row.title} statusLabel 应为 ${expectedLabel[row.title]}，实际 "${row.statusLabel}"`)
      if (row.title === T_C) {
        assertEq(row.processInstanceId, null, '已撤销单流程实例应已删（processInstanceId=null——契约 §2.2）')
      } else {
        assert(row.processInstanceId, `${row.title} 终态单应保留历史实例 id`)
      }
      assert(row.status !== '0', `不应有审批中残留（${row.title}）`)
    }
    // 4004 探针 X 永不落库（stamp 前缀兜底核验）
    assert(!mine.some((r) => r.title === T_X), '4004 探针 X 不应落库')
    // ---- UI 层复核（五行各自状态 tag 终态呈现） ----
    for (const title of [T_A, T_R, T_C, T_D, T_E]) {
      const row = await leaveRow(title)
      assert(row, `UI 应仍含 "${title}" 行`)
      const cells = await rowCells(row)
      assertEq(cells[3], expectedLabel[title], `UI ${title} 状态列应为 ${expectedLabel[title]}`)
      const tag = await statusTag(row)
      assert(tag.cls.includes('el-tag--'), `${title} 状态 tag 应存在`)
      assertEq(await row.getByRole('button', { name: '撤销' }).count(), 0, `UI ${title} 终态行不应有 撤销 按钮`)
    }
    await shot(page, 'cleanup-final.png')
  })

  // ================= 证据核验：无 console error / pageerror / >=400 / 网络失败 =================
  await step('BP-VERIFY', '证据核验：无 console error / pageerror / >=400 响应 / 网络失败（4002/4004 错误码在 body，HTTP 恒 200；401 探测走 page.request 不入统计）', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    const noiseFree404 = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noiseFree404.length, 0, `非 favicon 的资产 404 不应存在，实际 ${JSON.stringify(asset404)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（HTTP 恒 200；错误码在 body），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({
    extras: [
      `\n测试数据: ${T_A}（事假→已通过）/ ${T_R}（病假→已拒绝）/ ${T_C}（年假→已撤销）/ ${T_D}（事假→已通过，4002 探针附产）/ ${T_E}（年假→已通过，BP9 审批中高亮探针）/ ${T_X}（4004 探针，永不落库）`,
      '清扫纪律新形态（设计 D12）：bpmn 域无删除端点——业务表不清零，本轮 stamp 五单全部终态即验收通过；ACT_HI 允许残留；下一轮时间戳天然隔离；BP11 部署产生的 v2+ 定义允许残留（latestVersion 过滤下 UI 恒显最新版）',
      'B5 留档红线遵守：e2ebpmnb5114833 前缀单/角色与受限账号 e2ebpmnb5114833u 绝不办理/触碰（BP12 待办 min 公式允许留档待办计入）；历史 e2ebpmncurl/e2ebpmnf6 终态留档单未触碰（stamp 精确锚定，无全局计数断言）；admin 双角色（申请人+审批人）单人闭环；内置种子零触碰',
    ],
  })
  await browser.close()
}
