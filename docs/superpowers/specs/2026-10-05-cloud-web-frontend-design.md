# cloud-web 前端工程技术方案（试点：登录 + 布局 + 用户管理）

- 日期：2026-10-05
- 状态：已确认（主控派发试点任务；文中标注的两个开放决策点已给推荐项，按推荐执行）
- 仓库：`D:\source\cloud-ai`，工程位于子目录 `cloud-web/`
- 关联：API 契约 `docs/superpowers/contracts/2026-10-05-pilot-auth-user-api.md`（前后端唯一对齐物）；实施计划 `docs/superpowers/plans/2026-10-05-cloud-web-pilot.md`
- 后端现状：cloud-base 阶段 1-3 已完成并验收（网关 18080，sso/system/bpmn），本方案不改动任何后端行为

## 1. 定位与 MVP 范围

cloud-web 是企业应用基座平台的前端（内部管理台）。试点交付一条完整链路：**登录 → 主布局 → 用户管理 CRUD**。

**MVP 包含**：登录页、JWT Bearer 注入、401 处理、Layout（静态侧边菜单 + 顶栏 + 面包屑）、用户管理页（分页表格 + 新增/编辑/删除/重置密码/分配角色）。

**明确不做（后续项）**：动态路由/菜单（menu/tree 接口契约先行、前端不消费）、i18n、主题/暗色、按钮级权限指令（后端缺口，见 §10）、token 静默刷新（见 §6 推荐）、在线会话管理页、前端单元测试（见 §11）。

## 2. 技术选型与版本矩阵

| 层面 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 框架 | Vue | 3.x（3.5+） | Composition API + `<script setup>` |
| 语言 | TypeScript | 5.x | vue-tsc 作为构建门禁 |
| 构建 | Vite | 6.x（`create-vite vue-ts` 模板默认） | Node 18+；本机 Node 24 已验证 |
| UI | Element Plus | 2.x 最新稳定 | **全量引入**（见下方取舍） |
| 图标 | @element-plus/icons-vue | 随 EP | 侧边菜单图标 |
| 状态 | Pinia | 2.x/3.x | 仅 auth 一个 store |
| 路由 | Vue Router | 4.x | 静态路由 + 全局前置守卫 |
| HTTP | Axios | 1.x | 统一封装（§5） |
| 包管理 | npm | 本机 10.8 | 不引入 pnpm/monorepo 工具，保持零负担 |

**Element Plus 全量引入的取舍**：不引 unplugin-auto-import / unplugin-vue-components 按需加载。理由：内部管理台对产物体积不敏感（gzip 后 EP 约几百 KB），少两个构建插件即少两类版本兼容问题；按需加载留作上线前优化项（改造成本仅 vite.config + main.ts 两处）。

## 3. 工程结构

```
cloud-web/
├── index.html
├── package.json
├── vite.config.ts               # 代理 /api → http://localhost:18080（§9）
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json   # 脚手架默认（项目引用结构）
├── .env.development             # VITE_API_BASE_URL=/api
├── .env.production              # 预留占位（部署形态未定，"实现时验证"）
├── .gitignore                   # 脚手架自带（确认含 node_modules/dist）
└── src/
    ├── main.ts                  # 装配 pinia / router / Element Plus
    ├── App.vue                  # 仅 <router-view/>
    ├── api/                     # 接口层：一个后端服务域一个文件
    │   ├── auth.ts              # login / refresh / logout / online / kick
    │   ├── user.ts              # 用户分页/CRUD/重置密码/分配角色/角色回显
    │   └── role.ts              # 角色列表（分配角色弹窗用）
    ├── types/
    │   └── api.ts               # R<T>/PageResult<T>/LoginResult/SysUserVo/SysRoleVo... 全局类型（对齐契约 §6）
    ├── stores/
    │   └── auth.ts              # 登录态唯一来源
    ├── router/
    │   └── index.ts             # 路由表 + 全局前置守卫
    ├── layouts/
    │   ├── Layout.vue           # 侧边 + 顶栏 + 主区三段式
    │   └── components/          # Sidebar.vue / Navbar.vue / Breadcrumb.vue（布局私有）
    ├── views/
    │   ├── login/index.vue
    │   ├── dashboard/index.vue  # 占位页（侧边菜单第二项）
    │   └── system/user/
    │       ├── index.vue        # 用户管理页
    │       └── components/      # UserFormDialog / ResetPwdDialog / AssignRoleDialog（页面私有）
    ├── components/              # 跨页面通用组件（MVP 为空，见 §8 提取规则）
    ├── composables/             # 跨页面组合函数（MVP 为空，同上）
    └── utils/
        ├── request.ts           # axios 封装（§5）
        └── storage.ts           # localStorage 读写，token 键集中管理
```

## 4. 分层设计

| 层 | 职责 | 允许依赖 | 禁止 |
|---|---|---|---|
| `views/` | 页面业务：状态编排、调用 api、组装私有子组件 | stores / api / layouts / 自身 components / composables | 直接操作 localStorage；直接用 axios |
| `views/**/components/` | 页面私有组件，props 进、emit 出，不直接调 api（弹窗可例外：由父页面传入 api 回调） | 无内部依赖倾向 | 跨页面复用（复用即上提到 `src/components`） |
| `layouts/` | 布局骨架与布局私有子组件 | stores / router | 业务 api 调用 |
| `api/` | 按服务域导出类型化函数，纯薄层 | utils/request / types | **import stores**（防循环依赖）；含 UI 副作用（不弹 ElMessage） |
| `stores/` | 登录态：token/account，login/logout actions | api / utils/storage | 路由跳转（由调用方处理） |
| `utils/` | request（axios 封装）、storage（token 读写） | types / 第三方 | import api / stores / views |
| `components/`、`composables/` | 跨页面复用物，MVP 为空目录 | — | 过早抽象（§8） |

**关键决策——request.ts 不读 store 而读 storage util**：axios 拦截器运行在非 setup 上下文，直接读 `utils/storage.ts`（纯函数）避免 Pinia 激活时序与 `api → stores → api` 循环引用。store 是 storage 的上层视图：login 成功由 store 写 storage + state，logout 由 store 清两者。

## 5. 请求封装设计（utils/request.ts）

**类型**（`types/api.ts`，与契约 §6 逐字段对齐）：

```ts
export interface R<T = unknown> { code: number; msg: string; data: T }
export interface PageResult<T> { total: string; rows: T[] }   // total 为字符串（Long→String）
```

**实例**：`baseURL = import.meta.env.VITE_API_BASE_URL`（dev 为 `/api`），`timeout = 15000`。

**拦截器**（关键片段，<30 行）：

```ts
// 请求：Bearer 注入
instance.interceptors.request.use((config) => {
  const auth = getAuth()
  if (auth?.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

// 响应：HTTP 200 时按 body.code 分流，成功直接解包 data
instance.interceptors.response.use(
  (res) => {
    const body = res.data as R
    if (body.code === 200) {
      return body.data            // 页面代码拿到的即 data，无需再取 .data.data
    }
    if (body.code === 401) {
      redirectToLogin()
    }
    if (!(res.config as ReqConfig).skipErrorMessage) {
      ElMessage.error(body.msg || '请求失败')
    }
    return Promise.reject(body)
  },
  (err) => {
    if (err.response?.status === 401) {
      redirectToLogin()            // 网关鉴权失败：真实 HTTP 401 + R JSON body
    } else if (!(err.config as ReqConfig)?.skipErrorMessage) {
      ElMessage.error(err.response?.data?.msg || '网络异常，请稍后重试')
    }
    return Promise.reject(err)
  },
)
```

要点：

1. **解包策略**：`code===200` 时 resolve `body.data`。页面代码零分支——失败路径已被拦截器统一转为 reject，页面只需 try/catch（通常仅控制 loading 复位）。
2. **`skipErrorMessage` 自定义配置项**：通过 `declare module 'axios'` 扩展 `AxiosRequestConfig`。登录页使用它拿到原始错误做表单内联提示（而非全局 toast），其余场景一律全局 toast。
3. **`redirectToLogin()`**：清 storage 中 auth → 若当前不在 `/login` 则 `router.push({ path: '/login', query: { redirect: 当前完整路径 } })`。需防抖（并发请求同时 401 只跳一次：判断 `router.currentRoute.value.path !== '/login'` 即可）。
4. **错误处理矩阵**（与契约 §1 通用约定一一对应）：

| 场景 | HTTP 状态 | body.code | 前端行为 |
|---|---|---|---|
| 成功 | 200 | 200 | resolve `data` |
| 业务/权限失败 | 200 | 1002 / 2xxx / 3xxx / 403 | toast `msg` + reject |
| 网关鉴权失败（无/坏/过期 token、已注销/被强退） | **真实 401** | 401（R JSON body） | 清登录态 → 跳 `/login?redirect=` |
| 网络错误 / 超时 / 网关 503 | 非 200 或无响应 | — | toast 兜底文案 + reject |

5. **Long→String 适配**：`total`、`id` 等均为字符串，前端展示直用；分页组件 `total` 需 `Number(total)`。不全局反序列化为 number（防 19 位精度丢失的设计必须保留）。

## 6. 认证与令牌策略

**storage 契约**（`utils/storage.ts`）：单键 `cloud-web:auth`，值为 JSON `{ accessToken, refreshToken, account }`。不存 `expiresIn`（MVP 无静默刷新，无用）。

**顶栏用户名的来源（决策）**：登录响应只含 token（无用户信息），MVP 直接把**登录表单输入的 account** 存入 auth（顺手、无额外接口、无 JWT 解析）。放弃的备选：前端 base64 解 JWT payload 取 account——可行但引入解析代码，收益为零。后续若后端提供 `/me` 类接口再切换（§10）。

**静默刷新评估（开放决策点，推荐项已定）**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 401 时静默刷新重放 | 响应拦截器捕获 401 → 单飞调 `/sso/auth/refresh` → 重放原请求 | 用户无感 | 需实现单飞队列、请求重放、登出/强退竞态处理；**且后端 refresh 键模型为"每用户单活跃 refreshToken，后登录覆盖前者"且刷新即轮换旧 token（阶段 2+3 已知取舍）——多标签页下一个页签刷新会令其他页签 token 集体失效，静默刷新反而放大故障**；实现与联调成本高 |
| **B. 401 一律跳登录（推荐，MVP 采用）** | 清登录态 → 跳 `/login?redirect=` | 10 行内实现；与后端"注销/强退立即失效"语义一致；accessToken 有效期 2h，管理台重登录成本可接受 | 过期时需重登录 |

**推荐 B**。升级触发条件（满足即补做 A）：后端将 refresh 模型改为与 accessToken 一致的多会话模型（阶段 2+3 移交备忘已列）。届时实现要点：401 拦截 → 单飞 refresh（互斥锁 + 等待队列）→ 重放原请求 → refresh 再失败才清态跳登录。

**auth store**（`stores/auth.ts`）：

- state：`{ accessToken, refreshToken, account }`，初始化自 storage 恢复（F5 保持登录态）。
- actions：`loginAction(account, password)`（调 `api/auth.login`，成功写 storage+state）；`logoutAction()`（调 `api/auth.logout`，**失败也继续清本地态**——后端已注销/网络异常都不应卡住登出，然后由调用方跳 `/login`）。

## 7. 路由与守卫设计

**路由表**（全静态）：

```
/login                          # meta.public，不套 Layout
/                → redirect /system/user
  └─ Layout（嵌套路由父级）
      ├─ /system/user    用户管理   meta: { title: '用户管理', icon: 'User' }
      └─ /dashboard      工作台     meta: { title: '工作台', icon: 'Monitor' }   # 占位页
 pathMatch '*' → redirect /（不单独做 404 页）
```

**全局前置守卫**（关键片段）：

```ts
router.beforeEach((to) => {
  const logged = !!getAuth()?.accessToken
  if (to.meta.public) {
    return logged ? { path: '/' } : true          // 已登录访问 /login → 回首页
  }
  if (!logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})
```

要点：守卫只校验 **token 存在性**（不本地校验签名/过期——那是网关的职责，过期 token 会在首个 API 调用时以 401 触发 §5 的清态跳转，两处路径收敛）。菜单/路由不做权限过滤（MVP 全显，§10 缺口）。

## 8. 组件封装设计

**组件分层**（复用范围从窄到宽）：页面私有（`views/**/components/`）→ 布局私有（`layouts/components/`）→ 跨页通用（`src/components/`）。

**Layout 设计**（`layouts/Layout.vue`）：

- `el-container` 三段式：`el-aside`（Sidebar）+ `el-header`（Navbar）+ `el-main`（`<router-view/>`）。
- **Sidebar**：静态菜单数组（path/title/icon 两项），`el-menu` 的 `router` 模式 + `default-active = route.path`。不读 menu/tree 接口（契约先行、前端不消费）；不做折叠。
- **Navbar**：左侧 Breadcrumb，右侧 `el-dropdown`（显示 `authStore.account`，下拉仅"退出登录"→ `logoutAction()` + 跳 `/login`）。
- **Breadcrumb**：取 `route.matched` 过滤 `meta.title` 渲染 `el-breadcrumb`（实现 <30 行，无额外抽象）。

**通用 CRUD 表格组件的抽象程度（开放决策点，YAGNI 评估）**：**不做抽象**。理由：

1. MVP 仅一个 CRUD 页面（用户管理），抽象（列配置 schema + 通用 hooks）在单消费者下只会把直白代码变成间接层，违背 YAGNI；
2. Element Plus 的 `el-table`/`el-pagination`/`el-dialog` 组合本身已足够声明式，样板代码量有限；
3. 过早抽象会在第二个页面出现需求分叉时（不同的搜索、行操作、分页语义）被迫加配置项黑洞。

**提取触发条件**（满足其一才动手，优先提取 composable 而非渲染组件）：出现第 2 个结构相似的 CRUD 页；同一模式被复制第 3 次。届时首选 `usePagedTable()`（分页状态 + 加载函数封装），列渲染仍留在各页面。

**用户管理页拆分**（控制单文件长度，对齐后端"方法 ≤50 行"精神的组件版）：`index.vue`（表格 + 分页 + 编排）+ 三个页面私有弹窗组件 `UserFormDialog`（新增/编辑复用，`mode` prop 区分）/ `ResetPwdDialog` / `AssignRoleDialog`。弹窗组件 props 收参、emit 成功事件，api 调用由弹窗内部发起（父页面不代理数据，减少双向流转）。

## 9. 构建与代理配置

**开发代理**（vite.config.ts 关键片段）：

```ts
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:18080',   // cloud-gateway
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''),   // /api/sso/x → 网关 /sso/x
      },
    },
  },
})
```

- 前端代码内所有请求路径写 `/api/sso/...`、`/api/system/...`；axios `baseURL` 取 `VITE_API_BASE_URL=/api`（.env.development）。
- 代理命中后无 CORS 问题（浏览器视角同源 5173）；网关侧 globalcors 保留不动。

**生产构建**：`npm run build`（vue-tsc 类型检查 + 产物 `dist/`）。部署形态未定（同源 nginx 反代 `/api` → 网关，或 `VITE_API_BASE_URL` 直填网关地址）——**实现时验证**，MVP 仅要求 dev 链路 + build 绿。

## 10. 已知缺口与后续项（如实标注）

| # | 缺口 | MVP 处置 | 后续 |
|---|---|---|---|
| 1 | **按钮级权限**：后端无接口下发当前用户权限清单（登录响应不含 permissions、无 /me），JWT 内权限仅网关侧消费 | 按钮全显。风险可控：种子账号即 admin（全权限）；越权操作由服务层 403（HTTP 200 + body code 403）→ 拦截器 toast 兜底 | 后端补 `/sso/auth/me`（account+permissions）或登录响应带权限，前端再上 `v-permission` 指令 + 菜单过滤 |
| 2 | token 静默刷新 | 401 跳登录（§6 推荐B） | 后端 refresh 多会话模型落地后实施（§6 方案A要点已列） |
| 3 | 在线会话管理页 | 不做 | 接口已验收、契约已整理（契约 §2.4/2.5），页面随后续阶段 |
| 4 | 动态路由/菜单 | 静态两项 | menu/tree 契约先行，动态路由随后续阶段 |
| 5 | 生产部署（nginx/env） | 不做 | 部署形态确定后补 §9 生产配置 |

## 11. 测试与验收策略

- **不建前端单测**（务实决策）：MVP 页面逻辑薄（表单提交 + 列表加载），核心复杂度在拦截器分流，由 T6 端到端验收覆盖；`vue-tsc` 类型检查随 `npm run build` 作为构建门禁。
- **T6 手工验收清单**：登录（正/错凭证、redirect 回跳、F5 保持）→ 布局（菜单切换/面包屑/退出）→ 用户管理（分页/新增/编辑/删除/重置密码/分配角色回显）→ 401 链路（注销后旧 token 访问跳登录）。curl 经 dev 代理冒烟（`/api/system/demo/ping` 白名单、登录接口）。
