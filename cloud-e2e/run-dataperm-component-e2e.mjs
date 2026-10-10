/**
 * 数据权限组件化 e2e 第八脚本（契约 2026-10-10-dataperm-component-api §3 bpmn 审批三端点语义变更 +
 * §4 my-scope 复用 + §7.3 e2e 维护点核对；计划 2026-10-10-dataperm-component E1；harness 复用 lib/harness.mjs）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（registerRemote 已上线——bpmn_approval 在资源注册表）/
 *   bpmn 9203 与 MQ 全链已启动；前端 dev 5173 已启动。前置探测不过即 SKIP（D12 卡点纪律）。
 * 运行：cd cloud-e2e && npm run e2e:dataperm-component（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 *
 * 三账号矩阵（契约 §8 验收口径——同一 /bpmn/approval/page 三态）：
 * - admin：种子规则 bpmn_approval/ALL → 全部（提示条「全部」+ title 原值）
 * - M 主管（部门档）：角色规则 bpmn_approval/DEPT_AND_CHILD → 部门成员的单（提示条「指定范围（N 人）」）
 * - A/B 无规则员工：默认 SELF → 仅自己（提示条「仅自己」）
 *
 * 八段场景（计划 E1 用例序）：
 * - DC0 前置健康探测（resources 注册表含 bpmn_approval / approval 分页 / 部门树 / my-scope 四探针）
 * - DC1 建数（admin directApi）：部门两级 + 角色两枚（挂「请假申请+我的审批」菜单闭包——动态路由与
 *   发起/审批按钮级权限）+ 规则①仅行档（主管角色 DEPT_AND_CHILD 无列规则）+ 用户 M（子部/主管）/
 *   A（子部/员工）/ B（无部门/员工）
 * - DC2 A 发起请假（页内 fetch + approvalId 收敛轮询——MQ CREATE_RESULT 事件秒级回填）
 * - DC3 B 发起请假 + B 视角：提示条「仅自己」+ 行集仅自己 + A 的单不可见 + 深链 A 审批 id →
 *   4018（拦截器 toast「无权访问该审批单」+ 详情弹窗空态「暂无数据」零泄露）+ API 层双探针
 *   （detail/diagram 均 body 4018、HTTP 恒 200）
 * - DC4 M 视角（规则①仅行档）：提示条「指定范围（2 人）」无列括注 + 行集=部门成员恰 A 单 1 行 +
 *   title 原值（列规则未配）
 * - DC5 admin 配规则②加列（UI「配置」入口 upsert 全量覆盖）：资源筛选 bpmn_approval → 行「配置」→
 *   回显行档位 → 列·title=脱敏 → 保存
 * - DC6 M 视角（规则②行+列）：提示条括注（title:脱敏）+ 列表 title=*** + 详情弹窗标题=*** 零原文
 *   泄露 + 图区正常渲染（行级放行）——规则实时生效（求值无缓存，M 免重登）
 * - DC7 admin 留痕复核（决策留痕 tab：账号+资源双筛选）：M=list+detail 两类行 / B=list+deny 两类行，
 *   全行 resource=bpmn_approval
 * - DC8 清扫：A/B 各自撤销请假归终态（bpmn 域留档不物理删）→ API 删规则/用户x3/角色x2/部门x2 →
 *   残留断言（用户页/部门树/规则页零 e2edpc）+ admin 种子规则完好（bpmn_approval/ALL 在列）
 * - DC-VERIFY 环境卫生（console/pageerror/网络失败/>=400/资产 404——favicon 噪音滤除同款）
 *
 * 既有维护点核对结论（契约 §7.3，本轮零 diff 记档）：
 * - run-bpmn-e2e.mjs：BP12 审批卡行数=min(5, 接口长度) 动态 / BP13 行靶向断言 / CLEANUP 按 stamp
 *   前缀过滤——无 admin「仅自己」行集总数断言，admin 行集变全量零影响
 * - run-dataperm-e2e.mjs：规则分页断言均按 (resource, subjectType, subjectId) 预筛选——全表总数断言不存在，
 *   bpmn_approval 种子规则 +1 不影响
 *
 * 测试数据（e2edpc 前缀+时间戳——e2edp 家族延续、c=component 与 run-dataperm-e2e 的 e2edpa/b/m/s 区分；
 * admin 仅登录+建数+配置台，不改种子账号/角色/部门/规则：leave id=1、bpmn_approval admin id=13 零触碰）：
 * - 部门 E2EDPC部{stamp}/E2EDPC子部{stamp}；角色 e2edpcm{stamp}（主管·规则载体）/ e2edpcs{stamp}（员工·无规则）
 * - 用户 e2edpcm{stamp}（子部门+主管角色）/ e2edpca{stamp}（子部门+员工）/ e2edpcb{stamp}（员工·无部门）
 * - 请假单两枚（A/B 各自视角发起，清扫时撤销归终态留档——bpmn 域无删除端点先例）
 * - 清扫顺序：请假撤销（本人）→ 规则 → 用户 M/A/B → 角色两枚 → 子部门 → 父部门（3030 前置）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** 直连网关（preflight / directApi：page.request + Bearer，不入 page 网络统计——bpmn 先例） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, login, logoutViaUi, rowCells } = h

// ---------- 测试数据（e2edpc 前缀 + 时间戳） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const DEPT_P_NAME = `E2EDPC部${stamp}`
const DEPT_C_NAME = `E2EDPC子部${stamp}`
const ROLE_M_NAME = `E2EDPC主管${stamp}` // 主管：bpmn_approval 规则载体（DEPT_AND_CHILD → 再 upsert title 脱敏）
const ROLE_M_KEY = `e2edpcm${stamp}`
const ROLE_S_NAME = `E2EDPC员工${stamp}` // 员工：仅挂菜单，无任何数据权限规则（=SELF 默认档）
const ROLE_S_KEY = `e2edpcs${stamp}`
const USER_M = `e2edpcm${stamp}` // 子部门 + 主管角色 → 部门成员视角 + title 脱敏
const USER_A = `e2edpca${stamp}` // 子部门 + 员工角色 → 仅自己（M 的部门成员标的）
const USER_B = `e2edpcb${stamp}` // 员工角色无部门无规则 → 仅自己 + 4018 越权探针主体
const NICK_M = `E2EDPC主管甲${stamp}`
const NICK_A = `E2EDPC员工甲${stamp}`
const NICK_B = `E2EDPC员工乙${stamp}`
const PASSWORD = 'e2e123456'
const LEAVE_A_TITLE = `E2EDPC假A${stamp}`
const LEAVE_B_TITLE = `E2EDPC假B${stamp}`
/** 请假日期（未来月 20 日单日——API 造数无 picker，new Date 自处理跨年溢出） */
const future = new Date(ts.getFullYear(), ts.getMonth() + 2, 20)
const LEAVE_DAY = `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}`

const APPROVAL_PATH = '/bpmn/approval'
const DATAPERM_PATH = '/system/data-perm'

/** 建数产物 id（DC1 落、DC8 清扫用） */
const ids = { deptP: '', deptC: '', roleM: '', roleS: '', userM: '', userA: '', userB: '', ruleId: '', leaveA: '', leaveB: '' }
/** 审批单 id 锚点（DC2/DC3 收敛轮询落——行集/脱敏/4018 断言锚） */
const approvalIdOf = { A: '', B: '' }

// ---------- 前置健康探测（不过即 SKIP——D12 卡点纪律同款） ----------
async function preflight() {
  try {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    }).then((r) => r.json())
    if (lb.code !== 200 || !lb.data?.accessToken) return { ok: false, reason: `admin 登录失败 code=${lb.code} msg="${lb.msg}"` }
    const headers = { Authorization: `Bearer ${lb.data.accessToken}` }
    // 资源注册表须含 bpmn_approval（B2 registerRemote 上线标志——my-scope/规则台/求值全依赖）
    const res = await fetch(`${GATEWAY}/system/data-perm/resources`, { headers }).then((r) => r.json())
    if (res.code !== 200) return { ok: false, reason: `GET /system/data-perm/resources code=${res.code} msg="${res.msg}"` }
    const resources = (res.data || []).map((x) => x.resource)
    if (!resources.includes('bpmn_approval')) {
      return { ok: false, reason: `资源注册表不含 bpmn_approval（实际 ${JSON.stringify(resources)}）——后端 registerRemote 未上线？` }
    }
    // 审批分页三态语义版本（数据权限求值接入版）
    const ap = await fetch(`${GATEWAY}/bpmn/approval/page?pageNum=1&pageSize=1`, { headers }).then((r) => r.json())
    if (ap.code !== 200) return { ok: false, reason: `GET /bpmn/approval/page code=${ap.code} msg="${ap.msg}"（bpmn 求值版未就绪？）` }
    // my-scope 直探（§4 复用——3034=注册表缺项，200=就绪）
    const ms = await fetch(`${GATEWAY}/system/data-perm/my-scope?resource=bpmn_approval`, { headers }).then((r) => r.json())
    if (ms.code !== 200) return { ok: false, reason: `GET my-scope?resource=bpmn_approval code=${ms.code} msg="${ms.msg}"` }
    const tree = await fetch(`${GATEWAY}/system/dept/tree`, { headers }).then((r) => r.json())
    if (tree.code !== 200) return { ok: false, reason: `GET /system/dept/tree code=${tree.code} msg="${tree.msg}"` }
    return { ok: true, token: lb.data.accessToken }
  } catch (e) {
    return { ok: false, reason: `探测异常: ${e.message}` }
  }
}
const pre = await preflight()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202（registerRemote 版）/ bpmn 9203 / dev 5173 已启动')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
log('前置健康探测通过（资源注册表含 bpmn_approval + approval/page + my-scope + dept/tree 四探针）')

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

// ---------- 通用小助手（dataperm/bpmn 脚本同款模式本地化——跨脚本不 import 业务脚本，只共享 lib/harness） ----------
const apiPath = (url, pathname) => new URL(url).pathname === pathname
const dialogByTitle = (title) => page.locator('.el-dialog', { hasText: title }).last()

/** 直连网关（page localStorage token + page.request + Bearer——中文经 Node UTF-8 无 GBK 陷阱） */
async function directApi(method, path, data) {
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken)
  const res = await page.request.fetch(`${GATEWAY}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    data: data === undefined ? undefined : JSON.stringify(data),
  })
  return { httpStatus: res.status(), body: await res.json() }
}

/** 页内 fetch（走 /api 代理链路，带当前登录人 token；GET/POST/PUT——中文无 GBK 陷阱） */
async function pageFetch(method, pathWithQuery, data) {
  return page.evaluate(async ({ m, p, d }) => {
    const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
    const res = await fetch(p, {
      method: m,
      headers: { Authorization: `Bearer ${token}`, ...(d === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: d === undefined ? undefined : JSON.stringify(d),
    })
    return await res.json()
  }, { m: method, p: pathWithQuery, d: data })
}

/** EP select 打开（按 placeholder 定位，scope 限界防多 tab 同名 placeholder 撞车）并返回可见下拉层 */
async function openSelect(scope, placeholder) {
  await scope.locator('.el-select', { hasText: placeholder }).first().click()
  const dd = page.locator('.el-select-dropdown:visible')
  await dd.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  return dd
}

async function pickOption(dd, text) {
  await dd.locator('.el-select-dropdown__item', { hasText: text }).first().click()
  await sleep(300)
}

/** 当前可见 tab pane（el-tabs 非活动 pane display:none——dataperm 页三 tab 同 DOM） */
const visiblePane = () => page.locator('.el-tab-pane:visible')

/** 进入审批页并等首屏分页落定 */
async function loadApprovalPage() {
  const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/bpmn/approval/page'), { timeout: 15000 })
  await page.goto(`${BASE}${APPROVAL_PATH}`, { waitUntil: 'domcontentloaded' })
  await respP
  await waitTableIdle(page)
  await sleep(300)
}

/** 审批页提示条文本（契约 §4：`当前数据范围：${scopeLabel}${columnSummary ? （...） : ''}`） */
async function scopeAlertText() {
  await page.locator('.el-alert').waitFor({ state: 'visible', timeout: 10000 })
  return (await page.locator('.el-alert').innerText()).trim().replace(/\n/g, ' ')
}

/** approvalId 收敛轮询（MQ CREATE_RESULT 事件秒级回填——bpmn 脚本 waitForApprovalId 同款；页内 fetch 走本人视角） */
async function waitForApprovalId(leaveId, timeoutMs = 15000) {
  for (let i = 0; i < Math.ceil(timeoutMs / 500); i++) {
    const detail = await pageFetch('GET', `/api/system/leave/${leaveId}`)
    const approvalId = detail.data?.leave?.approvalId
    if (detail.code === 200 && approvalId) {
      log(`  approvalId 收敛: ${approvalId}（leaveId=${leaveId}）`)
      return approvalId
    }
    await sleep(500)
  }
  assert(false, `approvalId 应在 ${timeoutMs}ms 内收敛（MQ CREATE_RESULT 事件回填），leaveId=${leaveId}`)
}

/** 本人撤销请假归终态并轮询投影派生 status='3'（TERMINAL 事件回写——清扫纪律：留档不物理删） */
async function cancelOwnLeave(leaveId, title) {
  const canceled = await pageFetch('PUT', `/api/system/leave/cancel/${leaveId}`)
  assertEq(canceled.code, 200, `撤销 ${title} 应 200，实际 ${canceled.code} msg="${canceled.msg}"`)
  for (let i = 0; i < 30; i++) {
    const detail = await pageFetch('GET', `/api/system/leave/${leaveId}`)
    if (detail.data?.leave?.status === '3') {
      log(`  ${title} 已终态（已撤销）`)
      return
    }
    await sleep(500)
  }
  assert(false, `${title} 撤销后 15s 内应收敛为已撤销，leaveId=${leaveId}`)
}

/** 菜单子树闭包（含自身与全部后代）——角色分配菜单需完整 id 集（按钮级权限同闭包） */
function subtreeIds(nodes, name) {
  const out = []
  const walk = (ns) => {
    for (const n of ns || []) {
      if (n.name === name) {
        const collect = (x) => { out.push(x.id); (x.children || []).forEach(collect) }
        collect(n)
        return true
      }
      if (walk(n.children || [])) return true
    }
    return false
  }
  walk(nodes)
  return out
}

try {
  // ================= DC1 建数（admin）：部门两级 + 角色两枚 + 规则① + 用户 M/A/B =================
  await step('DC1', '建数（admin directApi）：部门两级 → 角色主管/员工（挂「请假申请+我的审批」菜单闭包）→ 规则①主管/DEPT_AND_CHILD 无列 → 用户 M（子部/主管）/A（子部/员工）/B（无部门/员工）', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)

    // 菜单闭包（两角色同款——M/A/B 都要能进 /bpmn/approval；A/B 还要 /system/leave 发起面）
    const tree = await directApi('GET', '/system/menu/tree')
    assertEq(tree.body.code, 200, '菜单树应 200')
    const menuIds = [...subtreeIds(tree.body.data, '请假申请'), ...subtreeIds(tree.body.data, '我的审批')]
    assert(menuIds.length >= 4, `菜单闭包应 ≥4 项（含按钮级），实际 ${menuIds.length}`)
    log(`  菜单闭包: ${menuIds.length} 项（请假申请+我的审批 子树）`)

    // 部门两级（父挂根 '1'）
    const deptP = await directApi('POST', '/system/dept', { parentId: '1', name: DEPT_P_NAME })
    assertEq(deptP.body.code, 200, `建父部门应 200，实际 ${deptP.body.code} msg="${deptP.body.msg}"`)
    ids.deptP = deptP.body.data
    const deptC = await directApi('POST', '/system/dept', { parentId: ids.deptP, name: DEPT_C_NAME })
    assertEq(deptC.body.code, 200, `建子部门应 200，实际 ${deptC.body.code} msg="${deptC.body.msg}"`)
    ids.deptC = deptC.body.data

    // 角色两枚 + 菜单
    const roleM = await directApi('POST', '/system/role', { name: ROLE_M_NAME, roleKey: ROLE_M_KEY, status: 0 })
    assertEq(roleM.body.code, 200, '建主管角色应 200')
    ids.roleM = roleM.body.data
    const roleS = await directApi('POST', '/system/role', { name: ROLE_S_NAME, roleKey: ROLE_S_KEY, status: 0 })
    assertEq(roleS.body.code, 200, '建员工角色应 200')
    ids.roleS = roleS.body.data
    for (const rid of [ids.roleM, ids.roleS]) {
      const a = await directApi('PUT', '/system/role/menu', { roleId: rid, menuIds })
      assertEq(a.body.code, 200, `角色菜单分配（${rid}）应 200，实际 ${a.body.code} msg="${a.body.msg}"`)
    }

    // 规则①：仅行档（主管角色 DEPT_AND_CHILD 无列规则——DC4 先验行集明文，DC5 再 upsert 加列）
    const rule = await directApi('POST', '/system/data-perm/rule', {
      resource: 'bpmn_approval', subjectType: 0, subjectId: ids.roleM, rowScope: 2, columns: [],
    })
    assertEq(rule.body.code, 200, `建规则①应 200，实际 ${rule.body.code} msg="${rule.body.msg}"`)
    // id 锚定（清扫用）：按 (resource, subjectType, subjectId) 查
    const ruleQ = await directApi('GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=bpmn_approval&subjectType=0&subjectId=${ids.roleM}`)
    ids.ruleId = ruleQ.body.data.rows[0]?.id || ''
    assert(ids.ruleId, '规则① id 应取到')

    // 用户 M/A/B（M/A 挂子部门——DEPT_AND_CHILD 行集标的；B 无部门）
    const mkUser = async (account, nickname, deptId) => {
      const u = await directApi('POST', '/system/user', { account, nickname, password: PASSWORD, status: 0, ...(deptId ? { deptId } : {}) })
      assertEq(u.body.code, 200, `建用户 ${account} 应 200，实际 ${u.body.code} msg="${u.body.msg}"`)
      return u.body.data
    }
    ids.userM = await mkUser(USER_M, NICK_M, ids.deptC)
    ids.userA = await mkUser(USER_A, NICK_A, ids.deptC)
    ids.userB = await mkUser(USER_B, NICK_B, null)
    for (const [uid, rid] of [[ids.userM, ids.roleM], [ids.userA, ids.roleS], [ids.userB, ids.roleS]]) {
      const a = await directApi('PUT', '/system/user/role', { userId: uid, roleIds: [rid] })
      assertEq(a.body.code, 200, `分配角色（${uid}）应 200，实际 ${a.body.code} msg="${a.body.msg}"`)
    }
    log(`  建数: 部门 P=${ids.deptP} C=${ids.deptC} / 角色 M=${ids.roleM} S=${ids.roleS} / 用户 M=${ids.userM} A=${ids.userA} B=${ids.userB} / 规则①=${ids.ruleId}（DEPT_AND_CHILD 无列）`)
    await shot(page, 'dc1-seeded.png')
  })

  // ================= DC2 A 发起请假（产生审批单——M 行集标的） =================
  await step('DC2', `A（${USER_A}）登录 → 页内 fetch 发起请假（${LEAVE_A_TITLE}）→ approvalId 收敛`, async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_A, PASSWORD)
    assert(r.ok, `A 登录应成功: ${r.msg || ''}`)
    const created = await pageFetch('POST', '/api/system/leave', {
      title: LEAVE_A_TITLE, leaveType: '1', startDate: LEAVE_DAY, endDate: LEAVE_DAY,
      reason: 'E2EDPC A 事由', approver: 'admin',
    })
    assertEq(created.code, 200, `A 发起请假应 200，实际 ${created.code} msg="${created.msg}"`)
    ids.leaveA = created.data
    approvalIdOf.A = await waitForApprovalId(ids.leaveA)
  })

  // ================= DC3 B 视角：默认档行集 + 深链 4018 + API 双探针 =================
  await step('DC3', `B（${USER_B}）登录 → 发起请假 → 提示条「仅自己」+ 行集仅自己（A 的单不可见）→ 深链 A 审批 id → 4018 toast + 弹窗空态零泄露 → API 层 detail/diagram 双探针（HTTP 200 + body 4018）`, async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_B, PASSWORD)
    assert(r.ok, `B 登录应成功: ${r.msg || ''}`)
    const created = await pageFetch('POST', '/api/system/leave', {
      title: LEAVE_B_TITLE, leaveType: '1', startDate: LEAVE_DAY, endDate: LEAVE_DAY,
      reason: 'E2EDPC B 事由', approver: 'admin',
    })
    assertEq(created.code, 200, `B 发起请假应 200，实际 ${created.code} msg="${created.msg}"`)
    ids.leaveB = created.data
    approvalIdOf.B = await waitForApprovalId(ids.leaveB)

    // —— my-scope 提示条（§4）：无规则默认档「仅自己」（无列括注）——
    await loadApprovalPage()
    const alertText = await scopeAlertText()
    log(`  B 提示条: "${alertText}"`)
    assert(alertText.includes('仅自己'), `B 提示条应为「仅自己」，实际 "${alertText}"`)
    assert(!alertText.includes('脱敏'), `B 无列规则，提示条不应有列括注，实际 "${alertText}"`)

    // —— 行集（API 层）：仅自己 1 行，A 的单不在行集 ——
    const fetched = await pageFetch('GET', '/api/bpmn/approval/page?pageNum=1&pageSize=50')
    assertEq(fetched.code, 200, 'B 审批分页应 200')
    const bRows = fetched.data.rows
    const own = bRows.find((row) => row.id === approvalIdOf.B)
    assert(own, `B 行集应含本人审批单（${LEAVE_B_TITLE}），实际 ${JSON.stringify(bRows.map((x) => [x.title, x.status]))}`)
    assertEq(own.title, LEAVE_B_TITLE, 'B 自身单 title 应为原值（B 无列规则）')
    assert(!bRows.some((row) => row.title === LEAVE_A_TITLE || row.id === approvalIdOf.A), `B 行集不应含 A 的单（行级 SELF 过滤），实际 ${JSON.stringify(bRows.map((x) => x.title))}`)
    assertEq(Number(fetched.data.total), bRows.length, `B 行集 total 应等于行数（仅自己），实际 total=${fetched.data.total} rows=${bRows.length}`)
    // UI 层互证：表内 B 单标题原值、无 A 单
    const uiRows = page.locator('.el-table__row')
    assertEq(await uiRows.count(), bRows.length, `B 视角 UI 行数应为 ${bRows.length}`)
    const uiText = (await page.locator('.el-table').innerText()).trim()
    assert(uiText.includes(LEAVE_B_TITLE) && !uiText.includes(LEAVE_A_TITLE), 'B 视角表格应含 B 单且无 A 单')
    await shot(page, 'dc3-b-self-only.png')

    // —— 深链 A 审批 id（§3.2 IDOR 收口）：4018 拦截器 toast + 弹窗空态零泄露 ——
    await page.goto(`${BASE}${APPROVAL_PATH}?approval=${approvalIdOf.A}`, { waitUntil: 'domcontentloaded' })
    const toastText = await waitToast(page, '无权访问该审批单', 'error')
    log(`  4018 toast: "${toastText}"`)
    const dlg = dialogByTitle('审批单详情')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    try {
      await sleep(800) // 详情拒绝落定（detail catch 留空态——图链不发起）
      const dText = (await dlg.innerText()).trim().replace(/\n/g, ' | ')
      log(`  4018 弹窗主体: "${dText}"`)
      assert(dText.includes('暂无数据'), `4018 弹窗应为空态「暂无数据」，实际 "${dText}"`)
      assert(!dText.includes(LEAVE_A_TITLE) && !dText.includes('E2EDPC'), `4018 弹窗不应泄露 A 单任何字段，实际 "${dText}"`)
      await shot(page, 'dc3-b-deeplink-4018.png')
    } finally {
      await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click({ timeout: 8000 }).catch(() => {})
      await waitDialogGone(page, '审批单详情').catch(() => {})
    }

    // —— API 层双探针（§3.2/§3.3）：detail/diagram 均 body 4018 + HTTP 恒 200 ——
    for (const suffix of ['', '/diagram']) {
      const respP = page.waitForResponse((r2) => apiPath(r2.url(), `/api/bpmn/approval/${approvalIdOf.A}${suffix}`), { timeout: 15000 })
      const body = await pageFetch('GET', `/api/bpmn/approval/${approvalIdOf.A}${suffix}`)
      const res = await respP
      assertEq(res.status(), 200, `契约：HTTP 恒 200（${suffix || '/detail'}），实际 ${res.status()}`)
      assertEq(body.code, 4018, `B 直连 A 审批 ${suffix || 'detail'} 应 body 4018，实际 ${body.code} msg="${body.msg}"`)
      log(`  4018 API 探针 ${suffix || '(detail)'}: HTTP 200 body.code=4018 msg="${body.msg}"（deny 留痕落库，DC7 复核）`)
    }
  })

  // ================= DC4 M 视角（规则①仅行档）：部门成员行集 + title 原值 =================
  await step('DC4', `M（${USER_M}）登录 → 提示条「指定范围（2 人）」无列括注 → 行集=部门成员恰 A 单 1 行（B 不可见）+ title 原值（列规则未配）`, async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_M, PASSWORD)
    assert(r.ok, `M 登录应成功: ${r.msg || ''}`)
    await loadApprovalPage()
    const alertText = await scopeAlertText()
    log(`  M 提示条（规则①）: "${alertText}"`)
    assert(alertText.includes('指定范围（2 人）'), `M 提示条应为「指定范围（2 人）」（部门档归类，成员=M+A），实际 "${alertText}"`)
    assert(!alertText.includes('脱敏'), `规则①无列规则，提示条不应有列括注，实际 "${alertText}"`)

    // 行集（API 层）：DEPT_AND_CHILD(deptC={M,A}) → 恰 A 单 1 行（M 未发起）+ title 原值
    const fetched = await pageFetch('GET', '/api/bpmn/approval/page?pageNum=1&pageSize=50')
    assertEq(fetched.code, 200, 'M 审批分页应 200')
    const mRows = fetched.data.rows
    assertEq(mRows.length, 1, `M 行集应恰 1 行（部门成员 A 的单；B 无部门不可见），实际 ${JSON.stringify(mRows.map((x) => x.title))}`)
    assertEq(mRows[0].id, approvalIdOf.A, `M 唯一行应为 A 的审批单，实际 id=${mRows[0].id}`)
    assertEq(mRows[0].title, LEAVE_A_TITLE, `规则①未配列——M 视角 title 应为原值，实际 "${mRows[0].title}"`)
    // UI 层互证
    assertEq(await page.locator('.el-table__row').count(), 1, 'M 视角 UI 应恰 1 行')
    const cells = await rowCells(page.locator('.el-table__row').first())
    assertEq(cells[0], LEAVE_A_TITLE, `M 视角 UI 标题列应为原值，实际 "${cells[0]}"`)
    await shot(page, 'dc4-m-dept-scope.png')
  })

  // ================= DC5 admin 配规则②加列（UI「配置」upsert 全量覆盖） =================
  await step('DC5', 'admin 重登 → 数据权限页资源筛选 bpmn_approval → 主管规则行「配置」→ 回显行档位（本部门及以下）→ 列·title=脱敏 → 保存（upsert 覆盖）', async () => {
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 重登应成功: ${r.msg || ''}`)
    const ruleP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/data-perm/rule/page'), { timeout: 15000 })
    await page.goto(`${BASE}${DATAPERM_PATH}`, { waitUntil: 'domcontentloaded' })
    await ruleP
    await waitTableIdle(page)
    // 资源筛选 bpmn_approval（规则 tab 默认激活；筛选 select 在可见 pane 内限界）
    const pane = visiblePane()
    const resDd = await openSelect(pane, '资源（全部）')
    await pickOption(resDd, 'bpmn_approval')
    // 响应等待须在触发点击之前注册（查询触发的重载即刻发请求——注册晚了会错过响应）
    const ruleQP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/data-perm/rule/page'), { timeout: 15000 })
    await pane.getByRole('button', { name: '查询' }).click()
    await ruleQP
    await waitTableIdle(page)
    await sleep(300)
    // 主管规则行 → 配置
    const ruleRow = pane.locator('.el-table__row', { hasText: ROLE_M_NAME }).first()
    await ruleRow.waitFor({ state: 'visible', timeout: 8000 })
    const ruleCells = await rowCells(ruleRow)
    log(`  规则①行（配置前）: ${JSON.stringify(ruleCells)}`)
    assert(ruleCells.join('|').includes('bpmn_approval'), '规则行资源列应为 bpmn_approval')
    await ruleRow.getByRole('button', { name: '配置' }).click()
    const dlg = dialogByTitle('配置规则')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(800) // getRuleConfig 权威回显（三项锁定 + 行档位回填）
    const scopeChecked = dlg.locator('.el-radio.is-checked', { hasText: '本部门及以下' })
    assert((await scopeChecked.count()) > 0, '配置弹窗应回显行档位「本部门及以下」（getRuleConfig 权威回显）')
    // 列·title → 脱敏（保存时 upsert 全量覆盖：rowScope 保持 + columns 追加）
    await dlg.locator('.el-form-item', { hasText: '列·title' }).locator('.el-select').click()
    const colDd = page.locator('.el-select-dropdown:visible')
    await colDd.waitFor({ state: 'visible', timeout: 8000 })
    await pickOption(colDd, '脱敏')
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '配置规则')
    await waitTableIdle(page)
    await sleep(300)
    // 行断言：列规则摘要 title:脱敏 + 行档位保持
    const ruleRow2 = pane.locator('.el-table__row', { hasText: ROLE_M_NAME }).first()
    const ruleCells2 = await rowCells(ruleRow2)
    log(`  规则②行（配置后）: ${JSON.stringify(ruleCells2)}`)
    assert(ruleCells2.join('|').includes('title:脱敏'), `规则行列摘要应含 title:脱敏，实际 ${JSON.stringify(ruleCells2)}`)
    assert((await ruleRow2.locator('.el-tag', { hasText: '本部门及以下' }).count()) > 0, 'upsert 后行档位应保持 本部门及以下')
    await shot(page, 'dc5-rule-upsert.png')
  })

  // ================= DC6 M 视角（规则②行+列）：脱敏三面 + diagram 行级放行 =================
  await step('DC6', 'M 重登（求值实时无缓存）→ 提示条括注（title:脱敏）→ 列表 title=*** → 详情弹窗标题=*** 零原文泄露 → diagram 端点行级放行（§3.3 code 200 出图数据）', async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_M, PASSWORD)
    assert(r.ok, `M 重登应成功: ${r.msg || ''}`)
    await loadApprovalPage()
    const alertText = await scopeAlertText()
    log(`  M 提示条（规则②）: "${alertText}"`)
    assert(alertText.includes('指定范围（2 人）'), `M 提示条范围应保持「指定范围（2 人）」，实际 "${alertText}"`)
    assert(alertText.includes('title:脱敏'), `M 提示条应括注列结论（title:脱敏），实际 "${alertText}"`)

    // 列表（§3.1）：title=***（UI + API 双层）
    assertEq(await page.locator('.el-table__row').count(), 1, 'M 视角 UI 应恰 1 行')
    const cells = await rowCells(page.locator('.el-table__row').first())
    assertEq(cells[0], '***', `M 视角 UI 标题列应为 ***，实际 "${cells[0]}"`)
    await shot(page, 'dc6-m-masked-list.png')
    const fetched = await pageFetch('GET', '/api/bpmn/approval/page?pageNum=1&pageSize=50')
    const aRow = fetched.data.rows.find((row) => row.id === approvalIdOf.A)
    assert(aRow, 'M 行集应仍含 A 的单（行集不受列规则影响）')
    assertEq(aRow.title, '***', `M 视角 API title 应为 ***，实际 "${aRow.title}"`)

    // 详情（§3.2）：标题=*** 零原文泄露。图区渲染不做断言——BpmnViewer 需
    // /bpmn/definition/{id}/xml（bpmn:definition:list 权限），M 未挂流程定义菜单，
    // 图区显示「流程图加载失败」属既有权限模型行为（非数据权限缺陷；admin 视角
    // 由 e2e:bpmn BP13 承载）；§3.3 行级放行由下方 diagram 数据端点断言承载。
    // finally 兜底关闭：断言中途抛出不遗留弹窗 overlay（拦截后续步骤 hover/点击）。
    await page.locator('.el-table__row').first().getByRole('button', { name: '详情' }).click()
    const dlg = dialogByTitle('审批单详情')
    try {
      await dlg.locator('.el-descriptions').first().waitFor({ state: 'visible', timeout: 15000 })
      await sleep(500)
      const dText = (await dlg.innerText()).trim().replace(/\n/g, ' | ')
      log(`  M 详情主体（节选）: "${dText.slice(0, 200)}"`)
      assert(dText.includes('***'), `M 详情标题应脱敏 ***，实际 "${dText.slice(0, 160)}"`)
      assert(!dText.includes(LEAVE_A_TITLE), `M 详情不应泄露标题原文，实际含：${dText.slice(0, 160)}`)
      assert(dText.includes('业务单据：请假申请'), 'M 详情应含业务单据回指（businessTypeName）')
      await shot(page, 'dc6-m-masked-detail.png')
    } finally {
      await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click({ timeout: 8000 }).catch(() => {})
      await waitDialogGone(page, '审批单详情').catch(() => {})
    }

    // diagram 数据端点（§3.3）：M 行级放行 → code 200 且出图数据（与 DC3 B 的 4018 成对照）
    const diagram = await pageFetch('GET', `/api/bpmn/approval/${approvalIdOf.A}/diagram`)
    assertEq(diagram.code, 200, `M 直连 A 审批 diagram 应 code 200（行级放行），实际 ${diagram.code} msg="${diagram.msg}"`)
    assert(Array.isArray(diagram.data?.activeActivityIds), `diagram 应出图数据（activeActivityIds 数组），实际 ${JSON.stringify(diagram.data)?.slice(0, 120)}`)
    log(`  diagram 放行: activeActivityIds=${JSON.stringify(diagram.data.activeActivityIds)}`)
  })

  // ================= DC7 admin 留痕复核：resource=bpmn_approval 的 list/detail/deny 三类行 =================
  await step('DC7', 'admin 留痕复核（决策留痕 tab：账号+资源双筛选 bpmn_approval）：M=list+detail 两类行 / B=list+deny 两类行，全行 resource=bpmn_approval', async () => {
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 重登应成功: ${r.msg || ''}`)
    await page.goto(`${BASE}${DATAPERM_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-tabs__item', { hasText: '决策留痕' }).click()
    const pane = visiblePane()
    await pane.locator('.el-table__row, .el-pagination').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
    // 资源筛选 bpmn_approval 一次落定（后续仅换账号重查——logFilter 页面生命周期内持久）
    const resDd = await openSelect(pane, '资源（全部）')
    await pickOption(resDd, 'bpmn_approval')
    await sleep(300)

    /** 按账号查询（资源筛选一次落定——el-select 选中值后 placeholder 不再渲染，
     *  不能按「资源（全部）」重复定位；logFilter.resource 在页面生命周期内持久） */
    const queryLogs = async (account) => {
      await pane.locator('input[placeholder="账号（精确）"]').fill(account)
      // 响应等待先注册（查询即刻发请求——晚注册会错过响应，行计数竞态）
      const logP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/data-perm/log/page'), { timeout: 15000 })
      await pane.getByRole('button', { name: '查询' }).click()
      await logP
      await waitTableIdle(page)
      await sleep(300)
      const rows = pane.locator('.el-table__row')
      const n = await rows.count()
      assert(n >= 1, `留痕（${account}/bpmn_approval）应至少 1 条，实际 ${n}`)
      const ops = new Set()
      for (let i = 0; i < n; i++) {
        const cells = await rowCells(rows.nth(i))
        assertEq(cells[2], 'bpmn_approval', `留痕行资源列应为 bpmn_approval，实际 "${cells[2]}"（行 ${JSON.stringify(cells)}）`)
        ops.add(cells[3])
      }
      return { n, ops }
    }

    // M：list（DC4/DC6 两页载）+ detail（DC6 详情弹窗——detail/diagram 双留痕均 detail 类）
    const m = await queryLogs(USER_M)
    log(`  M 留痕 ${m.n} 条，操作类型: ${JSON.stringify([...m.ops])}`)
    assert(m.ops.has('列表'), `M 留痕应含 list（列表）行，实际 ${JSON.stringify([...m.ops])}`)
    assert(m.ops.has('详情'), `M 留痕应含 detail（详情）行，实际 ${JSON.stringify([...m.ops])}`)
    await shot(page, 'dc7-logs-m.png')

    // B：list（DC3 页载）+ deny（深链 + API 双探针——4018 补痕）
    const b = await queryLogs(USER_B)
    log(`  B 留痕 ${b.n} 条，操作类型: ${JSON.stringify([...b.ops])}`)
    assert(b.ops.has('列表'), `B 留痕应含 list（列表）行，实际 ${JSON.stringify([...b.ops])}`)
    assert(b.ops.has('拒绝'), `B 留痕应含 deny（拒绝）行——4018 补痕，实际 ${JSON.stringify([...b.ops])}`)
    await shot(page, 'dc7-logs-b.png')
  })

  // ================= DC8 清扫：撤销两单归终态 + 删规则/用户/角色/部门 + 残留断言 =================
  await step('DC8', '清扫：A/B 各自撤销请假归终态（留档）→ admin 删规则 → 用户 M/A/B → 角色两枚 → 子部门 → 父部门 → 残留断言（用户页/部门树/规则页零 e2edpc + admin 种子规则完好）', async () => {
    // —— 请假撤销（本人视角——leave 资源 IDOR 3026 收口，须申请人本人会话）——
    await logoutViaUi(page)
    let r = await login(page, USER_A, PASSWORD)
    assert(r.ok, `A 重登应成功: ${r.msg || ''}`)
    await cancelOwnLeave(ids.leaveA, LEAVE_A_TITLE)
    await logoutViaUi(page)
    r = await login(page, USER_B, PASSWORD)
    assert(r.ok, `B 重登应成功: ${r.msg || ''}`)
    await cancelOwnLeave(ids.leaveB, LEAVE_B_TITLE)

    // —— admin 清扫（顺序：规则 → 用户 → 角色 → 子部门 → 父部门——3030 前置）——
    await logoutViaUi(page)
    r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 重登应成功: ${r.msg || ''}`)
    // 规则 id 兜底重锚（DC5 upsert 后 id 不变，但按 (resource,subject) 重查最稳）
    const ruleQ = await directApi('GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=bpmn_approval&subjectType=0&subjectId=${ids.roleM}`)
    if (ruleQ.body.data.rows[0]) ids.ruleId = ruleQ.body.data.rows[0].id
    if (ids.ruleId) {
      const del = await directApi('DELETE', `/system/data-perm/rule/${ids.ruleId}`)
      assertEq(del.body.code, 200, `删规则应 200，实际 ${del.body.code} msg="${del.body.msg}"`)
    }
    for (const [label, resource, id] of [
      ['用户M', 'user', ids.userM], ['用户A', 'user', ids.userA], ['用户B', 'user', ids.userB],
      ['角色主管', 'role', ids.roleM], ['角色员工', 'role', ids.roleS],
      ['子部门', 'dept', ids.deptC], ['父部门', 'dept', ids.deptP],
    ]) {
      const del = await directApi('DELETE', `/system/${resource}/${id}`)
      assertEq(del.body.code, 200, `清扫 ${label}(${id}) 应 200，实际 ${del.body.code} msg="${del.body.msg}"`)
    }
    log('  已清扫: 规则 / 用户x3 / 角色x2 / 部门x2（请假单两枚撤销终态留档——bpmn 先例）')

    // —— 残留断言：用户页 / 部门树 / 规则页 均零 e2edpc ——
    const users = await directApi('GET', '/system/user/page?pageNum=1&pageSize=200')
    const left = users.body.data.rows.filter((u) => String(u.account).startsWith('e2edpc')).map((u) => u.account)
    assertEq(left.length, 0, `用户页应零 e2edpc 残留，实际 ${JSON.stringify(left)}`)
    const tree = await directApi('GET', '/system/dept/tree')
    const names = []
    const walkNames = (nodes) => { for (const nd of nodes || []) { names.push(nd.name); walkNames(nd.children) } }
    walkNames(tree.body.data)
    assert(!names.some((nm) => nm.startsWith('E2EDPC')), `部门树应零 E2EDPC 残留，实际 ${JSON.stringify(names)}`)
    const ruleRes = await directApi('GET', `/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=bpmn_approval&subjectType=0&subjectId=${ids.roleM}`)
    assertEq(Number(ruleRes.body.data.total), 0, `规则分页（主管角色）应为空，实际 total=${ruleRes.body.data.total}`)
    // admin 种子规则完好（id=13 bpmn_approval/ALL 零触碰）
    const seed = await directApi('GET', '/system/data-perm/rule/page?pageNum=1&pageSize=200&resource=bpmn_approval')
    const adminSeed = seed.body.data.rows.find((row) => row.subjectName === '管理员')
    assert(adminSeed, `bpmn_approval admin 种子规则应在列，实际 ${JSON.stringify(seed.body.data.rows.map((row) => [row.id, row.subjectName, row.rowScope]))}`)
    assertEq(Number(adminSeed.rowScope), 4, `admin 种子规则行档位应为 4（ALL），实际 ${adminSeed.rowScope}`)
    log(`  残留断言通过：零 e2edpc 残留；admin 种子规则（id=${adminSeed.id}）完好`)
  })

  // ================= DC-VERIFY 环境卫生 =================
  await step('DC-VERIFY', 'console error / pageerror / >=400 / 网络失败 / 资产 404 环境卫生复核', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（favicon-404 文本由 asset404 白名单在响应层甄别）
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（4018 错误码在 body、HTTP 恒 200），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
    const noise = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noise.length, 0, `不应有非 favicon 资产 404，实际 ${JSON.stringify(noise)}`)
  })
} finally {
  await browser.close()
}

h.summary({
  extras: [
    `\n测试数据（stamp=${stamp}）：部门 ${DEPT_P_NAME}/${DEPT_C_NAME} 角色 ${ROLE_M_KEY}/${ROLE_S_KEY} 用户 ${USER_M}/${USER_A}/${USER_B}`,
    `清扫结论：规则/用户/角色/部门全清；请假单 ${LEAVE_A_TITLE}/${LEAVE_B_TITLE} 撤销终态留档（bpmn 先例——审批链路数据不物理删）`,
    'admin 种子零触碰（仅登录+建数+配置台操作；leave 规则 id=1、bpmn_approval admin 规则 id=13 未动）',
    '既有维护点核对（契约 §7.3）：run-bpmn-e2e 无 admin「仅自己」行集总数断言（BP12 min 动态/BP13 行靶向/CLEANUP stamp 前缀）+ run-dataperm-e2e 规则分页断言均按主体预筛选——两脚本零 diff',
  ],
})
