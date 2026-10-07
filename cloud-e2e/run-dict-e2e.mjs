/**
 * 数据字典管理 e2e（契约 2026-10-07-dict-api；D0-D4 + CLEANUP + D-VERIFY；harness 复用 lib/harness.mjs）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202（含 /system/dict/** 端点版本）已启动；前端 dev 5173 已启动
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 npm run e2e:dict）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码
 * 测试数据（删净纪律最高优先）：
 * - 类型 dictKey / 项 value 全部 e2e 前缀+时间戳；user_status 为内置种子（翻译契约 §0.3，当前无防删保护）——零触碰，仍不碰库内非本脚本数据
 * - 结束清扫全部 e2e 前缀类型（含 D5 的 e2econs；先删项后删类型，3011 禁删约束）并断言左表无 e2e 残留、仅剩种子
 * 核心断言（契约 §2 §3 §5；界面重构后字典项管理在弹框内——2026-10-07-dict-ui-list-dialog plan D4）：
 * - D0 侧边 字典管理 位于 菜单管理 之后 + 面包屑 首页/字典管理 + admin 重登快照含 dict 权限（新增类型按钮可见）
 * - D1 全宽类型表：4 列/恰 1 行（user_status 内置种子，契约 2026-10-07-translation-api §0.3）；
 *   种子行"字典项"弹框：标题 `字典项：用户状态（user_status）`、5 列精确序、种子 2 行（正常/停用）、共 2 条
 * - D2 类型闭环：空提交 0 请求 → 新增（提交恰三字段）→ 编辑改名+停用（全量三字段+id、tag danger）
 *   → 重开弹框标题跟随新名 → 同 dictKey 重提 3009 toast 弹窗保持
 * - D3 项闭环（全在弹框作用域）：空提交 0 请求 → 新增（提交恰五字段、typeId 对齐行类型）
 *   → 审计断言改页内 fetch（createBy/updateBy=admin、createTime 格式——UI 已减审计列，契约 VO 仍返回）
 *   → 同 value 重提 3012 toast 弹窗保持 → 编辑改 label/sort（全量五字段+id、弹框行内更新）
 * - D4 删除约束：有项删类型 3011 toast 行保留 → 弹框内删净项 → 删类型（确认框含类型名）→ 类型行消失
 * - D5 消费端点（契约 2026-10-07-translation-api §2.1）：造 e2econs 类型+3 项（sort 3/1/2，1 项停用）→
 *   消费断言停用过滤/长度 2/sort 升序/字段恰 value-label-sort；未知 dictKey → 200 data:[]；
 *   无 token 直调网关 401；种子 user_status 消费回归恰 2 项（CLEANUP 一并删 e2econs，种子零触碰）
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
    // 侧边菜单顺序（动态路由种子 14 字典管理插在 菜单管理 后）
    const items = page.locator('.el-menu .el-menu-item')
    const n = await items.count()
    const labels = []
    for (let i = 0; i < n; i++) labels.push((await items.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,字典管理,工作台', '侧边菜单顺序应为 用户管理→角色管理→菜单管理→字典管理→工作台')
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
  await step('D1', '全宽类型表：4 列 + 恰 1 行（user_status 种子）；种子行弹框：标题/5 列精确序/种子 2 行（正常/停用）/共 2 条', async () => {
    // 类型表 4 列精确序
    const ths = page.locator('.type-pane .el-table__header-wrapper th')
    const tn = await ths.count()
    const headers = []
    for (let i = 0; i < tn; i++) headers.push(((await ths.nth(i).innerText()) || '').trim())
    log(`  类型表头(${tn}): ${JSON.stringify(headers)}`)
    assertEq(headers.join(','), '字典名称,字典键,状态,操作', `类型表头应为 4 列精确序，实际 ${JSON.stringify(headers)}`)
    // 类型表：仅剩 user_status 内置种子（契约 2026-10-07-translation-api §0.3 本轮新增，管理页可见可操作）——
    // 原"共 0 条"断言随种子落地失真，改为锁定种子行内容（名称/键/状态）与总数恰 1
    const seedRows = page.locator('.type-pane .el-table__row')
    assertEq(await seedRows.count(), 1, `类型表应恰 1 行（user_status 种子），实际 ${await seedRows.count()}`)
    const seedCells = await rowCells(seedRows.first())
    log(`  种子行: ${JSON.stringify(seedCells)}`)
    assertEq(seedCells[0], '用户状态', '种子行字典名称应为 用户状态')
    assertEq(seedCells[1], 'user_status', '种子行字典键应为 user_status')
    assertEq(seedCells[2], '正常', '种子行状态应为 正常')
    const leftTotal = (await page.locator('.type-pane .el-pagination__total').innerText()).trim()
    log(`  类型表分页: ${leftTotal}`)
    assertEq(leftTotal, '共 1 条', `类型表分页应为 共 1 条（仅种子），实际 "${leftTotal}"`)
    // 种子行"字典项"弹框：标题（原右栏标题格式平移）+ 5 列精确序 + 种子 2 项 + 共 2 条
    const dlg = await openDataDialog(seedRows.first())
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
    assertEq(itemCellsA[0], '正常', '种子第 1 行标签应为 正常（sort 1）')
    assertEq(itemCellsB[0], '停用', '种子第 2 行标签应为 停用（sort 2）')
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
  await step('D5', '消费端点：e2econs 停用过滤 + sort 升序 + 字段恰 value/label/sort；未知键 200 空数组；无 token 直调网关 401；种子 user_status 回归 2 项', async () => {
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
  })

  // ================= CLEANUP 删净（兜底清扫全部 e2e 前缀类型：先删项后删类型；D5 的 e2econs 含在内） =================
  await step('CLEANUP', '删净：清扫全部 e2e 前缀字典类型（含 D5 e2econs，先删项后删类型）→ 断言左表无 e2e 残留、仅剩 user_status 种子', async () => {
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
    assertEq(leftTotal, '共 1 条', `清理后左表应仅剩 user_status 种子（共 1 条），实际 "${leftTotal}"`)
    // 种子零触碰终检：残留的恰 1 行就是 user_status（名称/键/状态原样）
    const finalRows = page.locator('.type-pane .el-table__row')
    assertEq(await finalRows.count(), 1, `清理后左表应恰 1 行（种子），实际 ${await finalRows.count()}`)
    const finalCells = await rowCells(finalRows.first())
    assertEq(finalCells[1], 'user_status', `清理后仅剩行应为 user_status 种子，实际 ${JSON.stringify(finalCells)}`)
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
      'user_status 内置种子（翻译契约 §0.3）全程零触碰；admin 未做任何种子外数据写操作',
    ],
  })
  await browser.close()
}
