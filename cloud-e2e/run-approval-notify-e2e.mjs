/**
 * 审批流任务通知接入站内信 e2e（契约 2026-11-08-approval-notify-mq-api §1.3/§1.4/§2；
 * 计划 2026-11-08-approval-notify-integration B6）——纯后端轨道：无浏览器，Node fetch 直连网关黑盒断言。
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（审批通知版）/ bpmn 9203（审批通知版）已启动；
 *   RocketMQ broker 与nameserver 在跑（通知链路经 BPMN_TASK_NOTIFY 异步消费）；种子已落库
 *   （模板 id=2 升级五变量 / id=3 approval_result——scripts/sql/2026-11-08-approval-notify-integration.sql）。
 * 运行：cd cloud-e2e && npm run e2e:approvalnotify
 * 前置探测（登录 + notify summary 面）失败即 SKIP（防环境漂移假红）。
 *
 * 场景（admin 发起自审请假单，单账号闭环两类通知）：
 * - AN1 前置健康探测（登录 + /system/notify/summary 200）——缺失 SKIP
 * - AN2 发起请假（approver=admin 自审）：POST /system/leave → 新单 id
 * - AN3 轮询任务到达通知（≤60s/3s）：content 含单据锚+「节点：审批」、linkUrl=/bpmn/task、
 *   type=2 提醒、level=1 重要、未读；summary 未读数 ≥1
 * - AN4 待办就位（GET /bpmn/task/todo 找锚任务）+ 单条已读（PUT /notify/read/{id} → readFlag=1）
 * - AN5 办理通过（POST /bpmn/task/complete approve=true）
 * - AN6 轮询审批结果通知（≤60s/3s）：content 含单据锚+「已通过」、linkUrl=/bpmn/approval、level=0
 * - AN7 收尾卫生：全部已读（PUT /notify/read-all）
 *
 * 测试数据：每轮新建一张请假单（标题带时间戳锚，历史轮次行不干扰断言）；不清理历史通知行
 * （通知为流水语义，read-all 后不影响后续轮次断言锚匹配）。
 */
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: GATEWAY, artDir: ART })
const { log, sleep, step, assert, assertEq, summary } = h

// ---------- API 助手（Node fetch UTF-8 直连网关，无 GBK 陷阱） ----------

async function api(token, method, pathWithQuery, body) {
  const res = await fetch(`${GATEWAY}${pathWithQuery}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (res.status !== 200) throw new Error(`HTTP ${res.status}（${method} ${pathWithQuery}）——契约恒 200`)
  return await res.json()
}

async function login(account, password) {
  const res = await fetch(`${GATEWAY}/sso/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, password }),
  })
  return await res.json()
}

/** 拉当前用户通知页（默认 50 行，通知流水新行在前——id 倒序） */
async function notifyPage(token) {
  const body = await api(token, 'GET', '/system/notify/page?pageNum=1&pageSize=50')
  if (body.code !== 200) throw new Error(`notify page code=${body.code} msg="${body.msg}"`)
  return body.data.rows || []
}

/** 轮询直到通知行出现（MQ 异步消费，链路=发送→消费→写扩散） */
async function pollNotifyRow(token, match, timeoutMs = 60000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    for (const row of await notifyPage(token)) {
      if (match(row)) return row
    }
    await sleep(3000)
  }
  return null
}

// ---------- AN1 前置健康探测（缺失即 SKIP，不硬跑） ----------

const pre = await (async () => {
  try {
    const lb = await login('admin', 'admin123')
    if (lb.code !== 200) return { ok: false, reason: `admin 登录 code=${lb.code} msg="${lb.msg}"` }
    const token = lb.data.accessToken
    const sum = await api(token, 'GET', '/system/notify/summary')
    if (sum.code !== 200) return { ok: false, reason: `notify summary code=${sum.code} msg="${sum.msg}"` }
    return { ok: true, token }
  } catch (e) {
    return { ok: false, reason: `探测异常: ${e.message}` }
  }
})()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway/sso/system/bpmn、RocketMQ 已启动且种子模板已落库')
  summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
const token = pre.token
log('前置健康探测通过（admin 登录 + notify summary 面可用）')

// 单据锚（标题时间戳——每轮唯一，历史轮次行不干扰）
const ts = Date.now()
const anchor = `e2e-审批通知-${ts}`

// ---------- 场景 ----------

let leaveId = null
let anchorTaskId = null
let arrivedRow = null
let resultRow = null

await step('AN2', '发起请假（approver=admin 自审，单账号闭环）', async () => {
  const d = new Date(Date.now() + 24 * 3600 * 1000)
  const d2 = new Date(Date.now() + 2 * 24 * 3600 * 1000)
  const iso = (x) => x.toISOString().slice(0, 10)
  const body = await api(token, 'POST', '/system/leave', {
    title: anchor,
    leaveType: '1',
    startDate: iso(d),
    endDate: iso(d2),
    reason: 'e2e 审批通知链路验证',
    approver: 'admin',
  })
  assertEq(body.code, 200, `发起请假 code（msg=${body.msg}）`)
  leaveId = body.data
  assert(!!leaveId, `应返回新请假单 id，实际 ${leaveId}`)
  log(`  请假单 id=${leaveId} 锚="${anchor}"`)
})

await step('AN3', '轮询任务到达通知（模板 approval_remind：五变量文案 + /bpmn/task + level=1）', async () => {
  arrivedRow = await pollNotifyRow(token, (r) =>
    String(r.content || '').includes(anchor) && String(r.content || '').includes('节点：'))
  assert(!!arrivedRow, `60s 内未收到任务到达通知（锚 "${anchor}"）——检查 bpmn 发送钩子与 system 消费者日志`)
  log(`  通知行 id=${arrivedRow.id} content="${arrivedRow.content}"`)
  assert(String(arrivedRow.content).includes('有待办任务待您审批'), `文案应为任务到达形态，实际 "${arrivedRow.content}"`)
  assertEq(arrivedRow.linkUrl, '/bpmn/task', '任务到达 linkUrl')
  assertEq(arrivedRow.type, 2, '消息类型应为 2 提醒')
  assertEq(arrivedRow.level, 1, '消息级别应为 1 重要（approval_remind 种子）')
  assertEq(arrivedRow.readFlag, 0, '新通知应未读')
  const sum = await api(token, 'GET', '/system/notify/summary')
  assert(sum.code === 200 && Number(sum.data.unreadCount) >= 1, `summary 未读数应 ≥1，实际 ${JSON.stringify(sum.data)}`)
})

await step('AN4', '待办就位（/bpmn/task/todo 含锚任务）+ 单条已读流转', async () => {
  const todoBody = await api(token, 'GET', '/bpmn/task/todo')
  assertEq(todoBody.code, 200, '待办列表 code')
  const task = (todoBody.data || []).find((t) => String(t.title || '').includes(anchor))
  assert(!!task, `待办应含锚任务（${anchor}），实际 ${JSON.stringify((todoBody.data || []).map((t) => t.title))}`)
  const readBody = await api(token, 'PUT', `/system/notify/read/${arrivedRow.id}`)
  assertEq(readBody.code, 200, '单条已读 code')
  const rows = await notifyPage(token)
  const after = rows.find((r) => r.id === arrivedRow.id)
  assertEq(after.readFlag, 1, '已读后 readFlag')
  // 锚任务 id 供 AN5 办理（step 闭包传值）
  anchorTaskId = task.taskId
})

await step('AN5', '办理通过（POST /bpmn/task/complete approve=true）', async () => {
  const body = await api(token, 'POST', '/bpmn/task/complete', {
    taskId: anchorTaskId,
    approve: 'true',
    comment: 'e2e-审批通知-同意',
  })
  assertEq(body.code, 200, `办理 code（msg=${body.msg}）`)
})

await step('AN6', '轮询审批结果通知（模板 approval_result：已通过文案 + /bpmn/approval + level=0）', async () => {
  resultRow = await pollNotifyRow(token, (r) =>
    String(r.content || '').includes(anchor) && String(r.content || '').includes('已通过'))
  assert(!!resultRow, `60s 内未收到审批结果通知（锚 "${anchor}"）——检查 completeTask 钩子与消费者日志`)
  log(`  通知行 id=${resultRow.id} content="${resultRow.content}"`)
  assert(String(resultRow.content).includes('您提交的'), `结果文案应为申请人视角形态，实际 "${resultRow.content}"`)
  assertEq(resultRow.linkUrl, '/bpmn/approval', '审批结果 linkUrl')
  assertEq(resultRow.type, 2, '消息类型应为 2 提醒')
  assertEq(resultRow.level, 0, '消息级别应为 0 普通（approval_result 种子）')
  assertEq(resultRow.readFlag, 0, '新结果通知应未读')
})

await step('AN7', '收尾卫生：全部已读', async () => {
  const body = await api(token, 'PUT', '/system/notify/read-all')
  assertEq(body.code, 200, '全部已读 code')
  log(`  read-all 标记 ${body.data} 行`)
})

summary({
  extras: [
    `\n测试数据：请假单 id=${leaveId} 锚="${anchor}"（admin 自审）；到达通知行 id=${arrivedRow?.id} / 结果通知行 id=${resultRow?.id}`,
    '断言口径：契约 §1.3 生产四触发点中 2 号（/inner 发起）与 3 号（completeTask 终态）两链路 + §1.4 消费两分支变量文案 + §2 模板 type/level 种子',
    '撤销链路（触发点 4）与 4015 补发（2b）不在本脚本面——单测覆盖（ApprovalWorkflowServiceTest/ApprovalCreateConsumerTest），B7 curl 冒烟由主控集成阶段执行',
  ],
})
