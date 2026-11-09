/**
 * 通知中心 e2e（契约 2026-11-08-notify-api §1-§3 / 计划 2026-11-08-notify-center F5）
 *
 * 运行前提：**仅前端 dev 5173**（后端 notify 域由另一轨道并行实现，本脚本零后端依赖）——
 * 登录链（sso/system 菜单权限）与 notify/user/role/bpmn 数据端点全部在浏览器网络层
 * route mock（route mock 分层，沿 run-ai-chat-e2e.mjs 先例；mock 态在脚本内存，
 * 结束即清）。gateway/sso/system 未启动也能全绿。
 * 运行：cd cloud-e2e && npm run e2e:notify（单跑，不进 run-e2e.mjs 聚合，沿 aichat 先例）；
 * E2E_HEADLESS=1 或 --headless 无头。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：`e2e_` 前缀 + 时间戳全在 route mock 内存态（零真实后端写）；admin 种子账号
 * 只登录不改。轮询 30s 等待窗由 mock 态迁移 + locator.waitFor 驱动。
 *
 * 场景（计划 F5 五段）：
 * - N1 登录+菜单+管理页：侧边「系统管理/通知管理」→ 列表渲染（2 内置模板/内置徽标/表头）
 *   + Navbar 铃铛角标=初始未读 2
 * - N2 模板 CRUD：新增（行出现）→ 同 code 再增 3043 toast → 编辑（回显+改名）→
 *   name 模糊过滤 → 内置行删除 disabled → 删除确认（行消失）
 * - N3 发送弹窗三档：预选模板回显 → 变量表单动态生成+实时预览 → 空变量提交 3045 toast
 *   → 填变量预览换值 → 按用户（options 渲染+提交成功 N 人）→ 按角色 → 全员
 * - N4 铃铛下拉：badge 渲染 + 最近未读 5 条（标题/时间）+ 底部两按钮存在
 * - N5 轮询弹框：mock summary 态迁移（burst 新增 8 条）→ 30s 轮询触发 → 恰弹最新 3 条
 *   （限流）+ 普通 4.5s 自动消失/重要 8s 消失/紧急常驻 + 点击紧急通知（markRead 请求 +
 *   通知关闭 + linkUrl 跳转）
 * - N6 消息中心：直链可达 + id 倒序渲染 + readFlag tab/type 过滤 + 单条已读（行离开未读集）
 *   + 幂等 UI 面（已读行无按钮）+ 全部已读（toast「已读 N 条」+ 角标归零）
 * - N-VERIFY 环境卫生（console/pageerror/>=400/网络失败——favicon 404 噪音滤除）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login, waitToast, waitTableIdle, rowCells } = h

const PAGE_PATH = '/system/notify'
const CENTER_PATH = '/notify/center'
const ts = Date.now()
const CODE = `e2e_notify_${ts}`
/** 管理页表头 10 列（实现面 index.vue 列序） */
const HEADERS = ['模板编码', '模板名称', '消息类型', '消息级别', '标题模板', '内容模板', '内置', '创建人', '创建时间', '操作']

// ---------- mock 数据（分层：模板 2 条 / 用户 3 / 角色 2 / 我的消息初始 3 条） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const ERR = (code, msg) => JSON.stringify({ code, msg, data: null })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

const mkTemplate = (over) => ({
  id: '1',
  code: 'welcome_notice',
  name: '欢迎公告',
  titleTemplate: '欢迎加入',
  contentTemplate: '欢迎使用本平台，如有问题请联系管理员',
  type: 1,
  level: 0,
  variablesDesc: '',
  isBuiltin: 1,
  createBy: 'admin',
  createTime: '2026-11-08 10:00:00',
  updateTime: '2026-11-08 10:00:00',
  ...over,
})
const templates = [
  mkTemplate(),
  mkTemplate({ id: '2', code: 'approval_remind', name: '审批提醒', titleTemplate: '审批提醒：${nickname}', contentTemplate: '${nickname}，你有新的审批任务待处理，请及时办理', type: 2, level: 1, variablesDesc: 'nickname=用户昵称' }),
]
let nextTemplateId = 100

const mkMsg = (id, title, level, type, readFlag, linkUrl = null) => ({
  id: String(id),
  messageId: '8001',
  title,
  content: `${title}——正文内容`,
  type,
  level,
  linkUrl,
  readFlag,
  readTime: readFlag ? '2026-11-08 12:00:00' : null,
  sendTime: '2026-11-08 12:00:00',
})
/** 我的收件行（mock 唯一真相源：summary/分页/已读全部派生） */
const msgs = [
  mkMsg(2000, 'e2e_存量消息00', 0, 1, 1),
  mkMsg(2001, 'e2e_存量消息01', 1, 2, 0),
  mkMsg(2002, 'e2e_存量消息02', 2, 1, 0),
]
/** burst 增量（N5 态迁移用：一次推 8 条——单轮增量 >3 触发限流只弹最新 3 条） */
const burstMsgs = [
  mkMsg(2010, 'e2e_增量消息10_普通', 0, 1, 0),
  mkMsg(2009, 'e2e_增量消息09_重要', 1, 2, 0),
  mkMsg(2008, 'e2e_增量消息08_紧急', 2, 2, 0, CENTER_PATH),
  mkMsg(2007, 'e2e_增量消息07', 1, 1, 0),
  mkMsg(2006, 'e2e_增量消息06', 0, 2, 0),
  mkMsg(2005, 'e2e_增量消息05', 0, 1, 0),
  mkMsg(2004, 'e2e_增量消息04', 1, 2, 0),
  mkMsg(2003, 'e2e_增量消息03', 0, 1, 0),
]

const users = [
  { id: '101', account: 'e2e_user1', nickname: '张三', status: 0, createBy: null, createTime: null, updateBy: null, updateTime: null, statusLabel: null, createByName: null, updateByName: null, builtin: false, deptId: null, deptName: null, mobile: null, email: null },
  { id: '102', account: 'e2e_user2', nickname: '李四', status: 0, createBy: null, createTime: null, updateBy: null, updateTime: null, statusLabel: null, createByName: null, updateByName: null, builtin: false, deptId: null, deptName: null, mobile: null, email: null },
  { id: '103', account: 'e2e_user3', nickname: '王五', status: 0, createBy: null, createTime: null, updateBy: null, updateTime: null, statusLabel: null, createByName: null, updateByName: null, builtin: false, deptId: null, deptName: null, mobile: null, email: null },
]
const roles = [
  { id: '201', name: 'e2e_角色A', roleKey: 'e2e_role_a', status: 0, createBy: null, createTime: null, updateBy: null, updateTime: null, statusLabel: null, createByName: null, updateByName: null, builtin: false },
  { id: '202', name: 'e2e_角色B', roleKey: 'e2e_role_b', status: 0, createBy: null, createTime: null, updateBy: null, updateTime: null, statusLabel: null, createByName: null, updateByName: null, builtin: false },
]

/** 与后端 NotifyTemplateParser 一致的占位符提取（前端 SendNotifyDialog 同款正则） */
const VAR_PATTERN = /\$\{([a-zA-Z][a-zA-Z0-9_]*)\}/g
const extractVars = (tpl) => {
  const out = new Set()
  for (const m of `${tpl.titleTemplate}${tpl.contentTemplate}`.matchAll(VAR_PATTERN)) out.add(m[1])
  return [...out]
}

const unreadCount = () => msgs.filter((m) => m.readFlag === 0).length
const latestId = () => msgs.reduce((max, m) => Math.max(max, Number(m.id)), 0)
let nextMessageId = 9001

/** 请求证据计数锚（apiCalls url 含 query，read 用 startsWith 匹配） */
const readCalls = (id) => h.state.apiCalls.filter((c) => c.method === 'PUT' && c.url === `/api/system/notify/read/${id}`).length

// ---------- route mock（登录链 + notify 域 + options + dashboard 数据，零后端依赖） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}；测试模板 code=${CODE}`)
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
    return route.fulfill(json(R({ accessToken: `e2e-token-${ts}`, refreshToken: `e2e-refresh-${ts}`, expiresIn: '3600' })))
  }
  if (p === '/sso/auth/me' && req.method() === 'GET') {
    return route.fulfill(json(R({ account: 'admin', permissions: ['system:notify:list', 'system:notify:add', 'system:notify:edit', 'system:notify:remove', 'system:notify:send'] })))
  }
  if (p === '/sso/auth/logout' && req.method() === 'POST') {
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/menu/user-nav' && req.method() === 'GET') {
    // 最小导航树：系统管理(M, sort 1) > 通知管理(C, /system/notify, sort 8)
    return route.fulfill(json(R([
      { id: '10', parentId: '0', name: '系统管理', type: 'M', path: '', icon: 'Setting', sort: 1, children: [
        { id: '19', parentId: '10', name: '通知管理', type: 'C', path: '/system/notify', icon: 'Bell', sort: 8, children: [] },
      ] },
    ])))
  }
  // dashboard 首屏数据（工作台两请求——mock 空态防后端未起报网络失败；todo 分页轮起返回 PageResult 形态）
  if (p === '/bpmn/task/todo' && req.method() === 'GET') {
    return route.fulfill(json(R({ total: '0', rows: [] })))
  }
  if (p === '/bpmn/approval/page' && req.method() === 'GET') {
    return route.fulfill(json(R({ total: '0', rows: [] })))
  }
  // 发送弹窗 options（复用既有 user/role 列表端点——计划 F2 口径）
  if (p === '/system/user/page' && req.method() === 'GET') {
    return route.fulfill(json(R({ total: String(users.length), rows: users })))
  }
  if (p === '/system/role/list' && req.method() === 'GET') {
    return route.fulfill(json(R(roles)))
  }

  // ---- notify 域（契约 §1-§3）----
  if (p === '/system/notify/template/page' && req.method() === 'GET') {
    let rows = [...templates]
    if (q.get('name')) rows = rows.filter((t) => t.name.includes(q.get('name')))
    if (q.get('code')) rows = rows.filter((t) => t.code.includes(q.get('code')))
    if (q.get('type')) rows = rows.filter((t) => t.type === Number(q.get('type')))
    const pageNum = Math.max(1, Number(q.get('pageNum') ?? 1))
    const pageSize = Math.max(1, Number(q.get('pageSize') ?? 10))
    return route.fulfill(json(R({ total: String(rows.length), rows: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize) })))
  }
  const tplDetail = p.match(/^\/system\/notify\/template\/(\d+)$/)
  if (tplDetail && req.method() === 'GET') {
    const found = templates.find((t) => t.id === tplDetail[1])
    return route.fulfill(found ? json(R(found)) : json(ERR(3044, '模板不存在')))
  }
  if (p === '/system/notify/template' && req.method() === 'POST') {
    const body = req.postDataJSON()
    if (templates.some((t) => t.code === body.code)) {
      return route.fulfill(json(ERR(3043, '模板编码已存在')))
    }
    templates.push(mkTemplate({ ...body, id: String(nextTemplateId++), isBuiltin: 0, createBy: 'admin' }))
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/notify/template' && req.method() === 'PUT') {
    const body = req.postDataJSON()
    const found = templates.find((t) => t.id === body.id)
    if (!found) return route.fulfill(json(ERR(3044, '模板不存在')))
    if (templates.some((t) => t.code === body.code && t.id !== body.id)) {
      return route.fulfill(json(ERR(3043, '模板编码已存在')))
    }
    Object.assign(found, body)
    return route.fulfill(json(R(null)))
  }
  if (tplDetail && req.method() === 'DELETE') {
    const idx = templates.findIndex((t) => t.id === tplDetail[1])
    if (idx < 0) return route.fulfill(json(ERR(3044, '模板不存在')))
    if (templates[idx].isBuiltin === 1) return route.fulfill(json(ERR(3049, '内置模板禁止删除')))
    templates.splice(idx, 1)
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/notify/send' && req.method() === 'POST') {
    const body = req.postDataJSON()
    const tpl = templates.find((t) => t.id === String(body.templateId))
    if (!tpl) return route.fulfill(json(ERR(3044, '模板不存在')))
    // 模拟后端 3045：入参变量集未覆盖模板提取变量集（msg 动态列缺失名）
    const missing = extractVars(tpl).filter((name) => !body.variables || !(name in body.variables))
    if (missing.length) return route.fulfill(json(ERR(3045, `模板变量缺失: ${missing.join(',')}`)))
    let count = 0
    if (body.receiverType === 1) count = (body.userIds || []).length
    else if (body.receiverType === 2) count = (body.roleIds || []).length * 3
    else count = users.length
    if (count === 0) return route.fulfill(json(ERR(3047, '接收人列表为空')))
    return route.fulfill(json(R({ messageId: String(nextMessageId++), receiverCount: count })))
  }
  if (p === '/system/notify/summary' && req.method() === 'GET') {
    return route.fulfill(json(R({ unreadCount: String(unreadCount()), latestId: String(latestId()) })))
  }
  if (p === '/system/notify/page' && req.method() === 'GET') {
    let rows = [...msgs].sort((a, b) => Number(b.id) - Number(a.id))
    if (q.get('type')) rows = rows.filter((m) => m.type === Number(q.get('type')))
    if (q.get('readFlag') !== null && q.get('readFlag') !== '') rows = rows.filter((m) => m.readFlag === Number(q.get('readFlag')))
    const pageNum = Math.max(1, Number(q.get('pageNum') ?? 1))
    const pageSize = Math.max(1, Number(q.get('pageSize') ?? 10))
    return route.fulfill(json(R({ total: String(rows.length), rows: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize) })))
  }
  const readMatch = p.match(/^\/system\/notify\/read\/(\d+)$/)
  if (readMatch && req.method() === 'PUT') {
    // 幂等语义（契约 §3.3）：已读行重复调用成功；不存在/非本人 3048
    const found = msgs.find((m) => m.id === readMatch[1])
    if (!found) return route.fulfill(json(ERR(3048, '收件记录不存在')))
    if (found.readFlag === 0) {
      found.readFlag = 1
      found.readTime = '2026-11-08 13:00:00'
    }
    return route.fulfill(json(R(null)))
  }
  if (p === '/system/notify/read-all' && req.method() === 'PUT') {
    const n = unreadCount()
    for (const m of msgs) {
      if (m.readFlag === 0) {
        m.readFlag = 1
        m.readTime = '2026-11-08 13:00:00'
      }
    }
    return route.fulfill(json(R(n)))
  }
  return route.fulfill(json(R(null)))
})

/** 等铃铛 badge 文本到达目标值（store 轮询/刷新链异步收敛的断言锚） */
async function waitBadge(expect, timeout = 15000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    const badge = page.locator('.navbar-bell .el-badge__content')
    if ((await badge.count()) > 0 && (await badge.isVisible())) {
      const text = (await badge.innerText()).trim()
      if (text === expect) return text
    }
    await sleep(150)
  }
  throw new Error(`等待铃铛角标=${expect} 超时（实际 "${await page.locator('.navbar-bell .el-badge__content').innerText().catch(() => '(不可见)')}"）`)
}

/** EP select 通用选择：multiple select 选后下拉保持开——已开则直接点 option，不再点 trigger（会 toggle 关闭） */
async function pickOption(trigger, optionText) {
  const dd = page.locator('.el-select-dropdown:visible').last()
  if ((await dd.count()) === 0) {
    await trigger.click()
    await dd.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(300)
  }
  await dd.locator('.el-select-dropdown__item', { hasText: optionText }).first().click()
  await sleep(300)
}

/** 查询区 type 下拉（非 filterable，容器定位） */
async function pickTypeFilter(label) {
  await page.locator('.filter-bar .el-select').first().click()
  const dd = page.locator('.el-select-dropdown:visible').last()
  await dd.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  await dd.locator('.el-select-dropdown__item', { hasText: label }).first().click()
  await sleep(300)
}

try {
  // ================= N1 登录 + 菜单 + 管理页列表渲染 =================
  await step('N1', '登录 + 菜单「通知管理」+ 列表渲染（2 内置模板/表头/内置徽标）+ 铃铛角标=2', async () => {
    const lr = await login(page, 'admin', 'admin123')
    assert(lr.ok, `admin 登录应成功（route mock 链）: ${lr.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const menuTexts = await page.evaluate(() => Array.from(document.querySelectorAll('.el-menu .el-menu-item, .el-menu .el-sub-menu__title')).map((el) => (el.textContent || '').trim()).filter(Boolean))
    assert(menuTexts.includes('通知管理'), `侧边栏应含「通知管理」（mock user-nav 菜单 19），实际 ${JSON.stringify(menuTexts)}`)
    assert(menuTexts.includes('系统管理'), '侧边栏应含「系统管理」分组')
    await shot(page, 'n1-menu.png')
    await page.goto(`${BASE}${PAGE_PATH}`, { waitUntil: 'domcontentloaded' })
    await waitTableIdle(page)
    await sleep(500)
    assertEq(new URL(page.url()).pathname, PAGE_PATH, '应停留在通知管理页')
    const crumbs = (await h.breadcrumbTexts(page)).join('>')
    assert(crumbs.includes('通知管理'), `面包屑应含通知管理，实际 "${crumbs}"`)
    const headers = await page.evaluate(() => Array.from(document.querySelectorAll('.el-table__header th .cell')).map((el) => el.textContent.trim()).filter(Boolean))
    for (const hd of HEADERS) assert(headers.includes(hd), `表头缺列「${hd}」，实际 ${JSON.stringify(headers)}`)
    // 2 条内置模板行 + 内置徽标 + 铃铛角标=2（初始未读：2001/2002）
    const rows = page.locator('.el-table__row')
    assertEq(await rows.count(), 2, `初始应 2 行内置模板，实际 ${await rows.count()}`)
    assertEq(await page.locator('.el-table__row', { hasText: 'welcome_notice' }).count(), 1, '应含 welcome_notice 行')
    assertEq(await page.locator('.el-table__row .el-tag', { hasText: '内置' }).count(), 2, '两行均应带内置徽标')
    assertEq(await waitBadge('2'), '2', '铃铛角标应为初始未读 2')
    await shot(page, 'n1-list.png')
  })

  // ================= N2 模板 CRUD 流 =================
  await step('N2', '模板 CRUD：新增行出现 → 同 code 3043 toast → 编辑改名 → 模糊过滤 → 内置删除 disabled → 删除行消失', async () => {
    // a. 新增
    await page.getByRole('button', { name: '新增模板' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增模板' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.getByPlaceholder('如 welcome_notice').fill(CODE)
    await dlg.getByPlaceholder('请输入模板名称').fill(`e2e通知模板${ts}`)
    await dlg.getByPlaceholder(/支持 \$\{变量\} 占位符，如/).fill('通知：${user}')
    await dlg.getByPlaceholder(/变量用 \$\{变量名\} 引用/).fill('${user} 的申请需要你处理')
    await dlg.getByText('提醒', { exact: true }).click()
    await dlg.getByText('重要', { exact: true }).click()
    await shot(page, 'n2-add-form.png')
    await dlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '新增成功', 'success')
    await h.waitDialogGone(page, '新增模板')
    await waitTableIdle(page)
    await sleep(400)
    const newRow = page.locator('.el-table__row', { hasText: CODE })
    assertEq(await newRow.count(), 1, `新增后应出现 code=${CODE} 行`)
    // b. 同 code 再增 → 3043 toast（拦截器统一弹后端 msg 原文）
    await page.getByRole('button', { name: '新增模板' }).click()
    const dlg2 = page.locator('.el-dialog', { hasText: '新增模板' }).last()
    await dlg2.waitFor({ state: 'visible', timeout: 8000 })
    await dlg2.getByPlaceholder('如 welcome_notice').fill(CODE)
    await dlg2.getByPlaceholder('请输入模板名称').fill(`e2e通知模板撞码${ts}`)
    await dlg2.getByPlaceholder(/支持 \$\{变量\} 占位符，如/).fill('撞码标题')
    await dlg2.getByPlaceholder(/变量用 \$\{变量名\} 引用/).fill('撞码内容')
    await dlg2.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '模板编码已存在', 'error')
    await dlg2.getByRole('button', { name: '取消' }).click()
    await dlg2.waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})
    // c. 编辑：回显 + 改名保存
    await newRow.getByRole('button', { name: '编辑', exact: true }).click()
    const editDlg = page.locator('.el-dialog', { hasText: '编辑模板' }).last()
    await editDlg.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(400)
    assertEq(await editDlg.getByPlaceholder('如 welcome_notice').inputValue(), CODE, '编辑弹窗应回显 code')
    assertEq(await editDlg.getByPlaceholder('请输入模板名称').inputValue(), `e2e通知模板${ts}`, '编辑弹窗应回显名称')
    await editDlg.getByPlaceholder('请输入模板名称').fill(`e2e通知模板改名${ts}`)
    await editDlg.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await h.waitDialogGone(page, '编辑模板')
    await waitTableIdle(page)
    await sleep(400)
    assertEq(await page.locator('.el-table__row', { hasText: `e2e通知模板改名${ts}` }).count(), 1, '编辑后行名应更新')
    // d. name 模糊过滤：e2e通知模板 → 恰 1 行
    await page.getByPlaceholder('模板名称（模糊）').fill('e2e通知模板')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await waitTableIdle(page)
    await sleep(300)
    assertEq(await page.locator('.el-table__row').count(), 1, 'name 模糊过滤后应恰 1 行')
    await page.getByRole('button', { name: '重置', exact: true }).click()
    await waitTableIdle(page)
    await sleep(300)
    assertEq(await page.locator('.el-table__row').count(), 3, '重置后应回 3 行')
    // e. 内置行删除按钮 disabled（UI 面禁删——3049 后端双防线）
    const builtinRow = page.locator('.el-table__row', { hasText: 'welcome_notice' })
    assert(!(await builtinRow.getByRole('button', { name: '删除', exact: true }).isEnabled()), '内置模板删除按钮应 disabled')
    // f. 删除用户模板：确认框 → 行消失
    const delRow = page.locator('.el-table__row', { hasText: CODE })
    await delRow.getByRole('button', { name: '删除', exact: true }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    assert((await box.innerText()).includes(CODE), `删除确认框文案应含模板编码，实际 "${await box.innerText()}"`)
    await box.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '已删除', 'success')
    await waitTableIdle(page)
    await sleep(400)
    assertEq(await page.locator('.el-table__row', { hasText: CODE }).count(), 0, '删除后行应消失')
    await shot(page, 'n2-crud-done.png')
  })

  // ================= N3 发送弹窗三档 =================
  await step('N3', '发送弹窗：预选模板 + 变量动态表单/实时预览 + 空变量 3045 toast + 按用户/按角色/全员三档', async () => {
    const row = page.locator('.el-table__row', { hasText: 'approval_remind' })
    await row.getByRole('button', { name: '发送', exact: true }).click()
    const dlg = page.locator('.el-dialog', { hasText: '发送通知' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // a. 预选模板回显（列表行进入时预选）+ 变量表单动态生成 + 预览占位符原样
    await dlg.locator('.send-preview-title').waitFor({ state: 'visible', timeout: 8000 })
    const tplSelectText = (await dlg.locator('.el-select').first().innerText()).replace(/\n/g, ' ')
    assert(tplSelectText.includes('审批提醒') && tplSelectText.includes('approval_remind'), `模板下拉应预选 approval_remind（列表行进入），实际 "${tplSelectText}"`)
    assertEq((await dlg.locator('.send-preview-title').innerText()).trim(), '审批提醒：${nickname}', '预览标题应含未填占位符')
    const varInput = dlg.getByPlaceholder('请输入 nickname 的值')
    await varInput.waitFor({ state: 'visible', timeout: 8000 })
    assert((await dlg.innerText()).includes('nickname=用户昵称'), '弹窗应展示模板变量说明')
    // b. 空变量提交（默认全员档）→ 后端 3045 toast 原文（msg 动态列缺失变量名）
    await dlg.locator('.el-dialog__footer button', { hasText: '发送' }).click()
    await waitToast(page, '模板变量缺失: nickname', 'error')
    // c. 填变量 → 预览实时换值（不再含 ${ 占位符）
    await varInput.fill('张三')
    await sleep(300)
    assertEq((await dlg.locator('.send-preview-title').innerText()).trim(), '审批提醒：张三', '预览标题应实时替换变量值')
    // d. 按用户档：options 渲染（mock 3 用户）→ 选 2 → linkUrl → 发送成功 N 人
    await dlg.getByText('按用户', { exact: true }).click()
    // EP 2.14 select 的 placeholder 是 span 非 input 属性（getByPlaceholder 匹配不到）——按容器序定位：0=模板 1=接收人
    const userSelect = dlg.locator('.el-select').nth(1)
    assert((await userSelect.innerText()).includes('请选择接收用户'), '弹窗内第 2 个 select 应为接收用户多选框')
    await pickOption(userSelect, '张三（e2e_user1）')
    await pickOption(userSelect, '李四（e2e_user2）')
    // 点标题收起 multiple select 的下拉（Escape 会冒泡触发 dialog close-on-press-escape 连带关弹窗）
    await dlg.locator('.el-dialog__title').click()
    await dlg.getByPlaceholder(/前端相对路由/).fill(CENTER_PATH)
    await shot(page, 'n3-send-user.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '发送' }).click()
    await waitToast(page, '发送成功：2 人', 'success')
    // e. 按角色档：options（mock 2 角色）→ 选 1 → 发送成功
    await row.getByRole('button', { name: '发送', exact: true }).click()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.getByText('按角色', { exact: true }).click()
    await dlg.getByPlaceholder(/请输入 nickname 的值/).fill('李四')
    const roleSelect = dlg.locator('.el-select').nth(1)
    assert((await roleSelect.innerText()).includes('请选择接收角色'), '弹窗内第 2 个 select 应为接收角色下拉')
    await pickOption(roleSelect, 'e2e_角色A')
    await dlg.locator('.el-dialog__title').click()
    await dlg.locator('.el-dialog__footer button', { hasText: '发送' }).click()
    await waitToast(page, '发送成功：3 人', 'success')
    // f. 全员档：文案确认 + 发送成功（mock 用户基数 3）
    await row.getByRole('button', { name: '发送', exact: true }).click()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.getByText('全员', { exact: true }).click()
    assert((await dlg.innerText()).includes('将发送给全部有效用户'), '全员档应展示快照文案确认')
    await dlg.getByPlaceholder(/请输入 nickname 的值/).fill('王五')
    await shot(page, 'n3-send-all.png')
    await dlg.locator('.el-dialog__footer button', { hasText: '发送' }).click()
    await waitToast(page, '发送成功：3 人', 'success')
    await h.waitDialogGone(page, '发送通知')
  })

  // ================= N4 铃铛下拉 =================
  await step('N4', '铃铛下拉：badge 渲染 + 最近未读列表（title/时间）+ 底部两按钮', async () => {
    assertEq(await waitBadge('2'), '2', '铃铛角标应为未读 2')
    await page.locator('.navbar-bell').click()
    const pop = page.locator('.notify-popover')
    await pop.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(500)
    const items = pop.locator('.notify-item')
    assertEq(await items.count(), 2, `最近未读应 2 条（2001/2002），实际 ${await items.count()}`)
    const firstTitle = (await items.first().locator('.notify-item-title').innerText()).trim()
    assertEq(firstTitle, 'e2e_存量消息02', '下拉首条应为最新未读（id 倒序）')
    assert((await pop.innerText()).includes('2026-11-08 12:00:00'), '下拉条目应含发送时间')
    for (const name of ['全部已读', '查看全部']) {
      assert(await pop.getByRole('button', { name }).isVisible(), `下拉底部应有「${name}」按钮`)
    }
    await shot(page, 'n4-popover.png')
    // 收起（trigger=click toggle），防遮挡后续点击
    await page.locator('.navbar-bell').click()
    await sleep(300)
  })

  // ================= N5 轮询弹框（30s 窗 + 限流 + 常驻 + 点击跳转） =================
  await step('N5', '轮询弹框：mock 态迁移（+8 条）→ 30s 轮询 → 恰弹最新 3 条（限流）+ 紧急常驻 + 点击跳转/已读', async () => {
    assertEq(await page.locator('.el-notification').count(), 0, '前置：当前无弹框')
    // EP notification pauseOnHover 默认开：鼠标停在右上角会暂停自动关闭计时器——
    // N4 收尾点击落在铃铛（弹框堆叠区），必须先把光标移到左下离开弹框区再等轮询
    await page.mouse.move(10, 400)
    // 态迁移：msgs 一次并入 8 条（latestId 2002→2010，unreadCount 2→10）
    msgs.push(...burstMsgs)
    log('  mock 态迁移完成（+8 条增量），等待下一轮 30s 轮询…')
    // 等下一轮轮询触发弹框（最坏 30s + 请求耗时；水位比较 BigInt > 0 才拉页）
    await page.locator('.el-notification').first().waitFor({ state: 'visible', timeout: 45000 })
    await sleep(800)
    // 限流断言：恰 3 条（2010/2009/2008），不含 2007 及更早
    const notes = page.locator('.el-notification')
    assertEq(await notes.count(), 3, `单轮增量 8 条应只弹最新 3 条，实际 ${await notes.count()}`)
    const noteTexts = []
    for (let i = 0; i < 3; i++) noteTexts.push((await notes.nth(i).innerText()).replace(/\n/g, ' '))
    const all = noteTexts.join('|')
    for (const t of ['e2e_增量消息10_普通', 'e2e_增量消息09_重要', 'e2e_增量消息08_紧急']) {
      assert(all.includes(t), `弹框应含「${t}」（最新 3 条），实际 "${all}"`)
    }
    assert(!all.includes('e2e_增量消息07'), `弹框不应含第 4 条及以后（限流），实际 "${all}"`)
    await shot(page, 'n5-popups.png')
    // 停留时序断言：普通 4.5s 自动关 / 重要 8s 自动关 / 紧急 0 常驻
    await sleep(5200)
    assertEq(await page.locator('.el-notification').count(), 2, '5.2s 后普通(info 4.5s)应已自动关闭，余重要+紧急')
    await sleep(3500)
    assertEq(await page.locator('.el-notification').count(), 1, '8.7s 后重要(warning 8s)应已自动关闭，余紧急')
    const urgent = page.locator('.el-notification', { hasText: 'e2e_增量消息08_紧急' })
    assertEq(await urgent.count(), 1, '紧急(error duration=0)应常驻不自动关闭')
    // 点击紧急通知：markRead 请求 + 关闭 + linkUrl 跳转 /notify/center
    const readsBefore = readCalls('2008')
    await urgent.first().click()
    await page.waitForURL(`**${CENTER_PATH}`, { timeout: 8000 })
    await urgent.first().waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})
    assertEq(readCalls('2008'), readsBefore + 1, `点击弹框应发出恰 1 次 PUT read/2008，实际 +${readCalls('2008') - readsBefore}`)
    assertEq(await waitBadge('9'), '9', '点击已读后角标应减 1（10-1）')
    await shot(page, 'n5-jump-center.png')
  })

  // ================= N6 消息中心 =================
  await step('N6', '消息中心：直链渲染（id 倒序）+ readFlag tab/type 过滤 + 单条已读 + 全部已读 + 角标归零', async () => {
    if (new URL(page.url()).pathname !== CENTER_PATH) {
      await page.goto(`${BASE}${CENTER_PATH}`, { waitUntil: 'domcontentloaded' })
    }
    await waitTableIdle(page)
    await sleep(500)
    assertEq((await h.breadcrumbTexts(page)).join('>'), '首页>消息中心', '消息中心面包屑（静态路由不进侧边菜单，面包屑恒两级）')
    // a. id 倒序渲染：首行 2010（未读加粗），含 2008 已读（N5 点击已读）
    const centerHeaders = await page.evaluate(() => Array.from(document.querySelectorAll('.el-table__header th .cell')).map((el) => el.textContent.trim()).filter(Boolean))
    for (const hd of ['标题', '消息类型', '级别', '已读', '发送时间', '操作']) {
      assert(centerHeaders.includes(hd), `消息中心表头缺「${hd}」，实际 ${JSON.stringify(centerHeaders)}`)
    }
    const firstRow = page.locator('.el-table__row').first()
    assert((await firstRow.innerText()).includes('e2e_增量消息10_普通'), '首行应为最新消息（id 倒序）')
    const read08 = page.locator('.el-table__row', { hasText: 'e2e_增量消息08_紧急' })
    assert((await read08.innerText()).includes('已读'), 'N5 点击弹框已读的 2008 行应显示已读')
    assertEq(await read08.getByRole('button', { name: '已读', exact: true }).count(), 0, '已读行不应再有「已读」按钮（幂等 UI 面）')
    assertEq(await read08.getByRole('button', { name: '跳转', exact: true }).count(), 1, 'linkUrl 有值行应有「跳转」按钮')
    await shot(page, 'n6-center-list.png')
    // b. readFlag tab：未读 → 全部行未读
    await page.locator('.el-radio-button', { hasText: '未读' }).click()
    await waitTableIdle(page)
    await sleep(400)
    const unreadRows = page.locator('.el-table__row')
    const unreadN = await unreadRows.count()
    assert(unreadN >= 1, '未读 tab 应有未读行')
    for (let i = 0; i < unreadN; i++) {
      const cells = await rowCells(unreadRows.nth(i))
      assertEq(cells[3], '未读', `未读 tab 第 ${i} 行应未读`)
    }
    // c. type 过滤叠加：type=公告 → 每行消息类型=公告
    await pickTypeFilter('公告')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await waitTableIdle(page)
    await sleep(400)
    const noticeRows = page.locator('.el-table__row')
    const noticeN = await noticeRows.count()
    assert(noticeN >= 1, 'type=公告过滤后应有行')
    for (let i = 0; i < noticeN; i++) {
      const cells = await rowCells(noticeRows.nth(i))
      assertEq(cells[1], '公告', `type 过滤后第 ${i} 行应为公告，实际 "${cells[1]}"`)
    }
    // d. 单条已读：未读 tab 下行离开集合 + 角标 9→8
    await page.locator('.el-radio-button', { hasText: '未读' }).click()
    await waitTableIdle(page)
    await sleep(400)
    const target = page.locator('.el-table__row', { hasText: 'e2e_增量消息10_普通' })
    await target.getByRole('button', { name: '已读', exact: true }).click()
    await waitTableIdle(page)
    await sleep(600)
    assertEq(await page.locator('.el-table__row', { hasText: 'e2e_增量消息10_普通' }).count(), 0, '已读后行应离开未读 tab 集合')
    assertEq(await waitBadge('8'), '8', '单条已读后角标应 9→8')
    // e. 全部已读：toast「已读 N 条」+ 角标归零 + 按钮转 disabled
    const readAllBtn = page.locator('.el-card .table-header button', { hasText: '全部已读' })
    await readAllBtn.click()
    await waitToast(page, '已读 8 条', 'success')
    await waitTableIdle(page)
    await sleep(600)
    const badge = page.locator('.navbar-bell .el-badge__content')
    assert(!(await badge.isVisible().catch(() => false)), '全部已读后角标应隐藏（unreadCount=0）')
    assert(!(await readAllBtn.isEnabled()), '全部已读后按钮应转 disabled')
    // f. 已读 tab 验证全部落已读（先重置清掉步骤 c 的 type=公告 过滤残留，否则叠加过滤只余 6 行）
    await page.getByRole('button', { name: '重置', exact: true }).click()
    await waitTableIdle(page)
    await sleep(400)
    await page.locator('.el-radio-button', { hasText: '已读' }).click()
    await waitTableIdle(page)
    await sleep(400)
    const doneRows = page.locator('.el-table__row')
    const doneN = await doneRows.count()
    assert(doneN >= 10, `已读 tab 应含全部消息（≥10），实际 ${doneN}`)
    for (let i = 0; i < doneN; i++) {
      const cells = await rowCells(doneRows.nth(i))
      assertEq(cells[3], '已读', `已读 tab 第 ${i} 行应已读`)
    }
    await shot(page, 'n6-readall-done.png')
  })

  // ================= 证据核验 =================
  await step('N-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
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
      `\n测试数据: e2e_ 前缀 + 时间戳 ${ts}（模板 code=${CODE}）全在 route mock 内存态，零真实后端写；admin 只登录不改`,
      'mock 面: page.route(**/api/**)——登录链(sso/system user-nav+me+logout) + notify 十端点 + user/role options + dashboard 空态全 fulfill 假 R；后端 gateway/sso/system 未启动亦可跑（notify 域后端由并行轨道实现，联调由主控集成段安排）',
      '断言口径: 契约 §1-§3（CRUD/3043/3045/发送三档/summary 水位/分页/已读幂等 UI 面）+ 计划 F3 弹框语义（30s 轮询/>3 限流 3 条/level 停留时序/点击已读+跳转）',
      '注记: 3046/3047（解析超长/收件人空）与登出停轮询不在本面（mock 自证覆盖 3047 分支；登出停轮询属 Layout 卸载语义，联调段人工复核）',
    ],
  })
  await browser.close()
}
