/**
 * MQ 死信治理 e2e 第十脚本（契约 2026-10-11-mq-dlq-governance-api §1-§4 / 设计 D6 状态机 /
 * 计划 2026-10-11-mq-dlq-governance E1；harness 复用 lib/harness.mjs——沿 dataperm 家族骨架）
 *
 * 运行前提：gateway 18080 / sso 9201 / system 9202（死信治理版）/ bpmn 9203 已启动；前端 dev 5173 已启动；
 *   数据前提 = B7 联调预置行（mq_dlq_log 内 msgKey 含 e2e-dlq 子串锚的行 ≥2，含同 msgKey 代际链 ≥2 行且须留一行待处理——MDQ3/5/6 依赖；KEYS 实际形态 leave:e2e-dlq-<ts>；代际链计数以台账实际为准，重投/再死信会自然增长）——
 *   前置探测缺失即 SKIP（防环境漂移假红），绝不自造死信（黑盒纪律外，设计 D12 记档）。
 * 运行：cd cloud-e2e && npm run e2e:mqdlq（有头 + slowMo 300；--headless 无头）
 * 黑盒纪律：只经 URL 与选择器交互，禁止 import 前端工程内部代码；preflight 直连网关 raw fetch。
 *
 * 场景（计划 E1 全文）：
 * - MDQ0 前置健康探测（admin 登录 + 台账 API ≥2 行 e2e-dlq + VO 17 字段完整性 + 详情 body 面）——缺失 SKIP
 * - MDQ1 浏览器登录 + 侧边栏「系统管理/死信治理」菜单出现（权限快照）+ 进入列表（面包屑/12 列表头）
 * - MDQ2 列表渲染：清空状态过滤 → 全量行；代际链标记（同 msgKey 两行各带「代际链 N 行」）；
 *   状态标签四态字典译文；重试次数列=4（broker 口径，契约 §3.1 修订版）
 * - MDQ3 过滤：状态=待处理 → 仅待处理行；msgKey 模糊 e2e-dlq → 行集收窄且每行含 e2e-dlq
 * - MDQ4 详情弹窗：标题/消息体原文展示/msgId 与列表一致（首 20 字符锚）/bodyTruncated 提示区存在性
 * - MDQ5 按钮可见性（契约 §4）：待处理行=详情+重投+忽略（无重置）；已重投行=详情+重投（无忽略/重置）
 * - MDQ6 忽略操作（B7 专用行）：确认弹窗含终态提示 → 行变已忽略 + 重置按钮出现
 * - MDQ7 重置恢复（数据回原状，可重复跑）：已忽略行重置 → 回待处理 + 重投次数清零
 * - MDQ-VERIFY 环境卫生（console/pageerror/>=400/网络失败——favicon 404 噪音滤除同款）
 *
 * 已放弃态按钮断言不在本脚本面（当前台账无已放弃行；mock 自证 + B7 curl 已覆盖——记档）。
 * 测试数据：不新建任何数据（台账行是 B7 预置资产）；MDQ6→MDQ7 状态流转后回原状（待处理/计数 0）；
 *   admin 仅登录与页面操作，不改种子（mq_dlq_log 行非种子）。
 */
import { chromium } from 'playwright'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHarness } from './lib/harness.mjs'

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173'
const ART = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
const GATEWAY = process.env.E2E_GATEWAY || 'http://localhost:18080'

const h = createHarness({ base: BASE, artDir: ART })
const { log, sleep, step, assert, assertEq, shot, login } = h
const { waitTableIdle, rowCells } = h

const PAGE_PATH = '/system/mq-dlq'
/** 表头 12 列（实现面 index.vue 列序逐字：0 消息键/1 消息 ID/2 原 topic/3 tag/4 来源组/5 body 预览/
 *  6 重试次数/7 进死信时间/8 处置状态/9 重投次数/10 处置人/11 操作） */
const HEADERS = ['消息键（msgKey）', '消息 ID', '原 topic', 'tag', '来源组', 'body 预览', '重试次数', '进死信时间', '处置状态', '重投次数', '处置人', '操作']
const COL = { MSG_KEY: 0, MSG_ID: 1, RETRY: 6, STATUS: 8, RESEND: 9 }
/** 契约 §3.1 MqDlqVo 17 字段 */
const VO_FIELDS = ['id', 'msgId', 'txNo', 'bornTopic', 'bornGroup', 'watchGroup', 'tag', 'msgKey', 'bodyPreview', 'reconsumeTimes', 'bornTime', 'dlqTime', 'status', 'resendCount', 'lastResendTime', 'operateAccount', 'operateTime']

// ---------- API 助手（preflight 直连网关；Node fetch UTF-8 无 GBK 陷阱） ----------
async function apiGet(pathWithQuery, token) {
  const res = await fetch(`${GATEWAY}${pathWithQuery}`, { headers: { Authorization: `Bearer ${token}` } })
  if (res.status !== 200) throw new Error(`HTTP ${res.status}（${pathWithQuery}）——契约恒 200`)
  return await res.json()
}

// ---------- 前置健康探测（缺失即 SKIP，不硬跑） ----------
async function preflight() {
  try {
    const lb = await fetch(`${GATEWAY}/sso/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }),
    }).then((r) => r.json())
    if (lb.code !== 200) return { ok: false, reason: `admin 登录 code=${lb.code} msg="${lb.msg}"` }
    const token = lb.data.accessToken
    const pageBody = await apiGet('/system/mq-dlq/page?pageNum=1&pageSize=50', token)
    if (pageBody.code !== 200) return { ok: false, reason: `台账分页 code=${pageBody.code} msg="${pageBody.msg}"` }
    const rows = pageBody.data.rows || []
    // B7 造死信 KEYS=leave:e2e-dlq-<ts>（带 leave: 业务前缀）——e2e-dlq 是子串锚非前缀锚
    const dlqRows = rows.filter((r) => String(r.msgKey || '').includes('e2e-dlq'))
    if (dlqRows.length < 2) {
      return { ok: false, reason: `数据前提缺失：e2e-dlq 前缀行 ${dlqRows.length} < 2（B7 预置资产缺失——需重跑 B7 造死信链）` }
    }
    const missing = VO_FIELDS.filter((f) => !(f in rows[0]))
    if (missing.length) return { ok: false, reason: `MqDlqVo 缺字段 ${missing.join(',')}` }
    const det = await apiGet(`/system/mq-dlq/${rows[0].id}`, token)
    if (det.code !== 200 || !('body' in (det.data || {})) || !('bodyTruncated' in (det.data || {}))) {
      return { ok: false, reason: `详情面异常 code=${det.code}` }
    }
    return { ok: true, token, rows }
  } catch (e) {
    return { ok: false, reason: `探测异常: ${e.message}` }
  }
}
const pre = await preflight()
if (!pre.ok) {
  log(`\n[SKIP] 前置健康探测未通过: ${pre.reason}`)
  log('按卡点纪律 SKIP 退出（不硬跑）——请确认 gateway/sso/system/bpmn 与 dev 5173 已启动，且 B7 预置行（msgKey 含 e2e-dlq ≥2）在库')
  h.summary({ extras: [`\nSKIP 原因: ${pre.reason}`] })
  process.exit(0)
}
const preDlqN = pre.rows.filter((r) => String(r.msgKey).includes('e2e-dlq')).length
log(`前置健康探测通过（台账 ${pre.rows.length} 行，e2e-dlq 锚 ${preDlqN} 行，VO 17 字段与详情 body 面完好）`)

/** 代际链锚 msgKey（同 msgKey 两行的键——台账内出现 ≥2 次的 e2e-dlq 键；无则退化首个） */
const keyCount = {}
for (const r of pre.rows) if (String(r.msgKey).includes('e2e-dlq')) keyCount[r.msgKey] = (keyCount[r.msgKey] || 0) + 1
const chainKey = Object.keys(keyCount).find((k) => keyCount[k] >= 2) || Object.keys(keyCount)[0]

// ---------- 浏览器（默认有头 + slowMo 300） ----------
const HEADLESS = process.env.E2E_HEADLESS === '1' || process.argv.includes('--headless')
log(`浏览器模式: ${HEADLESS ? 'headless' : 'headed + slowMo(300ms)'}；代际链锚 msgKey = ${chainKey}（${keyCount[chainKey] || 1} 行）`)
const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS, slowMo: HEADLESS ? 0 : 300 })
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  h.state.ctx = ctx
  h.state.page = page
  h.attachListeners(page)

  /** 状态过滤选值（沿 bpmn 脚本 openSelect/pickOption 成熟姿势：teleport 下拉取 :visible） */
  async function pickStatus(label) {
    await page.locator('.el-select').first().click()
    const dd = page.locator('.el-select-dropdown:visible')
    await dd.waitFor({ state: 'visible', timeout: 8000 })
    await sleep(300)
    await dd.locator('.el-select-dropdown__item', { hasText: label }).first().click()
    await sleep(300)
  }
  /** 清空状态过滤（hover 唤出清空按钮——el-select clearable；aria 树不含该图标，DOM 可见性过滤点按） */
  async function clearStatus() {
    await page.locator('.el-select').first().hover()
    await page.evaluate(() => {
      const vis = Array.from(document.querySelectorAll('.el-select__clear')).filter((i) => i.offsetParent !== null)
      if (!vis.length) throw new Error('状态过滤清空按钮不可见（el-select clearable 未生效？）')
      vis[0].click()
    })
    await sleep(300)
  }
  async function clickQuery() {
    await page.getByRole('button', { name: '查询' }).click()
    await waitTableIdle(page)
    await sleep(400)
  }
  /** 行内操作按钮 locator */
  const rowBtn = (row, name) => row.getByRole('button', { name, exact: true })
  /** ElMessageBox 确认（三个处置操作共用 .el-message-box + 主按钮） */
  async function confirmBox(titleAssert, needle) {
    const box = page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 8000 })
    const text = (await box.innerText()).trim().replace(/\n/g, ' | ')
    titleAssert(text)
    await box.locator('.el-message-box__btns .el-button--primary').click()
    await sleep(600)
  }

  await step('MDQ1', '浏览器登录 + 菜单「死信治理」出现（权限快照）+ 进入列表（面包屑/12 列表头）', async () => {
    const lr = await login(page, 'admin', 'admin123')
    assert(lr.ok, `admin 登录失败: ${lr.msg || lr.url}`)
    // textContent（非 innerText）：折叠子菜单项 display:none 时 innerText 返回空串，textContent 恒在
    const menuTexts = await page.evaluate(() => Array.from(document.querySelectorAll('.el-menu .el-menu-item, .el-menu .el-sub-menu__title')).map((el) => (el.textContent || '').trim()).filter(Boolean))
    assert(menuTexts.includes('死信治理'), `侧边栏应含「死信治理」，实际 ${JSON.stringify(menuTexts)}`)
    assert(menuTexts.includes('系统管理'), '侧边栏应含「系统管理」分组')
    await shot(page, 'mdq1-menu.png')
    await page.goto(`${BASE}${PAGE_PATH}`, { waitUntil: 'domcontentloaded' })
    await waitTableIdle(page)
    await sleep(500)
    assertEq(page.url(), `${BASE}${PAGE_PATH}`, '应停留在死信治理页')
    const crumbs = (await h.breadcrumbTexts(page)).join('>')
    assert(crumbs.includes('死信治理'), `面包屑应含死信治理，实际 "${crumbs}"`)
    const headers = await page.evaluate(() => Array.from(document.querySelectorAll('.el-table__header th .cell')).map((el) => el.textContent.trim()).filter(Boolean))
    for (const hd of HEADERS) assert(headers.includes(hd), `表头缺列「${hd}」，实际 ${JSON.stringify(headers)}`)
    await shot(page, 'mdq1-list.png')
  })

  await step('MDQ2', '列表全量渲染：代际链标记 + 状态标签字典译文 + 重试次数 4（broker 口径）', async () => {
    await clearStatus()
    await clickQuery()
    const total = await page.locator('.el-pagination .el-pagination__total').innerText()
    assert(Number(total.replace(/[^0-9]/g, '')) >= 2, `全量应 ≥2 行，实际 "${total}"`)
    const chainRows = page.locator('.el-table__row', { hasText: '代际链' })
    const chainN = await chainRows.count()
    assert(chainN >= 2, `代际链标记应 ≥2 行（同 msgKey 两行各带），实际 ${chainN}`)
    const chainText = (await chainRows.first().innerText()).replace(/\n/g, ' | ')
    // 期望值动态化（2026-10-12 修正）：徽标计数 == preflight 实测同 msgKey 行数——聚合语义才是本断言
    // 要验的点；硬编码「2 行」被台账自然增长打穿过一次（R9 晚间长尾死信 id=5 使链=3，headless 复跑实证）
    const chainExpect = keyCount[chainKey] || 1
    assert(chainText.includes(`代际链 ${chainExpect} 行`) && chainText.includes(chainKey), `代际链徽标应含「代际链 ${chainExpect} 行」与 ${chainKey}，实际 "${chainText.slice(0, 120)}"`)
    const allRows = page.locator('.el-table__row')
    const n = await allRows.count()
    const labels = []
    let retry4 = false
    for (let i = 0; i < n; i++) {
      const cells = await rowCells(allRows.nth(i))
      labels.push(cells[COL.STATUS])
      if (cells[COL.RETRY] === '4') retry4 = true
    }
    assert(labels.every((t) => ['待处理', '已重投', '已忽略', '已放弃'].includes(t)), `每行处置状态应为四态字典译文，实际 ${JSON.stringify(labels)}`)
    assert(retry4, `重试次数列应出现 4（broker 透传口径，契约 §3.1 修订版），各行 ${JSON.stringify(labels)}`)
    await shot(page, 'mdq2-chain.png')
  })

  await step('MDQ3', '过滤：状态=待处理仅待处理行；msgKey 模糊收窄', async () => {
    await pickStatus('待处理')
    await clickQuery()
    const allRows = page.locator('.el-table__row')
    const n = await allRows.count()
    assert(n >= 1, '待处理应 ≥1 行（B7 专用行）')
    for (let i = 0; i < n; i++) {
      const cells = await rowCells(allRows.nth(i))
      assertEq(cells[COL.STATUS], '待处理', `待处理过滤后第 ${i} 行状态`)
    }
    await page.getByPlaceholder('消息键（模糊）').fill('e2e-dlq')
    await clickQuery()
    const rows2 = page.locator('.el-table__row')
    const n2 = await rows2.count()
    assert(n2 >= 1, 'msgKey 模糊后应 ≥1 行')
    for (let i = 0; i < n2; i++) {
      const cells = await rowCells(rows2.nth(i))
      assert(cells[COL.MSG_KEY].includes('e2e-dlq'), `模糊过滤后第 ${i} 行消息键应含 e2e-dlq，实际 "${cells[COL.MSG_KEY]}"`)
    }
    await shot(page, 'mdq3-filter.png')
    // 还原过滤面（清 msgKey；状态留待处理——MDQ4/5 起点即待处理视角）
    await page.getByPlaceholder('消息键（模糊）').fill('')
    await clickQuery()
  })

  await step('MDQ4', '详情弹窗：消息体原文 + msgId 与列表一致 + 弹窗内异步新拉', async () => {
    const row = page.locator('.el-table__row').first()
    const msgIdCell = (await rowCells(row))[COL.MSG_ID] || ''
    await rowBtn(row, '详情').click()
    const dlg = page.locator('.el-dialog', { hasText: '死信详情' }).last()
    await dlg.waitFor({ state: 'visible', timeout: 8000 })
    // 弹窗内异步新拉详情（watch 打开才请求）：锚 .dlq-body 出现再断言
    await dlg.locator('.dlq-body').waitFor({ state: 'visible', timeout: 8000 })
    const dlgText = (await dlg.innerText()).replace(/\n/g, ' | ')
    assert(dlgText.includes('消息体原文'), '详情应含「消息体原文」区')
    assert(/[{\[]|not-json/.test(dlgText), `消息体应展示原文（JSON 或 not-json），实际片段 "${dlgText.slice(0, 160)}"`)
    assert(dlgText.includes(msgIdCell.slice(0, 20)), `详情 msgId 应与列表一致（首 20 字符锚），列表 "${msgIdCell.slice(0, 20)}"`)
    await shot(page, 'mdq4-detail.png')
    // 关闭锚 footer 按钮（成熟姿势沿 bpmn/dataperm：getByRole('button',{name:'关闭'}) 在 zh-cn 下
    // 与头部 X 的 aria-label「关闭」撞名——严格模式双元素直接抛错，弹窗遗留 overlay 拦截后续行内点击）
    await dlg.locator('.el-dialog__footer button', { hasText: '关闭' }).click()
    // EP 关闭是 display:none 留存 DOM——等 hidden 而非等节点消失（容忍动画时序抖动）
    await dlg.waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})
    await sleep(300)
  })

  await step('MDQ5', '按钮可见性（契约 §4）：待处理行=详情+重投+忽略；已重投行=详情+重投', async () => {
    // 已重投视角
    await pickStatus('已重投')
    await clickQuery()
    const resentRow = page.locator('.el-table__row').first()
    assert((await rowCells(resentRow))[COL.STATUS] === '已重投', '首行应为已重投')
    for (const name of ['详情', '重投']) {
      assert(await rowBtn(resentRow, name).count() > 0, `已重投行应有「${name}」按钮`)
    }
    assert(await rowBtn(resentRow, '忽略').count() === 0, '已重投行不应有忽略（D6：仅待处理可忽略）')
    assert(await rowBtn(resentRow, '重置').count() === 0, '已重投行不应有重置（非终态）')
    await shot(page, 'mdq5-resent.png')
    // 待处理视角
    await pickStatus('待处理')
    await clickQuery()
    const pendRow = page.locator('.el-table__row', { hasText: 'e2e-dlq' }).first()
    await pendRow.waitFor({ state: 'visible', timeout: 8000 })
    for (const name of ['详情', '重投', '忽略']) {
      assert(await rowBtn(pendRow, name).count() > 0, `待处理行应有「${name}」按钮`)
    }
    assert(await rowBtn(pendRow, '重置').count() === 0, '待处理行不应有重置（非终态）')
  })

  await step('MDQ6', '忽略操作（B7 专用行）：确认弹窗终态提示 → 行变已忽略 + 重置按钮出现', async () => {
    const row = page.locator('.el-table__row', { hasText: 'e2e-dlq' }).first()
    await rowBtn(row, '忽略').click()
    await confirmBox(
      (text) => assert(text.includes('忽略确认') && text.includes('终态') && text.includes('admin 重置'), `忽略确认应含终态与 admin 重置提示，实际 "${text.slice(0, 160)}"`),
    )
    await waitTableIdle(page)
    await sleep(400)
    // 行已离开待处理过滤集（loadPage 保持过滤重拉）→ 切已忽略视角断言
    await pickStatus('已忽略')
    await clickQuery()
    const ignRow = page.locator('.el-table__row', { hasText: 'e2e-dlq' }).first()
    await ignRow.waitFor({ state: 'visible', timeout: 8000 })
    const cells = await rowCells(ignRow)
    assertEq(cells[COL.STATUS], '已忽略', '行处置状态')
    assert(cells[COL.MSG_KEY].includes('e2e-dlq'), `行应为 B7 专用行，实际 "${cells[COL.MSG_KEY]}"`)
    assert(await rowBtn(ignRow, '重置').count() > 0, '已忽略行应出现重置按钮')
    assert(await rowBtn(ignRow, '重投').count() === 0, '已忽略行不应有重投（终态）')
    assert(await rowBtn(ignRow, '忽略').count() === 0, '已忽略行不应有忽略（终态不可再忽略）')
    await shot(page, 'mdq6-ignored.png')
  })

  await step('MDQ7', '重置恢复（数据回原状）：已忽略 → 待处理 + 重投计数清零', async () => {
    const row = page.locator('.el-table__row', { hasText: 'e2e-dlq' }).first()
    await rowBtn(row, '重置').click()
    await confirmBox(
      (text) => assert(text.includes('重置确认') && text.includes('待处理') && text.includes('清零'), `重置确认应含回待处理与计数清零提示，实际 "${text.slice(0, 160)}"`),
    )
    await waitTableIdle(page)
    await sleep(400)
    await pickStatus('待处理')
    await clickQuery()
    const back = page.locator('.el-table__row', { hasText: 'e2e-dlq' }).first()
    await back.waitFor({ state: 'visible', timeout: 8000 })
    const cells = await rowCells(back)
    assertEq(cells[COL.STATUS], '待处理', '重置后行状态')
    assertEq(cells[COL.RESEND], '0', '重投次数应清零')
    await shot(page, 'mdq7-reset.png')
  })

  await step('MDQ-VERIFY', 'console error / pageerror / >=400 / 网络失败 环境卫生复核', async () => {
    const NOISE_404 = /^Failed to load resource: the server responded with a status of 404/
    const realConsole = h.state.consoleErrors.filter((e) => !NOISE_404.test(e.text))
    assertEq(realConsole.length, 0, `不应有 console error（favicon 404 噪音除外），实际 ${JSON.stringify(realConsole)}`)
    assertEq(h.state.pageErrors.length, 0, `不应有未捕获异常，实际 ${JSON.stringify(h.state.pageErrors)}`)
    assertEq(h.state.badResponses.length, 0, `不应有 >=400 的 /api 响应，实际 ${JSON.stringify(h.state.badResponses)}`)
    assertEq(h.state.requestFailures.length, 0, `不应有网络层失败，实际 ${JSON.stringify(h.state.requestFailures)}`)
  })
} finally {
  await browser.close()
}

h.summary({
  extras: [
    `\n数据前提：B7 预置台账行（msgKey 含 e2e-dlq 子串锚，代际链锚 ${chainKey} ${keyCount[chainKey] || 1} 行）——脚本不新建数据；MDQ6→MDQ7 状态流转后回原状（待处理/计数 0）`,
    '断言口径：契约 §1-§4（端点面经 preflight API + 页面）+ §2 字典译文 + §4 按钮可见性（已忽略终态三断言）+ D6 状态机（ignore/reset 页面流转）+ 代际链标记 + 重试次数 4（修订版口径）',
    '已放弃态按钮断言不在本面（台账无该态行；mock 自证 + B7 curl 已覆盖记档）——重投真链路 B7 直证，e2e 不自造死信（黑盒纪律）',
  ],
})
