/**
 * 菜单管理页 e2e（设计 §10.3 M0-M5 + CLEANUP；harness 复用 lib/harness.mjs）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202（v2 契约版）已启动；前端 dev 5173 已启动（/api 代理 18080）
 * 运行：cd cloud-e2e && npm run e2e（串行含本脚本；单跑 node run-menu-e2e.mjs）
 * 测试数据（删净纪律最高优先，设计 D8）：
 * - 菜单名 E2E 前缀+时间戳；绝不编辑/删除种子菜单（10/11/12/13/111… 与 20/21/211）
 * - 结束按 F → C → M 自底向上删净并断言树中无 E2E 残留——
 *   role e2e R5a 断言 admin 绑定全部菜单全选（active == total），任何残留 E2E 行都会让下一轮回归必红
 * - 不断言"权限改完立即可用"（权限快照时效，契约 §1：变更需重登/refresh 生效）
 * - 动态路由适配（2026-10-07 计划 E1）：M3/M4 C 型表单补填新必填"路由路径"（契约 §5.2 v2），
 *   M4 请求体键断言随 v2 更新为八字段+id；其余场景零改动
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, waitToast, waitDialogGone, waitTableIdle, breadcrumbTexts, login, rowCells } = h

// ---------- 测试数据（只作用于 E2E 前缀菜单，不碰种子） ----------
const ts = new Date()
const pad = (n) => String(n).padStart(2, '0')
const stamp = `${pad(ts.getMonth() + 1)}${pad(ts.getDate())}${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}`
const TEST_DIR = `E2E目录${stamp}`
const TEST_PAGE = `E2E页面${stamp}`
const TEST_PAGE_V2 = `E2E页面v2${stamp}`
/** C 型新必填路由路径（契约 §5.2 v2 / 设计 D11）：/e2e/page + 时间戳风格（计划 E1） */
const TEST_PAGE_PATH = `/e2e/page${stamp}`
const TEST_FUNC = `E2E按钮${stamp}`
const TEST_PERMS = `system:e2e:test${stamp}`

const MENU_PATH = '/system/menu'

// ---------- 主流程（默认有头 + slowMo 300，与 run-e2e / run-role-e2e 一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

const menuPostCount = () => h.state.apiCalls.filter((c) => c.url === '/api/system/menu' && c.method === 'POST').length

/** 非 /api/ 资产的 404 清单（favicon 等环境噪音甄别用） */
const asset404 = []
page.on('response', (r) => {
  if (r.status() === 404 && !r.url().includes('/api/')) asset404.push(r.url().replace(BASE, ''))
})

/** waitForResponse 的 URL 匹配：r.url() 是含 origin 的完整地址，须比 pathname */
const apiPath = (url, pathname) => new URL(url).pathname === pathname

/** 加载树表并等待渲染落定 */
async function loadTreePage() {
  await page.goto(`${BASE}${MENU_PATH}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
  await waitTableIdle(page)
}

/** 按名称列精确找菜单行（树表无分页，全量行内线性检索；行首列=名称） */
async function findMenuRow(name, { reload = true } = {}) {
  if (reload) {
    await loadTreePage()
  }
  const rows = page.locator('.el-table__row')
  const n = await rows.count()
  for (let i = 0; i < n; i++) {
    const cells = await rowCells(rows.nth(i))
    if (cells[0] === name) return rows.nth(i)
  }
  return null
}

/** 行首列树形缩进（px，EP 实测 style="padding-left: 16px/32px"）——层级深度的 DOM 证据（根=无缩进元素） */
async function rowIndentWidth(row) {
  const indent = row.locator('.el-table__indent').first()
  if ((await indent.count()) === 0) return 0
  const style = await indent.getAttribute('style')
  const m = (style || '').match(/padding-left:\s*(\d+(?:\.\d+)?)px/)
  return m ? parseFloat(m[1]) : -1
}

/** 打开上级 tree-select 下拉并返回最新可见 popper（选项类 .el-select-dropdown__item，EP 实测）。
 *  取 .last()：已关弹窗的 tree-select 可能残留孤儿 popper 仍处可见态（EP teleport 生命周期怪癖），
 *  新建 popper 追加在 body 末尾——按 DOM 序取最后一个即当前弹窗的下拉 */
async function openParentDropdown(dlg) {
  await dlg.locator('.el-select__wrapper').click()
  const dd = page.locator('.el-tree-select__popper:visible').last()
  await dd.waitFor({ state: 'visible', timeout: 8000 })
  await sleep(300)
  return dd
}

/** 弹窗内收集全部校验错误文本 */
async function formErrors(dlg) {
  const errs = dlg.locator('.el-form-item__error')
  const texts = []
  const n = await errs.count()
  for (let i = 0; i < n; i++) texts.push((await errs.nth(i).innerText()).trim())
  return texts
}

/** 删除行（确认框文案断言后确认），返回 toast 文本 */
async function deleteMenuViaUi(name) {
  const row = await findMenuRow(name)
  assert(row, `应能定位菜单行 ${name}`)
  await row.getByRole('button', { name: '删除' }).click()
  const box = page.locator('.el-message-box')
  await box.waitFor({ state: 'visible', timeout: 8000 })
  const boxText = (await box.innerText()).trim().replace(/\n/g, ' | ')
  assert(boxText.includes(name), `确认框文案应含菜单名 "${name}"，实际 "${boxText}"`)
  assert(boxText.includes('解除'), `确认框文案应含"解除"绑定提示，实际 "${boxText}"`)
  await box.locator('.el-message-box__btns .el-button--primary').click()
  return boxText
}

try {
  // ================= M0 401/登录前置：无 token 直访被拦 → admin 登录回跳目标页 =================
  await step('M0', '无 token 直访 /system/menu → 拦截跳登录（带 redirect）→ admin 登录回跳', async () => {
    await page.goto(`${BASE}${MENU_PATH}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForURL('**/login**', { timeout: 10000 })
    const url = new URL(page.url())
    assertEq(url.searchParams.get('redirect'), MENU_PATH, 'redirect 参数应为 /system/menu')
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功: ${r.msg || ''}`)
    // 登录后按 redirect 回跳目标页
    await page.waitForURL(`**${MENU_PATH}`, { timeout: 15000 })
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 10000 })
    log(`  登录回跳: ${page.url()}`)
  })

  // ================= M1 列表加载：菜单顺序/面包屑/树全展开/表头 10 列/三色 tag/无分页 =================
  await step('M1', '菜单管理页加载：菜单顺序 用户→角色→菜单→字典→工作台/面包屑/树表全展开/10 列/类型三色 tag/无分页', async () => {
    // 侧边菜单项与顺序（动态路由种子：用户管理 → 角色管理 → 菜单管理 → 字典管理 → 工作台）
    const menuItems = page.locator('.el-menu .el-menu-item')
    const count = await menuItems.count()
    const labels = []
    for (let i = 0; i < count; i++) labels.push((await menuItems.nth(i).innerText()).trim())
    log(`  菜单项: ${JSON.stringify(labels)}`)
    assertEq(labels.join(','), '用户管理,角色管理,菜单管理,字典管理,工作台', '侧边菜单顺序应为 用户管理→角色管理→菜单管理→字典管理→工作台')
    await page.locator('.el-menu-item', { hasText: '菜单管理' }).click()
    await page.waitForURL(`**${MENU_PATH}`, { timeout: 8000 })
    await waitTableIdle(page)
    const active = (await page.locator('.el-menu-item.is-active').innerText()).trim()
    assertEq(active, '菜单管理', '/system/menu 下菜单管理应高亮')
    const bc = await breadcrumbTexts(page)
    log(`  面包屑: ${JSON.stringify(bc)}`)
    assertEq(bc.join('/'), '首页/菜单管理', '面包屑应为 首页/菜单管理')
    // 表头 10 列
    const headerCells = page.locator('.el-table__header-wrapper th')
    const hn = await headerCells.count()
    const headers = []
    for (let i = 0; i < hn; i++) headers.push(((await headerCells.nth(i).innerText()) || '').trim())
    log(`  表头(${hn}): ${JSON.stringify(headers)}`)
    for (const col of ['名称', '类型', '权限标识', '排序', '状态', '创建人', '创建时间', '更新人', '更新时间', '操作']) {
      assert(headers.includes(col), `表头应含"${col}"，实际 ${JSON.stringify(headers)}`)
    }
    assertEq(headers.length, 10, '表头应为 10 列')
    // 树表默认全展开：根行（系统管理）与孙行（用户新增，F 级）同屏可见
    const sysRow = await findMenuRow('系统管理', { reload: false })
    assert(sysRow, '树应含根行 系统管理')
    const funcRow = await findMenuRow('用户新增', { reload: false })
    assert(funcRow, '默认全展开：F 级行 用户新增 应可见')
    // 类型三色 tag 抽检：M=primary / C=success / F=warning
    const sysTag = sysRow.locator('.el-tag').first()
    assert((await sysTag.innerText()).trim() === '目录', '系统管理类型 tag 文本应为 目录')
    assert(((await sysTag.getAttribute('class')) || '').includes('el-tag--primary'), `目录 tag 应为 primary，实际 ${await sysTag.getAttribute('class')}`)
    const userRow = await findMenuRow('用户管理', { reload: false })
    const userTag = userRow.locator('.el-tag').first()
    assert((await userTag.innerText()).trim() === '菜单', '用户管理类型 tag 文本应为 菜单')
    assert(((await userTag.getAttribute('class')) || '').includes('el-tag--success'), '菜单 tag 应为 success')
    const addRow = await findMenuRow('用户新增', { reload: false })
    const addCells = await rowCells(addRow)
    const addTag = addRow.locator('.el-tag').first()
    assert((await addTag.innerText()).trim() === '按钮', '用户新增类型 tag 文本应为 按钮')
    assert(((await addTag.getAttribute('class')) || '').includes('el-tag--warning'), '按钮 tag 应为 warning')
    assertEq(addCells[2], 'system:user:add', '用户新增权限标识列应为 system:user:add')
    // M 行权限标识列显示 -
    const sysCells = await rowCells(sysRow)
    assertEq(sysCells[2], '-', '目录行权限标识列应显示 -')
    // 状态 tag 与时间格式
    assert(['正常', '停用'].includes(sysCells[4]), `状态列应为 正常/停用，实际 "${sysCells[4]}"`)
    assert(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(sysCells[6]) || sysCells[6] === '-', `创建时间应为 yyyy-MM-dd HH:mm:ss 或 -，实际 "${sysCells[6]}"`)
    // 无分页（全量树）
    assertEq(await page.locator('.el-pagination').count(), 0, '菜单树表不应渲染分页组件')
    await shot(page, 'm1-tree-table.png')
  })

  // ================= M2 新增目录：空提交 0 请求 → 根级目录出现 =================
  await step('M2', '新增目录（M）：空提交必填错误（0 请求）→ M 态无选择器固定根目录 → 行出现', async () => {
    log(`  测试目录: ${TEST_DIR}`)
    await page.getByRole('button', { name: '新增菜单' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新增菜单' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // M 态：类型默认目录、上级只读"根目录"、无 tree-select、无 perms 字段
    const typeChecked = dlg.locator('.el-radio', { hasText: '目录' }).locator('input')
    assert(await typeChecked.isChecked(), '新增弹窗类型默认应为 目录')
    assert((await dlg.innerText()).includes('根目录'), 'M 态上级应只读展示 根目录')
    assertEq(await dlg.locator('.el-select').count(), 0, 'M 态不应渲染上级选择器')
    assert((await dlg.locator('.el-form-item', { hasText: '权限标识' }).count()) === 0, 'M 态不应显示权限标识字段')
    await shot(page, 'm2-add-dialog-m.png')
    // 空提交
    const before = menuPostCount()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    const errs = await formErrors(dlg)
    log(`  空提交错误: ${JSON.stringify(errs)}`)
    assert(errs.includes('请输入菜单名称'), `空提交应报"请输入菜单名称"，实际 ${JSON.stringify(errs)}`)
    assertEq(menuPostCount(), before, '空提交不应发出新增菜单请求')
    await shot(page, 'm2-empty-errors.png')
    // 填名提交（目录：无上级无 perms，全靠默认值）
    await dlg.locator('input[placeholder="请输入菜单名称"]').fill(TEST_DIR)
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/menu') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    const reqBody = resp.request().postDataJSON()
    log(`  新增目录接口: HTTP ${resp.status()} body=${JSON.stringify(body).slice(0, 120)}`)
    log(`  提交体: ${JSON.stringify(reqBody)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '新增业务码应为 200')
    assert(body.data, '新增响应应返回新菜单 id（字符串）')
    assertEq(reqBody.parentId, '0', 'M 态提交 parentId 应为 "0"（根级）')
    assertEq(reqBody.perms, '', 'M 态提交 perms 应为空串')
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增菜单')
    const row = await findMenuRow(TEST_DIR)
    assert(row, `树应出现新目录行 ${TEST_DIR}`)
    const cells = await rowCells(row)
    log(`  新目录行: ${JSON.stringify(cells.slice(0, 5))}`)
    assertEq(cells[1], '目录', '新行类型应为 目录')
    assertEq(cells[2], '-', '目录行权限标识应显示 -')
    assertEq(cells[4], '正常', '新行状态应为 正常')
    assertEq(cells[5], 'admin', '新行创建人应为 admin（审计透传，契约 §3）')
    // 根级行无树缩进
    assertEq(await rowIndentWidth(row), 0, '根级目录行不应有树形缩进')
    await shot(page, 'm2-dir-created.png')
  })

  // ================= M3 新增 C 与 F：类型化候选 + F perms 必填 + 三行层级 =================
  await step('M3', '新增 C（候选=M）与 F（候选=C、perms 必填）→ 三行层级正确（缩进递进）', async () => {
    // ---- 3a. type=C 未选上级提交 → "请选择上级" + 0 请求 ----
    await page.getByRole('button', { name: '新增菜单' }).click()
    let dlg = page.locator('.el-dialog', { hasText: '新增菜单' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('.el-radio', { hasText: '菜单' }).click()
    await sleep(300)
    // 切换类型后不应残留校验错误（切换重置上级并 clearValidate）
    const preErrs = await formErrors(dlg)
    assertEq(preErrs.length, 0, `切换类型后不应残留校验错误，实际 ${JSON.stringify(preErrs)}`)
    await dlg.locator('input[placeholder="请输入菜单名称"]').fill(TEST_PAGE)
    const before = menuPostCount()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    let errs = await formErrors(dlg)
    log(`  C 未选上级提交错误: ${JSON.stringify(errs)}`)
    assert(errs.includes('请选择上级'), `C 未选上级应报"请选择上级"，实际 ${JSON.stringify(errs)}`)
    // v2 新必填（契约 §5.2 前端约定）：C 型路由路径未填同批报错（M1 断言域外的新校验验证）
    assert(errs.includes('请输入路由路径'), `C 未填路由路径应报"请输入路由路径"，实际 ${JSON.stringify(errs)}`)
    assertEq(menuPostCount(), before, '未选上级提交不应发出请求')
    // ---- 3b. 上级候选 = 仅 M 节点 ----
    const dd = await openParentDropdown(dlg)
    const opts = (await dd.locator('.el-select-dropdown__item').allInnerTexts()).map((t) => t.trim())
    log(`  C 态上级候选: ${JSON.stringify(opts)}`)
    assert(opts.includes(TEST_DIR) && opts.includes('系统管理'), `C 候选应含目录 ${TEST_DIR}/系统管理，实际 ${JSON.stringify(opts)}`)
    assert(!opts.includes('用户管理'), `C 候选不应含 C 节点（用户管理），实际 ${JSON.stringify(opts)}`)
    await shot(page, 'm3-c-candidates.png')
    await dd.locator('.el-select-dropdown__item', { hasText: TEST_DIR }).first().click()
    await sleep(300)
    // C 型新必填路由路径（计划 E1 补填）：placeholder 定位，/e2e/page+时间戳风格
    await dlg.locator('input[placeholder="必填，如 /system/xxx"]').fill(TEST_PAGE_PATH)
    // perms 选填留空提交
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/menu') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    const reqBody = resp.request().postDataJSON()
    log(`  新增菜单接口: HTTP ${resp.status()} code=${body.code} data=${body.data}`)
    log(`  提交体: ${JSON.stringify(reqBody)}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '新增业务码应为 200')
    // 契约 §5.2 v2：path 随新增提交；icon 未填提交空串
    assertEq(reqBody.path, TEST_PAGE_PATH, 'C 态提交 path 应为测试路由路径')
    assertEq(reqBody.icon, '', 'C 态 icon 未填应提交空串')
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增菜单')
    const cRow = await findMenuRow(TEST_PAGE)
    assert(cRow, `树应出现新菜单行 ${TEST_PAGE}`)
    const cCells = await rowCells(cRow)
    log(`  新菜单行: ${JSON.stringify(cCells.slice(0, 5))}`)
    assertEq(cCells[1], '菜单', '新行类型应为 菜单')
    assertEq(cCells[2], '-', 'C 选填 perms 留空 → 权限标识列应显示 -')
    const cIndent = await rowIndentWidth(cRow)
    log(`  C 行缩进: ${cIndent}px`)
    assert(cIndent > 0, 'C 行应有一级树缩进')

    // ---- 3c. type=F：perms 空提交被拦 → 候选=全部 C → 建按钮 ----
    await page.getByRole('button', { name: '新增菜单' }).click()
    dlg = page.locator('.el-dialog', { hasText: '新增菜单' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    await dlg.locator('.el-radio', { hasText: '按钮' }).click()
    await sleep(300)
    await dlg.locator('input[placeholder="请输入菜单名称"]').fill(TEST_FUNC)
    // 上级与 perms 均空提交
    const beforeF = menuPostCount()
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    errs = await formErrors(dlg)
    log(`  F 空提交错误: ${JSON.stringify(errs)}`)
    assert(errs.includes('请输入权限标识'), `F 空 perms 应报"请输入权限标识"，实际 ${JSON.stringify(errs)}`)
    assert(errs.includes('请选择上级'), `F 未选上级应报"请选择上级"，实际 ${JSON.stringify(errs)}`)
    assertEq(menuPostCount(), beforeF, 'F 空提交不应发出请求')
    await shot(page, 'm3-f-errors.png')
    // F 候选 = 全部 C 节点（剥 F 子级）
    const ddF = await openParentDropdown(dlg)
    const optsF = (await ddF.locator('.el-select-dropdown__item').allInnerTexts()).map((t) => t.trim())
    log(`  F 态上级候选: ${JSON.stringify(optsF)}`)
    assert(optsF.includes(TEST_PAGE) && optsF.includes('用户管理'), `F 候选应含 C 节点 ${TEST_PAGE}/用户管理，实际 ${JSON.stringify(optsF)}`)
    assert(!optsF.includes('系统管理') && !optsF.includes(TEST_DIR), `F 候选不应含 M 节点，实际 ${JSON.stringify(optsF)}`)
    await shot(page, 'm3-f-dialog.png')
    await ddF.locator('.el-select-dropdown__item', { hasText: TEST_PAGE }).first().click()
    await sleep(300)
    // 选好上级后再空 perms 提交（仅剩 perms 错误）
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    await sleep(500)
    errs = await formErrors(dlg)
    assert(errs.includes('请输入权限标识') && !errs.includes('请选择上级'), `选好上级后应仅剩 perms 错误，实际 ${JSON.stringify(errs)}`)
    // 填 perms 提交
    await dlg.locator('input[placeholder="必填，如 system:xxx:add"]').fill(TEST_PERMS)
    const respPF = page.waitForResponse((r) => apiPath(r.url(), '/api/system/menu') && r.request().method() === 'POST', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const respF = await respPF
    const bodyF = await respF.json()
    log(`  新增按钮接口: HTTP ${respF.status()} code=${bodyF.code}`)
    assertEq(bodyF.code, 200, '新增业务码应为 200')
    await waitToast(page, '新增成功')
    await waitDialogGone(page, '新增菜单')
    const fRow = await findMenuRow(TEST_FUNC)
    assert(fRow, `树应出现新按钮行 ${TEST_FUNC}`)
    const fCells = await rowCells(fRow)
    log(`  新按钮行: ${JSON.stringify(fCells.slice(0, 5))}`)
    assertEq(fCells[1], '按钮', '新行类型应为 按钮')
    assertEq(fCells[2], TEST_PERMS, `按钮行权限标识列应等于提交值 ${TEST_PERMS}`)
    const fIndent = await rowIndentWidth(fRow)
    log(`  F 行缩进: ${fIndent}px`)
    assert(fIndent > cIndent, `F 行缩进（${fIndent}px）应深于 C 行（${cIndent}px）——三级层级 DOM 证据`)
    await shot(page, 'm3-three-levels.png')
  })

  // ================= M4 编辑：type 锁定/上级回显 → 改名+停用 → 行内更新 =================
  await step('M4', '编辑 E2E 页面：type radio disabled/上级回显 → 改名+停用 → 行内名称更新+状态 danger', async () => {
    const row = await findMenuRow(TEST_PAGE)
    assert(row, '应能定位测试菜单行')
    await row.getByRole('button', { name: '编辑' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '编辑菜单' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // type 三 radio 全部 disabled 且当前值 C 选中（编辑锁定，D3）
    const radioStates = await dlg.locator('.el-radio-group').first().locator('input').evaluateAll((els) =>
      els.map((el) => ({ v: el.value, checked: el.checked, disabled: el.disabled })),
    )
    log(`  type radio: ${JSON.stringify(radioStates)}`)
    assert(radioStates.every((s) => s.disabled), '编辑弹窗 type radio 应全部 disabled（type 锁定）')
    assert(radioStates.find((s) => s.v === 'C')?.checked === true, '编辑弹窗 type 应回显 菜单（C）')
    // 上级回显当前父（E2E 目录）
    const wrapperText = (await dlg.locator('.el-select__wrapper').innerText()).trim()
    log(`  上级回显: "${wrapperText}"`)
    assert(wrapperText.includes(TEST_DIR), `上级选择器应回显当前父 ${TEST_DIR}，实际 "${wrapperText}"`)
    // 名称/perms/path 回显（path 为契约 §5.1 v2 additive 字段，M3 提交值应回显）
    assertEq(await dlg.locator('input[placeholder="请输入菜单名称"]').inputValue(), TEST_PAGE, '名称应回显原值')
    assertEq(await dlg.locator('input[placeholder="必填，如 /system/xxx"]').inputValue(), TEST_PAGE_PATH, '路由路径应回显 M3 提交值')
    await shot(page, 'm4-edit-echo.png')
    // 改名 + 停用
    await dlg.locator('input[placeholder="请输入菜单名称"]').fill(TEST_PAGE_V2)
    await dlg.locator('.el-radio', { hasText: '停用' }).click()
    const respP = page.waitForResponse((r) => apiPath(r.url(), '/api/system/menu') && r.request().method() === 'PUT', { timeout: 15000 })
    await dlg.locator('.el-dialog__footer button', { hasText: '保存' }).click()
    const resp = await respP
    const body = await resp.json()
    const reqBody = resp.request().postDataJSON()
    log(`  编辑接口: HTTP ${resp.status()} code=${body.code}`)
    assertEq(resp.status(), 200, '契约：HTTP 恒 200')
    assertEq(body.code, 200, '编辑业务码应为 200')
    // 全量提交八字段 + id（契约 §2.3 部分更新语义规避；§5.2 v2 追加 path/icon）
    const keys = Object.keys(reqBody).sort()
    assertEq(keys.join(','), 'icon,id,name,parentId,path,perms,sort,status,type', `编辑应全量提交八字段+id，实际 ${JSON.stringify(keys)}`)
    assertEq(reqBody.name, TEST_PAGE_V2, '编辑提交 name 应为新名')
    assertEq(reqBody.status, 1, '编辑提交 status 应为 1（停用）')
    assertEq(reqBody.path, TEST_PAGE_PATH, '编辑提交 path 应保持回显值（未改）')
    assertEq(reqBody.icon, '', '编辑提交 icon 应为空串')
    await waitToast(page, '保存成功')
    await waitDialogGone(page, '编辑菜单')
    const rowV2 = await findMenuRow(TEST_PAGE_V2)
    assert(rowV2, '保存后应能按新名定位测试行')
    const cells = await rowCells(rowV2)
    log(`  编辑后行: ${JSON.stringify(cells.slice(0, 6))}`)
    assertEq(cells[0], TEST_PAGE_V2, '行内名称应更新为新名')
    assertEq(cells[4], '停用', '行内状态应更新为 停用')
    const tagClass = await rowV2.locator('.el-tag').nth(1).getAttribute('class')
    assert((tagClass || '').includes('el-tag--danger'), `停用状态 tag 应为 danger，实际 ${tagClass}`)
    assertEq(cells[7], 'admin', '编辑后更新人应为 admin（审计透传）')
    await shot(page, 'm4-row-updated.png')
  })

  // ================= M5 删除约束：3005 拦截 → 确认框文案 → 自底向上 F→C→M 删净 =================
  await step('M5', '删除约束：删有子级目录 → 3005 toast 行保留 → 依次删 F→C→M → 行依次消失', async () => {
    // ---- 5a. 删有子级的目录 → 3005 ----
    const boxText = await deleteMenuViaUi(TEST_DIR)
    log(`  确认框内容: ${boxText}`)
    await waitToast(page, '存在子菜单', 'error')
    await shot(page, 'm5-3005-toast.png')
    const still = await findMenuRow(TEST_DIR)
    assert(still !== null, '3005 拦截后目录行应保留（自底向上删，不级联）')
    log('  3005 行保留 ✔')
    // ---- 5b. 自底向上：F → C → M ----
    await deleteMenuViaUi(TEST_FUNC)
    await waitToast(page, '删除成功')
    await sleep(800)
    assert((await findMenuRow(TEST_FUNC)) === null, `删除后 ${TEST_FUNC} 行应消失`)
    await deleteMenuViaUi(TEST_PAGE_V2)
    await waitToast(page, '删除成功')
    await sleep(800)
    assert((await findMenuRow(TEST_PAGE_V2)) === null, `删除后 ${TEST_PAGE_V2} 行应消失`)
    await deleteMenuViaUi(TEST_DIR)
    await waitToast(page, '删除成功')
    await sleep(800)
    assert((await findMenuRow(TEST_DIR)) === null, `删除后 ${TEST_DIR} 行应消失`)
    log('  F→C→M 自底向上删净 ✔')
    await shot(page, 'm5-after-delete.png')
  })

  // ================= CLEANUP 删净核验：无 E2E 残留 + 种子菜单仍在 =================
  await step('CLEANUP', '删净核验：树中无 E2E 前缀残留（保护 role e2e R5a 全选断言）；种子菜单仍在', async () => {
    await loadTreePage()
    const residue = await page.locator('.el-table__row', { hasText: 'E2E' }).count()
    log(`  E2E 残留行数: ${residue}`)
    assertEq(residue, 0, '清理后菜单树中不应残留任何 E2E 前缀行（残留会让 role e2e R5a admin 全选断言必红）')
    for (const seed of ['系统管理', '用户管理', '角色管理', '菜单管理', '认证管理', '用户新增']) {
      const row = await findMenuRow(seed, { reload: false })
      assert(row !== null, `种子菜单 ${seed} 应仍在（绝不删种子纪律核验）`)
    }
    const rowCount = await page.locator('.el-table__row').count()
    log(`  清理后总行数: ${rowCount}`)
    await shot(page, 'cleanup-final.png')
  })

  // ================= 证据核验：无 console error / pageerror / >=400 / 网络失败 =================
  await step('M-VERIFY', '证据核验：无 console error / pageerror / >=400 响应 / 网络失败', async () => {
    // favicon 404 是 dev server 无 favicon 的已知环境噪音（任何全新 context 首次加载必现，
    // user/role 脚本同受影响只是未断言）——资源类错误由下方 asset404 白名单精确甄别
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    const noiseFree404 = asset404.filter((u) => !u.includes('favicon'))
    assertEq(noiseFree404.length, 0, `非 favicon 的资产 404 不应存在，实际 ${JSON.stringify(asset404)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应（HTTP 恒 200；业务码在 body），实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  // ---------- 汇总 ----------
  h.summary({
    extras: [
      `\n测试数据: ${TEST_DIR} / ${TEST_PAGE}→${TEST_PAGE_V2} / ${TEST_FUNC}（perms ${TEST_PERMS}，应已在 M5 删净）`,
      '种子菜单（10/11/12/13/111… 20/21/211）未做任何写操作；权限快照时效语义未做"改完立即可用"断言（契约 §1）',
    ],
  })
  await browser.close()
}
