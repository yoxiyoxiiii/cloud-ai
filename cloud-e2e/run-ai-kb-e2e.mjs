/**
 * AI 知识库列表管理页 e2e（契约：docs/superpowers/contracts/2026-10-14-ai-kb-api.md）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202（种子 182-185 已落库——184/185 为
 * 编辑/删除按钮权限，admin 须在落库后登录取新 perms 快照）已启动；前端 dev 5173 已启动。
 * **cloud-base-ai 8010 与 Milvus 不需要起**——kb 六端点在浏览器网络层 route mock（fulfill 假 R JSON），
 * admin 登录走真实网关/sso/system（route mock 分层，同 run-ai-chat-e2e.mjs 先例）。
 * 运行：cd cloud-e2e && npm run e2e:aikb（单跑，不进 e2e 聚合——同 aichat 口径）；E2E_HEADLESS=1 无头。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：零后端写（kb 端点全 mock 内存态）；admin 种子账号只登录不改，种子菜单零触碰。
 *
 * 场景（AC1-AC10 知识库列表管理——AC6 的删空回退子场景独立为 AC10；
 * AC11-AC13 文件重新向量化，契约 §8）：
 * - AC1 菜单可达：admin 登录 → AI 助手/知识库 → /rag/kb 表格 mock 2 行 + 分页 total + 操作列三按钮
 * - AC2 搜索：name 参数断言 + 结果过滤；无结果空态；重置回全量（name 参数不携带断言）
 * - AC3 新建流：空提交内联必填（0 请求）→ 填写提交 mock 200 → toast + 表格刷新（新库 id 倒序居首）
 * - AC4 重名错误：mock {code:5003} → toast 文案断言 + 弹窗不关
 * - AC5 编辑流：弹窗回显原值 → 改名提交 mock 200 → toast + 表格刷新；重名分支 mock 5003
 * - AC6 删除流：确认框出现（文案含库名）→ 确认 → mock 200 → toast + 行消失
 * - AC7 文件抽屉：drawer 打开 → files 请求 kb_id + page=1&size=10 断言 + 表格渲染 + 翻页 page=2
 * - AC8 上传：抽屉内 mock upload 200 → 表格刷新 toast（multipart content-type route 层断言）
 * - AC9 KB 表分页：mock total=15 → 翻第 2 页 → 请求 page=2 断言 + 余 5 行
 * - AC10 删空回退：第 2 页连删 5 行 → 删空自动回第 1 页（page=1 请求断言 + 10 行）
 * - AC11 重嵌入弹窗：抽屉行「重新向量化」按钮可见（ai:kb:update）→ 弹窗预填行快照 128/20 + 文件名只读
 * - AC12 提交流：关系校验（重叠≥分片）内联错误 0 请求 → 改参 200/30 提交 route 层 body 断言
 *   → toast + 弹窗关 + files 当前页（page=2）重发；重开弹窗预填 mock 回写后的新快照
 * - AC13 错误分支：mock {code:5006} → toast「文件无原件…」+ 弹窗不关
 *
 * 选择器注意：文件抽屉 el-drawer 关闭后 DOM 仅 hidden 残留（页面侧已加 append-to-body 挂 body）——
 * 主表格断言用 `.el-card__body > .el-table`（直接子级）双保险，避免误数抽屉内表格行
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, breadcrumbTexts, login, waitToast, waitDialogGone, waitTableIdle, rowCells } = h

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

// ---------- kb 六端点 route mock（AI 服务不需起；登录链路放行走真实网关） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const ERR = (code, msg) => JSON.stringify({ code, msg, data: null })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

// 内存态 KB 数据（AC9 前脚本侧扩容到 15；create/update/delete 分支同步维护）
const kbs = [
  { id: 1, name: '产品手册库', description: '产品使用与安装手册', create_time: '2026-10-14 18:00:00' },
  { id: 2, name: '规章制度库', description: null, create_time: '2026-10-15 09:00:00' },
]
// kb1: 15 行（AC7 翻页 total=15）；类型混 parent-child/recursion（映射验证）；kb2: 空库
// 行带 §7 两 additive 快照列（AC11 弹窗预填数据源；显示列无这两项，纯 mock 侧数据）
const filesByKb = new Map([
  [
    1,
    Array.from({ length: 15 }, (_, i) => ({
      id: 100 - i,
      file_name: `guide-${i + 1}.pdf`,
      file_split_type: i % 3 === 1 ? 'recursion' : 'parent-child',
      file_chunk_size: 128,
      file_chunk_overlap: 20,
      create_time: '2026-10-15 09:30:00',
    })),
  ],
  [2, []],
])
let nextKbId = 100
let nextFileId = 500
let lastListUrl = '' // 最近一次 list 请求完整 URL（AC2/AC9/AC10 page 与 name 断言锚）
let lastListName = undefined // 最近一次 list 请求 name 参数解码值（undefined=未携带）
let lastFilesUrl = '' // 最近一次 files 请求完整 URL（AC7/AC8 kb_id 与 page 断言锚）
let uploadSawMultipart = false // AC8 multipart 证据（route 层验 content-type）
let uploadKbIdParam = '' // AC8 kb_id query 参数证据
let reembedFail = false // AC13 开关：true → reembedding 回 5006（文件无原件）
let lastReembedBody = null // AC12 route 层 reembedding 请求 body 证据

await page.route('**/api/ai/**', async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  // 只拦网关代理前缀 /api/ai/**（防误拦 Vite dev 模块 URL——如 /src/api/xxx.ts 含 "/api/" 字样）
  if (!u.pathname.startsWith('/api/ai/')) {
    return route.fallback()
  }
  const p = u.pathname.replace(/^\/api/, '')
  const q = u.searchParams

  // GET /rag/kb/list：分页切片 + name 包含过滤（内存实现，id 倒序——契约 §4）
  if (p === '/ai/rag/kb/list' && req.method() === 'GET') {
    lastListUrl = u.pathname + u.search
    lastListName = q.has('name') ? q.get('name') : undefined
    const name = (q.get('name') ?? '').trim()
    const pageNum = Math.max(1, Number(q.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(q.get('size') ?? 10)))
    const hit = kbs.filter((kb) => !name || kb.name.includes(name)).sort((a, b) => b.id - a.id)
    return route.fulfill(json(R({ total: hit.length, rows: hit.slice((pageNum - 1) * size, pageNum * size) })))
  }
  if (p === '/ai/rag/kb' && req.method() === 'POST') {
    const body = req.postDataJSON()
    if (kbs.some((kb) => kb.name === body.name)) {
      return route.fulfill(json(ERR(5003, '知识库名称已存在')))
    }
    const id = nextKbId++
    kbs.push({ id, name: body.name, description: body.description, create_time: '2026-10-15 12:00:00' })
    filesByKb.set(id, [])
    return route.fulfill(json(R(id)))
  }
  // PUT/DELETE /rag/kb/{kb_id}：编辑（§5 重名排除自身 5003 / 不存在 5004）与删除（§6 二次删 5004）
  const kbMatch = p.match(/^\/ai\/rag\/kb\/(\d+)$/)
  if (kbMatch) {
    const kbId = Number(kbMatch[1])
    const kb = kbs.find((k) => k.id === kbId)
    if (req.method() === 'PUT') {
      const body = req.postDataJSON()
      if (!kb) {
        return route.fulfill(json(ERR(5004, '知识库不存在')))
      }
      if (kbs.some((k) => k.name === body.name && k.id !== kbId)) {
        return route.fulfill(json(ERR(5003, '知识库名称已存在')))
      }
      kb.name = body.name
      kb.description = body.description
      return route.fulfill(json(R(null)))
    }
    if (req.method() === 'DELETE') {
      if (!kb) {
        return route.fulfill(json(ERR(5004, '知识库不存在')))
      }
      kbs.splice(kbs.indexOf(kb), 1)
      filesByKb.delete(kbId)
      return route.fulfill(json(R(null)))
    }
  }
  if (p === '/ai/rag/kb/upload' && req.method() === 'POST') {
    uploadSawMultipart = (req.headers()['content-type'] || '').includes('multipart/form-data')
    uploadKbIdParam = q.get('kb_id') ?? ''
    const kbId = Number(uploadKbIdParam)
    filesByKb.get(kbId)?.unshift({ id: nextFileId++, file_name: 'e2e-kb-upload.pdf', file_split_type: 'parent-child', file_chunk_size: 128, file_chunk_overlap: 20, create_time: '2026-10-15 11:00:00' })
    return route.fulfill(json(R(kbId)))
  }
  // POST /rag/kb/reembedding（§8）：记录 body 供 AC12 route 层断言；reembedFail 开关回 5006（AC13）；
  // 成功按提交参回写 mock 行快照——对齐后端「重嵌入后回写文件表快照」语义（AC12 重开预填断言锚）
  if (p === '/ai/rag/kb/reembedding' && req.method() === 'POST') {
    lastReembedBody = req.postDataJSON()
    if (reembedFail) {
      return route.fulfill(json(ERR(5006, '文件无原件，请删除后重新上传')))
    }
    for (const arr of filesByKb.values()) {
      const f = arr.find((x) => x.id === lastReembedBody.file_id)
      if (f) {
        f.file_chunk_size = lastReembedBody.chunk_size
        f.file_chunk_overlap = lastReembedBody.chunk_overlap
        break
      }
    }
    return route.fulfill(json(R(null)))
  }
  const filesMatch = p.match(/^\/ai\/rag\/kb\/(\d+)\/files$/)
  if (filesMatch) {
    lastFilesUrl = u.pathname + u.search
    const all = filesByKb.get(Number(filesMatch[1])) ?? []
    const pageNum = Math.max(1, Number(q.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(q.get('size') ?? 10)))
    return route.fulfill(json(R({ total: all.length, rows: all.slice((pageNum - 1) * size, pageNum * size) })))
  }
  return route.fulfill(json(R(null)))
})

/** 页面侧请求计数（「0 请求」与「刷新再请求」断言锚） */
const createPosts = () => h.state.apiCalls.filter((c) => c.method === 'POST' && c.url === '/api/ai/rag/kb').length
const filesGets = () => h.state.apiCalls.filter((c) => c.method === 'GET' && c.url.includes('/ai/rag/kb/') && c.url.includes('/files')).length
const reembedPosts = () => h.state.apiCalls.filter((c) => c.method === 'POST' && c.url.includes('/ai/rag/kb/reembedding')).length

// 主表格 scope：抽屉关闭后 DOM 残留仅 hidden，行/分页断言一律限定 .el-card（drawer teleport 在 body）
const mainRow = (n) => page.locator('.el-card__body > .el-table .el-table__row').nth(n)
const mainRowCount = () => page.locator('.el-card__body > .el-table .el-table__row').count()
// 重嵌入弹窗输入定位：「重叠大小」的校验文案含「分片大小」字样，hasText('分片大小') 会命中两项——
// DOM 顺序分片在前，取 .first() 锁定；el-input-number 数值一律 inputValue 断言（值不进 innerText）
const sizeInputOf = (dlg) => dlg.locator('.el-form-item', { hasText: '分片大小' }).locator('input').first()
const overlapInputOf = (dlg) => dlg.locator('.el-form-item', { hasText: '重叠大小' }).locator('input').first()

try {
  // ================= AC1 菜单可达 =================
  await step('AC1', '菜单可达：admin 登录 → AI 助手/知识库 → /rag/kb 表格 mock 2 行 + 分页 total + 操作列三按钮', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（种子 182-185 已落库的前提锚）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const menuItem = page.locator('.el-menu .el-menu-item', { hasText: '知识库' })
    assertEq(await menuItem.count(), 1, '侧边应恰 1 个「知识库」菜单项')
    await menuItem.click()
    await page.waitForURL('**/rag/kb', { timeout: 8000 })
    await waitTableIdle(page)
    await mainRow(0).waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await mainRowCount(), 2, '表格应渲染 mock 2 库（id 倒序：库2 在首行）')
    const cells0 = await rowCells(mainRow(0))
    assertEq(cells0[0], '规章制度库', '首行应为 id 最大的「规章制度库」（id 倒序）')
    assertEq(cells0[1], '暂无描述', 'description null 应降级「暂无描述」')
    assertEq(cells0[2], '2026-10-15 09:00:00', '创建时间应字符串直显')
    const totalText = await page.locator('.el-card .el-pagination__total').innerText()
    assert(totalText.includes('2'), `分页 total 应显示 2（AI 域 number 直取），实际 "${totalText}"`)
    // 操作列三按钮（admin 落库 184/185 后登录含 ai:kb:update/ai:kb:delete，编辑/删除按钮可见性锚）
    for (const label of ['编辑', '删除', '文件管理']) {
      assertEq(await mainRow(0).getByRole('button', { name: label }).count(), 1, `操作列应有「${label}」按钮（admin 权限可见）`)
    }
    const bc = await breadcrumbTexts(page)
    assertEq(bc.join('/'), '首页/知识库', `面包屑应为 首页/知识库，实际 ${JSON.stringify(bc)}`)
    await shot(page, 'aikb-ac1-table.png')
  })

  // ================= AC2 搜索/重置 =================
  await step('AC2', '搜索：name 参数断言 + 结果过滤；无结果空态；重置回全量（name 不携带断言）', async () => {
    await page.locator('input[placeholder="名称（模糊）"]').fill('产品')
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    assert(lastListName === '产品', `list 请求应携带 name=产品，实际 "${lastListName}"`)
    assertEq(await mainRowCount(), 1, '搜索「产品」应过滤为 1 行（包含匹配）')
    const cells = await rowCells(mainRow(0))
    assertEq(cells[0], '产品手册库', '过滤结果应为「产品手册库」')

    // 无结果空态
    await page.locator('input[placeholder="名称（模糊）"]').fill('不存在的库xyz')
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    assertEq(await mainRowCount(), 0, '无结果应 0 行')
    const emptyText = (await page.locator('.el-card .el-table__empty-text').innerText()).trim()
    assertEq(emptyText, '暂无知识库', `空态文案应为「暂无知识库」，实际 "${emptyText}"`)
    await shot(page, 'aikb-ac2-empty.png')

    // 重置回全量：name 参数不携带（undefined）
    await page.getByRole('button', { name: '重置' }).click()
    await waitTableIdle(page)
    assert(lastListName === undefined, `重置后 list 请求不应携带 name 参数，实际 "${lastListName}"`)
    assertEq(await mainRowCount(), 2, '重置应回全量 2 行')
  })

  // ================= AC3 新建流 =================
  await step('AC3', '新建流：空提交内联必填（0 请求）→ 填写提交 mock 200 → toast + 表格刷新（新库居首）', async () => {
    await page.getByRole('button', { name: '新增知识库' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新建知识库' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await shot(page, 'aikb-ac3-create-dialog.png')
    const postsBefore = createPosts()
    await page.getByRole('button', { name: '保存' }).click()
    await sleep(400)
    assertEq(await page.locator('.el-form-item__error').count(), 2, '空提交应出 2 条内联必填（name+description 双必填）')
    assertEq(createPosts(), postsBefore, '空提交不得发出 create 请求（前端校验先行）')
    const newName = `e2e_kb_e2e_${Date.now()}`
    await dlg.locator('input').first().fill(newName)
    await dlg.locator('textarea').fill('aikb e2e mock 新建')
    await page.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '新建成功', 'success')
    await waitDialogGone(page, '新建知识库')
    await waitTableIdle(page)
    assertEq(await mainRowCount(), 3, '新建后表格应刷新为 3 行（回第一页）')
    const cells0 = await rowCells(mainRow(0))
    assertEq(cells0[0], newName, `新库应居首行（id 倒序，mock 新 id=100），实际 "${cells0[0]}"`)
  })

  // ================= AC4 重名错误 =================
  await step('AC4', '重名错误：mock {code:5003} → toast「知识库名称已存在」+ 弹窗不关', async () => {
    await page.getByRole('button', { name: '新增知识库' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '新建知识库' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await dlg.locator('input').first().fill('产品手册库')
    await dlg.locator('textarea').fill('重名验证')
    await page.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '知识库名称已存在', 'error')
    assert(await dlg.isVisible(), '5003 后弹窗应保持打开（拦截器 toast，页面 catch 留空不关窗）')
    await shot(page, 'aikb-ac4-dup-5003.png')
    await page.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '新建知识库')
  })

  // ================= AC5 编辑流 =================
  await step('AC5', '编辑流：弹窗回显原值 → 改名提交 mock 200 → toast + 表格刷新；重名分支 mock 5003', async () => {
    const row = page.locator('.el-card .el-table__row', { hasText: '规章制度库' }).first()
    await row.getByRole('button', { name: '编辑' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '编辑知识库' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await dlg.locator('input').first().inputValue(), '规章制度库', '编辑弹窗应回显原名称')
    assertEq(await dlg.locator('textarea').inputValue(), '', 'description null 应回显空串')
    await shot(page, 'aikb-ac5-edit-dialog.png')
    await dlg.locator('input').first().fill('规章制度库v2')
    await dlg.locator('textarea').fill('编辑改描述')
    await page.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '保存成功', 'success')
    await waitDialogGone(page, '编辑知识库')
    await waitTableIdle(page)
    assertEq(await page.locator('.el-card .el-table__row', { hasText: '规章制度库v2' }).count(), 1, '表格应显示编辑后名称')

    // 重名分支：编辑改名成他库现有名 → mock 5003 → toast + 弹窗不关
    const row2 = page.locator('.el-card .el-table__row', { hasText: '规章制度库v2' }).first()
    await row2.getByRole('button', { name: '编辑' }).click()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    await dlg.locator('input').first().fill('产品手册库')
    await page.getByRole('button', { name: '保存' }).click()
    await waitToast(page, '知识库名称已存在', 'error')
    assert(await dlg.isVisible(), '编辑重名 5003 后弹窗应保持打开')
    await page.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '编辑知识库')
  })

  // ================= AC6 删除流 =================
  await step('AC6', '删除流：确认框出现（文案含库名）→ 确认 → mock 200 → toast + 行消失', async () => {
    // 以首行定位删除目标（AC3 新建的 mock 库 id 最大居首，重名不可达）
    const row = mainRow(0)
    const cells = await rowCells(row)
    const target = cells[0]
    assert(target.startsWith('e2e_kb_'), `首行应为 AC3 新建的 mock 库，实际 "${target}"`)
    await row.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 5000 })
    assert((await box.innerText()).includes(target), '删除确认框文案应含目标库名')
    await shot(page, 'aikb-ac6-delete-confirm.png')
    await box.getByRole('button', { name: '确定' }).click()
    await waitToast(page, '删除成功', 'success')
    await waitTableIdle(page)
    assertEq(await page.locator('.el-card .el-table__row', { hasText: target }).count(), 0, '删除后行应消失')
    assertEq(await mainRowCount(), 2, '删除后应余 2 行')
  })

  // ================= AC7 文件抽屉 =================
  await step('AC7', '文件抽屉：点「文件管理」→ drawer 打开 → files 请求 kb_id + page=1&size=10 → 表格渲染 + 翻页 page=2', async () => {
    const row = page.locator('.el-card .el-table__row', { hasText: '产品手册库' }).first()
    await row.getByRole('button', { name: '文件管理' }).click()
    const drawer = page.locator('.el-drawer')
    await drawer.waitFor({ state: 'visible', timeout: 8000 })
    const titleText = (await drawer.locator('.el-drawer__header').innerText()).trim()
    assert(titleText.includes('产品手册库'), `抽屉标题应为库名「产品手册库」，实际 "${titleText}"`)
    assert(lastFilesUrl.includes('/ai/rag/kb/1/files'), `files 请求应带 kb_id=1，实际 ${lastFilesUrl}`)
    assert(lastFilesUrl.includes('page=1') && lastFilesUrl.includes('size=10'), `files 默认 page=1&size=10（AI 域参数名），实际 ${lastFilesUrl}`)
    await waitTableIdle(page)
    await drawer.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await drawer.locator('.el-table__row').count(), 10, '抽屉文件表应 10 行（15 行库 size=10 首页）')
    assertEq((await drawer.locator('.el-table__row .el-tag').first().innerText()).trim(), '父子分片', 'parent-child 应映射「父子分片」')
    const filesTotal = (await drawer.locator('.el-pagination__total').first().innerText()).trim()
    assert(filesTotal.includes('15'), `抽屉分页 total 应显示 15，实际 "${filesTotal}"`)
    await shot(page, 'aikb-ac7-files-drawer.png')
    // 抽屉内翻页 page=2
    await drawer.locator('.el-pagination .el-pager li', { hasText: '2' }).click()
    await waitTableIdle(page)
    assert(lastFilesUrl.includes('page=2'), `抽屉翻页后 files 请求应带 page=2，实际 ${lastFilesUrl}`)
    assertEq(await drawer.locator('.el-table__row').count(), 5, '抽屉第 2 页应余 5 行（total 15 - 首页 10）')
  })

  // ================= AC8 上传 =================
  await step('AC8', '上传：抽屉内 mock upload 200 → 表格刷新 toast（multipart route 层断言）', async () => {
    const filesBefore = filesGets()
    await page.locator('.el-drawer input[type="file"]').setInputFiles({
      name: 'e2e-kb-upload.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-mock'),
    })
    await waitToast(page, '上传成功', 'success')
    await waitTableIdle(page)
    assertEq(filesGets(), filesBefore + 1, '上传成功后应重发 files 请求（回第一页刷新）')
    assert(lastFilesUrl.includes('page=1'), `刷新应回第一页（page=1），实际 ${lastFilesUrl}`)
    assert(uploadSawMultipart, 'upload 请求应为 multipart/form-data（route 层 content-type 证据）')
    assertEq(uploadKbIdParam, '1', `upload 请求 kb_id 应为当前抽屉库 1，实际 "${uploadKbIdParam}"`)
    const first = page.locator('.el-drawer .el-table__row').first()
    assert((await first.innerText()).includes('e2e-kb-upload.pdf'), '新上传文件应出现在抽屉表格首行（id 倒序）')
    // 关闭抽屉（后续主表操作前清场；DOM 仅 hidden，主表断言已 .el-card scope 防串）
    await page.locator('.el-drawer__close-btn').click()
    await page.locator('.el-drawer').waitFor({ state: 'hidden', timeout: 5000 })
  })

  // ================= AC9 KB 表分页 =================
  await step('AC9', 'KB 表分页：mock 扩容 total=15 → 查询回第一页 → 翻第 2 页 → 请求 page=2 断言 + 余 5 行', async () => {
    // 脚本侧 mock 内存扩容（AC6 删 1 后 kbs=2，补 13 个到 15）——分页只验前端请求与渲染
    for (let i = 0; i < 13; i++) {
      kbs.push({ id: 200 + i, name: `e2e_kb_page_${i}`, description: '分页扩容', create_time: '2026-10-15 12:30:00' })
    }
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    assertEq(await mainRowCount(), 10, '扩容后第 1 页应 10 行（size=10）')
    const totalText = (await page.locator('.el-card .el-pagination__total').innerText()).trim()
    assert(totalText.includes('15'), `分页 total 应显示 15，实际 "${totalText}"`)
    await page.locator('.el-card .el-pagination .el-pager li', { hasText: '2' }).click()
    await waitTableIdle(page)
    assert(lastListUrl.includes('page=2'), `翻页后 list 请求应带 page=2，实际 ${lastListUrl}`)
    assertEq(await mainRowCount(), 5, '第 2 页应余 5 行（total 15 - 首页 10）')
    await shot(page, 'aikb-ac9-page2.png')
  })

  // ================= AC10 删空回退 =================
  await step('AC10', '删空回退：第 2 页连删 5 行 → 删空自动回第 1 页（page=1 请求断言 + 10 行）', async () => {
    for (let i = 0; i < 5; i++) {
      const row = mainRow(0)
      await row.getByRole('button', { name: '删除' }).click()
      const box = page.locator('.el-message-box')
      await box.waitFor({ state: 'visible', timeout: 5000 })
      await box.getByRole('button', { name: '确定' }).click()
      await waitToast(page, '删除成功', 'success')
      await waitTableIdle(page)
      await sleep(200)
    }
    // 最后一删时当前页仅剩 1 行且 page=2>1 → 前端应回退第 1 页再查
    assert(lastListUrl.includes('page=1'), `删空后应回退第 1 页（list 请求 page=1），实际 ${lastListUrl}`)
    assertEq(await mainRowCount(), 10, '回退第 1 页后应显示 10 行（余 10 库）')
  })

  // ================= AC11 重嵌入弹窗 =================
  await step('AC11', '重嵌入弹窗：抽屉行「重新向量化」按钮可见 → 打开 → 预填行快照 128/20 + 文件名只读', async () => {
    // 脚本侧 mock 造库（AC10 后主表余 10 个分页扩容库，id 300 居首）；12 文件供第 2 页断言
    kbs.push({ id: 300, name: 'e2e_kb_reembed_库', description: '重嵌入场景', create_time: '2026-10-16 10:00:00' })
    filesByKb.set(
      300,
      Array.from({ length: 12 }, (_, i) => ({
        id: 612 - i,
        file_name: `reembed-doc-${i + 1}.pdf`,
        file_split_type: 'parent-child',
        file_chunk_size: 128,
        file_chunk_overlap: 20,
        create_time: '2026-10-16 10:10:00',
      })),
    )
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    await mainRow(0).getByRole('button', { name: '文件管理' }).click()
    const drawer = page.locator('.el-drawer')
    await drawer.waitFor({ state: 'visible', timeout: 8000 })
    await drawer.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 5000 })
    // 翻第 2 页（2 行）——AC12「提交后 files 保持当前页」断言的前提页
    await drawer.locator('.el-pagination .el-pager li', { hasText: '2' }).click()
    await waitTableIdle(page)
    assertEq(await drawer.locator('.el-table__row').count(), 2, '文件表第 2 页应 2 行（12 文件 - 首页 10）')
    const row = drawer.locator('.el-table__row', { hasText: 'reembed-doc-11.pdf' }).first()
    assertEq(await row.getByRole('button', { name: '重新向量化' }).count(), 1, '操作列应有「重新向量化」按钮（admin 含 ai:kb:update）')
    await row.getByRole('button', { name: '重新向量化' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '重新向量化' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    // 预填断言用 inputValue（input 值不进 innerText）；「分片大小」关系错误文案含「分片大小」故取 .first() 锁定顺序
    assertEq(
      await dlg.locator('.el-form-item', { hasText: '文件名' }).locator('input').first().inputValue(),
      'reembed-doc-11.pdf',
      '文件名应显示行数据（只读展示）',
    )
    assertEq(await sizeInputOf(dlg).inputValue(), '128', '分片大小应预填行快照 128（契约 §7 列）')
    assertEq(await overlapInputOf(dlg).inputValue(), '20', '重叠大小应预填行快照 20（契约 §7 列）')
    await shot(page, 'aikb-ac11-dialog.png')
    await dlg.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '重新向量化')
  })

  // ================= AC12 提交流 =================
  await step('AC12', '提交流：关系校验内联错误 0 请求 → 改参 200/30 提交 body 断言 → toast + 弹窗关 + files 当前页重发；重开预填回写快照', async () => {
    const drawer = page.locator('.el-drawer')
    const row = drawer.locator('.el-table__row', { hasText: 'reembed-doc-11.pdf' }).first()
    await row.getByRole('button', { name: '重新向量化' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '重新向量化' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    const sizeInput = sizeInputOf(dlg)
    const overlapInput = overlapInputOf(dlg)
    // 关系校验：重叠 20 ≥ 改后分片 20 → 内联错误 + 零请求（el-input-number 须 Tab 触发 change/blur）
    const postsBefore = reembedPosts()
    await sizeInput.fill('20')
    await sizeInput.press('Tab')
    await sleep(600)
    const err = dlg.locator('.el-form-item__error')
    await err.first().waitFor({ state: 'visible', timeout: 3000 })
    assert((await err.first().innerText()).includes('重叠大小须小于分片大小'), `内联文案应含关系提示，实际 "${await err.first().innerText()}"`)
    assertEq(reembedPosts(), postsBefore, '校验失败不得发出 reembedding 请求')
    // 合法参数提交（mock 200 → 快照回写 + R(null)）
    const filesBefore = filesGets()
    await sizeInput.fill('200')
    await sizeInput.press('Tab')
    await overlapInput.fill('30')
    await overlapInput.press('Tab')
    await sleep(300)
    assertEq(await dlg.locator('.el-form-item__error').count(), 0, '参数合法后内联错误应消失')
    await dlg.getByRole('button', { name: '重新向量化' }).click()
    await waitToast(page, '重新向量化完成', 'success')
    await shot(page, 'aikb-ac12-success.png')
    await waitDialogGone(page, '重新向量化')
    // route 层 body 断言（契约 §8：恒带 file_id + 两参）
    assert(lastReembedBody != null, 'route 层应记录到 reembedding 请求 body')
    assertEq(lastReembedBody.file_id, 602, `body.file_id 应为目标行 602，实际 ${lastReembedBody.file_id}`)
    assertEq(lastReembedBody.chunk_size, 200, `body.chunk_size 应为 200，实际 ${lastReembedBody.chunk_size}`)
    assertEq(lastReembedBody.chunk_overlap, 30, `body.chunk_overlap 应为 30，实际 ${lastReembedBody.chunk_overlap}`)
    assertEq(Object.keys(lastReembedBody).sort().join(','), 'chunk_overlap,chunk_size,file_id', 'body 应恰含三键（无多余字段）')
    // files 重发且保持当前页（提交发生在第 2 页）
    assertEq(filesGets(), filesBefore + 1, '成功后应重发 files 请求（当前页刷新）')
    assert(lastFilesUrl.includes('page=2'), `刷新应保持当前页（page=2），实际 ${lastFilesUrl}`)
    // 重开弹窗：mock 已按提交参回写行快照 → 预填新值（对齐后端快照回写语义）
    await row.getByRole('button', { name: '重新向量化' }).click()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await sizeInput.inputValue(), '200', '重开后分片大小应预填回写后的快照 200')
    assertEq(await overlapInput.inputValue(), '30', '重开后重叠大小应预填回写后的快照 30')
    await dlg.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '重新向量化')
  })

  // ================= AC13 错误分支 =================
  await step('AC13', '错误分支：mock {code:5006} → toast「文件无原件」+ 弹窗不关', async () => {
    reembedFail = true
    const drawer = page.locator('.el-drawer')
    const row = drawer.locator('.el-table__row', { hasText: 'reembed-doc-11.pdf' }).first()
    await row.getByRole('button', { name: '重新向量化' }).click()
    const dlg = page.locator('.el-dialog', { hasText: '重新向量化' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 5000 })
    // 预填即 AC12 回写后的 200/30（合法），直接提交走 5006 分支
    await dlg.getByRole('button', { name: '重新向量化' }).click()
    await waitToast(page, '文件无原件，请删除后重新上传', 'error')
    assert(await dlg.isVisible(), '5006 后弹窗应保持打开（拦截器 toast，页面 catch 留空不关窗）')
    await shot(page, 'aikb-ac13-5006.png')
    await dlg.getByRole('button', { name: '取消' }).click()
    await waitDialogGone(page, '重新向量化')
    // 关抽屉清场 + 还原开关
    await page.locator('.el-drawer__close-btn').click()
    await page.locator('.el-drawer').waitFor({ state: 'hidden', timeout: 5000 })
    reembedFail = false
  })

  // ================= 证据核验 =================
  await step('AC-VERIFY', '证据核验：无 console error / pageerror / >=400 /api / 网络失败', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应，实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  h.summary({
    extras: [
      '\n测试数据: 零后端写（kb 七端点全 mock 内存态，含 reembedding）；admin 种子账号只登录不改，种子菜单零触碰',
      'mock 面: page.route(**/api/ai/**) fulfill 假 R JSON（登录链走真实网关/sso/system）——cloud-base-ai 8010 与 Milvus 未依赖',
    ],
  })
  await browser.close()
}
