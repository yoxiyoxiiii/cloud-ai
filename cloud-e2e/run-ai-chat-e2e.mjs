/**
 * AI 对话页 e2e（R13 AI 对话轮；契约 2026-10-13-ai-rag-chat-api §3 帧协议/§6.2 快照，设计 D14 route mock 分层）
 *
 * 运行前提：后端 gateway 18080 / sso 9201 / system 9202（种子 18/181 已落库）已启动；前端 dev 5173 已启动。
 * **cloud-base-ai 8010 不需要起**——SSE 流在浏览器网络层 route mock（page.route fulfill 假流），零真实 LLM 依赖。
 * 运行：cd cloud-e2e && npm run e2e:aichat（单跑）；E2E_HEADLESS=1 无头。
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码。
 * 测试数据：零后端写（会话是页面前端本地态，刷新即丢——设计 §3）；admin 种子账号只登录不改。
 *
 * 场景（plan F2 / 设计 D14）：
 * - AC1 菜单可达：admin 登录 → 侧边「AI 助手」目录（default-openeds 展开）→「对话」→ /rag/chat 空态文案锚
 * - AC2 流式渲染：mock 3 帧（加粗 + py 代码块 + [DONE]）→ MdRenderer 渲染断言（strong 元素 / language-py / hljs 高亮）
 * - AC3 停止生成：route 延迟 fulfill 挂起 → 「思考中…」占位 + 停止按钮 + 输入禁用 → 点击 abort → 输入恢复
 *   （abort 网络证据 = requestfailed(net::ERR_ABORTED) 恰 +1；挂起窗按有头 slowMo 放大，plan 800ms 基线见注）
 * - AC4 断流兜底：两帧无 [DONE] → sse.ts 定死行为=按完成兜底（内容完整、无错误括号包裹、输入恢复）
 * - AC5 错误帧：data:{"msg":...} + [DONE] → 空气泡显示「（LLM 调用失败）」（onError 空内容兜底文案）
 * - AC6 会话本地态：新建对话 → 首问标题=前 16 字截断 → 删除回空态（全程零 /api 调用——纯本地行为锚）
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, breadcrumbTexts, login } = h

const CHAT_PATH = '/rag/chat'
/** 流式端点（dev 5173 代理前形态：页面 fetch 相对路径 /api/ai/rag/chat/stream） */
const STREAM_GLOB = '**/api/ai/rag/chat/stream'

// ---------- 主流程（默认有头 + slowMo 300，与其余脚本一致） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
h.state.ctx = ctx
h.state.page = page
h.attachListeners(page)

// ---------- SSE route mock（设计 D14：网络层假流，AI 服务不需起） ----------
/** 当前场景假流响应体 / fulfill 前挂起毫秒数（AC3 用）——每场景先行设置 */
let mockBody = ''
let mockDelayMs = 0
await page.route(STREAM_GLOB, async (route) => {
  if (mockDelayMs > 0) await sleep(mockDelayMs)
  try {
    await route.fulfill({ status: 200, contentType: 'text/event-stream; charset=utf-8', body: mockBody })
  } catch {
    /* AC3 停止生成后请求已 abort，fulfill 可能抛错——预期路径，忽略 */
  }
})

/** SSE 帧构造（契约 §3：每帧一个 data: 行 + 空行分隔；JSON.stringify 天然不转义中文以外的字符形态） */
const frame = (payload) => `data: ${JSON.stringify(payload)}\n\n`
const DONE_FRAME = 'data: [DONE]\n\n'

/** 发送问题（页面主路径：textarea + Enter，@keydown.enter.exact.prevent） */
async function sendQuestion(q) {
  const ta = page.locator('.input-bar textarea')
  await ta.fill(q)
  await ta.press('Enter')
}

/** 等流式结束回稳态：输入框恢复可用 + 发送按钮回归（停止生成按钮消失） */
async function waitStreamSettled(timeout = 10000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    const ta = page.locator('.input-bar textarea')
    const sendBtn = page.getByRole('button', { name: '发送' })
    if ((await ta.count()) > 0 && !(await ta.isDisabled()) && (await sendBtn.count()) > 0 && (await sendBtn.isVisible())) return
    await sleep(150)
  }
  throw new Error('等待流式结束（输入框恢复可用）超时')
}

/** 流请求的网络层失败清单（AC3 预期 abort 证据 / AC-VERIFY 甄别用） */
const streamFails = () => h.state.requestFailures.filter((f) => f.url.includes('/ai/rag/chat/stream'))

/** 场景前置守卫：AC2 起各场景都假定已在 /rag/chat（AC1 导航产物）——显式断言而非隐式依赖，
 *  杜绝「AC1 失败但后续场景因页面恰好正确而无效通过」（有头窗口存在人工点击干扰的可能，首跑实证） */
function ensureChatPage() {
  assertEq(new URL(page.url()).pathname, CHAT_PATH, `前置守卫：本场景须已在 ${CHAT_PATH}（AC1 菜单导航产物），实际 ${page.url()}`)
}

try {
  // ================= AC1 菜单可达 =================
  await step('AC1', '菜单可达：admin 登录 → AI 助手/对话 → /rag/chat 空态文案锚 + 面包屑/高亮', async () => {
    const r = await login(page, 'admin', 'admin123')
    assert(r.ok, `admin 登录应成功（种子 18/181 已落库的前提锚）: ${r.msg || ''}`)
    await page.locator('.sidebar-menu').waitFor({ state: 'visible', timeout: 10000 })
    // 侧边：AI 助手为第 3 个根目录（M sort 4，default-openeds 全 M 展开——子项初始即可见）
    const subTitles = page.locator('.el-menu .el-sub-menu__title')
    const stn = await subTitles.count()
    const stLabels = []
    for (let i = 0; i < stn; i++) stLabels.push((await subTitles.nth(i).innerText()).trim())
    log(`  侧边目录(M): ${JSON.stringify(stLabels)}`)
    assertEq(stLabels.join(','), '系统管理,流程管理,AI 助手', `侧边目录应为 系统管理,流程管理,AI 助手（AI 助手 M sort 4 在最后），实际 ${JSON.stringify(stLabels)}`)
    const menuItem = page.locator('.el-menu .el-menu-item', { hasText: '对话' })
    assertEq(await menuItem.count(), 1, `侧边应恰 1 个「对话」菜单项，实际 ${await menuItem.count()}`)
    await menuItem.click()
    await page.waitForURL(`**${CHAT_PATH}`, { timeout: 8000 })
    // 空态文案锚（无会话时 .empty）
    const empty = page.locator('.rag-chat .empty')
    await empty.waitFor({ state: 'visible', timeout: 8000 })
    assertEq((await empty.innerText()).trim(), '输入问题，开始知识库对话', '空态文案锚应为「输入问题，开始知识库对话」')
    const bc = await breadcrumbTexts(page)
    assertEq(bc.join('/'), '首页/对话', `面包屑应为 首页/对话，实际 ${JSON.stringify(bc)}`)
    assertEq((await page.locator('.el-menu-item.is-active').innerText()).trim(), '对话', `${CHAT_PATH} 下「对话」应高亮`)
    await shot(page, 'ac1-menu-reach.png')
  })

  // ================= AC2 流式渲染 =================
  await step('AC2', '流式渲染：mock 3 帧（加粗 + py 代码块 + [DONE]）→ MdRenderer strong/language-py/hljs 断言', async () => {
    mockDelayMs = 0
    mockBody = frame({ content: '**加粗**' }) + frame({ content: '\n\n```py\nprint(1)\n```' }) + DONE_FRAME
    ensureChatPage()
    await page.getByRole('button', { name: '新建对话' }).click()
    const q = 'e2e流式渲染验证'
    await sendQuestion(q)
    await waitStreamSettled()
    // 用户气泡：原文直出（非 MdRenderer）
    assertEq((await page.locator('.msg.user .bubble').innerText()).trim(), q, `用户气泡应为原文 "${q}"`)
    // assistant 气泡：MdRenderer 渲染锚（80ms 节流重渲——waitFor 轮询兜底）
    const mdBody = page.locator('.msg.assistant .bubble .md-body')
    const strong = mdBody.locator('strong')
    await strong.first().waitFor({ state: 'visible', timeout: 5000 })
    assertEq((await strong.first().innerText()).trim(), '加粗', 'markdown strong 元素应渲染「加粗」')
    assert((await mdBody.locator('pre code').count()) >= 1, '应渲染代码块 pre>code')
    const codeCls = (await mdBody.locator('pre code').first().getAttribute('class')) || ''
    assert(codeCls.includes('language-py'), `代码块应带 language-py 类（markdown-it langPrefix），实际 "${codeCls}"`)
    assert((await mdBody.locator('[class*="hljs-"]').count()) >= 1, 'highlight.js 高亮 span 应存在（class 含 hljs-）')
    log(`  代码块 class="${codeCls}"`)
    await shot(page, 'ac2-stream-render.png')
  })

  // ================= AC3 停止生成 =================
  await step('AC3', '停止生成：延迟 fulfill 挂起 → 思考中…占位 + 停止按钮 + 输入禁用 → abort → 输入恢复', async () => {
    // plan 基线 800ms——有头 slowMo 300ms 下断言链（占位/按钮/禁用三查 + click）耗时超 800ms 窗口会漏拍，
    // 放大挂起窗至 4000ms（mock 语义不变：fetch pending 期无任何帧到达）
    mockDelayMs = 4000
    mockBody = frame({ content: '不应到达' }) + DONE_FRAME
    ensureChatPage()
    await page.getByRole('button', { name: '新建对话' }).click()
    await sendQuestion('e2e停止生成验证')
    // 挂起期三态：思考中…占位（sse.ts 未收首 token）+ 停止生成按钮 + 输入禁用
    const thinking = page.locator('.msg.assistant .bubble', { hasText: '思考中…' })
    await thinking.first().waitFor({ state: 'visible', timeout: 3000 })
    const stopBtn = page.getByRole('button', { name: '停止生成' })
    assert(await stopBtn.isVisible(), '流式挂起期「停止生成」按钮应可见')
    assert(await page.locator('.input-bar textarea').isDisabled(), '流式挂起期输入框应禁用')
    // 点击停止 → AbortController abort → fetch 中断 → finally 恢复输入
    const failsBefore = streamFails().length
    await stopBtn.click()
    await waitStreamSettled()
    assertEq(streamFails().length - failsBefore, 1, `停止生成应触发恰 1 次流请求网络中断（AbortController abort 证据），实际 +${streamFails().length - failsBefore}：${JSON.stringify(streamFails())}`)
    // abort 后气泡观察记档（sse.ts 定死行为：pending 期 abort 走 onError 空内容兜底文案）
    const bubbleText = (await page.locator('.msg.assistant .bubble .md-body').first().innerText()).trim()
    log(`  停止后 assistant 气泡文本（观察项）: "${bubbleText}"`)
    await shot(page, 'ac3-stop-generate.png')
  })

  // ================= AC4 断流兜底 =================
  await step('AC4', '断流兜底：两帧无 [DONE] → 按完成兜底（内容完整呈现、无错误包裹、输入恢复）', async () => {
    mockDelayMs = 0
    mockBody = frame({ content: '兜底' }) + frame({ content: '帧二' }) // 契约 §3：断流是服务端 bug；前端 sse.ts 定死按完成兜底
    ensureChatPage()
    await page.getByRole('button', { name: '新建对话' }).click()
    await sendQuestion('e2e断流兜底验证')
    await waitStreamSettled()
    const mdBody = page.locator('.msg.assistant .bubble .md-body')
    await mdBody.first().waitFor({ state: 'visible', timeout: 5000 })
    const text = (await mdBody.first().innerText()).trim()
    assertEq(text, '兜底帧二', `断流兜底应按完成处理（两帧内容完整拼接），实际 "${text}"`)
    assert(!text.startsWith('（') && !text.endsWith('）'), `兜底不应表现为错误气泡（无（…）包裹），实际 "${text}"`)
    await shot(page, 'ac4-broken-stream.png')
  })

  // ================= AC5 错误帧 =================
  await step('AC5', '错误帧：{"msg":"LLM 调用失败"} + [DONE] → 空气泡显示（LLM 调用失败）', async () => {
    mockDelayMs = 0
    mockBody = frame({ msg: 'LLM 调用失败' }) + DONE_FRAME
    ensureChatPage()
    await page.getByRole('button', { name: '新建对话' }).click()
    await sendQuestion('e2e错误帧验证')
    await waitStreamSettled()
    const mdBody = page.locator('.msg.assistant .bubble .md-body')
    await mdBody.first().waitFor({ state: 'visible', timeout: 5000 })
    // onError 空内容兜底：content = `（${msg}）`（页面定死行为，契约 §3 错误帧消费）
    assertEq((await mdBody.first().innerText()).trim(), '（LLM 调用失败）', '错误帧后空气泡应显示「（LLM 调用失败）」')
    await shot(page, 'ac5-error-frame.png')
  })

  // ================= AC6 会话本地态 =================
  await step('AC6', '会话本地态：新建对话 → 首问标题前 16 字截断 → 删除回空态（零 /api 调用）', async () => {
    mockDelayMs = 0
    mockBody = frame({ content: '本地会话' }) + DONE_FRAME
    ensureChatPage()
    // 清场：删除此前场景累积的本地会话 → 回空态
    let guard = 0
    while ((await page.locator('.session-item').count()) > 0 && guard++ < 20) {
      await page.locator('.session-item .del').first().click()
      await sleep(150)
    }
    const empty = page.locator('.rag-chat .empty')
    await empty.waitFor({ state: 'visible', timeout: 5000 })
    // a. 新建对话：入列表置顶激活，初始标题「新对话」
    await page.getByRole('button', { name: '新建对话' }).click()
    const item = page.locator('.session-item').first()
    await item.waitFor({ state: 'visible', timeout: 5000 })
    assert(((await item.getAttribute('class')) || '').includes('active'), '新会话应即时激活（active 类）')
    assertEq((await item.locator('.title').innerText()).trim(), '新对话', '新会话初始标题应为「新对话」')
    assertEq(await page.locator('.session-item').count(), 1, '新建后会话列表应恰 1 项')
    // b. 首问标题 = 前 16 字（22 字问题截 16，非全文）
    const longQ = 'e2e会话标题十六字验证问题一二三四五六七八'
    await sendQuestion(longQ)
    await waitStreamSettled()
    const title = (await item.locator('.title').innerText()).trim()
    assertEq(title, longQ.slice(0, 16), `首问后标题应为前 16 字 "${longQ.slice(0, 16)}"，实际 "${title}"`)
    assert(!title.includes(longQ.slice(16)), `标题不应含第 17 字起的余文（${longQ.slice(16)}），实际 "${title}"`)
    // c. 删除会话：纯本地行为（零后端调用）→ 列表清空回空态
    const apiBefore = h.state.apiCalls.length
    await item.locator('.del').click()
    await sleep(300)
    assertEq(await page.locator('.session-item').count(), 0, '删除后会话列表应清空')
    await empty.waitFor({ state: 'visible', timeout: 5000 })
    assertEq(h.state.apiCalls.length, apiBefore, `删除会话是纯本地行为，不应触发任何 /api 调用，实际 +${h.state.apiCalls.length - apiBefore}`)
    await shot(page, 'ac6-session-local.png')
  })

  // ================= 证据核验 =================
  await step('AC-VERIFY', '证据核验：无 console error / pageerror / >=400 /api；网络失败仅 AC3 预期 abort', async () => {
    // favicon 404 是 dev server 已知环境噪音；AC3 abort 的 net::ERR_ABORTED 资源日志是停止生成的预期副产物（同款白名单）
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const NOISE_ABORT = /net::ERR_ABORTED/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text) && !NOISE_ABORT.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 与 AC3 abort 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应，实际 ${JSON.stringify(h.state.badResponses)}`)
    const others = h.state.requestFailures.filter((f) => !f.url.includes('/ai/rag/chat/stream'))
    assertEq(others.length, 0, `流请求之外不应有网络失败，实际 ${JSON.stringify(others)}`)
    assertEq(streamFails().length, 1, `流请求网络失败应恰 1 次（AC3 停止生成的预期 abort），实际 ${JSON.stringify(streamFails())}`)
  })
} finally {
  h.summary({
    extras: [
      '\n测试数据: 零后端写（会话为页面前端本地态，刷新即丢）；admin 种子账号只登录不改，种子菜单 18/181 零触碰',
      'mock 面: page.route(**/api/ai/rag/chat/stream) fulfill 假 SSE 流——cloud-base-ai 8010 未起，零真实 LLM 调用',
    ],
  })
  await browser.close()
}
