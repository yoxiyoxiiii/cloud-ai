/**
 * 审批平台化 e2e 第七脚本（契约 2026-10-08-approval-platform-api §2/§3/§4/§5 + diagram/designer 契约保留面；
 * 计划 Round I E2 全迁移——自 v1 bpmn-leave 契约一次性切换；harness 复用 lib/harness.mjs）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202 / bpmn 9203 已启动；前端 dev 5173 已启动
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 npm run e2e:bpmn）
 * 前置：9202+9203 健康探测（无浏览器 Node fetch 经网关——登录取 token 后 GET /system/leave/page +
 *   /bpmn/definition/page；首请求失败即打印 SKIP 原因退出，勿硬跑——卡点纪律同 B9 先例，设计 D12）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码
 *
 * Round I 迁移总览（契约 §0 变更点；语义保留、必红点不弱化）：
 * - 路径常量：LEAVE_PATH=/system/leave（31 段改造）、APPROVAL_PATH=/bpmn/approval（34 段新增）；
 *   TASK_PATH/DEF_PATH 不变（§0.2 字段通用化 / §0.3 定义面零变化）
 * - API 断言面：/api/system/leave/**（§5 迁移版）+ /api/bpmn/approval/**（§3 新）+ /api/bpmn/task/**（§2 通用化）
 * - 发起弹窗：类型下拉字典 system_leave_type（§7 迁名）；审批人投影 /system/leave/approvers（§5.5 仅启用）
 * - detailPath 断言 A2 语义（契约 §1 渲染值澄清）：{businessKey} 渲染值=审批单 id——
 *   /system/leave?approval={approvalId}（勿断业务单 id）
 * - 「去处理」跳转落点（A3 修复后口径）：落 /system/leave?approval={id} 弹窗自动打开**且内容已加载**
 *   （descriptions 非空——不再容忍零请求空白）
 * - 办理后纠偏终态（§5.2 读时纠偏）：/system/leave/page 原值 status 断言（语义不弱化：通过→"1"/拒绝→"2"）
 * - 防御面：无 token /system/leave/page → HTTP 401；stale 撤销 4002→3020（msg 逐字「请假单已终态，不可撤销」）；
 *   approver=nobody 4004→3023（msg 逐字「审批人无效: nobody」——契约 §6 已定稿逐字口径）
 * - BP9 图三态全覆盖（B6 教训）：审批中 active=[approval] / 已通过 active=[endApprove] / 已撤销截断 active=[] 无终态高亮
 * - BP13 新场景（平台我的审批页 §3）：列表四态色 + 详情弹窗（时间线+图）+ 行撤销终态禁用矩阵 +
 *   平台撤销后 /system/leave 纠偏已撤销（跨页真相源语义）
 *
 * 清扫纪律（沿设计 D12 形态 + 契约 §1 清扫纪律）：
 * - bpmn 域无删除端点（留档语义）→ 不做业务表清零；CLEANUP 断言「本轮 stamp 单全部终态 + 下一轮时间戳天然隔离」
 * - ACT_HI 历史表允许 e2e 前缀残留（引擎表不可控，不清理）；BP11 部署产生的 v2+ 定义允许残留
 * - 本轮 stamp 请假单/审批单全终态（stamp 精确锚定，无全局计数断言）
 *
 * 测试数据（title 前缀 e2ebpmn${stamp}；admin 双角色 = 申请人 + 审批人，单人闭环——e2e 环境最小依赖）：
 * - A 同意路径 / R 拒绝路径 / C 撤销路径（system 面撤销）/ P 平台面撤销（BP13）/
 *   D 3020 探针（后台审批后对已终态单再撤销——BP8 附产，终态=已通过）/
 *   E 审批中高亮探针（BP9 造单断言 approval 主高亮后即后台办结，终态=已通过）/
 *   X 3023 探针（approver=nobody，被校验拦截永不落库）
 *
 * 场景（契约 §11 验收口径）：
 * - BP0 admin 登录 + 请假申请页骨架（侧边 9 项/高亮/面包屑/表头 8 列/发起按钮=权限快照证据）+
 *   旧 /bpmn/leave 直访 NotFound（一次性切换实证）
 * - BP1 发起弹窗形态：类型下拉恰 3 项（字典 system_leave_type 消费）+ 审批人下拉含 admin（system 本库投影，仅启用）
 * - BP2 发起（A）→ 请假申请行：审批中 tag + 类型译文 + 审批人昵称（UI 层）
 *   + 页内 fetch /system/leave/page 原值与译文字段并存 + /bpmn/approval/page 交叉锚定
 *   （approvalId/status/businessTypeName/detailPath=approvalId 形态——A2 语义）
 * - BP3 待办出现（businessTypeName/detailPath 断言）→「去处理」跳转落点（弹窗自动开+descriptions 已加载）
 *   → 办理弹窗（同意默认选中 + 意见）→ 待办消行 + 请假申请变已通过（纠偏）+ 已办 tab 1 行同意
 * - BP4 拒绝路径（R）：办理选拒绝 → 已拒绝 + 已办 tab 拒绝 tag（与 A 行累积断言）
 * - BP5 撤销路径（C，system 面撤销）：ElMessageBox 二段确认（含标题与不可恢复提示）→ 已撤销 + 待办无此单
 * - BP6 流程定义页：leave_approval 行（key/名称/版本/部署时间格式）+ 表头 5 列（末位操作列）——零变化保留
 * - BP7 详情时间线：已通过单三步骤（发起申请/审批意见含文案/流程结束 result=已通过）+ 业务 descriptions 双源
 * - BP8 防御面：无 token 直调 /system/leave/page → HTTP 401（网关层）；D 单后台审批后 stale DOM 撤销 →
 *   toast 3020（msg 逐字）；approver=nobody 直连发起 body 3023（msg 逐字）；
 *   定义页写面反转（种子 331 bpmn:definition:deploy 绑 admin——零变化保留）
 * - BP9 详情流程图三态全覆盖：已通过 A 单主高亮恰 [endApprove] + completed 含 start/approval；
 *   审批中 E 单主高亮恰 [approval]（在途列）；已撤销 C 单截断形态 active=[] 无终态高亮 +
 *   completed 截断至删除点 + 时间线两步（发起申请/流程结束=已撤销）→ E 后台办结归终态
 * - BP10 定义页「查看图」弹窗：xml 端点 200 → svg 渲染 leave_approval 语义元素封闭集恰 9 id + 零高亮 marker → 关闭
 * - BP11 设计器：「设计」打开 leave_approval → 画布元素 ≥1 + 属性面板挂载 + XML 源码含 leave_approval →
 *   不改动「保存部署」→ 部署响应/toast/页内 fetch 三证 version+1（原样保留）
 * - BP12 工作台两卡：「我的待办」卡（title/businessTypeName 行）+「我的审批」卡（前 5、状态 tag）+
 *   两「查看全部」分别跳 /bpmn/task、/bpmn/approval
 * - BP13 平台我的审批页（新）：列表（businessTypeName/title/状态 tag 四态色）→ 详情弹窗（时间线+图）→
 *   行撤销终态禁用矩阵 → 平台撤销 P → /system/leave 纠偏已撤销（跨页真相源语义）
 * - CLEANUP/VERIFY：本轮 stamp 六单（请假面+审批面双接口）全部终态（A/D/E=已通过 R=已拒绝 C/P=已撤销，
 *   撤销单实例已删 processInstanceId=null；detailPath 全行 approvalId 形态复核）+ console/badResponses/网络失败零污染
 *
 * B5 留档红线：e2ebpmnb5114833 前缀单/角色与受限账号 e2ebpmnb5114833u 绝不办理/触碰——
 * 旧时代（bpmn_leave）待办残留经 §2.1 防御态三重跳过不进 todo 列表；BP12 待办 min 公式天然兼容
 *
 * 实操注意（F6/F9 联调结论沿袭）：el-date-picker 走日历面板点击（键盘输入会触发 EP Invalid user input
 * 警告污染 console 断言）；办理弹窗同意 radio 默认选中；两页撤销均有 ElMessageBox 二段确认
 *
 * Round J MQ 事务消息迁移（契约 2026-10-09-rocketmq-tx-approval-api，对 2026-10-08 契约的修订）：
 * - 发起链路 MQ 事务消息化：POST /system/leave 签名/返回零变化（返回新单 id=snowflake 预生成=businessKey）；
 *   approvalId 改由 CREATE_RESULT/SUCCESS 事件秒级回填（异步收敛时窗——纠偏只纠 status 且仅限已带
 *   approvalId 的行，回填只靠事件消费者条件 UPDATE 落库）——createLeaveViaUi 内置收敛轮询
 *   waitForApprovalId（GET /system/leave/{id} 至 approvalId 非 null，timeout 15s，契约 §6）
 * - 终态收敛主路径改 TERMINAL 事件回写（读时纠偏降级兜底）——BP3/BP4 纠偏原值断言语义不变（读仍纠偏）
 * - 消息不删纪律（契约 §6）：断言一律锚本轮 stamp 新单据；历史消息经幂等路径（业务 uk/L1 去重表/
 *   条件 UPDATE）静默吸收，不污染断言
 * - 预热纪律（契约 §6 + 2026-10-09 Feign D5 教训迁移）：preflight 后 MQ 全链预热
 *   （create→approvalId 收敛→complete→终态收敛）——producer 长闲后冷首发实测超默认 sendMsgTimeout
 *   （→1002「消息服务不可用」，重试即愈）+ 消费组冷启动首投递 ~25s（rebalance），四角色
 *   （p_system_tx/g_bpmn_approval_create/p_bpmn_tx/g_system_approval_event）一并暖；预热失败 SKIP 不硬跑
 * - 预热单 e2emqwarm 前缀（非本轮 stamp——不进 CLEANUP 计数），办结归终态留档（清扫纪律允许）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（BP8 无 token 401 探测 + 3020 后台审批 + 3023 直连——page.request 不入 page 网络统计，不污染 BP-VERIFY） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, findRow, rowCells } = h

// ---------- 测试数据（e2ebpmn 前缀 + 时间戳；title 是全链路唯一锚点） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const T_A = `e2ebpmn${stamp}A` // 同意路径
const T_R = `e2ebpmn${stamp}R` // 拒绝路径
const T_C = `e2ebpmn${stamp}C` // 撤销路径（system 面撤销）
const T_P = `e2ebpmn${stamp}P` // 平台面撤销（BP13 我的审批页）
const T_D = `e2ebpmn${stamp}D` // 3020 探针（审批后再撤销）
const T_E = `e2ebpmn${stamp}E` // 审批中高亮探针（BP9 造单断言后即后台办结，终态=已通过）
const T_X = `e2ebpmn${stamp}X` // 3023 探针（approver=nobody，永不落库）
const REASON_A = `E2E事由同意${stamp}`
const COMMENT_A = `E2E同意意见${stamp}`
const COMMENT_R = `E2E拒绝意见${stamp}`
const COMMENT_D = `E2E后台同意${stamp}`
const COMMENT_E = `E2E通过意见${stamp}`
/** 审批单 id 锚点（BP2 起逐单累积——detailPath/审批面交叉断言用） */
const approvalIdOf = {}

const LEAVE_PATH = '/system/leave'
const APPROVAL_PATH = '/bpmn/approval'
const TASK_PATH = '/bpmn/task'
const DEF_PATH = '/bpmn/definition'
/** 日期探针年（picker 断言用——当年当月 25/26 日，未来日期必 available） */
const YEAR = ts.getFullYear()

/** 类型下拉选项 → leaveType 原值（字典 system_leave_type：1 事假 / 2 病假 / 3 年假——契约 §7 迁名） */
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
    const headers = { Authorization: `Bearer ${lb.data.accessToken}` }
    const sr = await fetch(`${GATEWAY}/system/leave/page?pageNum=1&pageSize=1`, { headers })
    const sb = await sr.json().catch(() => null)
    if (!sb || sb.code !== 200) {
      return { ok: false, reason: `system 域探测失败（9202 经网关不可达或未就绪）: HTTP ${sr.status} body=${JSON.stringify(sb).slice(0, 200)}` }
    }
    const dr = await fetch(`${GATEWAY}/bpmn/definition/page?pageNum=1&pageSize=1`, { headers })
    const db = await dr.json().catch(() => null)
    if (!db || db.code !== 200) {
      return { ok: false, reason: `bpmn 域探测失败（9203 经网关不可达或未就绪）: HTTP ${dr.status} body=${JSON.stringify(db).slice(0, 200)}` }
    }
    return { ok: true, defTotal: db.data?.total, token: lb.data.accessToken }
  } catch (e) {
    return { ok: false, reason: `网络层异常: ${String(e)}` }
  }
}

/** MQ 全链预热（契约 §6 预热纪律）：create（p_system_tx 冷首发重试一次）→ approvalId 收敛
 *  （g_bpmn_approval_create + g_system_approval_event 冷启动预算 60s）→ complete（p_bpmn_tx）
 *  → 终态收敛（TERMINAL 事件回写）。预热单 e2emqwarm 前缀，办结归终态留档（不清零纪律）。 */
async function mqWarmup(accessToken) {
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` }
  const title = `e2emqwarm${Date.now()}`
  const gw = (path) => fetch(`${GATEWAY}${path}`, { headers: { Authorization: headers.Authorization } })
  let leaveId = null
  // 发起（producer 冷首发实测 1002——重试一次即愈，F4 联调实证）
  for (let attempt = 1; attempt <= 2 && !leaveId; attempt++) {
    const body = await fetch(`${GATEWAY}/system/leave`, {
      method: 'POST', headers,
      body: JSON.stringify({ title, leaveType: '1', startDate: '2026-11-25', endDate: '2026-11-26', reason: 'mq warmup', approver: 'admin' }),
    }).then((r) => r.json()).catch(() => null)
    if (body?.code === 200 && typeof body.data === 'string') leaveId = body.data
    else log(`  预热发起第 ${attempt} 次未成: code=${body?.code} msg="${body?.msg}"（producer 冷首发→1002 属实测已知，重试）`)
  }
  if (!leaveId) return { ok: false, reason: '预热发起两次失败（MQ 生产链路不可用？）' }
  // approvalId 收敛（消费组冷启动首投递 ~25s——预算 60s；详情 VO 是嵌套 {leave} 包装）
  let approvalId = null
  for (let i = 0; i < 120 && !approvalId; i++) {
    const body = await gw(`/system/leave/${leaveId}`).then((r) => r.json()).catch(() => null)
    approvalId = body?.data?.leave?.approvalId || null
    if (!approvalId) await new Promise((r) => setTimeout(r, 500))
  }
  if (!approvalId) return { ok: false, reason: `预热单 approvalId 60s 未收敛（消费链路不可用？leaveId=${leaveId}）`, leaveId }
  // 办结（bpmn 侧生产冷首发同防——重试一次）
  let done = null
  for (let attempt = 1; attempt <= 2 && done?.code !== 200; attempt++) {
    const todo = await gw('/bpmn/task/todo').then((r) => r.json()).catch(() => null)
    const task = (todo?.data || []).find((t) => String(t.approvalId) === String(approvalId))
    if (!task) return { ok: false, reason: `预热单待办未出现（leaveId=${leaveId} approvalId=${approvalId}）`, leaveId }
    done = await fetch(`${GATEWAY}/bpmn/task/complete`, {
      method: 'POST', headers,
      body: JSON.stringify({ taskId: task.taskId, approve: 'true', comment: 'mq warmup approve' }),
    }).then((r) => r.json()).catch(() => null)
    if (done?.code !== 200) log(`  预热办结第 ${attempt} 次未成: code=${done?.code} msg="${done?.msg}"（冷首发重试）`)
  }
  if (done?.code !== 200) return { ok: false, reason: `预热办结两次失败: code=${done?.code} msg="${done?.msg}"`, leaveId }
  // 终态收敛确认（TERMINAL 事件回写为主、读时纠偏兜底——15s；status 同嵌套 {leave} 包装）
  for (let i = 0; i < 30; i++) {
    const body = await gw(`/system/leave/${leaveId}`).then((r) => r.json()).catch(() => null)
    if (body?.data?.leave?.status === '1') return { ok: true, title }
    await new Promise((r) => setTimeout(r, 500))
  }
  return { ok: false, reason: `预热单终态 15s 未收敛（TERMINAL 事件链路？leaveId=${leaveId}）`, leaveId }
}

const pre = await preflight()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按设计 D12 卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202 / bpmn 9203 已启动且经网关可达')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
log(`前置健康探测通过（经网关 /system/leave/page + /bpmn/definition/page，流程定义 total=${pre.defTotal}）`)

// ---- MQ 全链预热（失败即 SKIP——契约 §6 预热纪律；残留预热单尽力撤销清理） ----
const warm = await mqWarmup(pre.token)
if (!warm.ok) {
  log(`\n[SKIP] MQ 链路预热未通过: ${warm.reason}`)
  if (warm.leaveId) {
    await fetch(`${GATEWAY}/system/leave/cancel/${warm.leaveId}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${pre.token}` },
    }).then((r) => r.json()).catch(() => null)
    log(`已尽力撤销残留预热单 leaveId=${warm.leaveId}`)
  }
  h.summary({ extras: [`\nSKIP 原因: ${warm.reason}`] })
  process.exit(0)
}
log(`MQ 全链预热通过（create→approvalId 收敛→complete→终态；预热单 ${warm.title} 归终态留档）`)

// ---------- 主流程（默认有头 + slowMo 300，与其余七脚本一致） ----------
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

/** BpmnViewer 高亮态提取（Round H BP9/BP10 沿袭）：canvas.addMarker 把 marker 类挂在 g.djs-element 上、
 *  data-element-id 即流程节点 id（BpmnViewer 双类：bpmn-highlight-active 主高亮 / -completed 已执行路径） */
async function viewerMarkers(scope) {
  return scope.evaluate((el) => {
    const ids = (cls) =>
      Array.from(el.querySelectorAll(`.djs-element.${cls}`)).map((g) => g.getAttribute('data-element-id'))
    return { active: ids('bpmn-highlight-active'), completed: ids('bpmn-highlight-completed') }
  })
}

/** 进入请假申请页并等首屏分页落定 */
async function loadLeavePage() {
  const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/leave/page'), { timeout: 15000 })
  await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded' })
  await respP
  await waitTableIdle(page)
  await sleep(300)
}

/** 请假申请行定位（title 唯一锚点；新单 id 倒序置顶但残留可能在任意页——全表翻页） */
async function leaveRow(title, { reload = true } = {}) {
  return findRow(page, title, { path: LEAVE_PATH, reload })
}

/** 我的审批行定位（当前页可见行；id 倒序本轮 stamp 单恒在第 1 页，不翻页） */
const approvalRow = (title) => page.locator('.el-table__row', { hasText: title }).first()

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
 * daterange 双月面板取左侧（当前月）；25/26 未来日期必 available
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
  assertEq(sv, `${YEAR}-${mm}-${pad(startDay)}`, `开始日期输入框应为 ${YEAR}-${mm}-${pad(startDay)}，实际 "${sv}"`)
  assertEq(ev, `${YEAR}-${mm}-${pad(endDay)}`, `结束日期输入框应为 ${YEAR}-${mm}-${pad(endDay)}，实际 "${ev}"`)
  return { sv, ev }
}

/** approvalId 收敛轮询（契约 2026-10-09 §2.2/§6）：发起后 approvalId 短暂 null（CREATE_RESULT 事件回填前），
 *  轮询 GET /system/leave/{id} 至非空（timeout 15s 窗口内必达；纠偏不回填 approvalId——只等事件消费者
 *  条件 UPDATE 落库）。跳转入口/图渲染/待办锚点均以 approvalId 非空为前置——本 helper 即收敛闸门。 */
async function waitForApprovalId(leaveId, timeoutMs = 15000) {
  const t0 = Date.now()
  for (let i = 0; i < Math.ceil(timeoutMs / 500); i++) {
    const detail = await pageFetch(`/api/system/leave/${leaveId}`)
    const approvalId = detail.data?.leave?.approvalId
    if (detail.code === 200 && approvalId) {
      log(`  approvalId 收敛: ${approvalId}（${Date.now() - t0}ms——MQ CREATE_RESULT 事件回填）`)
      return approvalId
    }
    await sleep(500)
  }
  assert(false, `approvalId 应在 ${timeoutMs}ms 内收敛（MQ CREATE_RESULT 事件回填），leaveId=${leaveId}`)
}

/** 发起请假全流程（开弹窗 → 填五字段 → 提交 → 发起成功 toast + 弹窗关闭 + 列表刷新落定 →
 *  approvalId 收敛轮询——契约 §6「approvalId 相关场景改收敛轮询」统一闸门）；返回新单 id */
async function createLeaveViaUi({ title, typeLabel, reason }) {
  // 发起按钮只在 /system/leave（调用方可能在任务/定义/审批页——先归位再开弹窗）
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
  const postP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/leave') && r.request().method() === 'POST', { timeout: 15000 })
  const pageP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/leave/page'), { timeout: 15000 })
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
  log(`  发起成功: title=${title} type=${typeLabel}(${TYPE_LABELS[typeLabel]}) ${sv}~${ev} leaveId=${body.data}`)
  // MQ 形态收敛闸门（BP2 approvalId 非空断言/锚定/待办/跳转/图渲染全依赖此后置成立）
  await waitForApprovalId(body.data)
  return body.data
}

/** 发起后锚定审批单 id（审批面 page 交叉查——BP2 起所有 detailPath 断言的锚点源） */
async function anchorApprovalId(title) {
  const fetched = await pageFetch('/api/bpmn/approval/page?pageNum=1&pageSize=50')
  assertEq(fetched.code, 200, '审批面分页业务码应为 200（锚定审批单 id）')
  const row = fetched.data.rows.find((r) => r.title === title)
  assert(row, `审批面应含 "${title}" 行（锚定 approvalId），实际 ${JSON.stringify(fetched.data.rows.map((r) => r.title).slice(0, 8))}…`)
  approvalIdOf[title] = row.id
  return row.id
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
 *  以「页签激活 + 已办表面板可见」为落定准绳（run2 实测点击后偶发无新请求，waitForResponse 硬等会假红） */
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
  assert((await dlg.innerText()).includes(title), `办理弹窗应展示单据标题 "${title}"`)
  assert((await dlg.innerText()).includes('请假申请'), `办理弹窗应展示业务类型名 请假申请（TaskVo businessTypeName），实际 "${(await dlg.innerText()).replace(/\n/g, ' | ').slice(0, 160)}"`)
  const checked = dlg.locator('.el-radio.is-checked')
  assertEq(((await checked.innerText()) || '').trim(), '同意', '办理弹窗同意 radio 应默认选中')
  if (approveLabel !== '同意') {
    await dlg.locator('.el-radio', { hasText: approveLabel }).first().click()
    await sleep(200)
    assertEq(((await dlg.locator('.el-radio.is-checked').innerText()) || '').trim(), approveLabel, `选后 ${approveLabel} 应为选中态`)
  }
  if (comment) await dlg.locator('textarea[placeholder="选填，不超过 200 字"]').fill(comment)
  // 响应等待须在触发点击之前注册（handleCompleteSuccess 即刻双发 todo+done 刷新——晚了会错过响应）
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

/** 请假详情弹窗打开并等图渲染落定（行入口）；返回弹窗定位器 */
async function openLeaveDetail(title) {
  const row = await leaveRow(title)
  assert(row, `请假申请应含 "${title}" 行`)
  await row.getByRole('button', { name: '详情' }).click()
  const dlg = dialogByTitle('请假单详情')
  await dlg.waitFor({ state: 'visible', timeout: 8000 })
  // 两段式数据链（detail → approval → diagram → xml）+ BpmnViewer async chunk：等首个 djs 元素可见
  const viewer = dlg.locator('.bpmn-viewer')
  await viewer.locator('.djs-element').first().waitFor({ state: 'visible', timeout: 20000 })
  await sleep(500)
  return dlg
}

/** 关闭请假详情弹窗 */
async function closeLeaveDetail() {
  const dlg = dialogByTitle('请假单详情')
  await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
  await waitDialogGone(page, '请假单详情')
}

try {
  // ================= BP0 登录 + 请假申请页骨架 + 旧路由NotFound =================
  await step('BP0', '无 token 直访被拦 → admin 登录回跳 + 侧边 9 项（请假申请高亮）+ 面包屑 + 表头 8 列 + 发起按钮 + 旧 /bpmn/leave 直访 NotFound', async () => {
    await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForURL('**/login**', { timeout: 10000 })
    const url = new URL(page.url())
    assertEq(url.searchParams.get('redirect'), LEAVE_PATH, `redirect 参数应为 ${LEAVE_PATH}`)
    // admin 双角色登录 = 全新权限快照（含 system:leave:*/bpmn:task:*/bpmn:definition:*/bpmn:approval:* 30 权限）
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
    await page.waitForURL(`**${LEAVE_PATH}`, { timeout: 15000 })
    await waitTableIdle(page)
    log(`  登录回跳: ${page.url()}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const items = page.locator('.el-menu .el-menu-item')
    const n = await items.count()
    const labels = []
    for (let i = 0; i < n; i++) labels.push((await items.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,字典管理,请假申请,我的审批,待办任务,流程定义,工作台', '侧边菜单顺序（Round I 迁移后 9 项形态：31 改造 + 34 新增）')
    assertEq(((await page.locator('.el-menu-item.is-active').innerText()) || '').trim(), '请假申请', `${LEAVE_PATH} 下请假申请应高亮`)
    const bc = await breadcrumbTexts(page)
    log(`  面包屑: ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/请假申请', '面包屑应为 首页/请假申请')
    const ths = page.locator('.el-table__header-wrapper th')
    const tn = await ths.count()
    const headers = []
    for (let i = 0; i < tn; i++) headers.push(((await ths.nth(i).innerText()) || '').trim())
    log(`  表头(${tn}): ${JSON.stringify(headers)}`)
    assertEq(headers.join(','), '标题,请假类型,起止日期,状态,申请人,审批人,发起时间,操作', '请假申请表头应为 8 列精确序')
    assertEq(await page.getByRole('button', { name: '发起请假' }).count(), 1, '"发起请假"按钮应可见（admin 快照含 system:leave:add——311 迁 perms）')
    await shot(page, 'bp0-skeleton.png')
    // ---- 旧 /bpmn/leave 一次性切换实证（契约 §0.1 整体废弃）：直访落 NotFound 兜底 ----
    await page.goto(`${BASE}/bpmn/leave`, { waitUntil: 'domcontentloaded' })
    const sub = page.locator('.el-result__subtitle')
    await sub.waitFor({ state: 'visible', timeout: 10000 })
    const subText = (await sub.innerText()).trim()
    log(`  旧 /bpmn/leave 直访兜底文案: "${subText}"`)
    assert(subText.includes('页面不存在或无访问权限'), `旧 /bpmn/leave 应落 NotFound 兜底，实际 "${subText}"`)
    assertEq(new URL(page.url()).pathname, '/bpmn/leave', 'URL 应保持 /bpmn/leave（不重定向）')
  })

  // ================= BP1 发起弹窗形态：字典消费 + 审批人投影（仅启用） =================
  await step('BP1', '发起弹窗：类型下拉恰 3 项（system_leave_type 字典迁名消费）+ 审批人下拉含 admin（/system/leave/approvers 投影，仅启用账号）', async () => {
    await loadLeavePage()
    await page.getByRole('button', { name: '发起请假' }).click()
    const dlg = dialogByTitle('发起请假')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    assertEq(((await dlg.locator('.el-dialog__title').innerText()) || '').trim(), '发起请假', '弹窗标题应为 发起请假')
    // 类型下拉（字典 system_leave_type：1 事假 / 2 病假 / 3 年假——契约 §7 恰 3 项 sort 升序）
    const typeDd = await openSelect(dlg, '请选择请假类型')
    const types = await optionTexts(typeDd)
    log(`  类型下拉选项: ${JSON.stringify(types)}`)
    assertEq(types.join(','), '事假,病假,年假', `类型下拉应恰 3 项（字典 system_leave_type 消费），实际 ${JSON.stringify(types)}`)
    await pickOption(typeDd, '事假')
    await sleep(300)
    // 审批人下拉（GET /system/leave/approvers → system 本库投影，仅启用账号——迁移收紧语义）
    const apprDd = await openSelect(dlg, '请选择审批人')
    const approvers = await optionTexts(apprDd)
    log(`  审批人下拉选项: ${JSON.stringify(approvers)}`)
    assert(approvers.length >= 1, `审批人下拉应至少 1 项，实际 ${JSON.stringify(approvers)}`)
    assert(
      approvers.some((t) => t.includes('admin') && t.includes('管理员')),
      `审批人下拉应含 admin（管理员）——仅启用账号投影，实际 ${JSON.stringify(approvers)}`,
    )
    await pickOption(apprDd, 'admin')
    await shot(page, 'bp1-dialog.png')
    // 本场景仅验形态：取消关闭，不落库
    await dlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '发起请假')
  })

  // ================= BP2 发起（同意路径 A）→ 行出现（UI 层）+ 双接口双层断言 =================
  await step('BP2', '发起 A（事假）→ 请假申请行：审批中 tag + 类型译文 + 审批人昵称 + 撤销按钮（UI）；页内 fetch leave/page 原值译文并存 + approval/page 交叉锚定（detailPath=approvalId 形态）', async () => {
    const id = await createLeaveViaUi({ title: T_A, typeLabel: '事假', reason: REASON_A })
    const row = await leaveRow(T_A, { reload: false })
    assert(row, `请假申请应出现 "${T_A}" 行`)
    const cells = await rowCells(row)
    log(`  A 行: ${JSON.stringify(cells)}`)
    assertEq(cells[0], T_A, '标题列应为提交值')
    assertEq(cells[1], '事假', '请假类型列应为译文 事假（leaveTypeLabel）')
    assert(cells[2].includes(`${YEAR}-${pad(ts.getMonth() + 1)}-25`) && cells[2].includes(`${YEAR}-${pad(ts.getMonth() + 1)}-26`), `起止日期列应含所选两日，实际 "${cells[2]}"`)
    assertEq(cells[3], '审批中', '状态列应为 审批中（statusLabel）')
    assertEq(cells[4], '管理员', '申请人列应为昵称译文 管理员')
    assertEq(cells[5], '管理员', '审批人列应为昵称译文 管理员（approverName 非空）')
    const tag = await statusTag(row)
    assert(tag.cls.includes('el-tag--warning'), `审批中 tag 应为 warning 色，实际 class="${tag.cls}"`)
    assertEq(await row.getByRole('button', { name: '撤销' }).count(), 1, '审批中且本人行应有 撤销 按钮（v-if + v-perms 双闸放行）')
    // ---- API 层（E2 双层模式）：页内 fetch /system/leave/page 原值与译文字段并存 ----
    const fetched = await pageFetch('/api/system/leave/page?pageNum=1&pageSize=10')
    assertEq(fetched.code, 200, '页内 fetch 业务码应为 200')
    const frow = fetched.data.rows.find((r) => r.id === id)
    assert(frow, `fetch 应含 id=${id} 行`)
    log(`  fetch leave 行原值: status=${frow.status} leaveType=${frow.leaveType} applyUser=${frow.applyUser} approver=${frow.approver} approvalId=${frow.approvalId}`)
    log(`  fetch leave 行译文: statusLabel=${frow.statusLabel} leaveTypeLabel=${frow.leaveTypeLabel} applyUserName=${frow.applyUserName} approverName=${frow.approverName}`)
    assertEq(frow.status, '0', '原值 status 应为 "0"（审批中）')
    assertEq(frow.leaveType, '1', '原值 leaveType 应为 "1"（事假）')
    assertEq(frow.applyUser, 'admin', '原值 applyUser 应为 admin')
    assertEq(frow.approver, 'admin', '原值 approver 应为 admin')
    assert(typeof frow.approvalId === 'string' && frow.approvalId.length > 0, `审批中单应有审批单 id（approvalId 非空），实际 ${JSON.stringify(frow.approvalId)}`)
    assertEq(frow.statusLabel, '审批中', '译文 statusLabel 应为 审批中（与原值并存）')
    assertEq(frow.leaveTypeLabel, '事假', '译文 leaveTypeLabel 应为 事假（与原值并存）')
    assertEq(frow.applyUserName, '管理员', '译文 applyUserName 应为 管理员')
    assertEq(frow.approverName, '管理员', '译文 approverName 应为 管理员')
    assertEq(frow.reason, REASON_A, '事由应为提交值')
    // ---- 审批面交叉锚定（§3.1 + §1 detailPath 渲染值澄清/A2）：approvalId 互指 + detailPath=审批单 id 形态 ----
    const approvalId = await anchorApprovalId(T_A)
    assertEq(approvalId, frow.approvalId, '审批面行 id 应与请假面 approvalId 互指（双源关联锚点）')
    const afetch = await pageFetch('/api/bpmn/approval/page?pageNum=1&pageSize=50')
    const arow = afetch.data.rows.find((r) => r.id === approvalId)
    assert(arow, `审批面应含 id=${approvalId} 行`)
    log(`  fetch approval 行: businessType=${arow.businessType} businessTypeName=${arow.businessTypeName} status=${arow.status} detailPath=${arow.detailPath} processInstanceId=${arow.processInstanceId?.slice(0, 8)}…`)
    assertEq(arow.businessType, 'leave', '审批面 businessType 应为 leave')
    assertEq(arow.businessTypeName, '请假申请', '审批面 businessTypeName 应为 请假申请（配置表 join）')
    assertEq(arow.status, '0', '审批面 status 应为 "0"')
    assertEq(arow.detailPath, `/system/leave?approval=${approvalId}`, `detailPath 应为审批单 id 形态 /system/leave?approval={approvalId}（A2 修复语义——渲染值=审批单 id 非业务单 id），实际 "${arow.detailPath}"`)
    assert(arow.processInstanceId, '审批中单应保留在途流程实例 id（processInstanceId 非 null）')
    assertEq(arow.applyUserName, '管理员', '审批面 applyUserName 应为 管理员')
    await shot(page, 'bp2-row.png')
  })

  // ================= BP3 待办（去处理跳转落点）→ 办理（同意）→ 已通过 + 已办 1 行同意 =================
  await step('BP3', '待办出现 A（businessTypeName/detailPath=approvalId 断言）→「去处理」跳 /system/leave?approval={id} 弹窗自动开且内容已加载 → 办理同意 → 待办消行 + 请假申请变已通过（纠偏）+ 已办 tab 1 行同意', async () => {
    await loadTaskTodo()
    // 待办出现（admin 双角色：自己是自己的审批人）
    const todoRow = visibleRowByText(T_A)
    assert(await todoRow.isVisible(), `待办应含 "${T_A}" 行`)
    const todoCells = await rowCells(todoRow)
    log(`  待办 A 行: ${JSON.stringify(todoCells)}`)
    assertEq(todoCells[1], '请假申请', '待办业务类型列应为 请假申请（TaskVo businessTypeName——§0.2 通用化）')
    assertEq(todoCells[2], '管理员', '待办申请人列应为 管理员')
    // ---- API 层：todo 项 detailPath 形态（A2 语义：渲染值=审批单 id） ----
    const todoApi = await pageFetch('/api/bpmn/task/todo')
    assertEq(todoApi.code, 200, 'todo 接口业务码应为 200')
    const tItem = todoApi.data.find((t) => t.title === T_A)
    assert(tItem, `todo 应含 "${T_A}" 项`)
    log(`  todo 项: approvalId=${tItem.approvalId} businessType=${tItem.businessType} businessTypeName=${tItem.businessTypeName} detailPath=${tItem.detailPath} applyUserName=${tItem.applyUserName}`)
    assertEq(tItem.businessType, 'leave', 'todo 项 businessType 应为 leave')
    assertEq(tItem.businessTypeName, '请假申请', 'todo 项 businessTypeName 应为 请假申请')
    assert(typeof tItem.approvalId === 'string' && tItem.approvalId.length > 0, 'todo 项 approvalId 应非空')
    assertEq(tItem.approvalId, approvalIdOf[T_A], 'todo 项 approvalId 应与 BP2 锚定值一致')
    assertEq(tItem.detailPath, `/system/leave?approval=${tItem.approvalId}`, `todo 项 detailPath 应为 /system/leave?approval={approvalId}（A2 修复语义——勿断业务单 id），实际 "${tItem.detailPath}"`)
    assertEq(tItem.applyUserName, '管理员', 'todo 项 applyUserName 应为 管理员')
    // ---- 「去处理」跳转落点（契约 §1 待办跳转协议 + A3 修复：冷进入弹窗自动开且内容已加载） ----
    await todoRow.getByRole('button', { name: '去处理' }).click()
    await page.waitForURL(`**${LEAVE_PATH}?approval=*`, { timeout: 10000 })
    const landed = new URL(page.url())
    assertEq(landed.pathname, LEAVE_PATH, `「去处理」应落 ${LEAVE_PATH}，实际 ${landed.pathname}`)
    assertEq(landed.searchParams.get('approval'), tItem.approvalId, `落点 query.approval 应为审批单 id ${tItem.approvalId}，实际 "${landed.searchParams.get('approval')}"`)
    const dlg = dialogByTitle('请假单详情')
    await dlg.waitFor({ state: 'visible', timeout: 10000 })
    // 内容已加载断言（A3：descriptions 非空——不再容忍零请求空白）
    await dlg.locator('.el-descriptions').first().waitFor({ state: 'visible', timeout: 15000 })
    const bodyText = (await dlg.innerText()).replace(/\n/g, ' | ')
    log(`  落点详情主体: ${bodyText.slice(0, 200)}`)
    for (const expected of [T_A, '事假', '审批中', '管理员', `${YEAR}-${pad(ts.getMonth() + 1)}-25`]) {
      assert(bodyText.includes(expected), `落点详情应已加载含 "${expected}"（A3 冷进入修复实证）`)
    }
    await shot(page, 'bp3-gohandler-detail.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    await waitDialogGone(page, '请假单详情')
    // 关闭动画后落点 query 清除（防刷新重弹——前端行为，容忍短窗重试）
    for (let i = 0; i < 20; i++) {
      if (!new URL(page.url()).searchParams.get('approval')) break
      await sleep(150)
    }
    assert(!new URL(page.url()).searchParams.get('approval'), `详情关闭后落点 query.approval 应清除，实际 ${page.url()}`)
    // ---- 办理（同意）→ 待办消行 ----
    await loadTaskTodo()
    await completeTaskViaUi({ title: T_A, approveLabel: '同意', comment: COMMENT_A })
    assert(!(await visibleRowByText(T_A).isVisible()), `办理后待办应消行（无 "${T_A}"）`)
    // 已办 tab：1 行同意
    await switchDoneTab()
    const doneRow = visibleRowByText(T_A)
    assert(await doneRow.isVisible(), `已办应含 "${T_A}" 行`)
    const doneCells = await rowCells(doneRow)
    log(`  已办 A 行: ${JSON.stringify(doneCells)}`)
    assertEq(doneCells[1], '请假申请', '已办业务类型列应为 请假申请（TaskDoneVo businessTypeName）')
    assertEq(doneCells[4], '同意', '办理结果列应为 同意（approve="true"）')
    assertEq(doneCells[5], COMMENT_A, '审批意见列应为提交意见')
    assertEq(doneCells[6], '已通过', '当前单状态列应为 已通过（approvalStatusLabel）')
    const doneTag = doneRow.locator('td .el-tag').first()
    assert(((await doneTag.getAttribute('class')) || '').includes('el-tag--success'), '同意结果 tag 应为 success 色')
    // 请假申请变已通过（撤销按钮随终态消失）——读时纠偏后的实时状态
    const row = await leaveRow(T_A)
    assert(row, `请假申请应仍含 "${T_A}" 行`)
    const cells = await rowCells(row)
    assertEq(cells[3], '已通过', '状态列应变 已通过')
    const tag = await statusTag(row)
    assert(tag.cls.includes('el-tag--success'), `已通过 tag 应为 success 色，实际 class="${tag.cls}"`)
    assertEq(await row.getByRole('button', { name: '撤销' }).count(), 0, '终态行不应再有 撤销 按钮（v-if 关闸）')
    // API 层纠偏终态断言（语义不弱化：办理通过 → system page 原值即时变 "1"）
    const fetched = await pageFetch('/api/system/leave/page?pageNum=1&pageSize=10')
    const frow = fetched.data.rows.find((r) => r.title === T_A)
    assert(frow, 'fetch 应含 A 行')
    assertEq(frow.status, '1', `办理通过后请假面原值 status 应纠偏为 "1"（已通过），实际 "${frow.status}"`)
    assertEq(frow.statusLabel, '已通过', '纠偏后 statusLabel 应为 已通过')
    await shot(page, 'bp3-approved.png')
  })

  // ================= BP4 拒绝路径（R）→ 已拒绝 + 已办累积断言 =================
  await step('BP4', '发起 R（病假）→ 办理选拒绝 → 请假申请变已拒绝（纠偏 "2"）+ 已办 tab 拒绝 tag（与 A 行累积）', async () => {
    const id = await createLeaveViaUi({ title: T_R, typeLabel: '病假', reason: '' })
    assert(typeof id === 'string', '发起 R 应返回 id')
    await anchorApprovalId(T_R)
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
    // 请假申请变已拒绝
    const row = await leaveRow(T_R)
    const cells = await rowCells(row)
    assertEq(cells[1], '病假', '类型列应为译文 病假')
    assertEq(cells[3], '已拒绝', '状态列应变 已拒绝')
    const tag = await statusTag(row)
    assert(tag.cls.includes('el-tag--danger'), `已拒绝 tag 应为 danger 色，实际 class="${tag.cls}"`)
    // API 层纠偏终态（拒绝 → "2"）
    const fetched = await pageFetch('/api/system/leave/page?pageNum=1&pageSize=10')
    const frow = fetched.data.rows.find((r) => r.title === T_R)
    assert(frow, 'fetch 应含 R 行')
    assertEq(frow.status, '2', `办理拒绝后请假面原值 status 应纠偏为 "2"（已拒绝），实际 "${frow.status}"`)
    await shot(page, 'bp4-rejected.png')
  })

  // ================= BP5 撤销路径（C，system 面撤销）：二段确认 → 已撤销 + 待办无此单 =================
  await step('BP5', '发起 C（年假）→ 本人撤销（system 面撤销，ElMessageBox 二段确认含标题）→ 已撤销 + 待办无此单', async () => {
    const id = await createLeaveViaUi({ title: T_C, typeLabel: '年假', reason: '' })
    assert(typeof id === 'string', '发起 C 应返回 id')
    await anchorApprovalId(T_C)
    const row = await leaveRow(T_C, { reload: false })
    assert(row, `请假申请应含 "${T_C}" 行`)
    assertEq(await row.getByRole('button', { name: '撤销' }).count(), 1, '审批中且本人行应有 撤销 按钮')
    // 二段确认：文案含标题 + 不可恢复提示
    const putP = page.waitForResponse(
      (r) => apiPath(r.url(), `/api/system/leave/cancel/${id}`) && r.request().method() === 'PUT',
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
    // 撤销后 approvalId 仍在（双源关联锚点——契约 §5.2 撤销后仍在）
    const fetched = await pageFetch('/api/system/leave/page?pageNum=1&pageSize=10')
    const frow = fetched.data.rows.find((r) => r.title === T_C)
    assert(frow, 'fetch 应含 C 行')
    assertEq(frow.status, '3', '撤销后请假面原值 status 应为 "3"')
    assertEq(frow.approvalId, approvalIdOf[T_C], '撤销后 approvalId 应仍在（契约 §5.2）')
    // 待办无此单（撤销同事务删流程实例 → 任务随实例消失）
    await loadTaskTodo()
    assert(!(await visibleRowByText(T_C).isVisible()), `撤销后待办不应含 "${T_C}"`)
    assertEq(await visibleRows().filter({ hasText: T_C }).count(), 0, '待办全表无 C 行（双保险）')
    await shot(page, 'bp5-canceled.png')
  })

  // ================= BP6 流程定义页：leave_approval 行（零变化保留） =================
  await step('BP6', '流程定义页：leave_approval 行（key/名称=请假审批/版本/部署时间格式）+ 表头 5 列（末位操作列——零变化保留）', async () => {
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

  // ================= BP7 详情时间线：已通过单三步骤 + 业务 descriptions 双源 =================
  await step('BP7', 'A 单详情弹窗：业务 descriptions 四译文（标题/类型/状态/审批人昵称）+ 审批单号锚点 + el-steps 恰 3 步（发起申请/审批意见含文案/流程结束 result=已通过）', async () => {
    const dlg = await openLeaveDetail(T_A)
    await sleep(400)
    // 主体平铺（弹窗内新拉 detail，非行数据）
    const bodyText = (await dlg.innerText()).replace(/\n/g, ' | ')
    log(`  详情主体: ${bodyText.slice(0, 280)}`)
    for (const expected of [T_A, '事假', '已通过', '管理员', REASON_A, `${YEAR}-${pad(ts.getMonth() + 1)}-25 ~ ${YEAR}-${pad(ts.getMonth() + 1)}-26`]) {
      assert(bodyText.includes(expected), `详情主体应含 "${expected}"`)
    }
    assert(bodyText.includes(approvalIdOf[T_A]), `详情应含审批单号 ${approvalIdOf[T_A]}（双源关联锚点）`)
    // el-steps 时间线（steps 升序三源拼装：apply/approval/end——契约 §3.2）
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
    await closeLeaveDetail()
  })

  // ================= BP8 防御面：401 / 3020 / 3023 / 定义页写面反转（零变化保留） =================
  await step('BP8', '防御面：无 token 直调 401 → D 单后台审批后 stale 撤销 toast 3020（msg 逐字）→ nobody 直连发起 body 3023（msg 逐字）→ 定义页写面反转（新建流程按钮/操作列可见）', async () => {
    // ---- a. 无 token 直调网关 → HTTP 401（网关层真实状态码 + R JSON body——认证链路契约） ----
    const noToken = await page.request.get(`${GATEWAY}/system/leave/page`)
    log(`  无 token 直调 ${GATEWAY}/system/leave/page: HTTP ${noToken.status()}`)
    assertEq(noToken.status(), 401, '无 token 直调应 HTTP 401（网关验签拒绝）')
    const noTokenBody = await noToken.json().catch(() => null)
    assert(noTokenBody && noTokenBody.code, '401 body 应为 R JSON（含 code）')
    // ---- b. 3020：D 单发起（页面呈现审批中）→ 直连后台审批 → stale DOM 撤销 → toast 3020 ----
    const dId = await createLeaveViaUi({ title: T_D, typeLabel: '事假', reason: '' })
    await anchorApprovalId(T_D)
    const rowD = await leaveRow(T_D, { reload: false })
    assert(rowD, `请假申请应含 "${T_D}" 行`)
    assertEq(await rowD.getByRole('button', { name: '撤销' }).count(), 1, 'D 审批中行应有 撤销 按钮（stale DOM 锚点）')
    // 直连后台审批（不经 UI——构造「页面数据已过期」窗口）
    const todo = await directApi('GET', '/bpmn/task/todo')
    assertEq(todo.body.code, 200, '直连待办业务码应为 200')
    const dTask = todo.body.data.find((t) => t.title === T_D)
    assert(dTask, `直连待办应含 "${T_D}"（taskId 办理锚点）`)
    const done = await directApi('POST', '/bpmn/task/complete', { taskId: dTask.taskId, approve: 'true', comment: COMMENT_D })
    log(`  直连后台审批 D: HTTP ${done.httpStatus} code=${done.body.code}`)
    assertEq(done.body.code, 200, '后台审批应 body 200')
    // 页面未刷新：撤销按钮仍在 → 点击走 UI 撤销 → 后端 3020 → 拦截器 toast（HTTP 200 + body 错误码）
    const putP = page.waitForResponse(
      (r) => apiPath(r.url(), `/api/system/leave/cancel/${dId}`) && r.request().method() === 'PUT',
      { timeout: 15000 },
    )
    await rowD.getByRole('button', { name: '撤销' }).click()
    await confirmBox(T_D)
    const res = await putP
    const body = await res.json()
    log(`  stale 撤销 D: HTTP ${res.status()} code=${body.code} msg="${body.msg}"`)
    assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(body.code, 3020, `对已通过单撤销应 body 3020（Round I 迁移：4002→3020），实际 ${body.code}`)
    assertEq(body.msg, '请假单已终态，不可撤销', `3020 msg 应为契约逐字「请假单已终态，不可撤销」，实际 "${body.msg}"`)
    const toastText = await waitToast(page, '请假单已终态', 'error')
    log(`  3020 toast: "${toastText}"`)
    assert(toastText.includes('请假单已终态，不可撤销'), `toast 应为契约 msg 逐字，实际 "${toastText}"`)
    // 刷新后 D 呈终态（BP8 附产单的清扫锚点）
    const rowD2 = await leaveRow(T_D)
    const dCells = await rowCells(rowD2)
    assertEq(dCells[3], '已通过', '刷新后 D 应为 已通过（后台审批生效）')
    // ---- c. 3023：approver=nobody 直连发起（UI 下拉无法选出无效人——直连是唯一路径；永不落库） ----
    const bad = await directApi('POST', '/system/leave', {
      title: T_X,
      leaveType: '1',
      startDate: `${YEAR}-${pad(ts.getMonth() + 1)}-25`,
      endDate: `${YEAR}-${pad(ts.getMonth() + 1)}-26`,
      approver: 'nobody',
    })
    log(`  nobody 发起: HTTP ${bad.httpStatus} code=${bad.body.code} msg="${bad.body.msg}"`)
    assertEq(bad.httpStatus, 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(bad.body.code, 3023, `审批人无效应 body 3023（Round I 迁移：4004→3023），实际 ${bad.body.code}`)
    assertEq(bad.body.msg, '审批人无效: nobody', `3023 msg 应为契约 §6 逐字「审批人无效: nobody」，实际 "${bad.body.msg}"`)
    // ---- d. 定义页写面反转（Round H 迁移零变化保留：种子 331 bpmn:definition:deploy 绑 admin——
    //      本脚本 BP0 无 token 直访后 admin 全新登录，快照必含 deploy 权限；断言按可见形态写） ----
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/definition/page'), { timeout: 15000 })
    await page.goto(`${BASE}${DEF_PATH}`, { waitUntil: 'domcontentloaded' })
    await respP
    await waitTableIdle(page)
    await sleep(300)
    const createBtn = page.getByRole('button', { name: '新建流程' })
    assertEq(await createBtn.count(), 1, '定义页应可见「新建流程」按钮且唯一（admin 快照含 bpmn:definition:deploy）')
    assert(await createBtn.isVisible(), '「新建流程」按钮应可见（v-perm bpmn:definition:deploy 放行形态）')
    assertEq(await page.locator('.el-table__header-wrapper th', { hasText: '操作' }).count(), 1, '定义表头应有操作列（5 列，与 BP6 表头断言互证）')
    assert((await page.locator('.el-table__row button', { hasText: '查看图' }).count()) >= 1, '定义行内应有「查看图」按钮（perms bpmn:definition:list 可见）')
    assert((await page.locator('.el-table__row button', { hasText: '设计' }).count()) >= 1, '定义行内应有「设计」按钮（v-perm bpmn:definition:deploy）')
    await shot(page, 'bp8-defense.png')
  })

  // ================= BP9 详情流程图三态全覆盖（已通过/审批中/已撤销截断——B6 教训补全） =================
  await step('BP9', '图三态全覆盖：已通过 A 单主高亮恰 [endApprove]；审批中 E 单主高亮恰 [approval]；已撤销 C 单截断形态 active=[] 无终态高亮 + completed 截断至删除点 → E 后台办结（终态=已通过）', async () => {
    // ---- a. 已通过单（A）：active=[] + endActivityId 并入主高亮（契约 §3.4 三态矩阵终态列） ----
    const dlgA = await openLeaveDetail(T_A)
    const viewerA = dlgA.locator('.bpmn-viewer')
    const mA = await viewerMarkers(viewerA)
    log(`  A 单(已通过) markers: active=${JSON.stringify(mA.active)} completed=${JSON.stringify(mA.completed)}`)
    assertEq([...mA.active].sort().join(','), 'endApprove', `已通过单主高亮应恰 [endApprove]（契约 §3.4 终态列），实际 ${JSON.stringify(mA.active)}`)
    assert(mA.completed.includes('start') && mA.completed.includes('approval'), `已通过单 completed 应含 start+approval（已执行路径），实际 ${JSON.stringify(mA.completed)}`)
    // 流程图与时间线并存（F3：图区不挤占时间线——与 BP7 三步断言互证）
    assertEq(await dlgA.locator('.el-step').count(), 3, '详情弹窗时间线应仍恰 3 步（图与时间线并存）')
    await shot(page, 'bp9-approved-highlight.png')
    await closeLeaveDetail()
    // ---- b. 审批中单（E 造单）：activeActivityIds 主高亮当前节点 ----
    const eId = await createLeaveViaUi({ title: T_E, typeLabel: '年假', reason: '' })
    assert(typeof eId === 'string', '发起 E 应返回 id')
    await anchorApprovalId(T_E)
    const dlgE = await openLeaveDetail(T_E)
    const viewerE = dlgE.locator('.bpmn-viewer')
    const mE = await viewerMarkers(viewerE)
    log(`  E 单(审批中) markers: active=${JSON.stringify(mE.active)} completed=${JSON.stringify(mE.completed)}`)
    assertEq([...mE.active].sort().join(','), 'approval', `审批中单主高亮应恰 [approval]（契约 §3.4 在途列），实际 ${JSON.stringify(mE.active)}`)
    assert(mE.completed.includes('start'), `审批中单 completed 应含 start，实际 ${JSON.stringify(mE.completed)}`)
    assert(!mE.active.includes('endApprove') && !mE.active.includes('endReject'), '审批中单主高亮不应含任何 end 节点')
    await shot(page, 'bp9-active-highlight.png')
    await closeLeaveDetail()
    // ---- c. 已撤销单（C，BP5 撤销）：截断形态——end=null 无主高亮，completed 截断至删除点 ----
    const dlgC = await openLeaveDetail(T_C)
    const viewerC = dlgC.locator('.bpmn-viewer')
    const mC = await viewerMarkers(viewerC)
    log(`  C 单(已撤销) markers: active=${JSON.stringify(mC.active)} completed=${JSON.stringify(mC.completed)}`)
    assertEq(mC.active.length, 0, `已撤销单应无主高亮（active=[]，end=null 截断形态——契约 §3.4 撤销态行），实际 ${JSON.stringify(mC.active)}`)
    assert(mC.completed.includes('start') && mC.completed.includes('approval'), `已撤销单 completed 应截断至删除点含 start+approval（实例于 approval 任务处被删），实际 ${JSON.stringify(mC.completed)}`)
    assert(!mC.completed.includes('endApprove') && !mC.completed.includes('endReject'), `已撤销单 completed 不应含任何 end 节点，实际 ${JSON.stringify(mC.completed)}`)
    // 撤销单时间线：两步（发起申请/流程结束 result=已撤销——实例已删无审批意见步）
    const stepsC = dlgC.locator('.el-step')
    assertEq(await stepsC.count(), 2, `已撤销单时间线应恰 2 步，实际 ${await stepsC.count()}`)
    const tC = []
    for (let i = 0; i < 2; i++) tC.push(((await stepsC.nth(i).locator('.el-step__title').innerText()) || '').trim())
    assertEq(tC.join(','), '发起申请,流程结束', '已撤销单两步标题应为 发起申请/流程结束')
    const dEndC = (await stepsC.nth(1).locator('.el-step__description').innerText()).trim()
    assert(dEndC.includes('结果：已撤销'), `已撤销单流程结束步 result 应为 已撤销，实际 "${dEndC.replace(/\n/g, ' | ')}"`)
    await shot(page, 'bp9-canceled-truncated.png')
    await closeLeaveDetail()
    // ---- d. E 后台办结（BP8-b 同款直连手法）：归终态 已通过（保 CLEANUP 六单全终态） ----
    const todo = await directApi('GET', '/bpmn/task/todo')
    assertEq(todo.body.code, 200, '直连待办业务码应为 200')
    const eTask = todo.body.data.find((t) => t.title === T_E)
    assert(eTask, `直连待办应含 "${T_E}"（taskId 办理锚点）`)
    const done = await directApi('POST', '/bpmn/task/complete', { taskId: eTask.taskId, approve: 'true', comment: COMMENT_E })
    log(`  直连后台办结 E: HTTP ${done.httpStatus} code=${done.body.code}`)
    assertEq(done.body.code, 200, '后台办结 E 应 body 200')
  })

  // ================= BP10 定义页「查看图」弹窗：五节点四连线零高亮（零变化保留） =================
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
    // 元素清点（bpmn-js 为 start/网关/end 另建外置标签元素（id 后缀 _label）——语义断言走 data-element-id 封闭集）
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

  // ================= BP11 设计器：不改动原样重部署 version+1（三证——零变化保留） =================
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

  // ================= BP12 工作台两卡：我的待办 + 我的审批（前 5、状态 tag）+ 双跳转 =================
  await step('BP12', '/dashboard 两卡标题 + 待办卡行数 = min(5, todo 接口长度) + 我的审批卡行数 = min(5, page 行数) 含本轮 stamp 行与状态 tag + 两「查看全部」分别跳 /bpmn/task、/bpmn/approval', async () => {
    // 数据链在 onMounted（keep-alive 组件 Dashboard——首访即挂载即拉取；响应等待先注册）
    const todoP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/task/todo'), { timeout: 15000 })
    const approvalP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/approval/page'), { timeout: 15000 })
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' })
    const todoBody = await (await todoP).json()
    const approvalBody = await (await approvalP).json()
    assertEq(todoBody.code, 200, 'todo 接口业务码应为 200')
    assertEq(approvalBody.code, 200, 'approval 分页业务码应为 200')
    const todoLen = Array.isArray(todoBody.data) ? todoBody.data.length : 0
    const approvalLen = Array.isArray(approvalBody.data?.rows) ? approvalBody.data.rows.length : 0
    // 旧时代（bpmn_leave）待办残留经 §2.1 防御态三重跳过不进列表——todo 长度即平台时代真实待办
    const todoCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的待办' }) })
    const approvalCard = page.locator('.dash-card').filter({ has: page.locator('.el-card__header', { hasText: '我的审批' }) })
    assertEq(await todoCard.count(), 1, '「我的待办」卡应恰 1 张（admin 快照含 bpmn:task:list）')
    assertEq(await approvalCard.count(), 1, '「我的审批」卡应恰 1 张（admin 快照含 bpmn:approval:list——Round I 新卡）')
    await todoCard.waitFor({ state: 'visible', timeout: 8000 })
    await approvalCard.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500)
    // ---- 待办卡（title/businessTypeName 行结构；空态走 el-empty 分支） ----
    const todoRowN = await todoCard.locator('.dash-row').count()
    log(`  待办卡: 行数=${todoRowN}（todo 接口长度=${todoLen} → min(5, ${todoLen})=${Math.min(5, todoLen)}）`)
    assertEq(todoRowN, Math.min(5, todoLen), `待办卡行数应为 min(5, todo 接口长度)=${Math.min(5, todoLen)}，实际 ${todoRowN}`)
    if (todoLen === 0) {
      const empty = todoCard.locator('.el-empty')
      assert(await empty.isVisible(), '无待办时应呈现空态 暂无待办任务')
      assert(((await empty.getAttribute('aria-label')) || (await todoCard.innerText())).includes('暂无待办任务') || (await todoCard.innerText()).includes('暂无待办任务'), '待办空态文案应为 暂无待办任务')
    } else {
      const firstRow = todoCard.locator('.dash-row').first()
      const rowText = (await firstRow.innerText()).replace(/\n/g, ' | ')
      log(`  待办卡首行: "${rowText}"`)
      assert((await firstRow.locator('.el-tag').count()) >= 1, '待办行应含业务类型 tag（businessTypeName）')
      assert((await firstRow.locator('.dash-row-title').count()) === 1, '待办行应含标题元素（title）')
    }
    // ---- 我的审批卡（前 5 + 本轮 stamp 行 + 状态 tag——跨业务超集含全部状态） ----
    const approvalRowN = await approvalCard.locator('.dash-row').count()
    const stampRows = await approvalCard.locator('.dash-row', { hasText: `e2ebpmn${stamp}` }).count()
    log(`  审批卡: 行数=${approvalRowN} 其中本轮 stamp 行=${stampRows}（approval 接口行数=${approvalLen}）`)
    assertEq(approvalRowN, Math.min(5, approvalLen), `审批卡行数应为 min(5, 行数)=${Math.min(5, approvalLen)}，实际 ${approvalRowN}`)
    assert(approvalRowN > 0, `审批卡应有数据行（本轮 stamp 单置顶），实际 ${approvalRowN}`)
    assert(stampRows >= 1, `审批卡应含本轮 stamp（e2ebpmn${stamp}）行，实际 ${stampRows}`)
    const stampRow = approvalCard.locator('.dash-row', { hasText: `e2ebpmn${stamp}` }).first()
    const stampText = (await stampRow.innerText()).replace(/\n/g, ' | ')
    log(`  审批卡 stamp 首行: "${stampText}"`)
    assert(stampText.includes('请假申请'), '审批卡行应含业务类型名 请假申请（businessTypeName meta）')
    const stampTag = stampRow.locator('.el-tag').first()
    assertEq(await stampRow.locator('.el-tag').count(), 1, '审批卡行应恰 1 个状态 tag')
    const stampTagCls = (await stampTag.getAttribute('class')) || ''
    assert(/el-tag--(success|warning|danger|info)/.test(stampTagCls), `审批卡状态 tag 应为四态色之一，实际 "${stampTagCls}"`)
    await shot(page, 'bp12-dashboard.png')
    // 两「查看全部」跳转（keep-alive：二次 goto /dashboard 不重挂载，卡片仍在 DOM 可定位 footer）
    await todoCard.getByRole('button', { name: '查看全部' }).click()
    await page.waitForURL('**/bpmn/task', { timeout: 10000 })
    assertEq(new URL(page.url()).pathname, TASK_PATH, `待办卡「查看全部」应跳 ${TASK_PATH}，实际 ${page.url()}`)
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' })
    await todoCard.waitFor({ state: 'visible', timeout: 8000 })
    await approvalCard.getByRole('button', { name: '查看全部' }).click()
    await page.waitForURL(`**${APPROVAL_PATH}`, { timeout: 10000 })
    assertEq(new URL(page.url()).pathname, APPROVAL_PATH, `审批卡「查看全部」应跳 ${APPROVAL_PATH}，实际 ${page.url()}`)
  })

  // ================= BP13 平台我的审批页（新场景）：列表四态色 + 详情弹窗 + 行撤销禁用矩阵 + 跨页纠偏 =================
  await step('BP13', '平台我的审批页：列表（表头 8 列/businessTypeName/四态 tag 色）→ 详情弹窗（时间线+图）→ 终态行撤销禁用矩阵 → 平台撤销 P → /system/leave 纠偏已撤销（跨页真相源）', async () => {
    // ---- a. 造 P 单（审批中——四态之 warning + 平台面撤销标的） ----
    const pLeaveId = await createLeaveViaUi({ title: T_P, typeLabel: '事假', reason: '' })
    const pApprovalId = await anchorApprovalId(T_P)
    log(`  P 单: leaveId=${pLeaveId} approvalId=${pApprovalId}（审批中）`)
    // ---- b. 列表形态（契约 §3.1） ----
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/approval/page'), { timeout: 15000 })
    await page.goto(`${BASE}${APPROVAL_PATH}`, { waitUntil: 'domcontentloaded' })
    const resp = await respP
    const body = await resp.json()
    await waitTableIdle(page)
    await sleep(300)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '审批面分页业务码应为 200')
    assertEq(((await page.locator('.el-card__header').first().innerText()) || '').trim(), '我的审批', '卡片标题应为 我的审批')
    const ths = page.locator('.el-table__header-wrapper th')
    const tn = await ths.count()
    const headers = []
    for (let i = 0; i < tn; i++) headers.push(((await ths.nth(i).innerText()) || '').trim())
    log(`  审批面表头(${tn}): ${JSON.stringify(headers)}`)
    assertEq(headers.join(','), '标题,业务类型,状态,申请人,审批人,发起时间,最近变更,操作', '审批面表头应为 8 列精确序（§3.1 字段面）')
    // P 行（id 倒序置顶——本轮 stamp 单恒在第 1 页）
    const rowP = approvalRow(T_P)
    assert(await rowP.isVisible(), `审批面应含 "${T_P}" 行`)
    const cellsP = await rowCells(rowP)
    log(`  P 行: ${JSON.stringify(cellsP)}`)
    assertEq(cellsP[0], T_P, '标题列应为快照值')
    assertEq(cellsP[1], '请假申请', '业务类型列应为 请假申请（businessTypeName 配置表 join）')
    assertEq(cellsP[2], '审批中', '状态列应为 审批中（statusLabel）')
    assertEq(cellsP[3], '管理员', '申请人列应为昵称译文')
    assertEq(cellsP[4], '管理员', '审批人列应为昵称译文')
    // ---- c. 四态 tag 色（本轮 stamp 单恰覆盖 审批中/已通过/已拒绝/已撤销） ----
    const fourState = [
      { title: T_P, label: '审批中', cls: 'el-tag--warning' },
      { title: T_A, label: '已通过', cls: 'el-tag--success' },
      { title: T_R, label: '已拒绝', cls: 'el-tag--danger' },
      { title: T_C, label: '已撤销', cls: 'el-tag--info' },
    ]
    for (const st of fourState) {
      const row = approvalRow(st.title)
      assert(await row.isVisible(), `审批面应含 "${st.title}" 行（${st.label}）`)
      const tag = await statusTag(row)
      log(`  ${st.title} 状态 tag: "${tag.text}" class 含 ${st.cls}`)
      assertEq(tag.text, st.label, `${st.title} 状态列应为 ${st.label}`)
      assert(tag.cls.includes(st.cls), `${st.title} tag 应为 ${st.cls} 色，实际 class="${tag.cls}"`)
    }
    await shot(page, 'bp13-approval-list.png')
    // ---- d. 详情弹窗（平台单源：descriptions + 时间线 + 图——ApprovalDetailDialog） ----
    await rowP.getByRole('button', { name: '详情' }).click()
    const dlg = dialogByTitle('审批单详情')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('.el-descriptions').first().waitFor({ state: 'visible', timeout: 15000 })
    const dText = (await dlg.innerText()).replace(/\n/g, ' | ')
    log(`  审批详情主体: ${dText.slice(0, 260)}`)
    for (const expected of [T_P, '请假申请', '审批中', '管理员', `业务单据：请假申请（${pLeaveId}）`]) {
      assert(dText.includes(expected), `审批详情应含 "${expected}"`)
    }
    // 时间线（审批中单：apply 恒在、无 end 步）
    const steps = dlg.locator('.el-step')
    assertEq(await steps.count(), 1, `审批中单时间线应恰 1 步（发起申请），实际 ${await steps.count()}`)
    assertEq(((await steps.nth(0).locator('.el-step__title').innerText()) || '').trim(), '发起申请', '审批中单唯一步标题应为 发起申请')
    // 图（审批中主高亮 [approval]——平台图链与请假详情图链同源互证）
    const viewer = dlg.locator('.bpmn-viewer')
    await viewer.locator('.djs-element').first().waitFor({ state: 'visible', timeout: 20000 })
    await sleep(500)
    const m = await viewerMarkers(viewer)
    log(`  P 单 markers: active=${JSON.stringify(m.active)} completed=${JSON.stringify(m.completed)}`)
    assertEq([...m.active].sort().join(','), 'approval', `平台详情图审批中主高亮应恰 [approval]，实际 ${JSON.stringify(m.active)}`)
    await shot(page, 'bp13-approval-detail.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    await waitDialogGone(page, '审批单详情')
    // ---- e. 行撤销终态禁用矩阵（终态行 disabled 置灰；P 审批中可点） ----
    for (const title of [T_A, T_R, T_C]) {
      const row = approvalRow(title)
      const btn = row.getByRole('button', { name: '撤销' })
      assertEq(await btn.count(), 1, `${title} 行应有 撤销 按钮（v-perms 放行、终态禁用置灰形态）`)
      assert(await btn.isDisabled(), `${title}（终态）撤销按钮应禁用置灰（§3.3 终态禁用矩阵）`)
    }
    const btnP = approvalRow(T_P).getByRole('button', { name: '撤销' })
    assert(!(await btnP.isDisabled()), 'P（审批中）撤销按钮应可点')
    // ---- f. 平台面撤销 P（ElMessageBox 二段确认 → PUT /bpmn/approval/cancel/{approvalId}） ----
    const putP = page.waitForResponse(
      (r) => apiPath(r.url(), `/api/bpmn/approval/cancel/${pApprovalId}`) && r.request().method() === 'PUT',
      { timeout: 15000 },
    )
    await btnP.click()
    const boxText = await confirmBox(T_P)
    log(`  平台撤销确认框: "${boxText}"`)
    assert(boxText.includes('撤销审批单'), '确认框文案应为平台面口径（撤销审批单）')
    assert(boxText.includes('撤销后不可恢复'), '确认框应含不可恢复提示')
    const res = await putP
    const putBody = await res.json()
    assertEq(res.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(putBody.code, 200, `平台撤销应 body 200，实际 ${putBody.code} msg="${putBody.msg}"`)
    await waitToast(page, '撤销成功')
    await waitTableIdle(page)
    await sleep(300)
    // 行内状态变已撤销 + 撤销按钮转禁用
    const rowP2 = approvalRow(T_P)
    const cellsP2 = await rowCells(rowP2)
    assertEq(cellsP2[2], '已撤销', '平台撤销后状态列应变 已撤销')
    const tagP2 = await statusTag(rowP2)
    assert(tagP2.cls.includes('el-tag--info'), `已撤销 tag 应为 info 色，实际 class="${tagP2.cls}"`)
    assert(await rowP2.getByRole('button', { name: '撤销' }).isDisabled(), '撤销后行撤销按钮应转禁用')
    await shot(page, 'bp13-canceled.png')
    // ---- g. 跨页真相源语义：平台撤销 → /system/leave 读时纠偏为已撤销 ----
    const leaveRowP = await leaveRow(T_P)
    assert(leaveRowP, `请假申请应含 "${T_P}" 行`)
    const lCells = await rowCells(leaveRowP)
    log(`  P 请假行（跨页纠偏后）: ${JSON.stringify(lCells)}`)
    assertEq(lCells[3], '已撤销', '平台撤销后请假面状态应纠偏为 已撤销（读时纠偏——真相源语义）')
    const lTag = await statusTag(leaveRowP)
    assert(lTag.cls.includes('el-tag--info'), `请假面 P 行 tag 应为 info 色，实际 class="${lTag.cls}"`)
    assertEq(await leaveRowP.getByRole('button', { name: '撤销' }).count(), 0, '请假面 P 终态行不应有 撤销 按钮')
    // API 层互证：审批面 P 单 processInstanceId 已删 + 请假面 approvalId 仍在
    const afetch = await pageFetch('/api/bpmn/approval/page?pageNum=1&pageSize=10')
    const aP = afetch.data.rows.find((r) => r.id === pApprovalId)
    assert(aP, '审批面应仍含 P 行')
    assertEq(aP.status, '3', '平台面 P 原值 status 应为 "3"')
    assertEq(aP.processInstanceId, null, '撤销单流程实例应已删（processInstanceId=null——契约 §3.1）')
    const lfetch = await pageFetch('/api/system/leave/page?pageNum=1&pageSize=10')
    const lP = lfetch.data.rows.find((r) => r.title === T_P)
    assert(lP, '请假面应含 P 行')
    assertEq(lP.status, '3', '请假面 P 原值 status 应纠偏为 "3"')
    assertEq(lP.approvalId, pApprovalId, '撤销后 approvalId 应仍在（双源锚点不断链）')
  })

  // ================= CLEANUP 清扫纪律：本轮 stamp 单全部终态（双接口；不清零、允许留档） =================
  await step('CLEANUP', '清扫：本轮 stamp 六单（请假面+审批面双接口）全部终态（A/D/E=已通过 R=已拒绝 C/P=已撤销）+ 撤销单实例已删 + detailPath 全行 approvalId 形态 + 无审批中残留', async () => {
    // ---- 请假面 API 权威断言（页内 fetch，数据面以接口为准） ----
    const fetched = await pageFetch('/api/system/leave/page?pageNum=1&pageSize=50')
    assertEq(fetched.code, 200, '页内 fetch 业务码应为 200')
    const mine = fetched.data.rows.filter((r) => r.title.startsWith(`e2ebpmn${stamp}`))
    log(`  本轮 stamp 请假单: ${mine.length} 行`)
    assertEq(mine.length, 6, `本轮 stamp 应恰 6 单（A/R/C/P/D/E），实际 ${JSON.stringify(mine.map((r) => [r.title, r.status]))}`)
    const expected = { [T_A]: '1', [T_R]: '2', [T_C]: '3', [T_P]: '3', [T_D]: '1', [T_E]: '1' }
    const expectedLabel = { [T_A]: '已通过', [T_R]: '已拒绝', [T_C]: '已撤销', [T_P]: '已撤销', [T_D]: '已通过', [T_E]: '已通过' }
    for (const row of mine) {
      assertEq(row.status, expected[row.title], `${row.title} 终态应为 ${expected[row.title]}（${expectedLabel[row.title]}），实际 ${row.status}`)
      assertEq(row.statusLabel, expectedLabel[row.title], `${row.title} statusLabel 应为 ${expectedLabel[row.title]}，实际 "${row.statusLabel}"`)
      assertEq(row.approvalId, approvalIdOf[row.title], `${row.title} approvalId 应与锚定值一致（双源锚点）`)
      assert(row.status !== '0', `不应有审批中残留（${row.title}）`)
    }
    // ---- 审批面 API 断言（§3.1：撤销单实例删、其余保留历史；detailPath 全行 approvalId 形态——A2 复核） ----
    const afetch = await pageFetch('/api/bpmn/approval/page?pageNum=1&pageSize=50')
    assertEq(afetch.code, 200, '审批面 fetch 业务码应为 200')
    const amine = afetch.data.rows.filter((r) => r.title.startsWith(`e2ebpmn${stamp}`))
    assertEq(amine.length, 6, `审批面本轮 stamp 应恰 6 行，实际 ${JSON.stringify(amine.map((r) => [r.title, r.status]))}`)
    for (const row of amine) {
      assertEq(row.status, expected[row.title], `审批面 ${row.title} 终态应为 ${expected[row.title]}，实际 ${row.status}`)
      assertEq(row.statusLabel, expectedLabel[row.title], `审批面 ${row.title} statusLabel 应为 ${expectedLabel[row.title]}`)
      assertEq(row.detailPath, `/system/leave?approval=${row.id}`, `审批面 ${row.title} detailPath 应为 /system/leave?approval={审批单 id}（A2 语义），实际 "${row.detailPath}"`)
      assertEq(row.businessTypeName, '请假申请', `审批面 ${row.title} businessTypeName 应为 请假申请`)
      if (row.title === T_C || row.title === T_P) {
        assertEq(row.processInstanceId, null, `${row.title} 已撤销单流程实例应已删（processInstanceId=null）`)
      } else {
        assert(row.processInstanceId, `${row.title} 终态单应保留历史实例 id`)
      }
    }
    // 3023 探针 X 永不落库（stamp 前缀兜底核验——双面）
    assert(!mine.some((r) => r.title === T_X) && !amine.some((r) => r.title === T_X), '3023 探针 X 不应落库（双面核验）')
    // ---- UI 层复核（请假面六行各自状态 tag 终态呈现） ----
    await loadLeavePage()
    for (const title of [T_A, T_R, T_C, T_P, T_D, T_E]) {
      const row = await leaveRow(title, { reload: false })
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
  await step('BP-VERIFY', '证据核验：无 console error / pageerror / >=400 响应 / 网络失败（3020/3023 错误码在 body，HTTP 恒 200；401 探测走 page.request 不入统计）', async () => {
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
      `\n测试数据: ${T_A}（事假→已通过）/ ${T_R}（病假→已拒绝）/ ${T_C}（年假→已撤销，system 面撤销）/ ${T_P}（事假→已撤销，BP13 平台面撤销）/ ${T_D}（事假→已通过，3020 探针附产）/ ${T_E}（年假→已通过，BP9 审批中高亮探针）/ ${T_X}（3023 探针，永不落库）`,
      '清扫纪律（设计 D12 + 契约 §1）：bpmn 域无删除端点——业务表不清零，本轮 stamp 六单（请假面+审批面双接口）全部终态即验收通过；ACT_HI 允许残留；下一轮时间戳天然隔离；BP11 部署产生的 v2+ 定义允许残留（latestVersion 过滤下 UI 恒显最新版）',
      'B5 留档红线遵守：e2ebpmnb5114833 前缀单/角色与受限账号 e2ebpmnb5114833u 绝不办理/触碰（旧时代待办残留经 §2.1 防御态三重跳过不进 todo 列表）；admin 双角色（申请人+审批人）单人闭环；内置种子（admin/34 段菜单/字典新 2 键）零触碰',
    ],
  })
  await browser.close()
}
