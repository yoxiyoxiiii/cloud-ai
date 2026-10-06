---
name: frontend-page
description: 在 cloud-web（Vue3+TS+Vite+Element Plus+Pinia）新增页面/业务组件/接口对接、配置路由与守卫，或做前端构建联通验证与 Playwright 浏览器 UI 测试时使用——工程定版、目录分层、api/弹窗/列表页模板、契约类型映射、有头测试与视觉核对规范。凡涉及"新增前端页面/组件/接口对接/UI 测试"均应触发本技能。
---

# 前端页面开发与 UI 测试规范（cloud-web）

来源：2026-10-05 前端试点（登录页/Layout/用户管理页）沉淀。第一原则：**与后端 API 契约（docs/superpowers/contracts/）逐字段对齐——契约是唯一对齐物，发现缺失/冲突上报主控，不得猜接口、不得单方改契约**。

## 技术栈与工程定版（勿擅改）

| 项 | 定版 | 陷阱 |
|---|---|---|
| Vue 3.5 + TS 5.8 strict | 组合式 API，`<script setup lang="ts">` | 禁 any / 裸 `as` 断言（类型问题用正确的 interface 收敛） |
| Vite 6 | dev 端口 5173；proxy `/api` → `http://localhost:18080`，rewrite 去掉 `/api` 前缀 | 直连后端一律走 `/api` 前缀，不写绝对地址 |
| Element Plus 2.x **按需引入** | `vite.config.ts`：`Components({ resolvers: [ElementPlusResolver()], dts: 'src/components.d.ts' })`，模板 `<el-xxx>` 构建期自动转组件级 import + 样式。**严禁 `app.use(ElementPlus)` 全量注册与 `dist/index.css` 全量样式**（CLAUDE.md 红线；全量→按需实测 JS -49% / CSS -60%） | ① 函数式 API（ElMessage/ElMessageBox）模板解析器捕不到——使用处显式 `import { ElMessage } from 'element-plus'`，且 `main.ts` 需单独引其样式 `element-plus/es/components/message(-box)/style/css`；② locale 全局配置改在 `App.vue`：`<el-config-provider :locale="zhCn">` 包裹 `<router-view/>`（zhCn 从 `element-plus/es/locale/lang/zh-cn` 引入）；③ `src/components.d.ts` 是构建生成物不进 git，首次 build 前不存在、二次起类型更严——验收以**连续两次 `npm run build` 全绿**为准 |
| vue-router | **必须定版 4.x** | latest(5.x) 与 vite 7/8 peer 冲突 |
| Pinia 3 | `stores/` 目录 | |
| Axios | 仅经 `utils/request.ts` 统一封装 | 页面禁止裸 import axios |

## 目录分层

```
cloud-web/src/
├── api/          # 按后端服务模块封装（auth.ts/user.ts/role.ts）；一函数 = 一契约端点
├── types/api.ts  # 契约 §6 类型字典逐项对齐（类型唯一来源）
├── stores/       # Pinia（auth.ts = 登录态唯一来源）
├── router/       # 静态路由表 + 全局前置守卫
├── layouts/      # 布局（Layout + Sidebar/Navbar/Breadcrumb 私有组件）
├── utils/        # request.ts（axios 拦截器）/ storage.ts（localStorage 单键）
└── views/<模块>/<页面>/index.vue + components/   # 页面与其私有弹窗组件就近放置
```

## 契约类型映射（types/api.ts）

- `R<T> = { code, msg, data }`：HTTP 恒 200，业务状态看 `body.code`
- **Long→String：id/total/expiresIn/userId/roleIds 等一律 `string`**（后端 Jackson 全局配置防 JS 精度丢失）；`total` 给 el-pagination 前需 `Number()`
- 日期 `yyyy-MM-dd HH:mm:ss` 字符串直接展示；epoch 毫秒（如 loginTime）需自行格式化
- 每个类型注明契约节号（`/** 用户 VO（契约 §3）：status 语义 0=正常 1=停用 */`），语义写进注释

## api 层模板（api/xxx.ts）

```ts
/**
 * Xxx 接口（契约 §n，cloud-<svc>，网关前缀 /<svc>）
 * 注意：id 均为字符串（Long→String）
 */
import { request } from '../utils/request'
import type { PageResult, XxxVo } from '../types/api'

export interface XxxPageQuery {
  /** 页码（1 起） */
  pageNum: number
  /** 每页条数（后端分页插件 maxLimit 200） */
  pageSize: number
}

export interface CreateXxxPayload {
  name: string
  status: number
}

/** Xxx 分页（契约 §n.1） */
export function pageXxx(query: XxxPageQuery): Promise<PageResult<XxxVo>> {
  return request<PageResult<XxxVo>>({ url: '/system/xxx/page', method: 'get', params: query })
}

/** 新增 Xxx（契约 §n.3）：返回新记录 id 字符串 */
export function createXxx(payload: CreateXxxPayload): Promise<string> {
  return request<string>({ url: '/system/xxx', method: 'post', data: payload })
}
```

规则：URL 带网关服务前缀（`/system` `/sso` `/bpmn`——proxy rewrite 后直达网关）；函数名动词开头（pageXxx/createXxx/updateXxx/deleteXxx/listXxxRoles…）；入参出参全部 interface；每个函数 JSDoc 注契约节号；契约没有的参数不私自加（如分页无搜索参数就先不写，注明"契约现状"）。

## 请求封装铁律（utils/request.ts 已建，勿绕过）

- 拦截器已解包：`request<T>` 成功 resolve 即业务 data；失败 reject（R 体或 axios 错误）→ 页面 try/catch
- `code !== 200` 已统一 `ElMessage.error(msg)`；**需内联展示后端 msg 的页面（登录页）传 `skipErrorMessage: true`** 自行处理
- 401（body.code 401 或 HTTP 401——网关鉴权失败是真实状态码）自动清登录态跳 `/login?redirect=...`，页面不重复处理
- Bearer 注入读 storage 纯函数（**不读 store**，防循环依赖、兼容非 setup 上下文）

## 登录态与路由

- storage 单键 `cloud-web:auth` = JSON `{ accessToken, refreshToken, account }`；解析失败视为未登录（容错清键）
- auth store：state 初始化自 storage（F5 保持登录态）；`loginAction` 成功写 storage + state；**`logoutAction` try/finally——后端注销失败也继续清本地态**；store 不做路由跳转（调用方跳）
- 路由守卫只校验 token 存在性（签名/过期是网关职责，首个 API 401 触发清态收敛）；`meta`：`title`（面包屑/标题）/`icon`（EP 图标名）/`public`（登录页不套 Layout）；未登录访问受保护页 → `/login?redirect=to.fullPath`
- 新页面：路由表加 children 项（静态，菜单由路由表驱动）

## 列表页模板（views/<模块>/<xxx>/index.vue）

结构：工具栏（新增按钮等）→ `el-table`（列渲染 + 状态 `el-tag` + 操作列按钮）→ `el-pagination`（`total` 先 `Number()`）。要点：

- 数据加载函数 `load()`：调 pageXxx → 落 rows/total；onMounted 与弹窗 `success` 后刷新
- 提交类操作一律走弹窗组件，弹窗 `emit('success')` → 父组件 `load()`
- 时间列直接展示字符串；状态列 tag（正常绿/停用红）；操作列删除为红色文字按钮 + `ElMessageBox.confirm` 确认（文案含目标名称）
- **el-table-column 插槽 row 固定为 `DefaultRow`（EP 的泛型不流入列插槽）**：模板解构处禁类型标注（`{ row }: { row: XxxVo }` 会 TS2322）；页面内集中一个收窄函数（唯一断言点，模板/处理器禁散落裸 `as`）：
  ```ts
  /** EP 列插槽 row 固定 DefaultRow，全页断言集中此一处 */
  function rowOf(row: unknown): SysUserVo {
    return row as SysUserVo
  }
  ```
  模板中 `rowOf(row).status` / `openEdit(rowOf(row))`
- 中文 UI 文案

## 表单弹窗模板（views/.../components/XxxFormDialog.vue，样板 UserFormDialog.vue）

props：`modelValue: boolean` + `mode: 'add' | 'edit'` + 行数据（编辑回显）；emit：`update:modelValue` + `success`。要点：

- `watch(modelValue)` 打开时初始化：edit 回显行数据（**契约不可改字段 disabled 只读展示**，如 account）/ add 给默认值；先 `clearValidate()`
- `el-form` rules：必填 + 长度（密码 6-32 位是前端约定兜底——契约注明后端无强约束）；提交前 `formRef.validate().catch(() => false)`
- `loading` 防重复提交（按钮 `:loading` + 输入 `:disabled`）；成功 `ElMessage.success` + `emit('success')` + 关闭；**catch 留空**（拦截器已统一 toast 业务 msg）
- 状态字面量用常量（`const STATUS_NORMAL = 0` / `STATUS_DISABLED = 1`），radio 选项绑定常量，禁魔法数

## 构建与联通验证（每个功能完成时）

1. `npm run build` 零错误（TS strict 下 any/类型不匹配即红）
2. dev server 起后：`curl http://localhost:5173/api/system/demo/ping` 经代理应返回后端 R（5173 → 18080 → 9202 链路）
3. 后端未就绪：先按契约 mock（proxy 临时指向本地 json），联调切回 18080 并在报告注明
4. npm 安装失败先换 npmmirror 镜像

## 浏览器自动化测试规范（UI 验收）

**有头模式（用户明确要求）：`headless: false` + `slowMo: 300`**——用户要在桌面直接看到操作过程；证据以有头跑为准。

工具路径二选一：
- Playwright MCP（`mcp__playwright__*`）→ browser_navigate / browser_type / browser_click / browser_snapshot / browser_take_screenshot
- MCP 不可用 → 脚本路径：`npm i --no-save playwright`（不写 package.json）；本机有 Chrome 时 `chromium.launch({ channel: 'chrome', headless: false, slowMo: 300 })` 免下载浏览器；脚本放 `cloud-web/e2e/`，截图 `e2e/artifacts/`

已知坑：
- Element Plus 弹窗关闭是 `display:none` 而非移除 DOM——等待弹窗消失用 `waitFor({ state: 'hidden' })`
- **中文入参经浏览器 fetch 走真实 `/api` 链路造数**（Git Bash curl 发中文 JSON 是 GBK 会 500）
- 分页断言在离开当前页前先取值

场景集（目标页功能 + 通用回归）：
1. 登录流：无 token 直访受保护页 → redirect 跳登录；空表单内联必填错误（0 请求）；错误密码内联错误停留；正确账密 → 回跳目标页 Layout 渲染；退出 → 清态回登录且受保护页仍被拦
2. 布局：菜单渲染/点击高亮/面包屑；F5 保持登录态恢复
3. 目标页核心流：表格加载（列/状态 tag/时间格式/分页 total；数据不足一页时经 fetch 造数验证翻页）→ 新增（空提交校验 → 成功 toast → 行出现）→ 编辑（不可改字段只读）→ 领域动作（重置密码/分配角色等按页面）→ 删除（确认框 → 行消失）
4. 401：篡改 localStorage token → 首个请求 401 → 自动清态跳登录页

**视觉核对（必做）**：关键状态截图 ≥5 张（登录页/错误态/Layout 全貌/列表页/核心弹窗）→ `mcp__4_5v_mcp__analyze_image` 比对预期要素（提示词写明布局/组件/数据预期）。注意该工具**只收远程 URL 且部分地址报 1210 解析错误**（localhost 实测不可用）——有效通路：Read 截图 → 经平台上传得到 CDN 远程 URL → analyze_image。

**测试数据纪律**：测试账号 `e2e` 前缀+时间戳；**绝不改种子账号（admin）的密码/角色，绝不删 admin**；结束清理测试数据（UI 删除或 API 删），报告残留。

**e2e 产物处置**（脚本与截图性质不同，区别对待）：
- **场景脚本（e2e/*.mjs）保留并进 git**——它是前端目前唯一的自动化回归手段（选择器、EP 弹窗等待、翻页断言等坑都已调通），下次功能改动可整段重跑回归；页面改版时同步维护选择器，随功能演进。MCP 路径跑通的场景也回写为脚本沉淀。
- **专项 verify-* 修复验证脚本**：验证通过、缺陷关闭后即删（一次性使命）；其中值得长期保留的断言并入场景脚本。
- **截图与日志（e2e/artifacts/）不进 git**（.gitignore 已配），**验收报告交付后即可删除**——视觉核对结论以文字记录在报告里，二进制证据不入库。
- 测试账号用契约默认种子账号（admin），脚本中不得写入其他真实账号/密码。

## 已知取舍（沿用，勿擅自"修复"）

- MVP 不做静默刷新（后端 refresh 单活跃模型，与后端架构决策冲突）
- 用户管理页无搜索栏（契约现状：`/system/user/page` 无查询参数——缺口由架构-agent 补契约后再做）
- CRUD 弹窗不做通用抽象（第 2 个 CRUD 页出现时再提 composable）
