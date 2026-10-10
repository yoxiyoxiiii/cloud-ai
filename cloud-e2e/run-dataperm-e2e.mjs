/**
 * 数据权限 e2e（契约 2026-10-10-data-permission-api / 设计 §8/§10 三账号矩阵；harness 复用 lib/harness.mjs）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（含 /system/dept/** /system/data-perm/** 端点版本）
 * + bpmn 9203 与 MQ 全链（发起请假走 MQ 事务消息）已启动；前端 dev 5173 已启动。前置探测不过即 SKIP（D12 卡点纪律）。
 * 运行：cd cloud-e2e && npm run e2e:dataperm（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 *
 * 五段场景（任务 E2 口径）：
 * ① DP0-DP1 admin 登录 + 侧边 11 项（E1 双锚：+部门管理/数据权限）+ 内置根部门删除双防线
 *    （UI 禁用面 + 直连 3029）+ 部门两级 UI 闭环 + 测试角色两枚（均挂「请假申请」菜单——
 *    动态路由按菜单生成，无菜单权限的账号进不了 /system/leave）+ 用户 A（子部门/主管角色）/ 用户 B（无部门/员工角色）
 * ② DP2 数据权限页配规则（入口走角色页「数据权限」按钮 = F5 预筛选联动）：主管角色 DEPT_AND_CHILD
 *    + reason=脱敏 → 规则行断言 → 模拟解释 tab：账号 A + leave → narratives/命中规则/列决策三块
 * ③ DP3 用户 A 视角：leave 页 my-scope 提示条含「指定范围」（部门档 scopeLabel 归类——主控裁决）→ UI 发起请假 → 详情弹窗事由=***
 *    （列规则脱敏，A 自身视角）→ 撤销归终态（清扫纪律）
 * ④ DP4 用户 B 视角：提示条「仅自己」→ API 造单 → 列表仅自己 1 行 → 直连 A 单详情 body 3026（IDOR 收口）
 *    + deny 留痕落库（DP5 复核）→ 撤销归终态
 * ⑤ DP5-DP6 admin 复核与清扫：leave 全量视野（种子 ALL——含 A/B 两单）+ 决策留痕 tab（A=list 记录 /
 *    B=deny 记录）→ UI 删规则（确认框含主体名）→ API 删用户/角色/部门 → 残留断言（部门树/规则/用户页零 e2e 残留）
 *
 * 测试数据（e2e 前缀+时间戳；admin 仅只读+建数，不改种子账号/角色/部门）：
 * - 部门 E2EDP部{stamp} / E2EDP子部{stamp}；角色 e2edpm{stamp}（主管·配规则）/ e2edps{stamp}（员工·无规则）
 * - 用户 e2edpa{stamp}（子部门+主管角色）/ e2edpb{stamp}（员工角色）；请假单两枚（各自主视角发起，终态留档同 bpmn 先例）
 * - 清扫顺序：规则（UI）→ 用户 A/B → 角料两枚 → 子部门 → 父部门（3030 前置：先删挂靠用户再删部门）
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
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, login, logoutViaUi, findRow, findRowByCell, rowCells } = h

// ---------- 测试数据（e2e 前缀+时间戳） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const YEAR = ts.getFullYear()
const DEPT_P_NAME = `E2EDP部${stamp}`
const DEPT_C_NAME = `E2EDP子部${stamp}`
const ROLE_M_NAME = `E2E主管${stamp}` // 主管：数据权限规则载体（DEPT_AND_CHILD + reason 脱敏）
const ROLE_M_KEY = `e2edpm${stamp}`
const ROLE_S_NAME = `E2E员工${stamp}` // 员工：仅授「请假申请」菜单，无任何数据权限规则（=SELF 默认档）
const ROLE_S_KEY = `e2edps${stamp}`
const USER_A = `e2edpa${stamp}` // 子部门 + 主管角色 → 本部门及以下 + reason 脱敏
const USER_B = `e2edpb${stamp}` // 员工角色无规则 → 仅自己
const NICK_A = `E2E权限主管${stamp}`
const NICK_B = `E2E权限员工${stamp}`
const PASSWORD = 'e2e123456'
const LEAVE_A_TITLE = `E2E权限假A${stamp}`
const LEAVE_B_TITLE = `E2E权限假B${stamp}`

const DEPT_PATH = '/system/dept'
const DATAPERM_PATH = '/system/data-perm'
const LEAVE_PATH = '/system/leave'
const USER_PATH = '/system/user'
const ROLE_PATH = '/system/role'

/** 建数产物 id（DP1-DP2 落、DP6 清扫用） */
const ids = { deptP: '', deptC: '', roleM: '', roleS: '', userA: '', userB: '', ruleId: '', leaveA: '', leaveB: '' }

// ---------- 前置健康探测（不过即 SKIP——bpmn 脚本 D12 卡点纪律同款） ----------
async function preflight() {
  try {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    }).then((r) => r.json())
    if (lb.code !== 200 || !lb.data?.accessToken) return { ok: false, reason: `admin 登录失败 code=${lb.code} msg="${lb.msg}"` }
    const headers = { Authorization: `Bearer ${lb.data.accessToken}` }
    const res = await fetch(`${GATEWAY}/system/data-perm/resources`, { headers }).then((r) => r.json())
    if (res.code !== 200) return { ok: false, reason: `GET /system/data-perm/resources code=${res.code} msg="${res.msg}"（后端数据权限端点未就绪？）` }
    const tree = await fetch(`${GATEWAY}/system/dept/tree`, { headers }).then((r) => r.json())
    if (tree.code !== 200) return { ok: false, reason: `GET /system/dept/tree code=${tree.code} msg="${tree.msg}"` }
    const leave = await fetch(`${GATEWAY}/system/leave/page?pageNum=1&pageSize=1`, { headers }).then((r) => r.json())
    if (leave.code !== 200) return { ok: false, reason: `GET /system/leave/page code=${leave.code} msg="${leave.msg}"（bpmn 链路未就绪？）` }
    return { ok: true, token: lb.data.accessToken }
  } catch (e) {
    return { ok: false, reason: `探测异常: ${e.message}` }
  }
}
const pre = await preflight()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway 18080 / sso 9201 / system 9202（数据权限版本）/ bpmn 9203 已启动')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
log('前置健康探测通过（resources + dept/tree + leave/page 经网关可达）')

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

// ---------- 通用小助手（bpmn 脚本同款模式本地化——跨脚本不 import 业务脚本，只共享 lib/harness） ----------
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

/** 页内 fetch（走 /api 代理链路，带当前登录人 token；GET/POST/PUT） */
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

/** EP select 打开（按 placeholder 定位）并返回可见下拉层 */
async function openSelect(scope, placeholder) {
  await scope.locator('.el-select', { hasText: placeholder }).click()
  const dd = page.locator('.el-select-dropdown:visible')
  await dd.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  return dd
}

async function pickOption(dd, text) {
  await dd.locator('.el-select-dropdown__item', { hasText: text }).first().click()
  await sleep(300)
}

/** 主体选项点选（label 后端拼装、角色形态未定——key/name 双兜底，先等选项渲染再点） */
async function pickSubjectOption(dd, key, name) {
  const item = dd.locator('.el-select-dropdown__item').filter({ hasText: key })
    .or(dd.locator('.el-select-dropdown__item').filter({ hasText: name })).first()
  await item.waitFor({ state: 'visible', timeout: 8000 })
  await item.click()
  await sleep(300)
}

/** el-tree-select 节点点选——实测（DOM 探针）tree-select 树节点文本直接渲染在 .el-tree-node__content，
 *  无 __label 包装（deptForm 上级/用户弹窗部门同构）；收窄到可见 tree-select 弹层防误触页内其他树 */
async function pickTreeNode(name) {
  const node = page.locator('.el-tree-select__popper:visible .el-tree-node__content', { hasText: name }).first()
  await node.waitFor({ state: 'visible', timeout: 8000 })
  await node.click()
  await sleep(300)
}

/** ElMessageBox 二段确认：等可见 → 文案包含断言 → 点主按钮 */
async function confirmBox(expected) {
  const box = page.locator('.el-message-box')
  await box.waitFor({ state: 'visible', timeout: 8000 })
  const text = (await box.innerText()).trim().replace(/\n/g, ' | ')
  assert(text.includes(expected), `确认框文案应含 "${expected}"，实际 "${text}"`)
  await box.locator('.el-message-box__btns .el-button--primary').click()
  return text
}

/** 当前可见 tab pane（el-tabs 非活动 pane display:none——dataperm 页三 tab 同 DOM） */
const visiblePane = () => page.locator('.el-tab-pane:visible')

/** 部门弹窗新增提交（表头入口 parent=根部门 预选；行内「新增子级」入口 parent=该行 预选——均只需填名） */
async function createDeptViaUi(name) {
  const dlg = dialogByTitle('新增部门')
  await dlg.waitFor({ state: 'visible', timeout: 8000 })
  await dlg.locator('input[placeholder="请输入部门名称"]').fill(name)
  await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
  await waitToast(page, '新增成功')
  await waitDialogGone(page, '新增部门')
}

/** 角色新增 UI（名称+标识，状态默认正常） */
async function createRoleViaUi(name, roleKey) {
  await page.getByRole('button', { name: '新增角色' }).click()
  const dlg = dialogByTitle('新增角色')
  await dlg.waitFor({ state: 'visible', timeout: 8000 })
  await dlg.locator('input[placeholder="请输入角色名称"]').fill(name)
  await dlg.locator('input[placeholder="字母开头，如 ops"]').fill(roleKey)
  await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
  await waitToast(page, '新增成功')
  await waitDialogGone(page, '新增角色')
}

try {
  // ================= ① DP0 admin 登录 + 导航 11 项 + 内置根部门删除双防线 =================
  await step('DP0', 'admin 登录 → 侧边 11 项（+部门管理/数据权限）→ 部门页树含内置总公司 + 删除双防线（UI 禁用 + 直连 3029）', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const items = page.locator('.el-menu .el-menu-item')
    const n = await items.count()
    const labels = []
    for (let i = 0; i < n; i++) labels.push(((await items.nth(i).innerText()) || '').trim())
    log(`  侧边菜单: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,字典管理,部门管理,数据权限,请假申请,我的审批,待办任务,流程定义,工作台', '侧边菜单顺序（数据权限轮 11 项形态）')

    // 部门页树表（契约 §2.1：全量含停用、sort 序、内置徽标）
    const treeP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/dept/tree'), { timeout: 15000 })
    await page.goto(`${BASE}${DEPT_PATH}`, { waitUntil: 'domcontentloaded' })
    await treeP
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    const rootRow = page.locator('.el-table__row', { hasText: '总公司' }).first()
    assert((await rootRow.count()) > 0, '部门树应含内置根部门 总公司')
    assert((await rootRow.locator('.el-tag', { hasText: '内置' }).count()) > 0, '内置根部门应带「内置」徽标')
    // UI 禁用面（第一防线）：内置行删除按钮 disabled
    const delDisabled = await rootRow.getByRole('button', { name: '删除' }).isDisabled()
    assert(delDisabled, '内置根部门行删除按钮应禁用（UI 第一防线）')
    // 后端最终防线：直连 DELETE /system/dept/1 → 3029（契约 §2.4）
    const probe = await directApi('DELETE', '/system/dept/1')
    assertEq(probe.httpStatus, 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(probe.body.code, 3029, `内置部门删除应 body 3029，实际 ${probe.body.code} msg="${probe.body.msg}"`)
    log(`  3029 最终防线: msg="${probe.body.msg}"`)
    await shot(page, 'dp0-dept-tree.png')
  })

  // ================= ① DP1 数据准备：部门两级 + 角色两枚（挂请假申请菜单）+ 用户 A/B =================
  await step('DP1', '建数：部门两级 UI 闭环 → 角色主管/员工各一枚 + 分配「请假申请」菜单 → 用户 A（子部门/主管）UI + 用户 B API', async () => {
    // —— 部门两级（表头=父 / 行内新增子级=子）——
    await page.getByRole('button', { name: '新增部门' }).click()
    await createDeptViaUi(DEPT_P_NAME)
    const deptPRow = page.locator('.el-table__row', { hasText: DEPT_P_NAME }).first()
    await deptPRow.waitFor({ state: 'visible', timeout: 8000 })
    await deptPRow.getByRole('button', { name: '新增子级' }).click()
    await createDeptViaUi(DEPT_C_NAME)
    await page.locator('.el-table__row', { hasText: DEPT_C_NAME }).first().waitFor({ state: 'visible', timeout: 8000 })
    // id 锚定（清扫用）：经页内 fetch 取树回溯
    const treeBody = await pageFetch('GET', '/api/system/dept/tree')
    const walk = (nodes) => {
      for (const nd of nodes || []) {
        if (nd.name === DEPT_P_NAME) ids.deptP = nd.id
        if (nd.name === DEPT_C_NAME) ids.deptC = nd.id
        walk(nd.children)
      }
    }
    walk(treeBody.data)
    assert(ids.deptP && ids.deptC, `两级部门 id 应取到，实际 P=${ids.deptP} C=${ids.deptC}`)
    log(`  部门两级: ${DEPT_P_NAME}=${ids.deptP} / ${DEPT_C_NAME}=${ids.deptC}`)

    // —— 角色两枚（均挂「请假申请」菜单——动态路由按菜单生成，无菜单进不了 /system/leave）——
    // 归位角色页（此刻仍在部门页——createRoleViaUi 的「新增角色」按钮按页内按钮定位，不 goto 必超时）
    await page.goto(`${BASE}${ROLE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    for (const [name, key] of [[ROLE_M_NAME, ROLE_M_KEY], [ROLE_S_NAME, ROLE_S_KEY]]) {
      await createRoleViaUi(name, key)
      const row = await findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value: key })
      assert(row, `角色行应出现（roleKey=${key}）`)
      await row.getByRole('button', { name: '分配权限' }).click()
      const dlg = dialogByTitle(`分配权限（${name}）`)
      await dlg.waitFor({ state: 'visible', timeout: 8000 })
      await sleep(300)
      // 勾选「请假申请」C 节点（toggleC 整棵子树——含发起/撤销按钮级权限）
      await dlg.locator('.el-checkbox', { hasText: '请假申请' }).first().click()
      await sleep(300)
      await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
      await waitToast(page, '分配成功')
      await waitDialogGone(page, `分配权限（${name}）`)
    }
    const roleBody = await pageFetch('GET', '/api/system/role/page?pageNum=1&pageSize=50')
    ids.roleM = roleBody.data.rows.find((x) => x.roleKey === ROLE_M_KEY)?.id || ''
    ids.roleS = roleBody.data.rows.find((x) => x.roleKey === ROLE_S_KEY)?.id || ''
    assert(ids.roleM && ids.roleS, `两角色 id 应取到，实际 M=${ids.roleM} S=${ids.roleS}`)
    log(`  角色: ${ROLE_M_KEY}=${ids.roleM} / ${ROLE_S_KEY}=${ids.roleS}`)

    // —— 用户 A：UI 新增（挂子部门——el-tree-select）+ UI 分配主管角色 ——
    await page.goto(`${BASE}${USER_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '新增用户' }).click()
    const dlg = dialogByTitle('新增用户')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500) // 部门树候选懒加载落定
    await dlg.locator('input[placeholder="请输入账号"]').fill(USER_A)
    await dlg.locator('input[placeholder="请输入昵称"]').fill(NICK_A)
    await dlg.locator('input[placeholder="6-32 位"]').fill(PASSWORD)
    await dlg.locator('.el-select, .el-tree-select', { hasText: '请选择部门（可空）' }).first().click()
    await pickTreeNode(DEPT_C_NAME)
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增用户')
    const userARow = await findRowByCell(page, { path: USER_PATH, cellIndex: 0, value: USER_A })
    assert(userARow, `用户 A 行应出现（account=${USER_A}）`)
    await userARow.getByRole('button', { name: '分配角色' }).click()
    const arDlg = dialogByTitle(`分配角色（${USER_A}）`)
    await arDlg.waitFor({ state: 'visible', timeout: 8000 })
    await arDlg.locator('.el-checkbox', { hasText: ROLE_M_KEY }).first().click()
    await sleep(300)
    await arDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '分配成功')
    await waitDialogGone(page, `分配角色（${USER_A}）`)

    // —— 用户 B：API 直建（无部门）+ 员工角色（无数据权限规则 → 默认仅自己）——
    const created = await directApi('POST', '/system/user', { account: USER_B, nickname: NICK_B, password: PASSWORD, status: 0 })
    assertEq(created.body.code, 200, `用户 B 直建应 200，实际 ${created.body.code} msg="${created.body.msg}"`)
    ids.userB = created.body.data
    const assigned = await directApi('PUT', '/system/user/role', { userId: ids.userB, roleIds: [ids.roleS] })
    assertEq(assigned.body.code, 200, `用户 B 分配员工角色应 200，实际 ${assigned.body.code}`)
    const userBody = await pageFetch('GET', '/api/system/user/page?pageNum=1&pageSize=50')
    ids.userA = userBody.data.rows.find((x) => x.account === USER_A)?.id || ''
    assert(ids.userA, `用户 A id 应取到`)
    log(`  用户: A=${USER_A}(${ids.userA}) B=${USER_B}(${ids.userB})`)
    await shot(page, 'dp1-seeded.png')
  })

  // ================= ② DP2 数据权限页：配规则（角色页联动入口）+ 规则行 + 模拟解释 =================
  await step('DP2', '角色页「数据权限」按钮 → 预筛选落点 → 新增规则（主管/本部门及以下/reason 脱敏）→ 规则行断言 + 模拟解释（A/leave）', async () => {
    // F5 联动入口：角色行「数据权限」按钮 → /system/data-perm?subjectType=0&subjectId=
    const roleRow = await findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value: ROLE_M_KEY })
    await roleRow.getByRole('button', { name: '数据权限' }).click()
    await page.waitForURL(`**${DATAPERM_PATH}**`, { timeout: 8000 })
    const q = new URL(page.url()).searchParams
    assertEq(q.get('subjectType'), '0', '联动入口应带 subjectType=0（角色）')
    assertEq(q.get('subjectId'), ids.roleM, `联动入口应带 subjectId=${ids.roleM}，实际 "${q.get('subjectId')}"`)
    log(`  联动入口落点: ${page.url()}`)

    // 新增规则（契约 §3.3 upsert 全量覆盖）
    await page.getByRole('button', { name: '新增规则' }).click()
    const dlg = dialogByTitle('新增规则')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500) // 主体选项加载
    // 资源默认取注册表第一项（leave 唯一）；主体类型默认角色——仅选主体 + 档位 + 列动作
    const subjDd = await openSelect(dlg, '请选择主体')
    await pickSubjectOption(subjDd, ROLE_M_KEY, ROLE_M_NAME)
    await dlg.locator('.el-radio', { hasText: '本部门及以下' }).click()
    await sleep(300)
    await dlg.locator('.el-form-item', { hasText: '列·reason' }).locator('.el-select').click()
    const colDd = page.locator('.el-select-dropdown:visible')
    await colDd.waitFor({ state: 'visible', timeout: 8000 })
    await pickOption(colDd, '脱敏')
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增规则')

    // 规则行断言（预筛选下该主体唯一行；rowScope tag + 列规则摘要）
    // 表格「主体名称」列渲染 subjectName=角色名（前端 dataperm/index.vue prop=subjectName）——按 NAME 定位，KEY 不在行内
    await page.locator('.el-table__row', { hasText: ROLE_M_NAME }).first().waitFor({ state: 'visible', timeout: 10000 })
    const ruleRow = page.locator('.el-table__row', { hasText: ROLE_M_NAME }).first()
    assert((await ruleRow.locator('.el-tag', { hasText: '本部门及以下' }).count()) > 0, '规则行行范围应为本部门及以下')
    const ruleCells = await rowCells(ruleRow)
    assert(ruleCells.join('|').includes('reason:脱敏'), `规则行列规则应含 reason:脱敏，实际 ${JSON.stringify(ruleCells)}`)
    // id 锚定（清扫兜底）：经页内 fetch 按 (resource, subjectType, subjectId) 查
    const ruleBody = await pageFetch('GET', `/api/system/data-perm/rule/page?pageNum=1&pageSize=10&resource=leave&subjectType=0&subjectId=${ids.roleM}`)
    ids.ruleId = ruleBody.data.rows[0]?.id || ''
    assert(ids.ruleId, '规则 id 应取到')
    log(`  规则: leave/角色/${ROLE_M_KEY}=${ids.ruleId}（DEPT_AND_CHILD + reason 脱敏）`)
    await shot(page, 'dp2-rule-row.png')

    // —— 模拟解释 tab（契约 §3.8：narratives + hitRules + columns；explain 不留痕）——
    // 账号/资源点选走 openSelect+pickOption（DP1 弹窗/DP3 已验证模式）——filterable select 的
    // 键盘 type+Enter 不落值（run-4 失败实证：账号空占位符 + 资源已选 leave →「请选择账号与资源」
    // 警告分支早退，explain 请求全程未发出，时间线 10s 超时）
    await page.locator('.el-tabs__item', { hasText: '模拟解释' }).click()
    const pane = visiblePane()
    // 账号选项 label=昵称(账号)（§6.5 后端拼装）——按账号子串点选；资源注册表当前唯一 leave
    const accDd = await openSelect(pane, '请选择账号')
    await accDd.locator('.el-select-dropdown__item', { hasText: USER_A }).first().click()
    await sleep(300)
    const resDd = await openSelect(pane, '请选择资源')
    await pickOption(resDd, 'leave')
    await pane.getByRole('button', { name: '解释' }).click()
    await pane.locator('.el-timeline').waitFor({ state: 'visible', timeout: 10000 })
    const narrativeText = (await pane.locator('.el-timeline').innerText()).trim()
    // narratives 为后端拼装文本（契约 §6.7 无逐字约定）——档位措辞若随 my-scope 同轮归类会脆，
    // 此处只做结构断言（≥2 条）；档位语义由下方 hitRules 表的前端 ROW_SCOPE_MAP tag 断言承载（契约 §1 值域稳定）
    const narrativeCount = await pane.locator('.el-timeline .el-timeline-item').count()
    assert(narrativeCount >= 2, `解释 narratives 应至少 2 条，实际 ${narrativeCount}（"${narrativeText.replace(/\n/g, ' ')}"）`)
    // 命中规则表「主体」列 = tag(角色) + subjectName（NAME）——同规则表口径按 NAME 定位
    const hitRow = pane.locator('.el-table__row', { hasText: ROLE_M_NAME }).first()
    await hitRow.waitFor({ state: 'visible', timeout: 8000 })
    assert((await hitRow.locator('.el-tag', { hasText: '本部门及以下' }).count()) > 0, '命中规则行应含主管角色 + 本部门及以下')
    // 列决策表：reason → 脱敏（契约 §1 动作映射）
    const colDecision = pane.locator('.el-table').last().locator('.el-table__row').first()
    const colDecisionText = (await colDecision.innerText()).trim()
    assert(colDecisionText.includes('reason') && colDecisionText.includes('脱敏'), `列决策应为 reason/脱敏，实际 "${colDecisionText}"`)
    log(`  解释 narratives: ${narrativeText.replace(/\n/g, ' / ')}`)
    await shot(page, 'dp2-explain.png')
  })

  // ================= ③ DP3 用户 A 视角：范围提示条 + 发起 + 详情脱敏 + 撤销 =================
  await step('DP3', `用户 A（${USER_A}）登录 → my-scope 含「指定范围」（部门档归类）→ UI 发起请假 → 详情事由=***（列脱敏）→ 撤销归终态`, async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_A, PASSWORD)
    assert(r.ok, `用户 A 登录应成功: ${r.msg || ''}`)
    const pageP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/leave/page'), { timeout: 15000 })
    await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded' })
    await pageP
    await page.locator('.el-alert').waitFor({ state: 'visible', timeout: 10000 })
    const alertText = (await page.locator('.el-alert').innerText()).trim().replace(/\n/g, ' ')
    // scopeLabel 契约 §6.8 五态：部门档（本部门/本部门及以下）展开集落「指定范围（共 N 人）」——
    // 主控 2026-10-10 裁决对齐（五标签无「本部门及以下」措辞）；列摘要括注 reason:脱敏 不变
    assert(alertText.includes('指定范围'), `A 的 my-scope 提示条应含「指定范围」（部门档归类），实际 "${alertText}"`)
    assert(alertText.includes('脱敏'), `A 的提示条应括注列结论（reason:脱敏），实际 "${alertText}"`)
    log(`  A 提示条: "${alertText}"`)

    // UI 发起请假（五字段：标题/事假/起止/事由/审批人 admin——approver 候选仅启用账号）
    await page.getByRole('button', { name: '发起请假' }).click()
    const dlg = dialogByTitle('发起请假')
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('input[placeholder="请输入标题"]').fill(LEAVE_A_TITLE)
    const typeDd = await openSelect(dlg, '请选择请假类型')
    await pickOption(typeDd, '事假')
    await dlg.locator('.el-date-editor').click()
    const panel = page.locator('.el-picker__popper')
    await panel.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(300)
    const table = panel.locator('.el-date-table').first()
    const day = (d) => table.locator('td.available:not(.prev-month):not(.next-month)').filter({ hasText: new RegExp(`^${d}$`) })
    await day(20).first().click()
    await sleep(400)
    await day(21).first().click()
    await sleep(400)
    await dlg.locator('textarea[placeholder="选填，不超过 500 字"]').fill('数据权限脱敏验证事由')
    const apprDd = await openSelect(dlg, '请选择审批人')
    await pickOption(apprDd, 'admin')
    const postP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/leave') && r2.request().method() === 'POST', { timeout: 20000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '提交' }).click()
    const res = await postP
    const body = await res.json()
    assertEq(body.code, 200, `A 发起请假应 200，实际 ${body.code} msg="${body.msg}"`)
    ids.leaveA = body.data
    await waitToast(page, '发起成功')
    await waitDialogGone(page, '发起请假')
    log(`  A 发起成功: ${LEAVE_A_TITLE} leaveId=${ids.leaveA}`)

    // 详情弹窗：事由 = ***（列规则脱敏作用于 A 自身视角——行级 self 恒在范围）
    const ownRow = page.locator('.el-table__row', { hasText: LEAVE_A_TITLE }).first()
    await ownRow.waitFor({ state: 'visible', timeout: 10000 })
    await ownRow.getByRole('button', { name: '详情' }).click()
    const detailDlg = dialogByTitle('请假单详情')
    await detailDlg.waitFor({ state: 'visible', timeout: 10000 })
    await sleep(800) // 详情接口 + descriptions 渲染
    const detailText = (await detailDlg.innerText()).trim()
    assert(detailText.includes('***'), `A 视角详情事由应脱敏 ***，实际含：${detailText.slice(0, 120)}…`)
    await shot(page, 'dp3-a-detail-masked.png')
    await detailDlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    await waitDialogGone(page, '请假单详情')

    // 撤销归终态（清扫纪律：单据不进删除面，终态留档同 bpmn 先例）
    await ownRow.getByRole('button', { name: '撤销' }).click()
    await confirmBox(LEAVE_A_TITLE)
    await waitToast(page, '撤销成功')
    await sleep(500)
  })

  // ================= ④ DP4 用户 B 视角：默认档 + IDOR 3026 + deny 留痕 =================
  await step('DP4', `用户 B（${USER_B}）登录 → my-scope「仅自己」→ API 造单仅见 1 行 → 直连 A 单详情 3026（IDOR 收口）→ 撤销归终态`, async () => {
    await logoutViaUi(page)
    const r = await login(page, USER_B, PASSWORD)
    assert(r.ok, `用户 B 登录应成功: ${r.msg || ''}`)
    await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-alert').waitFor({ state: 'visible', timeout: 10000 })
    const alertText = (await page.locator('.el-alert').innerText()).trim().replace(/\n/g, ' ')
    assert(alertText.includes('仅自己'), `B 的 my-scope 提示条应为默认档「仅自己」，实际 "${alertText}"`)
    log(`  B 提示条: "${alertText}"`)

    // B 造单（页内 fetch——当前登录人 B；走 /api 代理链路无 GBK 陷阱）
    const created = await pageFetch('POST', '/api/system/leave', {
      title: LEAVE_B_TITLE, leaveType: '1',
      startDate: `${YEAR}-${pad(ts.getMonth() + 1)}-22`, endDate: `${YEAR}-${pad(ts.getMonth() + 1)}-22`,
      reason: 'B 的事由', approver: 'admin',
    })
    assertEq(created.code, 200, `B 造单应 200，实际 ${created.code} msg="${created.msg}"`)
    ids.leaveB = created.data

    // B 列表：仅自己 1 行（A 的单不在行集——SELF 档行级过滤）
    const reloadP = page.waitForResponse((r2) => apiPath(r2.url(), '/api/system/leave/page'), { timeout: 15000 })
    await page.goto(`${BASE}${LEAVE_PATH}`, { waitUntil: 'domcontentloaded' })
    await reloadP
    await waitTableIdle(page)
    const rowCount = await page.locator('.el-table__row').count()
    assertEq(rowCount, 1, `B 视角应仅 1 行（仅自己），实际 ${rowCount}`)
    const rowText = (await page.locator('.el-table__row').first().innerText()).trim()
    assert(rowText.includes(LEAVE_B_TITLE), `B 唯一行应为本人的 ${LEAVE_B_TITLE}，实际 "${rowText.replace(/\n/g, ' ')}"`)
    assert(!rowText.includes(LEAVE_A_TITLE), 'B 视角不应出现 A 的单（行级过滤）')
    await shot(page, 'dp4-b-self-only.png')

    // IDOR 收口（契约 §4.2）：B 直连 A 单详情 → body 3026（HTTP 200）
    const probe = await pageFetch('GET', `/api/system/leave/${ids.leaveA}`)
    assertEq(probe.code, 3026, `B 直连 A 单详情应 body 3026，实际 ${probe.code} msg="${probe.msg}"`)
    log(`  3026 IDOR 收口: msg="${probe.msg}"（deny 留痕落库，DP5 复核）`)

    // 撤销归终态（清扫纪律）
    const canceled = await pageFetch('PUT', `/api/system/leave/cancel/${ids.leaveB}`)
    assertEq(canceled.code, 200, `B 撤销本人单应 200，实际 ${canceled.code} msg="${canceled.msg}"`)
  })

  // ================= ⑤ DP5 admin 复核：全量视野 + 决策留痕（list/deny 双记录） =================
  await step('DP5', 'admin 重登 → leave 全量视野（种子 ALL：含 A/B 两单）→ 决策留痕 tab：A=list 记录（规则摘要）/ B=deny 记录', async () => {
    await logoutViaUi(page)
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 重登应成功: ${r.msg || ''}`)
    // 种子规则 leave/ALL：admin 视角行集全量（跨部门跨用户）
    const rowA = await findRow(page, LEAVE_A_TITLE, { path: LEAVE_PATH })
    assert(rowA, `admin 全量视野应含 A 的单（${LEAVE_A_TITLE}）`)
    // findRow 各自 reload=true：从第 1 页重扫（上一锚定行可能停在后页）
    const rowB = await findRow(page, LEAVE_B_TITLE, { path: LEAVE_PATH })
    assert(rowB, `admin 全量视野应含 B 的单（${LEAVE_B_TITLE}）`)
    log('  admin 全量视野: A/B 两单均可见（种子 leave/ALL）')

    // 决策留痕 tab（契约 §3.7/§6.6：list=列表决策 / deny=详情被拒补记；explain/my-scope 不留痕）
    await page.goto(`${BASE}${DATAPERM_PATH}`, { waitUntil: 'domcontentloaded' })
    await page.locator('.el-tabs__item', { hasText: '决策留痕' }).click()
    const pane = visiblePane()
    await pane.locator('input[placeholder="账号（精确）"]').fill(USER_A)
    await pane.getByRole('button', { name: '查询' }).click()
    await pane.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    let rows = pane.locator('.el-table__row')
    let count = await rows.count()
    assert(count >= 1, `留痕（${USER_A}）应至少 1 条，实际 ${count}`)
    const aLogText = (await rows.first().innerText()).trim()
    assert(aLogText.includes(USER_A) && aLogText.includes('列表'), `A 留痕应为 list 记录，实际 "${aLogText.replace(/\n/g, ' ')}"`)
    assert(aLogText.includes(ROLE_M_NAME) || aLogText.includes(ROLE_M_KEY), `A 留痕规则摘要应含主管角色，实际 "${aLogText.replace(/\n/g, ' ')}"`)
    await shot(page, 'dp5-log-a.png')

    await pane.locator('input[placeholder="账号（精确）"]').fill(USER_B)
    await pane.getByRole('button', { name: '查询' }).click()
    await sleep(500)
    await waitTableIdle(page)
    rows = pane.locator('.el-table__row')
    count = await rows.count()
    assert(count >= 1, `留痕（${USER_B}）应至少 1 条，实际 ${count}`)
    let denyFound = false
    for (let i = 0; i < count; i++) {
      const t = (await rows.nth(i).innerText()).trim()
      if (t.includes('拒绝')) { denyFound = true; log(`  B deny 留痕: "${t.replace(/\n/g, ' ')}"`); break }
    }
    assert(denyFound, `B 留痕应含 deny（拒绝）记录——3026 补记，实际 ${count} 条均无`)
  })

  // ================= ⑤ DP6 清扫：UI 删规则 + API 删用户/角色/部门 + 残留断言 =================
  await step('DP6', '清扫：UI 删规则（确认框含主体名）→ API 删用户 A/B → 角色 → 子部门 → 父部门 → 残留断言零 e2e', async () => {
    // UI 删规则（数据权限页规则 tab 行「删除」——契约 §3.4 连带物理删列规则）
    await page.locator('.el-tabs__item', { hasText: '规则配置' }).click()
    await page.locator('.el-table__row', { hasText: ROLE_M_NAME }).first().waitFor({ state: 'visible', timeout: 10000 })
    await page.locator('.el-table__row', { hasText: ROLE_M_NAME }).first().getByRole('button', { name: '删除' }).click()
    await confirmBox(ROLE_M_NAME)
    await waitToast(page, '删除成功')
    await sleep(500)
    const ruleGone = (await page.locator('.el-table__row', { hasText: ROLE_M_NAME }).count()) === 0
    assert(ruleGone, '删规则后行应消失')

    // API 清扫（顺序：用户 → 角色 → 子部门 → 父部门——3030 前置：部门下须无在职用户/子部门）
    for (const [label, resource, id] of [
      ['用户A', 'user', ids.userA], ['用户B', 'user', ids.userB],
      ['角色主管', 'role', ids.roleM], ['角色员工', 'role', ids.roleS],
      ['子部门', 'dept', ids.deptC], ['父部门', 'dept', ids.deptP],
    ]) {
      const del = await directApi('DELETE', `/system/${resource}/${id}`)
      assertEq(del.body.code, 200, `清扫 ${label}(${id}) 应 200，实际 ${del.body.code} msg="${del.body.msg}"`)
    }
    log(`  已清扫: 规则(UI) 用户x2 角色x2 部门x2（请假单两枚已撤销终态留档——bpmn 先例）`)

    // 残留断言：部门树 / 规则分页（按主体）/ 用户分页 均零 e2e 残留
    const tree = await pageFetch('GET', '/api/system/dept/tree')
    const names = []
    const walkNames = (nodes) => { for (const nd of nodes || []) { names.push(nd.name); walkNames(nd.children) } }
    walkNames(tree.data)
    assert(!names.some((nm) => nm.startsWith('E2EDP')), `部门树应零 E2EDP 残留，实际 ${JSON.stringify(names)}`)
    const ruleRes = await pageFetch('GET', `/api/system/data-perm/rule/page?pageNum=1&pageSize=10&subjectType=0&subjectId=${ids.roleM}`)
    assertEq(Number(ruleRes.data.total), 0, `规则分页（subjectId=${ids.roleM}）应为空`)
    const users = await pageFetch('GET', '/api/system/user/page?pageNum=1&pageSize=50')
    const accounts = users.data.rows.map((u) => u.account)
    assert(!accounts.includes(USER_A) && !accounts.includes(USER_B), `用户页应零 e2e 用户残留，实际 ${JSON.stringify(accounts.slice(0, 10))}…`)
    log('  残留断言通过：部门树/规则/用户页零 e2e 残留')
  })

  // ================= DP-VERIFY 环境卫生 =================
  await step('DP-VERIFY', 'console error / pageerror / 网络失败 / 资产 404 环境卫生复核', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（M-VERIFY/D-VERIFY 同款口径）——
    // 资源类 console 文本滤掉，由 asset404 白名单在响应层精确甄别
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
    const noise = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noise.length, 0, `不应有非 favicon 资产 404，实际 ${JSON.stringify(noise)}`)
  })
} finally {
  await browser.close()
}

h.summary({
  extras: [
    `\n测试数据（stamp=${stamp}）：部门 ${DEPT_P_NAME}/${DEPT_C_NAME} 角色 ${ROLE_M_KEY}/${ROLE_S_KEY} 用户 ${USER_A}/${USER_B}`,
    `清扫结论：规则(UI 删)/用户/角色/部门全清；请假单 ${LEAVE_A_TITLE}/${LEAVE_B_TITLE} 撤销终态留档（bpmn 先例——审批链路数据不物理删）`,
    'admin 种子账号零触碰（仅登录 + 只读 + 建数）',
  ],
})
