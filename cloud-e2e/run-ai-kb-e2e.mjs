/**
 * AI 知识库管理页 e2e（2026-10-15 知识库管理轮；契约 2026-10-14-ai-kb-api，plan I1）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202（种子 182/183 已落库）已启动；前端 dev 5173 已启动。
 * **cloud-base-ai 8010 与 Milvus 不需要起**——kb 四端点在浏览器网络层 route mock（fulfill 假 R JSON），
 * admin 登录走真实网关/sso/system（route mock 分层，同 run-ai-chat-e2e.mjs 先例）。
 * 运行：cd cloud-e2e && npm run e2e:aikb（单跑，不进 e2e 聚合——同 aichat 口径）；E2E_HEADLESS=1 无头。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：零后端写（kb 端点全 mock，内存态）；admin 种子账号只登录不改，种子菜单 182/183 零触碰。
 *
 * 场景（plan I1 AC1-AC6）：
 * - AC1 菜单可达：admin 登录 → AI 助手/知识库 → /rag/kb 左栏 mock 2 库 + 未选空态
 * - AC2 选中切换：点第 2 库 → files 请求 URL 带 kb_id + page=1&size=10 断言 + 表格渲染 mock 行
 * - AC3 新建流：空提交内联必填（0 请求）→ 填写提交 mock 200 → toast + 左栏刷新含新库且选中
 * - AC4 重名错误：mock {code:5003} → toast 文案断言 + 弹窗不关
 * - AC5 分页：mock total=15 → 翻第 2 页 → 请求 page=2 断言
 * - AC6 上传：mock upload 200 → 表格刷新 toast（断言 multipart 请求已发出）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, breadcrumbTexts, login, waitToast, waitDialogGone, waitTableIdle } = h

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

// ---------- kb 四端点 route mock（AI 服务不需起；登录链路放行走真实网关） ----------
const R = (data) => JSON.stringify({ code: 200, msg: '操作成功', data })
const json = (body) => ({ status: 200, contentType: 'application/json', body })

const kbs = [
  { id: 1, name: '产品手册库', description: '产品使用与安装手册' },
  { id: 2, name: '规章制度库', description: null },
]
// kb1: 15 行（AC5 翻页 total=15）；类型混 parent-child/recursion（映射验证）
const filesByKb = new Map([
  [
    1,
    Array.from({ length: 15 }, (_, i) => ({
      id: 100 - i,
      file_name: `guide-${i + 1}.pdf`,
      file_split_type: i % 3 === 1 ? 'recursion' : 'parent-child',
      create_time: '2026-10-15 09:30:00',
    })),
  ],
  [
    2,
    [
      { id: 300, file_name: '制度汇编.docx', file_split_type: 'parent-child', create_time: '2026-10-14 18:20:31' },
      { id: 301, file_name: '实验.txt', file_split_type: 'custom-x', create_time: '2026-10-15 10:00:00' },
    ],
  ],
])
let nextKbId = 100
let nextFileId = 500
let lastFilesUrl = '' // 最近一次 files 请求完整 URL（AC2/AC5 kb_id 与 page 断言锚）
let uploadSawMultipart = false // AC6 multipart 证据（route 层验 content-type）
let uploadKbIdParam = '' // AC6 kb_id query 参数证据

await page.route('**/api/ai/**', async (route) => {
  const req = route.request()
  const u = new URL(req.url())
  // 只拦网关代理前缀 /api/ai/**（防误拦 Vite dev 模块 URL——如 /src/api/xxx.ts 含 "/api/" 字样）
  if (!u.pathname.startsWith('/api/ai/')) {
    return route.fallback()
  }
  const p = u.pathname.replace(/^\/api/, '')
  const q = u.searchParams

  if (p === '/ai/rag/kb/list') {
    return route.fulfill(json(R(kbs)))
  }
  if (p === '/ai/rag/kb' && req.method() === 'POST') {
    const body = req.postDataJSON()
    if (kbs.some((kb) => kb.name === body.name)) {
      return route.fulfill(json(JSON.stringify({ code: 5003, msg: '知识库名称已存在', data: null })))
    }
    const id = nextKbId++
    kbs.push({ id, name: body.name, description: body.description })
    filesByKb.set(id, [])
    return route.fulfill(json(R(id)))
  }
  if (p === '/ai/rag/kb/upload' && req.method() === 'POST') {
    uploadSawMultipart = (req.headers()['content-type'] || '').includes('multipart/form-data')
    uploadKbIdParam = q.get('kb_id') ?? ''
    const kbId = Number(uploadKbIdParam)
    filesByKb.get(kbId)?.unshift({ id: nextFileId++, file_name: 'e2e-kb-upload.pdf', file_split_type: 'parent-child', create_time: '2026-10-15 11:00:00' })
    return route.fulfill(json(R(kbId)))
  }
  const filesMatch = p.match(/^\/ai\/rag\/kb\/(\d+)\/files$/)
  if (filesMatch) {
    lastFilesUrl = u.pathname + u.search
    const all = filesByKb.get(Number(filesMatch[1])) ?? []
    const page_ = Math.max(1, Number(q.get('page') ?? 1))
    const size = Math.min(200, Math.max(1, Number(q.get('size') ?? 10)))
    return route.fulfill(json(R({ total: all.length, rows: all.slice((page_ - 1) * size, page_ * size) })))
  }
  return route.fulfill(json(R(null)))
})

/** 页面侧 POST /ai/rag/kb 计数（AC3「空提交 0 请求」断言锚） */
const createPosts = () => h.state.apiCalls.filter((c) => c.method === 'POST' && c.url === '/api/ai/rag/kb').length
const filesGets = () => h.state.apiCalls.filter((c) => c.method === 'GET' && c.url.includes('/ai/rag/kb/') && c.url.includes('/files')).length

const kbItem = (name) => page.locator('.kb-item', { hasText: name }).first()

try {
  // ================= AC1 菜单可达 =================
  await step('AC1', '菜单可达：admin 登录 → AI 助手/知识库 → /rag/kb 左栏 mock 2 库 + 未选空态 + 面包屑', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（种子 182 已落库的前提锚）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    const menuItem = page.locator('.el-menu .el-menu-item', { hasText: '知识库' })
    assertEq(await menuItem.count(), 1, '侧边应恰 1 个「知识库」菜单项')
    await menuItem.click()
    await page.waitForURL('**/rag/kb', { timeout: 8000 })
    await kbItem('产品手册库').waitFor({ state: 'visible', timeout: 8000 })
    assertEq(await page.locator('.kb-item').count(), 2, '左栏应渲染 mock 2 库')
    const empty = page.locator('.kb-main .empty')
    await empty.waitFor({ state: 'visible', timeout: 5000 })
    assertEq((await empty.innerText()).trim(), '请选择左侧知识库', '未选库时右栏应为「请选择左侧知识库」空态')
    const bc = await breadcrumbTexts(page)
    assertEq(bc.join('/'), '首页/知识库', `面包屑应为 首页/知识库，实际 ${JSON.stringify(bc)}`)
    await shot(page, 'aikb-ac1-menu-reach.png')
  })

  // ================= AC2 选中切换 =================
  await step('AC2', '选中切换：点第 2 库 → files 请求带 kb_id=2 + page=1&size=10 → 表格 2 行 + tag 映射', async () => {
    await kbItem('规章制度库').click()
    await waitTableIdle(page)
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 5000 })
    assert(lastFilesUrl.includes('/ai/rag/kb/2/files'), `files 请求应带 kb_id=2，实际 ${lastFilesUrl}`)
    assert(lastFilesUrl.includes('page=1') && lastFilesUrl.includes('size=10'), `files 默认 page=1&size=10（AI 域参数名），实际 ${lastFilesUrl}`)
    assertEq(await page.locator('.el-table__row').count(), 2, '第 2 库应 2 行')
    const tags = page.locator('.el-table__row .el-tag')
    assertEq((await tags.nth(0).innerText()).trim(), '父子分片', 'parent-child 应映射「父子分片」')
    assertEq((await tags.nth(1).innerText()).trim(), 'custom-x', '未知分片类型应原样展示 custom-x')
    assertEq((await page.locator('.el-table__row').nth(0).locator('td').nth(2).innerText()).trim(), '2026-10-14 18:20:31', '上传时间应字符串直显')
    assert(((await kbItem('规章制度库').getAttribute('class')) || '').includes('active'), '选中库应有 active 样式')
    await shot(page, 'aikb-ac2-selected-table.png')
  })

  // ================= AC3 新建流 =================
  await step('AC3', '新建流：空提交内联必填（0 请求）→ 填写提交 mock 200 → toast + 左栏刷新含新库且选中', async () => {
    await page.getByRole('button', { name: '新建知识库' }).click()
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
    await sleep(300)
    assertEq(await page.locator('.kb-item').count(), 3, '新建后左栏应刷新为 3 库')
    assert(((await kbItem(newName).getAttribute('class')) || '').includes('active'), '新库应按返回 id 自动选中（active）')
    assertEq((await page.locator('.main-title').innerText()).trim(), newName, '右栏标题应为新库名')
    assert(lastFilesUrl.includes('/ai/rag/kb/100/files'), `新库选中应触发 files 请求（mock 新 id=100），实际 ${lastFilesUrl}`)
  })

  // ================= AC4 重名错误 =================
  await step('AC4', '重名错误：mock {code:5003} → toast「知识库名称已存在」+ 弹窗不关', async () => {
    await page.getByRole('button', { name: '新建知识库' }).click()
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

  // ================= AC5 分页 =================
  await step('AC5', '分页：选 15 行库 → 翻第 2 页 → 请求 page=2 断言 + 余 5 行', async () => {
    await kbItem('产品手册库').click()
    await waitTableIdle(page)
    await page.locator('.el-table__row').first().waitFor({ state: 'visible', timeout: 5000 })
    assertEq(await page.locator('.el-table__row').count(), 10, '首屏应 10 行（size=10）')
    const totalText = await page.locator('.el-pagination__total').innerText()
    assert(totalText.includes('15'), `分页 total 应显示 15（AI 域 number 直取），实际 "${totalText}"`)
    await page.locator('.el-pagination .el-pager li', { hasText: '2' }).click()
    await waitTableIdle(page)
    assert(lastFilesUrl.includes('page=2'), `翻页后 files 请求应带 page=2，实际 ${lastFilesUrl}`)
    assertEq(await page.locator('.el-table__row').count(), 5, '第 2 页应余 5 行（total 15 - 首页 10）')
    await shot(page, 'aikb-ac5-page2.png')
  })

  // ================= AC6 上传 =================
  await step('AC6', '上传：mock upload 200 → 表格刷新 toast + multipart 请求已发出断言（route 层验 content-type）', async () => {
    const filesBefore = filesGets()
    await page.locator('.kb-main input[type="file"]').setInputFiles({
      name: 'e2e-kb-upload.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-mock'),
    })
    await waitToast(page, '上传成功', 'success')
    await waitTableIdle(page)
    assertEq(filesGets(), filesBefore + 1, '上传成功后应重发 files 请求（回第一页刷新）')
    assert(lastFilesUrl.includes('page=1'), `刷新应回第一页（page=1），实际 ${lastFilesUrl}`)
    assert(uploadSawMultipart, 'upload 请求应为 multipart/form-data（route 层 content-type 证据）')
    assertEq(uploadKbIdParam, '1', `upload 请求 kb_id 应为当前选中库 1，实际 "${uploadKbIdParam}"`)
    const first = page.locator('.el-table__row').first()
    assert((await first.innerText()).includes('e2e-kb-upload.pdf'), '新上传文件应出现在表格首行（id 倒序）')
    await shot(page, 'aikb-ac6-upload-refresh.png')
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
      '\n测试数据: 零后端写（kb 四端点全 mock 内存态）；admin 种子账号只登录不改，种子菜单 182/183 零触碰',
      'mock 面: page.route(**/api/ai/**) fulfill 假 R JSON（登录链走真实网关/sso/system）——cloud-base-ai 8010 与 Milvus 未依赖',
    ],
  })
  await browser.close()
}
