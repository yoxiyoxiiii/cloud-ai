/**
 * 数据字典管理 e2e（契约 2026-10-07-dict-api；D0-D4 + CLEANUP + D-VERIFY；harness 复用 lib/harness.mjs）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202（含 /system/dict/** 端点版本）已启动；前端 dev 5173 已启动
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 npm run e2e:dict）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码
 * 测试数据（删净纪律最高优先）：
 * - 类型 dictKey / 项 value 全部 e2e 前缀+时间戳；内置种子零放行触碰——SEED_KEYS 四类型（user_status/common_status
 *   翻译契约 §0.3/§8.3 + bpmn_approval_status/system_leave_type 契约 2026-10-08-approval-platform-api §7，is_builtin=1 自动落入 3015/3016 保护；
 *   Round I 审批平台化：-bpmn_leave_status/-bpmn_leave_type 种子行已 DELETE，+bpmn_approval_status（审批状态 4 项）/+system_leave_type（请假类型 3 项），总数 4 不变）；
 *   D5b 内置保护：user_status 行徽标/禁用面 + 直连 3015/3016 拒（契约 2026-10-07-builtin-protection §2，种子零变更；E2 断言迁移）
 * - 结束清扫全部 e2e 前缀类型（含 D5 的 e2econs；先删项后删类型，3011 禁删约束）并断言左表无 e2e 残留、残留 ⊇ SEED_KEYS（宽松，不锁上限）
 * 核心断言（契约 §2 §3 §5；界面重构后字典项管理在弹框内——2026-10-07-dict-ui-list-dialog plan D4）：
 * - D0 侧边 字典管理 位于 菜单管理 之后 + 面包屑 首页/字典管理 + admin 重登快照含 dict 权限（新增类型按钮可见）
 * - D1 全宽类型表：4 列/跨页行数 ≥ SEED_KEYS.length 且 seenKeys ⊇ SEED_KEYS（E1 候选②宽松语义——不锁上限，
 *   后续加内置字典种子只改 SEED_KEYS/SEED_TYPE_NAMES 两常量），各行带「内置」徽标+状态列正常；
 *   种子行"字典项"弹框：标题 `字典项：用户状态（user_status）`、5 列精确序、种子 2 行（正常/停用）、共 2 条（user_status 自身面，零改动）
 * - D2 类型闭环：空提交 0 请求 → 新增（提交恰三字段）→ 编辑改名+停用（全量三字段+id、tag danger）
 *   → 重开弹框标题跟随新名 → 同 dictKey 重提 3009 toast 弹窗保持
 * - D3 项闭环（全在弹框作用域）：空提交 0 请求 → 新增（提交恰五字段、typeId 对齐行类型）
 *   → 审计断言改页内 fetch（createBy/updateBy=admin、createTime 格式——UI 已减审计列，契约 VO 仍返回）
 *   → 同 value 重提 3012 toast 弹窗保持 → 编辑改 label/sort（全量五字段+id、弹框行内更新）
 * - D4 删除约束：有项删类型 3011 toast 行保留 → 弹框内删净项 → 删类型（确认框含类型名）→ 类型行消失
 * - D5 消费端点（契约 2026-10-07-translation-api §2.1/§8.3）：造 e2econs 类型+3 项（sort 3/1/2，1 项停用）→
 *   消费断言停用过滤/长度 2/sort 升序/字段恰 value-label-sort；未知 dictKey → 200 data:[]；
 *   无 token 直调网关 401；种子 user_status/common_status 消费回归各恰 2 项 + bpmn_approval_status 恰 4 项
 *   （value "0"-"3" 文案断言，契约 2026-10-08-approval-platform-api §7；CLEANUP 一并删 e2econs，种子零触碰）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
/** D5c 无 token 直调网关（绕过 /api 代理与页面事件采集——page.request 不触发 page.on('response')，不污染 D-VERIFY） */
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, rowCells } = h

// ---------- 测试数据（e2e 前缀+时间戳；字典域无种子，仅作用于本脚本数据） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const TEST_KEY = `e2edict${stamp}` // 类型 dictKey（唯一锚点）
const TEST_NAME = `E2E字典${stamp}`
const TEST_NAME_V2 = `E2E字典v2${stamp}` // 编辑改名后
const TEST_NAME_DUP = `E2E字典重复${stamp}` // 3009 探针（正常路径不落库）
const ITEM_VALUE = `e2e_on_${stamp}`
const ITEM_LABEL = `E2E启用${stamp}`
const ITEM_LABEL_V2 = `E2E启用v2${stamp}` // 编辑改 label 后
const ITEM_LABEL_B = `E2E重复${stamp}` // 3012 探针（正常路径不落库）
const CONS_KEY = `e2econs${stamp}` // D5 消费端点测试类型 dictKey（CLEANUP 随 e2e 前缀一并清扫）
const CONS_NAME = `E2E消费${stamp}`

/** 内置字典种子清单（E1 候选②宽松断言锚点；Round I 审批平台化迁移后 4 键）：后续加内置字典种子只改这两行——
 *  D1/CLEANUP 的 ⊇ 断言随之覆盖新键；互指义务：菜单侧种子清单见 run-menu-e2e.mjs / run-role-e2e.mjs 的 SEED_MENU_IDS（改种子段时 grep 各脚本） */
const SEED_KEYS = ['user_status', 'common_status', 'bpmn_approval_status', 'system_leave_type']
const SEED_TYPE_NAMES = { user_status: '用户状态', common_status: '通用状态', bpmn_approval_status: '审批状态', system_leave_type: '请假类型' }

const DICT_PATH = '/system/dict'

// ---------- 主流程（默认有头 + slowMo 300，与其余五脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

/** 非 /api/ 资产的 404 清单（favicon 环境噪音甄别用，M-VERIFY 同款） */
const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

/** waitForResponse 的 URL 匹配：r.url() 是含 origin 的完整地址，须比 pathname */
const apiPath = (url, pathname) => new URL(url).pathname === pathname

/** 类型 POST 计数（D2 空提交 0 请求断言用） */
const typePostCount = () => h.state.apiCalls.filter((c) => c.url === '/api/system/dict/type' && c.method === 'POST').length
/** 项 POST 计数（D3 空提交 0 请求断言用） */
const dataPostCount = () => h.state.apiCalls.filter((c) => c.url === '/api/system/dict/data' && c.method === 'POST').length

// ---------- 面板 scoped 助手（一页双表，harness 通用找行不能直接用） ----------

/** 打开字典页并等左表首屏落定（返回类型分页响应 promise 的等待句柄由调用方自行 waitForResponse） */
async function loadDictPage() {
  const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/type/page'), { timeout: 15000 })
  await page.goto(`${BASE}${DICT_PATH}`, { waitUntil: 'domcontentloaded' })
  await respP
  await waitTableIdle(page)
  await sleep(300)
}

/** 左表（类型）按 dictKey 列精确找行：跨页全量检索（新增 id 倒序置顶，残留可能在任意页） */
async function findTypeRowByKey(dictKey, { reload = true } = {}) {
  if (reload) await loadDictPage()
  for (let guard = 0; guard < 30; guard++) {
    const rows = page.locator('.type-pane .el-table__row')
    const n = await rows.count()
    for (let i = 0; i < n; i++) {
      const cells = await rowCells(rows.nth(i))
      if (cells[1] === dictKey) return rows.nth(i)
    }
    const next = page.locator('.type-pane .el-pagination .btn-next')
    if ((await next.count()) === 0 || !(await next.isEnabled())) return null
    await next.click()
    await waitTableIdle(page)
    await sleep(300)
  }
  return null
}

/** 跨页扫描类型表：就地断言每个内置种子行（名称徽标剥离后/状态列 正常/内联徽标存在），返回跨页总行数与命中的种子键——
 *  id 倒序下新类型置顶、种子可能落在任意页，须整表走查（E1 候选②：只断言 ⊇ 与下限，不锁上限） */
async function scanSeedTypeRows() {
  let total = 0
  const seen = []
  for (let guard = 0; guard < 30; guard++) {
    const rows = page.locator('.type-pane .el-table__row')
    const n = await rows.count()
    total += n
    for (let i = 0; i < n; i++) {
      const cells = await rowCells(rows.nth(i))
      if (!SEED_TYPE_NAMES[cells[1]]) continue
      seen.push(cells[1])
      log(`  种子行[${cells[1]}]: ${JSON.stringify(cells)}`)
      assertEq(stripBadge(cells[0]), SEED_TYPE_NAMES[cells[1]], `种子行 ${cells[1]} 字典名称应为 ${SEED_TYPE_NAMES[cells[1]]}（徽标剥离后）`)
      assertEq(cells[2], '正常', `种子行 ${cells[1]} 状态列应为 正常（statusLabel 译文/降级链同文案）`)
      assert((await rows.nth(i).locator('.builtin-badge').count()) === 1, `种子行 ${cells[1]} 名称格应有内联「内置」徽标（el-tag）`)
    }
    const next = page.locator('.type-pane .el-pagination .btn-next')
    if ((await next.count()) === 0 || !(await next.isEnabled())) break
    await next.click()
    await waitTableIdle(page)
    await sleep(300)
  }
  return { total, seen }
}

/** 宽松断言套件（E1 候选②）：跨页行数 ≥ SEED_KEYS.length 且 seenKeys ⊇ SEED_KEYS——后续加内置字典种子零适配 */
async function assertSeedSuperset(where) {
  const { total, seen } = await scanSeedTypeRows()
  assert(total >= SEED_KEYS.length, `${where}：类型表跨页行数应 ≥ ${SEED_KEYS.length}，实际 ${total}`)
  const missing = SEED_KEYS.filter((k) => !seen.includes(k))
  assertEq(missing.length, 0, `${where}：seenKeys 应 ⊇ SEED_KEYS，缺 ${JSON.stringify(missing)}，实际 ${JSON.stringify(seen)}`)
  return seen
}

/** 字典项管理弹框定位器（标题以 "字典项：" 开头，与二层表单弹框"新增/编辑字典项"无子串冲突；
 *  el-dialog 关闭是 display:none 留存 DOM——断言前先确认 visible，必要时重建定位器） */
const dataDialog = () => page.locator('.el-dialog').filter({ has: page.locator('.el-dialog__title', { hasText: '字典项：' }) }).last()

/** 按标题文本定位弹框（表单弹框 append-to-body 在 body 尾部，.last() 兜底多节点留存） */
const dialogByTitle = (title) =>
  page.locator('.el-dialog').filter({ has: page.locator('.el-dialog__title', { hasText: title }) }).last()

/** 弹框标题文案（联动断言锚点：字典项：{名}（{键}）） */
async function dataDialogTitle() {
  return (await dataDialog().locator('.el-dialog__title').innerText()).trim()
}

/** 点击类型行"字典项"按钮打开弹框，并等首屏数据加载完成（data/page 第 1 页）；返回弹框定位器 */
async function openDataDialog(row) {
  const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/data/page'), { timeout: 15000 })
  await row.getByRole('button', { name: '字典项' }).click()
  await dataDialog().waitFor({ state: 'visible', timeout: 8000 })
  await respP
  await waitTableIdle(page)
  await sleep(300)
  return dataDialog()
}

/** 关闭字典项弹框（点头部 X）并等隐藏（EP 关闭是 display:none 而非移除 DOM） */
async function closeDataDialog() {
  await dataDialog().locator('.el-dialog__headerbtn').click()
  await dataDialog().waitFor({ state: 'hidden', timeout: 8000 })
  await sleep(300)
}

/** 弹窗内收集全部校验错误文本 */
async function formErrors(dlg) {
  const errs = dlg.locator('.el-form-item__error')
  const texts = []
  const n = await errs.count()
  for (let i = 0; i < n; i++) texts.push((await errs.nth(i).innerText()).trim())
  return texts
}

/** 删除确认框：断言文案含 expected 后点确定（返回完整文案供日志） */
async function confirmDelete(expected) {
  const box = page.locator('.el-message-box')
  await box.waitFor({ state: 'visible', timeout: 8000 })
  const boxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
  assert(boxText.includes(expected), `确认框文案应含 "${expected}"，实际 "${boxText}"`)
  await box.locator('.el-message-box__btns .el-button--primary').click()
  return boxText
}

/** 直连网关小助手（E2 通用模式）：page.evaluate 取 localStorage token + page.request + Bearer——
 *  不入 page 网络统计（不污染 D-VERIFY，N5/D5c 先例）；请求体中文经 Node UTF-8 无 GBK 陷阱 */
async function directApi(method, path, data) {
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken)
  const res = await page.request.fetch(`${GATEWAY}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    data: data === undefined ? undefined : JSON.stringify(data),
  })
  return { httpStatus: res.status(), body: await res.json() }
}

/** 名称格内联徽标（el-tag「内置」）使 innerText 变 "{name}\n内置"（tag 独立成行）——比对前统一剥离尾缀并 trim（E2 通用模式） */
const stripBadge = (s) => s.replace(/\s*内置\s*$/, '').trim()

/** 删净弹框内当前类型的全部字典项（逐行首项删，含确认框文案断言；页大小 10 场景足够） */
async function deleteAllDataItems() {
  const dlg = dataDialog()
  for (let guard = 0; guard < 100; guard++) {
    const rows = dlg.locator('.el-table__row')
    if ((await rows.count()) === 0) {
      const total = (await dlg.locator('.el-pagination__total').innerText()).trim()
      if (total.includes('共 0 条')) return
      // 行未渲染完（翻页边界）：稍候重试
      await sleep(400)
      if ((await rows.count()) === 0) return
    }
    const cells = await rowCells(rows.first())
    await rows.first().getByRole('button', { name: '删除' }).click()
    await confirmDelete(`确定删除字典项 "${cells[0]}"`)
    await waitToast(page, '删除成功')
    await waitTableIdle(page)
    await sleep(300)
  }
  throw new Error('删净字典项超过护栏轮数（100）')
}

try {
  // ================= D0 前置：无 token 直访被拦 → admin 登录回跳 + 侧边序 + 面包屑 =================
  await step('D0', '无 token 直访 /system/dict → 拦截跳登录（带 redirect）→ admin 登录回跳 + 侧边 字典管理 在 菜单管理 后', async () => {
    await page.goto(`${BASE}${DICT_PATH}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForURL('**/login**', { timeout: 10000 })
    const url = new URL(page.url())
    assertEq(url.searchParams.get('redirect'), DICT_PATH, 'redirect 参数应为 /system/dict')
    // admin 重登 = 全新权限快照（含 system:dict:*，按钮级权限可见性即快照证据）
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
    await page.waitForURL(`**${DICT_PATH}`, { timeout: 15000 })
    await waitTableIdle(page)
    log(`  登录回跳: ${page.url()}`)
    // 侧边菜单顺序（动态路由种子：系统管理 4 项 + 流程管理 4 项 + 工作台——Round I 审批平台化 34 段菜单种子后新形态，E1 迁移）
    const items = page.locator('.el-menu .el-menu-item')
    const n = await items.count()
    const labels = []
    for (let i = 0; i < n; i++) labels.push((await items.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,字典管理,请假申请,我的审批,待办任务,流程定义,工作台', '侧边菜单顺序应为 用户管理→角色管理→菜单管理→字典管理→请假申请→我的审批→待办任务→流程定义→工作台')
    const active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '字典管理', '/system/dict 下字典管理应高亮')
    const bc = await breadcrumbTexts(page)
    log(`  面包屑: ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/字典管理', '面包屑应为 首页/字典管理')
    // 新权限已进登录快照（v-perms 未摘除按钮）
    assertEq(await page.locator('.type-pane .pane-header button', { hasText: '新增类型' }).count(), 1, '左栏"新增类型"按钮应可见（admin 快照含 system:dict:add）')
    await shot(page, 'd0-enter.png')
  })

  // ================= D1 全宽类型表 + 种子行"字典项"弹框 =================
  await step('D1', '全宽类型表：4 列 + 内置种子行 ⊇ SEED_KEYS（4 类型，均带徽标+状态正常，宽松不锁上限）；种子行弹框：标题/5 列精确序/种子 2 行（正常/停用）/共 2 条', async () => {
    // 类型表 4 列精确序
    const ths = page.locator('.type-pane .el-table__header-wrapper th')
    const tn = await ths.count()
    const headers = []
    for (let i = 0; i < tn; i++) headers.push(((await ths.nth(i).innerText()) || '').trim())
    log(`  类型表头(${tn}): ${JSON.stringify(headers)}`)
    assertEq(headers.join(','), '字典名称,字典键,状态,操作', `类型表头应为 4 列精确序，实际 ${JSON.stringify(headers)}`)
    // 类型表种子行（E1 候选②迁移）：跨页行数 ≥ SEED_KEYS.length 且 seenKeys ⊇ SEED_KEYS——
    // scanSeedTypeRows 就地断言各行徽标与状态列「正常」、名称逐键锁定（审批两类型契约 approval-platform-api §7）
    const seenKeys = await assertSeedSuperset('D1')
    const leftTotal = (await page.locator('.type-pane .el-pagination__total').innerText()).trim()
    log(`  类型表分页: ${leftTotal}`)
    const leftTotalN = parseInt((leftTotal.match(/\d+/) || ['0'])[0], 10)
    assert(leftTotalN >= SEED_KEYS.length, `类型表分页总数应 ≥ 共 ${SEED_KEYS.length} 条（宽松不锁上限），实际 "${leftTotal}"`)
    // 种子行"字典项"弹框：标题（原右栏标题格式平移）+ 5 列精确序 + 种子 2 项 + 共 2 条
    // （user_status 自身面零改动；scanSeedTypeRows 走查后可能停在后页，reload 回第 1 页再跨页定位）
    const userStatusRow = await findTypeRowByKey('user_status')
    assert(userStatusRow, '应能定位 user_status 种子类型行（弹框场景载体）')
    const dlg = await openDataDialog(userStatusRow)
    assertEq(await dataDialogTitle(), '字典项：用户状态（user_status）', `弹框标题应为 字典项：用户状态（user_status），实际 "${await dataDialogTitle()}"`)
    const dhs = dlg.locator('.el-table__header-wrapper th')
    const dn = await dhs.count()
    const dHeaders = []
    for (let i = 0; i < dn; i++) dHeaders.push(((await dhs.nth(i).innerText()) || '').trim())
    log(`  弹框表头(${dn}): ${JSON.stringify(dHeaders)}`)
    assertEq(dHeaders.join(','), '标签,值,排序,状态,操作', `弹框表头应为 5 列精确序（审计 4 列已减），实际 ${JSON.stringify(dHeaders)}`)
    const itemRows = dlg.locator('.el-table__row')
    assertEq(await itemRows.count(), 2, `种子弹框应恰 2 行（正常/停用），实际 ${await itemRows.count()}`)
    const itemCellsA = await rowCells(itemRows.nth(0))
    const itemCellsB = await rowCells(itemRows.nth(1))
    log(`  种子项行: ${JSON.stringify(itemCellsA)} / ${JSON.stringify(itemCellsB)}`)
    assertEq(stripBadge(itemCellsA[0]), '正常', '种子第 1 行标签应为 正常（sort 1，内联「内置」徽标剥离后）')
    assertEq(stripBadge(itemCellsB[0]), '停用', '种子第 2 行标签应为 停用（sort 2，徽标剥离后）')
    const dlgTotal = (await dlg.locator('.el-pagination__total').innerText()).trim()
    assertEq(dlgTotal, '共 2 条', `弹框分页应为 共 2 条，实际 "${dlgTotal}"`)
    await shot(page, 'd1-seed-dialog.png')
    await closeDataDialog()
  })

  // ================= D2 类型闭环：空提交 → 新增选中联动 → 编辑 → 3009 =================
  await step('D2', '类型闭环：空提交必填错误（0 请求）→ 新增（三字段）→ 编辑改名停用 → 重开弹框标题跟随新名 → 同键 3009 弹窗保持', async () => {
    log(`  测试类型: ${TEST_NAME}（dictKey ${TEST_KEY}）`)
    // ---- 2a. 空提交：必填错误 + 0 请求 ----
    await page.locator('.type-pane .pane-header button', { hasText: '新增类型' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增字典类型' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await shot(page, 'd2-type-dialog.png')
    const before = typePostCount()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    const errs = await formErrors(dlg)
    log(`  空提交错误: ${JSON.stringify(errs)}`)
    assert(errs.includes('请输入字典名称'), `空提交应报"请输入字典名称"，实际 ${JSON.stringify(errs)}`)
    assert(errs.includes('请输入字典键'), `空提交应报"请输入字典键"，实际 ${JSON.stringify(errs)}`)
    assertEq(typePostCount(), before, '空提交不应发出新增类型请求')
    // ---- 2b. 填表提交：恰三字段 + id 倒序置顶 + 点击选中联动 ----
    await dlg.locator('input[placeholder="请输入字典名称，如：用户状态"]').fill(TEST_NAME)
    await dlg.locator('input[placeholder="字母开头，如：user_status"]').fill(TEST_KEY)
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/type') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    const reqBody = resp.request().postDataJSON()
    log(`  新增类型接口: HTTP ${resp.status()} code=${body.code} data=${body.data}`)
    log(`  提交体: ${JSON.stringify(reqBody)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '新增业务码应为 200')
    assert(typeof body.data === 'string' && body.data.length > 0, '新增响应应返回新类型 id（字符串，Long→String）')
    const typeId = body.data
    assertEq(Object.keys(reqBody).sort().join(','), 'dictKey,dictName,status', `新增应恰提交三字段，实际 ${JSON.stringify(reqBody)}`)
    assertEq(reqBody.status, 0, '新增默认状态应为 0（正常）')
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增字典类型')
    // 新行 id 倒序在第 1 页置顶：等重渲染后的 DOM 出现新行（勿 goto——会丢页面内存态）
    await page.locator('.type-pane .el-table__row', { hasText: TEST_KEY }).first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    const row = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert(row, `左表应出现新类型行 ${TEST_KEY}`)
    const cells = await rowCells(row)
    log(`  新类型行: ${JSON.stringify(cells)}`)
    assertEq(cells[0], TEST_NAME, '新行字典名称应为提交值')
    assertEq(cells[2], '正常', '新行状态应为 正常')
    await shot(page, 'd2-type-created.png')
    // ---- 2c. 编辑：回显 → 改名 + 停用 → 全量三字段+id → 行内更新 + 弹框标题跟随新名 ----
    await row.getByRole('button', { name: '编辑' }).click()
    const editDlg = page.locator('.el-dialog', { hasText: '编辑字典类型' }).last()
    await editDlg.waitFor({ state: 'visible', timeout: 8000 })
    assertEq(await editDlg.locator('input[placeholder="请输入字典名称，如：用户状态"]').inputValue(), TEST_NAME, '编辑应回显原字典名称')
    assertEq(await editDlg.locator('input[placeholder="字母开头，如：user_status"]').inputValue(), TEST_KEY, '编辑应回显原字典键（dictKey 可改非锁定）')
    await editDlg.locator('input[placeholder="请输入字典名称，如：用户状态"]').fill(TEST_NAME_V2)
    await editDlg.locator('.el-radio', { hasText: '停用' }).click()
    const putP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/type') && r.request().method() === 'PUT', { timeout: 15000 })
    await editDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const put = await putP
    const putBody = await put.json()
    const putReq = put.request().postDataJSON()
    log(`  编辑类型接口: HTTP ${put.status()} code=${putBody.code}`)
    log(`  编辑提交体: ${JSON.stringify(putReq)}`)
    assertEq(put.status(), 200, '契约：HTTP 恒 200')
    assertEq(putBody.code, 200, '编辑业务码应为 200')
    assertEq(Object.keys(putReq).sort().join(','), 'dictKey,dictName,id,status', `编辑应全量提交三字段+id，实际 ${JSON.stringify(putReq)}`)
    assertEq(putReq.dictName, TEST_NAME_V2, '编辑提交 dictName 应为新名')
    assertEq(putReq.status, 1, '编辑提交 status 应为 1（停用）')
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑字典类型')
    // loadTypePage(当前页) 原地刷新：等行内出现新名（勿 goto——保持行定位器与页面内存态，2e 重开弹框断言标题跟随）
    await page.locator('.type-pane .el-table__row', { hasText: TEST_NAME_V2 }).first().waitFor({ state: 'visible', timeout: 10000 })
    await waitTableIdle(page)
    await sleep(300)
    const rowV2 = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert(rowV2, '编辑后应仍能按 dictKey 定位类型行')
    const cellsV2 = await rowCells(rowV2)
    log(`  编辑后类型行: ${JSON.stringify(cellsV2)}`)
    assertEq(cellsV2[0], TEST_NAME_V2, '行内字典名称应更新为新名')
    assertEq(cellsV2[2], '停用', '行内状态应更新为 停用')
    const statusTag = rowV2.locator('.el-tag').first()
    assert(((await statusTag.getAttribute('class')) || '').includes('el-tag--danger'), `停用状态 tag 应为 danger，实际 ${await statusTag.getAttribute('class')}`)
    // 联动价值平移：重开"字典项"弹框断言标题跟随新名（原右栏标题联动断言的替代）
    await openDataDialog(rowV2)
    assertEq(await dataDialogTitle(), `字典项：${TEST_NAME_V2}（${TEST_KEY}）`, '弹框标题应跟随新字典名')
    await shot(page, 'd2-type-edited.png')
    await closeDataDialog()
    // ---- 2d. 同 dictKey 再新增 → 3009 toast + 弹窗保持打开 ----
    await page.locator('.type-pane .pane-header button', { hasText: '新增类型' }).click()
    const dupDlg = page.locator('.el-dialog', { hasText: '新增字典类型' }).last()
    await dupDlg.waitFor({ state: 'visible', timeout: 8000 })
    await dupDlg.locator('input[placeholder="请输入字典名称，如：用户状态"]').fill(TEST_NAME_DUP)
    await dupDlg.locator('input[placeholder="字母开头，如：user_status"]').fill(TEST_KEY)
    const dupP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/type') && r.request().method() === 'POST', { timeout: 15000 })
    await dupDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const dup = await dupP
    const dupBody = await dup.json()
    log(`  重复 dictKey 接口: HTTP ${dup.status()} code=${dupBody.code} msg="${dupBody.msg}"`)
    assertEq(dup.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(dupBody.code, 3009, `同 dictKey 新增应 body 3009，实际 ${dupBody.code}`)
    const toastText = await waitToast(page, '字典键已存在', 'error')
    log(`  3009 toast: "${toastText}"`)
    assert(await dupDlg.isVisible(), '3009 后弹窗应保持打开（可改后重提）')
    await dupDlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '新增字典类型')
    const dupRow = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert((await rowCells(dupRow))[0] === TEST_NAME_V2, '3009 拦截后原类型行应保持不变')
  })

  // ================= D3 项闭环（弹框作用域）：空提交 → 新增 → 3012 → 编辑 =================
  await step('D3', '项闭环：空提交必填错误（0 请求）→ 新增（五字段、typeId 对齐）→ 同 value 3012 弹窗保持 → 编辑 label/sort 弹框行内更新', async () => {
    // ---- 3a. 打开"字典项"弹框 → 新增表单空提交：必填错误 + 0 请求 ----
    const typeRow = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert(typeRow, '应能定位测试类型行')
    const dlg = await openDataDialog(typeRow)
    assertEq(await dataDialogTitle(), `字典项：${TEST_NAME_V2}（${TEST_KEY}）`, '弹框标题应为 字典项：{名}（{键}）')
    await dlg.getByRole('button', { name: '新增字典项' }).click()
    const formDlg = dialogByTitle('新增字典项')
    await formDlg.waitFor({ state: 'visible', timeout: 8000 })
    await shot(page, 'd3-data-dialog.png')
    const before = dataPostCount()
    await formDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    const errs = await formErrors(formDlg)
    log(`  空提交错误: ${JSON.stringify(errs)}`)
    assert(errs.includes('请输入标签'), `空提交应报"请输入标签"，实际 ${JSON.stringify(errs)}`)
    assert(errs.includes('请输入字典值'), `空提交应报"请输入字典值"，实际 ${JSON.stringify(errs)}`)
    assertEq(dataPostCount(), before, '空提交不应发出新增字典项请求')
    // ---- 3b. 填表提交：恰五字段（typeId 注入行类型）→ 弹框内行出现 ----
    await formDlg.locator('input[placeholder="请输入展示标签，如：启用"]').fill(ITEM_LABEL)
    await formDlg.locator('input[placeholder="存库值，如：0"]').fill(ITEM_VALUE)
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/data') && r.request().method() === 'POST', { timeout: 15000 })
    await formDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    const reqBody = resp.request().postDataJSON()
    log(`  新增字典项接口: HTTP ${resp.status()} code=${body.code} data=${body.data}`)
    log(`  提交体: ${JSON.stringify(reqBody)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '新增业务码应为 200')
    assert(typeof body.data === 'string' && body.data.length > 0, '新增响应应返回新项 id（字符串）')
    assertEq(Object.keys(reqBody).sort().join(','), 'label,sort,status,typeId,value', `新增应恰提交五字段，实际 ${JSON.stringify(reqBody)}`)
    assert(typeof reqBody.typeId === 'string' && reqBody.typeId.length > 0, `typeId 应为非空字符串（Long→String），实际 ${JSON.stringify(reqBody.typeId)}`)
    assertEq(reqBody.sort, 0, '排序默认应为 0')
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增字典项')
    await waitTableIdle(page)
    await sleep(300)
    // 弹框内行出现（5 列精简：标签/值/排序/状态/操作——审计列已减）
    const rows = dlg.locator('.el-table__row')
    assertEq(await rows.count(), 1, `弹框内应恰 1 行，实际 ${await rows.count()}`)
    const cells = await rowCells(rows.first())
    log(`  新字典项行: ${JSON.stringify(cells)}`)
    assertEq(cells[0], ITEM_LABEL, '行内标签应为提交值')
    assertEq(cells[1], ITEM_VALUE, '行内值应为提交值')
    assertEq(cells[2], '0', '行内排序应为 0')
    assertEq(cells[3], '正常', '行内状态应为 正常')
    // ---- 3b-audit. 审计断言改页内 fetch（UI 已减审计列，契约 §4.2 VO 仍返回——黑盒锁定平移） ----
    const audited = await page.evaluate(async (typeId) => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch(`/api/system/dict/data/page?typeId=${typeId}&pageNum=1&pageSize=10`, { headers: { Authorization: `Bearer ${token}` } })
      return await res.json()
    }, reqBody.typeId)
    assertEq(audited.code, 200, '项分页 fetch 业务码应为 200')
    assertEq(audited.data.rows.length, 1, `fetch 应恰 1 行（当前类型仅新增的 1 项），实际 ${audited.data.rows.length}`)
    log(`  审计 fetch: createBy=${audited.data.rows[0].createBy} updateBy=${audited.data.rows[0].updateBy} createTime=${audited.data.rows[0].createTime}`)
    assertEq(audited.data.rows[0].createBy, 'admin', '创建人应为 admin（审计透传，契约 VO 仍返回）')
    assertEq(audited.data.rows[0].updateBy, 'admin', '更新人应为 admin（插入时 update 值 = create 值）')
    assert(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(audited.data.rows[0].createTime), `创建时间应为 yyyy-MM-dd HH:mm:ss，实际 "${audited.data.rows[0].createTime}"`)
    // ---- 3c. 同 value 再新增 → 3012 toast + 二层弹窗保持 ----
    await dlg.getByRole('button', { name: '新增字典项' }).click()
    const dupDlg = dialogByTitle('新增字典项')
    await dupDlg.waitFor({ state: 'visible', timeout: 8000 })
    await dupDlg.locator('input[placeholder="请输入展示标签，如：启用"]').fill(ITEM_LABEL_B)
    await dupDlg.locator('input[placeholder="存库值，如：0"]').fill(ITEM_VALUE)
    const dupP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/data') && r.request().method() === 'POST', { timeout: 15000 })
    await dupDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const dup = await dupP
    const dupBody = await dup.json()
    log(`  重复 value 接口: HTTP ${dup.status()} code=${dupBody.code} msg="${dupBody.msg}"`)
    assertEq(dup.status(), 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(dupBody.code, 3012, `同 value 新增应 body 3012，实际 ${dupBody.code}`)
    const toastText = await waitToast(page, '字典项值已存在', 'error')
    log(`  3012 toast: "${toastText}"`)
    assert(await dupDlg.isVisible(), '3012 后弹窗应保持打开（可改后重提）')
    await dupDlg.locator('.el-dialog__footer button', { hasText: '取消' }).click()
    await waitDialogGone(page, '新增字典项')
    assertEq(await dlg.locator('.el-table__row').count(), 1, '3012 拦截后弹框内应仍恰 1 行')
    // ---- 3d. 编辑：回显 → 改 label + sort=5 → 全量五字段+id → 弹框行内更新 ----
    await rows.first().getByRole('button', { name: '编辑' }).click()
    const editDlg = dialogByTitle('编辑字典项')
    await editDlg.waitFor({ state: 'visible', timeout: 8000 })
    assertEq(await editDlg.locator('input[placeholder="请输入展示标签，如：启用"]').inputValue(), ITEM_LABEL, '编辑应回显原标签')
    assertEq(await editDlg.locator('input[placeholder="存库值，如：0"]').inputValue(), ITEM_VALUE, '编辑应回显原字典值')
    await editDlg.locator('input[placeholder="请输入展示标签，如：启用"]').fill(ITEM_LABEL_V2)
    await editDlg.locator('.el-input-number input').fill('5')
    const putP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/dict/data') && r.request().method() === 'PUT', { timeout: 15000 })
    await editDlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const put = await putP
    const putBody = await put.json()
    const putReq = put.request().postDataJSON()
    log(`  编辑字典项接口: HTTP ${put.status()} code=${putBody.code}`)
    log(`  编辑提交体: ${JSON.stringify(putReq)}`)
    assertEq(put.status(), 200, '契约：HTTP 恒 200')
    assertEq(putBody.code, 200, '编辑业务码应为 200')
    assertEq(Object.keys(putReq).sort().join(','), 'id,label,sort,status,typeId,value', `编辑应全量提交五字段+id，实际 ${JSON.stringify(putReq)}`)
    assertEq(putReq.label, ITEM_LABEL_V2, '编辑提交 label 应为新标签')
    assertEq(putReq.sort, 5, '编辑提交 sort 应为 5')
    assertEq(putReq.typeId, reqBody.typeId, '编辑提交 typeId 应与新增一致（归属不变）')
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑字典项')
    await waitTableIdle(page)
    await sleep(300)
    const cellsV2 = await rowCells(rows.first())
    log(`  编辑后字典项行: ${JSON.stringify(cellsV2)}`)
    assertEq(cellsV2[0], ITEM_LABEL_V2, '行内标签应更新为新标签')
    assertEq(cellsV2[2], '5', '行内排序应更新为 5')
    // 编辑后审计 fetch（更新人/更新时间——原 UI 列断言的平移）
    const auditedV2 = await page.evaluate(async (typeId) => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch(`/api/system/dict/data/page?typeId=${typeId}&pageNum=1&pageSize=10`, { headers: { Authorization: `Bearer ${token}` } })
      return await res.json()
    }, reqBody.typeId)
    assertEq(auditedV2.data.rows[0].updateBy, 'admin', `编辑后更新人应为 admin（审计透传），实际 "${auditedV2.data.rows[0].updateBy}"`)
    assert(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(auditedV2.data.rows[0].updateTime), `更新时间应为 yyyy-MM-dd HH:mm:ss，实际 "${auditedV2.data.rows[0].updateTime}"`)
    await shot(page, 'd3-item-edited.png')
    // 弹框留着开：D4 删净项直接在弹框内继续（删类型按钮在弹框外，先关再操作）
  })

  // ================= D4 删除约束：3011 禁删 → 弹框内删净项 → 删类型 → 行消失 =================
  await step('D4', '删除约束：有项删类型 3011 toast 行保留 → 弹框内删净字典项 → 删类型（确认框含类型名）→ 类型行消失', async () => {
    // ---- 4a. 有项删类型 → 3011（先关 D3 留开的弹框——删类型按钮在弹框外，modal 遮罩挡行操作） ----
    await closeDataDialog()
    const row = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert(row, '应能定位测试类型行')
    await row.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const boxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
    log(`  删除类型确认框: ${boxText}`)
    assert(boxText.includes(`确定删除字典类型 "${TEST_NAME_V2}"`), `确认框文案应含 确定删除字典类型 "${TEST_NAME_V2}"，实际 "${boxText}"`)
    await shot(page, 'd4-delete-confirm.png')
    const delP = page.waitForResponse((r) => r.request().method() === 'DELETE' && new URL(r.url()).pathname.startsWith('/api/system/dict/type/'), { timeout: 15000 })
    await box.locator('.el-message-box__btns .el-button--primary').click()
    const del = await delP
    const delBody = await del.json()
    log(`  删除类型接口: HTTP ${del.status()} code=${delBody.code} msg="${delBody.msg}"`)
    assertEq(del.status(), 200, '契约：HTTP 恒 200（3011 在 body）')
    assertEq(delBody.code, 3011, `有项删类型应 body 3011，实际 ${delBody.code}`)
    const toastText = await waitToast(page, '先删除字典项', 'error')
    log(`  3011 toast: "${toastText}"`)
    await shot(page, 'd4-3011-toast.png')
    // 3011 行保留复查原地（reload:false，页面内存态无选中语义可丢）
    const still = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert(still !== null, '3011 拦截后类型行应保留（无级联删除）')
    log('  3011 行保留 ✔')
    // ---- 4b. 弹框内删净字典项（确认框逐项含标签） ----
    const dlg = await openDataDialog(still)
    await deleteAllDataItems()
    assertEq(await dlg.locator('.el-table__row').count(), 0, '弹框内字典项应已删净')
    log('  字典项删净 ✔')
    await closeDataDialog()
    // ---- 4c. 删类型成功 → 类型行消失 ----
    const rowAgain = await findTypeRowByKey(TEST_KEY, { reload: false })
    assert(rowAgain, '删净项后应仍能定位类型行')
    await rowAgain.getByRole('button', { name: '删除' }).click()
    const box2 = page.locator('.el-message-box')
    await box2.waitFor({ state: 'visible', timeout: 8000 })
    const box2Text = (await box2.innerText()).trim().replace(/\n/g, ' | ')
    assert(box2Text.includes(`确定删除字典类型 "${TEST_NAME_V2}"`), `确认框文案应含类型名，实际 "${box2Text}"`)
    const delP2 = page.waitForResponse((r) => r.request().method() === 'DELETE' && new URL(r.url()).pathname.startsWith('/api/system/dict/type/'), { timeout: 15000 })
    await box2.locator('.el-message-box__btns .el-button--primary').click()
    const del2 = await delP2
    const del2Body = await del2.json()
    log(`  删除类型接口: HTTP ${del2.status()} code=${del2Body.code}`)
    assertEq(del2Body.code, 200, '删净项后删类型业务码应为 200')
    await waitToast(page, '删除成功')
    // 类型表 loadTypePage 原地刷新：行消失即可（主从右栏语义已不存在）
    await page.locator('.type-pane .el-table__row', { hasText: TEST_KEY }).first().waitFor({ state: 'hidden', timeout: 10000 })
    await sleep(500)
    assertEq(await page.locator('.type-pane .el-table__row', { hasText: TEST_KEY }).count(), 0, `删除后类型表不应再有 ${TEST_KEY}`)
    await shot(page, 'd4-after-type-delete.png')
  })

  // ================= D5 消费端点（契约 2026-10-07-translation-api §2.1，D4 后 CLEANUP 前） =================
  await step('D5', '消费端点：e2econs 停用过滤 + sort 升序 + 字段恰 value/label/sort；未知键 200 空数组；无 token 直调网关 401；种子 user_status/common_status 各 2 项 + bpmn_approval_status 恰 4 项', async () => {
    // ---- 5a. 造数（页内 fetch，admin 会话）：类型 + 3 项（sort 3/1/2 乱序，其中 1 项停用）----
    const made = await page.evaluate(async (args) => {
      const { dictName, dictKey } = args
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const H = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
      const typeRes = await fetch('/api/system/dict/type', { method: 'POST', headers: H, body: JSON.stringify({ dictName, dictKey, status: 0 }) })
      const typeBody = await typeRes.json()
      if (typeBody.code !== 200) return { error: `新增类型 code=${typeBody.code} msg=${typeBody.msg}` }
      const typeId = typeBody.data
      const items = [
        { label: '消费甲', value: 'a', sort: 3, status: 0 },
        { label: '消费乙', value: 'b', sort: 1, status: 0 },
        { label: '消费丙', value: 'c', sort: 2, status: 1 }, // 停用项：消费口径应过滤
      ]
      const madeItems = []
      for (const it of items) {
        const res = await fetch('/api/system/dict/data', { method: 'POST', headers: H, body: JSON.stringify({ typeId, ...it }) })
        const body = await res.json()
        madeItems.push({ value: it.value, sort: it.sort, status: it.status, code: body.code })
      }
      return { typeId, madeItems }
    }, { dictName: CONS_NAME, dictKey: CONS_KEY })
    assert(!made.error, `消费测试造数应成功: ${made.error || ''}`)
    assert(made.madeItems.every((m) => m.code === 200), `3 个字典项应全部新增成功，实际 ${JSON.stringify(made.madeItems)}`)
    log(`  造数: 类型 ${CONS_NAME}（dictKey ${CONS_KEY}，id=${made.typeId}）+ 3 项（sort 3/1/2，丙停用）`)
    // ---- 5a-consume. 消费口径：停用过滤（长度 2）+ sort 升序（首项最小）+ 字段恰 value/label/sort ----
    const consumed = await page.evaluate(async (dictKey) => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch(`/api/system/dict/data/type/${dictKey}`, { headers: { Authorization: `Bearer ${token}` } })
      return { httpStatus: res.status, body: await res.json() }
    }, CONS_KEY)
    log(`  消费接口: HTTP ${consumed.httpStatus} body=${JSON.stringify(consumed.body)}`)
    assertEq(consumed.httpStatus, 200, '契约：HTTP 恒 200')
    assertEq(consumed.body.code, 200, '消费端点业务码应为 200')
    assert(Array.isArray(consumed.body.data), `消费 data 应为数组，实际 ${typeof consumed.body.data}`)
    const items = consumed.body.data
    assertEq(items.length, 2, `停用项应被过滤，data 长度应为 2，实际 ${items.length}（${JSON.stringify(items)}）`)
    assertEq(items[0].value, 'b', '首项应为 sort 最小的 消费乙（value=b）')
    assertEq(items[0].sort, 1, '首项 sort 应为 1（消费口径 sort 升序）')
    assertEq(items[0].label, '消费乙', '首项 label 应为 消费乙')
    assertEq(items[1].value, 'a', '次项应为 消费甲（value=a，sort=3；停用的丙被过滤）')
    for (const it of items) {
      assertEq(Object.keys(it).sort().join(','), 'label,sort,value', `消费项字段应恰 value/label/sort，实际 ${JSON.stringify(Object.keys(it))}`)
    }
    await shot(page, 'd5-consume-endpoint.png')
    // ---- 5b. 未知 dictKey → 200 + data:[]（表单容错语义，不设业务错误码） ----
    const unknown = await page.evaluate(async () => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch('/api/system/dict/data/type/nonexistent_key', { headers: { Authorization: `Bearer ${token}` } })
      return { httpStatus: res.status, body: await res.json() }
    })
    log(`  未知键: HTTP ${unknown.httpStatus} body=${JSON.stringify(unknown.body)}`)
    assertEq(unknown.httpStatus, 200, '未知 dictKey 也应 HTTP 200')
    assertEq(unknown.body.code, 200, '未知 dictKey 业务码应为 200（空态非错误）')
    assert(Array.isArray(unknown.body.data), `未知 dictKey data 应为数组，实际 ${typeof unknown.body.data}`)
    assertEq(unknown.body.data.length, 0, '未知 dictKey data 应为空数组 []')
    // ---- 5c. 无 token 直调网关 → HTTP 401（网关鉴权真实状态码） ----
    const noToken = await page.request.get(`${GATEWAY}/system/dict/data/type/user_status`)
    log(`  无 token 直调网关 ${GATEWAY}: HTTP ${noToken.status()}`)
    assertEq(noToken.status(), 401, '无 token 直调网关应 HTTP 401')
    // ---- 5d. 种子回归：user_status 消费恰 2 项（内置字典消费面锁定，零触碰） ----
    const seed = await page.evaluate(async () => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch('/api/system/dict/data/type/user_status', { headers: { Authorization: `Bearer ${token}` } })
      return await res.json()
    })
    log(`  种子消费: ${JSON.stringify(seed)}`)
    assertEq(seed.code, 200, '种子消费业务码应为 200')
    assert(Array.isArray(seed.data), `种子消费 data 应为数组，实际 ${typeof seed.data}`)
    assertEq(seed.data.length, 2, `user_status 种子应恰 2 项，实际 ${JSON.stringify(seed.data)}`)
    assertEq(seed.data[0].label, '正常', '种子首项应为 正常（sort 1）')
    assertEq(seed.data[0].value, '0', '种子首项 value 应为 "0"')
    assertEq(seed.data[1].label, '停用', '种子次项应为 停用（sort 2）')
    assertEq(seed.data[1].value, '1', '种子次项 value 应为 "1"')
    // ---- 5e. common_status 消费回归（E2 补，契约 §8.3：role/menu/dict 四域 status 译文字典）----
    const common = await page.evaluate(async () => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch('/api/system/dict/data/type/common_status', { headers: { Authorization: `Bearer ${token}` } })
      return await res.json()
    })
    log(`  common_status 种子消费: ${JSON.stringify(common)}`)
    assertEq(common.code, 200, 'common_status 消费业务码应为 200')
    assert(Array.isArray(common.data), `common_status data 应为数组，实际 ${typeof common.data}`)
    assertEq(common.data.length, 2, `common_status 种子应恰 2 项，实际 ${JSON.stringify(common.data)}`)
    assertEq(common.data[0].label, '正常', 'common_status 首项应为 正常（sort 1）')
    assertEq(common.data[0].value, '0', 'common_status 首项 value 应为 "0"')
    assertEq(common.data[1].label, '停用', 'common_status 次项应为 停用（sort 2）')
    assertEq(common.data[1].value, '1', 'common_status 次项 value 应为 "1"')
    // ---- 5f. bpmn_approval_status 消费回归（E1 补→Round I 迁移：契约 2026-10-08-approval-platform-api §7——
    //      恰 4 项，value "0"-"3" 文案断言；bpmn_leave_status 种子行已 DELETE，旧键消费面随迁移收敛到新键）----
    const approvalStatus = await page.evaluate(async () => {
      const token = JSON.parse(localStorage.getItem('cloud-web:auth')).accessToken
      const res = await fetch('/api/system/dict/data/type/bpmn_approval_status', { headers: { Authorization: `Bearer ${token}` } })
      return await res.json()
    })
    log(`  bpmn_approval_status 种子消费: ${JSON.stringify(approvalStatus)}`)
    assertEq(approvalStatus.code, 200, 'bpmn_approval_status 消费业务码应为 200')
    assert(Array.isArray(approvalStatus.data), `bpmn_approval_status data 应为数组，实际 ${typeof approvalStatus.data}`)
    assertEq(approvalStatus.data.length, 4, `bpmn_approval_status 种子应恰 4 项，实际 ${JSON.stringify(approvalStatus.data)}`)
    const APPROVAL_STATUS_LABELS = { 0: '审批中', 1: '已通过', 2: '已拒绝', 3: '已撤销' }
    for (let i = 0; i < 4; i++) {
      assertEq(approvalStatus.data[i].value, String(i), `bpmn_approval_status 第 ${i + 1} 项 value 应为 "${i}"（sort 升序），实际 ${JSON.stringify(approvalStatus.data[i])}`)
      assertEq(approvalStatus.data[i].label, APPROVAL_STATUS_LABELS[i], `bpmn_approval_status value "${i}" 文案应为 ${APPROVAL_STATUS_LABELS[i]}，实际 "${approvalStatus.data[i].label}"`)
    }
  })

  // ================= D5b 内置字典保护（契约 2026-10-07-builtin-protection §2/§7.2-§7.3：徽标+禁用面 UI 断言 + 直连 3015/3016 API 断言，种子零变更） =================
  await step('D5b', '内置字典保护：user_status 行徽标 + 编辑/删除禁用、「字典项」放行 → 直连删类型 3015（先于 3011）→ 弹框种子项徽标/行内禁用/「新增」放行 → 直连删项 3016 → 行/项原样', async () => {
    // ---- a. UI 禁用面 + 徽标（§7.2：编辑/删除禁用；§7.3：「字典项」放行——内置类型可进弹框管理项）----
    await loadDictPage()
    let row = await findTypeRowByKey('user_status', { reload: false })
    assert(row, '应能定位 user_status 种子类型行')
    const cellsBefore = await rowCells(row)
    log(`  种子类型行（保护前）: ${JSON.stringify(cellsBefore)}`)
    const badge = row.locator('.builtin-badge')
    assert((await badge.count()) === 1, 'user_status 行名称格应有内联「内置」徽标（el-tag）')
    assertEq((await badge.innerText()).trim(), '内置', '徽标文本应为 内置')
    assert(await row.getByRole('button', { name: '编辑' }).isDisabled(), '内置类型「编辑」按钮应禁用')
    assert(await row.getByRole('button', { name: '删除' }).isDisabled(), '内置类型「删除」按钮应禁用')
    assert(await row.getByRole('button', { name: '字典项' }).isEnabled(), '「字典项」按钮应放行（§7.3 内置类型可进弹框管理项）')
    await shot(page, 'd5b-builtin-disabled.png')
    // ---- b. 直连 DELETE /system/dict/type/1 → body 3015（次序断言在 API 层保留：user_status 有 2 项，
    //      旧路径必 3011——3015 意味着保护校验先于 3011 项检查）----
    const del = await directApi('DELETE', '/system/dict/type/1')
    log(`  直连删除类型接口: HTTP ${del.httpStatus} code=${del.body.code} msg="${del.body.msg}"`)
    assertEq(del.httpStatus, 200, '契约：HTTP 恒 200（错误码在 body）')
    assertEq(del.body.code, 3015, `内置类型删除应 body 3015（先于 3011 项检查）而非 3011，实际 ${del.body.code}`)
    // ---- c. 弹框（放行入口）：种子项徽标 + 行内编辑/删除禁用 + 「新增字典项」放行（§7.3）----
    row = await findTypeRowByKey('user_status', { reload: false })
    assert(row, '3015 拒绝后 user_status 类型行应仍在')
    const dlg = await openDataDialog(row)
    const itemRows = dlg.locator('.el-table__row')
    assertEq(await itemRows.count(), 2, '种子弹框应恰 2 行（正常/停用）')
    const firstCells = await rowCells(itemRows.first())
    log(`  种子首项行: ${JSON.stringify(firstCells)}`)
    assertEq(stripBadge(firstCells[0]), '正常', '种子首项应为 正常（sort 1，徽标剥离后）')
    assert((await itemRows.first().locator('.builtin-badge').count()) === 1, '种子项行标签格应有内联「内置」徽标（el-tag）')
    assert(await itemRows.first().getByRole('button', { name: '编辑' }).isDisabled(), '内置项行内「编辑」按钮应禁用')
    assert(await itemRows.first().getByRole('button', { name: '删除' }).isDisabled(), '内置项行内「删除」按钮应禁用')
    assert(await dlg.getByRole('button', { name: '新增字典项' }).isEnabled(), '弹框「新增字典项」按钮应放行（§7.3 内置类型可追加项）')
    await shot(page, 'd5b-item-disabled.png')
    // ---- d. 直连 DELETE 种子项（id=1 正常）→ body 3016 ----
    const itemsPage = await directApi('GET', '/system/dict/data/page?typeId=1&pageNum=1&pageSize=10')
    assertEq(itemsPage.body.code, 200, '项分页直连业务码应为 200')
    assertEq(itemsPage.body.data.rows.length, 2, `user_status 应恰 2 项，实际 ${itemsPage.body.data.rows.length}`)
    assertEq(String(itemsPage.body.data.rows[0].id), '1', `种子首项 id 应为 1（正常），实际 ${itemsPage.body.data.rows[0].id}`)
    const itemDel = await directApi('DELETE', `/system/dict/data/${itemsPage.body.data.rows[0].id}`)
    log(`  直连删除项接口: HTTP ${itemDel.httpStatus} code=${itemDel.body.code} msg="${itemDel.body.msg}"`)
    assertEq(itemDel.body.code, 3016, `内置字典项删除应 body 3016，实际 ${itemDel.body.code}`)
    // ---- e. 种子终态：弹框 2 行/共 2 条 + 类型行原样 ----
    assertEq(await dlg.locator('.el-table__row').count(), 2, '3016 拒绝后弹框内种子项应仍恰 2 行')
    const dlgTotal = (await dlg.locator('.el-pagination__total').innerText()).trim()
    assertEq(dlgTotal, '共 2 条', `弹框分页应仍为 共 2 条，实际 "${dlgTotal}"`)
    await closeDataDialog()
    row = await findTypeRowByKey('user_status', { reload: false })
    assert(row, '保护场景后 user_status 类型行应仍在（种子终态）')
    const cellsAfter = await rowCells(row)
    log(`  种子类型行（保护后）: ${JSON.stringify(cellsAfter)}`)
    assertEq(stripBadge(cellsAfter[0]), '用户状态', '种子类型名应保持 用户状态（徽标剥离后）')
    assertEq(cellsAfter[1], 'user_status', '种子 dictKey 应保持 user_status')
    assertEq(cellsAfter[2], cellsBefore[2], `种子类型状态应保持原值 "${cellsBefore[2]}"`)
  })

  // ================= CLEANUP 删净（兜底清扫全部 e2e 前缀类型：先删项后删类型；D5 的 e2econs 含在内） =================
  await step('CLEANUP', '删净：清扫全部 e2e 前缀字典类型（含 D5 e2econs，先删项后删类型）→ 断言左表无 e2e 残留、残留 ⊇ SEED_KEYS（宽松，不锁上限）', async () => {
    await loadDictPage()
    for (let guard = 0; guard < 100; guard++) {
      const rows = page.locator('.type-pane .el-table__row')
      const n = await rows.count()
      let target = null
      let targetKey = ''
      for (let i = 0; i < n; i++) {
        const cells = await rowCells(rows.nth(i))
        if (cells[1].startsWith('e2e')) {
          target = rows.nth(i)
          targetKey = cells[1]
          break
        }
      }
      if (target) {
        // 删一个类型 = 结构性变化：弹框内删净其字典项（3011 禁删约束）→ 关弹框 → 删类型，然后回第 1 页重扫
        log(`  [清扫] 发现残留类型 ${targetKey}，先删净其字典项`)
        await openDataDialog(target)
        await deleteAllDataItems()
        await closeDataDialog()
        await target.getByRole('button', { name: '删除' }).click()
        await confirmDelete('确定删除字典类型')
        await waitToast(page, '删除成功')
        await sleep(800)
        await loadDictPage()
        continue
      }
      // 本页无残留：向后翻页继续找（残留类型 id 倒序可能在后续页；此处不 goto——避免回第 1 页死循环）
      const next = page.locator('.type-pane .el-pagination .btn-next')
      if ((await next.count()) > 0 && (await next.isEnabled())) {
        await next.click()
        await waitTableIdle(page)
        await sleep(300)
        continue
      }
      break // 全部页扫完无 e2e 残留
    }
    await loadDictPage()
    const residue = await page.locator('.type-pane .el-table__row', { hasText: 'e2e' }).count()
    log(`  e2e 残留类型行数: ${residue}`)
    assertEq(residue, 0, `清理后左表不应残留任何 e2e 前缀类型行，实际 ${residue}`)
    const leftTotal = (await page.locator('.type-pane .el-pagination__total').innerText()).trim()
    log(`  清理后左表分页: ${leftTotal}`)
    const leftTotalN = parseInt((leftTotal.match(/\d+/) || ['0'])[0], 10)
    assert(leftTotalN >= SEED_KEYS.length, `清理后左表分页总数应 ≥ 共 ${SEED_KEYS.length} 条（宽松不锁上限），实际 "${leftTotal}"`)
    // 种子零触碰终检（E1 迁移）：残留 ⊇ SEED_KEYS 且各行名称/状态/徽标原样（scanSeedTypeRows 就地断言）
    const finalSeen = await assertSeedSuperset('CLEANUP')
    log(`  残留种子键: ${JSON.stringify(finalSeen)}`)
    await shot(page, 'cleanup-final.png')
  })

  // ================= 证据核验：无 console error / pageerror / >=400 / 网络失败 =================
  await step('D-VERIFY', '证据核验：无 console error / pageerror / >=400 响应 / 网络失败（3009/3011/3012 错误码在 body，HTTP 恒 200）', async () => {
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
      `\n测试数据: 类型 ${TEST_KEY}（${TEST_NAME}→${TEST_NAME_V2}）/ 项 ${ITEM_VALUE}（${ITEM_LABEL}→${ITEM_LABEL_V2}）/ 3009 探针 ${TEST_NAME_DUP} / 3012 探针 ${ITEM_LABEL_B} / D5 消费类型 ${CONS_KEY}（${CONS_NAME}，含 3 项）——应均已在 D4/CLEANUP 删净或从未落库`,
      'user_status/common_status/bpmn_approval_status/system_leave_type 内置种子（SEED_KEYS 四类型）全程零触碰；admin 未做任何种子外数据写操作',
    ],
  })
  await browser.close()
}
