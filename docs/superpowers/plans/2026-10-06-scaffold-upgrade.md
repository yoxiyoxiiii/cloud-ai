# cloud-web 前端脚手架升级实施计划（系统名称 / 登录背景 / B 端增强包）

- 日期：2026-10-06
- 执行者：frontend-agent（角色定义 `.claude/agents/frontend-agent.md`），**单 agent 串行**（F1→F8 有依赖，无并行点）
- 关联：设计 `docs/superpowers/specs/2026-10-06-scaffold-upgrade-design.md`（**D1-D12 决策必读**）；规范 `/frontend-page` 技能（唯一规范来源）；**无 contracts 文档**（纯前端，不新增/不改动任何后端接口与契约消费）
- 分支：建议 `feat/scaffold-upgrade` 从 main 切出（主控定夺）；工作目录 `D:\source\cloud-ai\cloud-web\`（e2e 任务在 `cloud-e2e/`）
- 基线：main@53096cb（菜单管理已合并）；e2e user/role/menu 三脚本 32 项全 PASS

## 任务拆分总览

纯前端单章。F1 深色基建 → F2 品牌区+折叠 → F3 全屏 → F4 tags+缓存 → F5 搜索 → F6 水印 → F7 登录背景+名称收口 → F8 e2e（适配核查+T 系列+全量回归）。每 Task 一次提交、独立可验收（build 绿 + 手工冒烟）。

### 执行前须知

- 环境：Node 24 / npm 10 已验证；**不新增任何 npm 依赖**（水印/搜索/tags 全手写，设计红线）。
- 后端 4 服务仅 F8 e2e 阶段需要（按 CLAUDE.md `java -jar` 启动，18080 网关最后起）；F1-F7 用 dev server + build 验收即可（登录后页面需要后端在线才能出数据——手工冒烟时把服务起上，或按 §F 验收降级为"Layout 渲染骨架可见"）。
- Git Bash curl 下载二进制图片无 GBK 问题（GBK 陷阱只影响中文 JSON body）；查图片大小时注意 jpg 后缀保持。
- `src/components.d.ts` 是构建生成物不进 git：**验收口径=连续两次 `npm run build` 全绿**（F8 收尾必做一次）。
- e2e 红线（F8 全程遵守）：场景脚本与功能代码同一 commit 收尾；截图与 artifacts 不进 git；测试数据纪律（不碰 admin/种子——本计划 T 系列零后端写）；黑盒纪律（cloud-e2e 禁 import 前端内部代码）。

## 文件结构终态（相对 cloud-web/，★=新建）

```
index.html                                    # F7（title）
src/
├── main.ts                                   # F1（dark css-vars + 预挂载主题）
├── constants/  ★app.ts（APP_TITLE）  ★menus.ts（MENU_ITEMS）
├── stores/    ★app.ts  ★tags.ts              #（auth.ts 不动）
├── utils/storage.ts                          # F1（+app prefs 键）
├── router/index.ts                           # F4（redirect 路由）F2（afterEach 标题）
├── layouts/
│   ├── Layout.vue                            # F2/F4/F6（宽度/tags 行/keep-alive/watermark）
│   └── components/  Sidebar.vue(F2)  Navbar.vue(F1-F3)
│         ★TagsView.vue(F4)  ★MenuSearch.vue(F5)  ★Watermark.vue(F6)
├── views/
│   ├── redirect/index.vue                    # F4 ★
│   ├── login/index.vue                       # F4（清 tags）F7（背景+标题）
│   ├── dashboard/index.vue                   # F4（defineOptions name）
│   └── system/{user,role,menu}/index.vue     # F4（defineOptions name）
└── assets/login-bg.jpg                       # F7 ★（≤300KB，免商用授权）
cloud-e2e/  ★run-scaffold-e2e.mjs  package.json（F8）
```

---

## F1 深色模式基建（app store + storage 扩展 + 主题按钮）

**Files:**
- Create: `src/stores/app.ts`
- Modify: `src/utils/storage.ts`、`src/main.ts`、`src/layouts/components/Navbar.vue`
- Modify（样式变量化扫尾）: `src/layouts/Layout.vue`、`src/views/login/index.vue`（仅样式色值改 EP 变量，不改结构）

**Steps:**
- [ ] storage.ts：新增 `APP_KEY = 'cloud-web:app'` 与 `AppPrefs { theme: 'light'|'dark'; sidebarCollapsed: boolean }` 接口；`getAppPrefs()`（解析失败回退 `{light,false}` 并清键，镜像 getAuth）/`setAppPrefs()`（设计 §3.1）
- [ ] main.ts：`import 'element-plus/theme-chalk/dark/css-vars.css'`（D2 红线例外，注释写明论证）；mount 前 `if (getAppPrefs()?.theme === 'dark') document.documentElement.classList.add('dark')`（FOUC 防护）
- [ ] stores/app.ts：state 初始化自 getAppPrefs；`toggleTheme()`（toggle `html.dark` + 落盘）/`toggleSidebar()`（落盘，本任务先建 action，F2 消费）
- [ ] Navbar：账号下拉左侧加 `.navbar-theme` 图标按钮（Sunny/Moon 图标随态切换，点击 toggleTheme）；自定义样式一律用 EP CSS 变量（D2）
- [ ] `npm run build` 绿

**验收标准:**
1. 手工：未登录/登录页按主题按钮 → 全站（含 EP 组件与自定义区域）深浅切换无残留白块；F5 后主题保持；localStorage `cloud-web:app` 值正确
2. 清掉 `cloud-web:app` 再 F5 → 回到浅色默认（容错路径）
3. 构建绿；浏览器 console 无错误

**提交:** `feat(cloud-web): F1 深色模式（dark css-vars/app store/持久化/FOUC 防护）`

---

## F2 系统名称 + 品牌区 + 侧栏折叠

**Files:**
- Create: `src/constants/app.ts`、`src/constants/menus.ts`
- Modify: `src/layouts/components/Sidebar.vue`、`src/layouts/Layout.vue`、`src/layouts/components/Navbar.vue`、`src/router/index.ts`

**Steps:**
- [ ] `constants/app.ts`：`export const APP_TITLE = 'CloudAI 企业基座'`（D1）
- [ ] `constants/menus.ts`：把 Sidebar 内静态菜单数组原样搬出为 `MENU_ITEMS`（4 项，顺序不变：用户/角色/菜单/工作台）；Sidebar 改 import（D7）
- [ ] Sidebar：el-menu **兄弟节点之前**加 `.sidebar-brand`（高 48px、下边框；Cloudy 图标 + `<span v-show="!collapsed">` 全称）——**不得放进 el-menu**（D3，保护 M1 顺序断言）；el-menu 加 `:collapse="collapsed"`（collapsed 读 app store）
- [ ] Layout：`el-aside :width="appStore.sidebarCollapsed ? '64px' : '200px'"`，aside 内 flex 列布局（brand 固定 + menu flex-1 overflow）
- [ ] Navbar：Breadcrumb 左侧加 `.navbar-collapse` 汉默按钮（Fold/Expand 图标随态，点击 toggleSidebar）
- [ ] router：`afterEach` 设 `document.title = to.meta.title ? \`${to.meta.title} - ${APP_TITLE}\` : APP_TITLE`（D1）
- [ ] `npm run build` 绿

**验收标准:**
1. 展开态：品牌区显示"CloudAI 企业基座"，菜单 4 项顺序不变（用户管理→角色管理→菜单管理→工作台）
2. 点汉堡：aside 64px、品牌区仅图标、菜单项仅图标（hover 出 tooltip）、面包屑与内容区正常；再点还原；F5 后折叠态保持
3. document.title 随路由变化：/system/user = "用户管理 - CloudAI 企业基座"，/login = "CloudAI 企业基座"
4. 深浅两主题下品牌区/边框可读（EP 变量）

**提交:** `feat(cloud-web): F2 系统名称常量/侧栏品牌区/折叠（持久化+文档标题联动）`

---

## F3 全屏按钮

**Files:**
- Modify: `src/layouts/components/Navbar.vue`

**Steps:**
- [ ] `.navbar-fullscreen` 图标按钮（FullScreen 图标）：有 `document.fullscreenElement` → exitFullscreen，否则 `document.documentElement.requestFullscreen()`；promise `.catch(() => {})` 静默（D9）
- [ ] `fullscreenchange` 监听同步按钮态（onMounted 注册 / onUnmounted 移除）
- [ ] `npm run build` 绿

**验收标准:**
1. 点击进入全屏（浏览器 UI 消失、图标态翻转）、再点退出；Esc 退出后按钮态同步
2. 全屏下水印/标签页正常渲染；console 无错误

**提交:** `feat(cloud-web): F3 顶栏全屏切换（fullscreen API，失败静默）`

---

## F4 多标签页 + keep-alive + 刷新（本计划最大任务）

**Files:**
- Create: `src/stores/tags.ts`、`src/layouts/components/TagsView.vue`、`src/views/redirect/index.vue`
- Modify: `src/layouts/Layout.vue`、`src/router/index.ts`、`src/views/login/index.vue`、`src/views/dashboard/index.vue`、`src/views/system/user/index.vue`、`src/views/system/role/index.vue`、`src/views/system/menu/index.vue`

**Steps:**
- [ ] `stores/tags.ts`（D5/D7）：`TagView { path: string; title: string; name: string }`；state `{ visitedTags, cachedNames }`；actions `addTag(route)`（name 非空且非 'Redirect' 才加；visited 去重按 path；cached 按 name 去重）/`removeTag(path)`（返回跳转落点：左邻→右邻→null）/`closeOthers(path)`/`closeRight(path)`/`closeAll()`/`removeCached(name)`（幂等）；closeOthers/closeRight/closeAll 后 `cachedNames` = 剩余 visited 的 name 去重集
- [ ] **四个视图加 `defineOptions({ name: 'SystemUser' | 'SystemRole' | 'SystemMenu' | 'Dashboard' })`**（与 route.name 一致——script setup 推断名全为 "index"，include 无法区分，D5 红字坑）
- [ ] `views/redirect/index.vue`：setup 内 `router.replace({ path: '/' + route.params.path, query: route.query })` + 空模板（D6）
- [ ] router：Layout children 追加 `{ path: 'redirect/:path(.*)', name: 'Redirect', component: RedirectView }`
- [ ] Layout：el-header 下方加 `<TagsView />`（34px 独立行）；`el-main` 内改 `<router-view v-slot="{ Component }"><keep-alive :include="tagsStore.cachedNames"><component :is="Component" /></keep-alive></router-view>`（**不加 :key**，D5）；`watch(() => route.fullPath)` 非空 name 且非 Redirect 且非 meta.public → `addTag(route)`
- [ ] TagsView.vue：页签 el-tag（closable；`:effect="isActive ? 'dark' : 'plain'"`，点击 push(tag.path)，`@close` 走 removeTag + 落点跳转/`/dashboard`）；bar 右端 `.tags-actions` el-dropdown 五动作：刷新当前页（`removeCached(route.name)` → `router.replace('/redirect' + route.fullPath)`）/关闭当前/关闭其他/关闭右侧/全部关闭（落点 `/dashboard`）
- [ ] login/index.vue：`onMounted(() => tagsStore.closeAll())`（D4 会话清理，统一覆盖退出/401/直登三路径）
- [ ] `npm run build` 绿

**验收标准（后端在线，admin 手工）:**
1. 菜单点开 用户/角色/菜单/工作台：页签依次出现、active 跟随路由、点页签可来回切
2. **缓存语义**：用户页翻到第 2 页 → 切工作台 → 切回：仍第 2 页且 network 无新 page 请求；打开"新增用户"弹窗 → 切走切回：弹窗仍在
3. **刷新语义**：在用户页点"刷新当前页"：URL 不变、network 重发 page 请求、页码回 1（全新挂载）
4. 关闭当前签跳左邻；关闭其他仅剩当前；全部关闭落 /dashboard 且标签栏只剩工作台一签（由导航重建）
5. 退出登录 → 登录页 → 重新登录：标签栏从零重建（上一会话页签不残留）
6. F5：页签全部消失（内存态，预期）但主题/折叠保持

**提交:** `feat(cloud-web): F4 多标签页/keep-alive 缓存/redirect 刷新（defineOptions 命名）`

---

## F5 菜单搜索（Ctrl+K）

**Files:**
- Create: `src/layouts/components/MenuSearch.vue`
- Modify: `src/layouts/components/Navbar.vue`

**Steps:**
- [ ] MenuSearch.vue（D10）：`.navbar-search` 图标按钮（Search 图标）；`window` keydown 监听 Ctrl/Shift+K → `e.preventDefault()` + 打开（onMounted/onUnmounted 增删监听）；`<el-dialog v-model="visible" v-if="visible" ...>` 外层 v-if 保证**默认零 DOM**，width 480、`custom-class`/`class` 为 `menu-search-dialog`、`@opened` 聚焦输入框
- [ ] 数据源 `MENU_ITEMS`（constants/menus.ts，D7）；输入即过滤（title 不区分大小写 includes；空关键词全量）；结果行 `.menu-search-item`（title + path 灰字，hover/高亮态样式）；↑↓ 移动 `activeIndex`（环回）、Enter `router.push` + 关闭、Esc 走 el-dialog 默认关闭；无结果 el-empty；点击结果行同 Enter
- [ ] Navbar：右区（theme 按钮左侧）嵌 `<MenuSearch />`
- [ ] `npm run build` 绿

**验收标准:**
1. 默认 DOM 中无 `.menu-search-dialog`（DevTools 佐证零渲染）
2. Ctrl+K（页面任意聚焦处）唤起并自动聚焦输入框；输"角色"只剩角色管理；Enter/点击跳 /system/role 且弹层关闭；Esc 关闭
3. 空关键词显示全部 4 项；无匹配显示空态；console 无错误

**提交:** `feat(cloud-web): F5 菜单搜索（Ctrl+K 弹层，静态菜单域过滤跳转）`

---

## F6 全局水印

**Files:**
- Create: `src/layouts/components/Watermark.vue`
- Modify: `src/layouts/Layout.vue`（根级挂载，el-container 之后）

**Steps:**
- [ ] Watermark.vue（D8）：props 无（内部读 auth store）；离屏 canvas 200×120 绘 `account`（rotate ≈ -22°，字号 14）→ dataURL → `.app-watermark` `background-image repeat`；`position:fixed; inset:0; pointer-events:none; z-index:9999; user-select:none`；颜色随 `appStore.theme`（light `rgba(0,0,0,0.13)` / dark `rgba(255,255,255,0.13)`），watch theme 重绘；`v-if="authStore.account"`
- [ ] 水印随窗口尺寸自然平铺（background repeat，无 resize 监听需求）
- [ ] `npm run build` 绿

**验收标准:**
1. 登录后全屏稀疏账号水印：表格/弹窗/消息之上有水印、内容仍可读；深浅两主题均可读
2. 水印下点击菜单/按钮/表格操作全部正常（pointer-events:none）；登录页无水印（不在 Layout）
3. 切换深色水印颜色即时翻转；console 无错误

**提交:** `feat(cloud-web): F6 全局水印（canvas 平铺/主题自适应/pointer-events none）`

---

## F7 登录背景图 + 名称收口

**Files:**
- Create: `cloud-web/src/assets/login-bg.jpg`（联网下载，≤300KB）
- Modify: `src/views/login/index.vue`、`index.html`

**Steps:**
- [ ] **下载（联网，D11）**：Pexels/Unsplash 检索"dark blue technology background / abstract tech lines"类深蓝科技感图；CDN 直链带压缩参数下载（Pexels `?auto=compress&cs=tinysrgb&w=1600` / Unsplash `?w=1600&q=60&fm=jpg`）：
      `curl -L -o cloud-web/src/assets/login-bg.jpg "<直链>"`；`ls -la` 核对 ≤300KB（超限降 w=1400/q=50 重下，仍超换图）；**两源都不可达则保留 CSS 深蓝渐变降级并回报主控**（不得引外链图/不得入库超限图）
- [ ] login/index.vue：`import loginBg from '../../assets/login-bg.jpg'`，import 处注释记录来源页 URL + License 名（Unsplash License / Pexels License）；`.login-page` 样式 `background: #0b1e3f`（底色降级）+ `background-image: url(loginBg)` center/cover；标题改 `APP_TITLE`（字号 20 加粗）；**保留 `.login-card`/`button.login-submit`/`.login-alert`/placeholder 文案不动**（D11）
- [ ] index.html：`<title>CloudAI 企业基座</title>` + 注释"与 src/constants/app.ts 同步"（D1）
- [ ] `npm run build` 绿

**验收标准:**
1. 登录页深色科技背景铺满视口、卡片可读、深浅主题均正常（图片两态通用）
2. DevTools Network：背景图来自本地资产（构建产物内联/打包，非外网请求）；无 404
3. 文件 ≤300KB；标题三处一致（登录卡片 / 浏览器标签 / 品牌区）
4. S1-S5 既有登录断言选择器全部仍命中（.login-card/.login-submit/.login-alert/placeholder）

**提交:** `feat(cloud-web): F7 登录背景图（免商用图库入库≤300KB）+ 系统名称三处统一`

---

## F8 e2e：既有回归核查 + T 系列 + 全量回归

**Files:**
- Create: `cloud-e2e/run-scaffold-e2e.mjs`（复用 `lib/harness.mjs`）
- Modify: `cloud-e2e/package.json`（`e2e` 链尾追加 `&& node run-scaffold-e2e.mjs`；`e2e:headless` 同理；新增 `"e2e:scaffold"`）

**Steps:**
- [ ] 起 4 后端服务 + 前端 dev（5173）；先连续两次 `npm run build` 全绿
- [ ] **既有回归（预期零适配）**：依次 `npm run e2e:user` / `e2e:role` / `e2e:menu`——全 PASS 则 D12 论证成立；**任何红项**按"既有 e2e 适配清单"（下节）定位属设计违约还是选择器需微调，修复与场景脚本同 commit
- [ ] 新增 `run-scaffold-e2e.mjs`（场景明细见下节；admin 会话、零后端写、弹窗只开不提交）
- [ ] `npm run e2e` 四脚本串行全 PASS（有头）
- [ ] 截图 ≥5 张（t-login.png 登录新视觉 / t-dark.png 深色 Layout / t-collapse.png 折叠态 / t-tags.png 多签+下拉 / t-search.png 搜索弹层）→ Read → 上传得远程 URL → analyze_image 视觉核对（提示词写明预期要素），结论文字记录；截图留在 artifacts（不进 git）
- [ ] 停服（netstat + taskkill //F，CLAUDE.md Windows 陷阱）

**验收标准:**
1. 连续两次 build 零错误
2. `npm run e2e` 四脚本全 PASS（含既有 32 项 + 新增 T 系列）；无 console error / pageerror / ≥400 /api 响应 / 网络失败
3. 视觉核对结论（文字）覆盖 5 张截图要素
4. 场景脚本、功能适配（如有）、package.json 与最后一项功能改动同一 commit 收尾；artifacts 未入 git

**提交:** `feat(cloud-e2e): F8 脚手架升级 T 系列场景 + 全量回归（四脚本）`

---

## 既有 e2e 适配清单（逐脚本逐断言核查，预期全部"无改动"）

| 脚本/场景 | 断言（选择器/行为） | 新增 DOM 影响 | 处置 |
|---|---|---|---|
| user S1/S2/S3 | `.login-card` 可见、`button.login-submit`、`.login-alert`、placeholder 请输入账号/密码、错误码 2001 内联 | F7 保留全部类名与 placeholder | 无改动 |
| user S4/S12 | `.el-menu` 可见、`.navbar-account` innerText 含 admin | 品牌区在 el-menu 外；新按钮不在 `.navbar-account` 内 | 无改动 |
| user S6/S7 | `.el-menu .el-menu-item` labels includes、`.is-active` 高亮、面包屑、F5 恢复 | 菜单 4 项结构顺序不变；折叠默认关；F5 后 app prefs 默认 light/展开（新 context 无脏键） | 无改动 |
| user S8/S8b/S10-S14/S15 | 表格/分页/弹窗/message-box/401 清态跳转 | keep-alive 不影响（全部 goto/reload 驱动）；水印 pointer-events:none；tags 行不遮表格 | 无改动 |
| role R0-R6 | 菜单相邻断言（用户→角色）、`.perm-*` 弹窗内选择器、PUT 请求体断言 | `.perm-*` 域完全未动；tags 下拉文案无"退出登录" | 无改动 |
| menu M0 | login（harness：.login-card input/placeholder + .login-submit） | F7 保留 | 无改动 |
| menu M1 | **精确串** `labels.join(',') === '用户管理,角色管理,菜单管理,工作台'`、`.el-pagination` count===0 | 品牌区非 menu-item；tags 非分页组件 | 无改动（**最高风险项，F2 完成后即可单跑 M1 提前验证**） |
| menu M2-M5 | 树表缩进/弹窗 hasText（新增菜单/编辑菜单）/请求体字段 | 搜索弹层默认零 DOM，`.menu-search-dialog` 与业务弹窗文案无交集 | 无改动 |
| menu M-VERIFY | 零 console error / pageerror / ≥400 / 非 favicon 资产 404 / 网络失败 | dark css-vars 无运行时输出；fullscreen catch 静默；背景图本地资产不 404；水印 canvas 无输出 | 无改动（**新增代码的 console 纪律在 F1-F7 每任务手工验收中前置把关**） |
| 通用 | 每脚本独立 chromium 启动 = 独立 localStorage | T 系列遗留的 dark/collapsed 持久化不会泄漏进其他脚本 context | 无改动（T 系列自身收尾恢复浅色/展开态，双保险） |

## 新增 T 系列场景明细（run-scaffold-e2e.mjs）

前置：admin 登录（复用 harness login）；viewport 1440×900；本脚本**零后端写**（所有弹窗只开不提交）。

| # | 场景 | 断言要点 |
|---|---|---|
| T1 | 登录页视觉与标题 | 未登录直访 /login：`.login-card` 可见、`.login-title` 文本=CloudAI 企业基座、`document.title`=CloudAI 企业基座、`.login-page` computed `background-image` 含 `url(`；截图 t-login.png |
| T2 | 品牌区与文档标题 | 登录后 `.sidebar-brand` 可见且文本含 CloudAI 企业基座；进入 /system/user 后 `document.title` === '用户管理 - CloudAI 企业基座' |
| T3 | 折叠 | 点 `.navbar-collapse`：`.layout-aside` computed width=64px、`.sidebar-brand span` 不可见、`.el-menu` 含 `el-menu--collapse` 类；还原后 width=200px、文本恢复 |
| T4 | 全屏（headless 时记 SKIP） | 点 `.navbar-fullscreen` → `document.fullscreenElement !== null`；再点 → null。HEADLESS 模式直接 push SKIP 结果并注明"无头全屏行为未定"（设计 §8.5） |
| T5 | 深色持久化 | 点 `.navbar-theme` → `html.dark` 存在、localStorage `cloud-web:app` 含 `"theme":"dark"`、`getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color')` 与切换前不同；reload 后仍 dark；再点回 light（**收尾恢复**） |
| T6 | 菜单搜索 | 初始 `.menu-search-dialog` count===0；Ctrl+K 弹层可见且输入框聚焦；输"角色"→ `.menu-search-item` 仅角色管理；Enter → URL /system/role 且弹层关闭（display:none 语义，waitDialogGone）；Esc 路径：重开输乱码 → el-empty 可见 → Esc 关闭 |
| T7 | 水印 | `.app-watermark` 存在；computed `pointer-events` === 'none'；computed `background-image` !== 'none'；**点击穿透**：水印常驻下点菜单切页成功（全脚本本身就是持续验证） |
| T8 | 标签页全套 | 菜单点开 用户/角色/菜单 → `.tags-view-item` count=3、active 跟随；点用户签回 /system/user；**缓存断言**：记 `/api/system/user/page` 请求数 n1 → 切角色签再切回 → 请求数仍 n1；**刷新断言**：点 .tags-actions 下拉"刷新当前页" → 请求数 n1+1 且仍在 /system/user；**弹窗保持断言**：开"新增用户"弹窗（不提交）→ 切工作台签 → 切回 → 弹窗仍可见 → 取消；下拉"关闭其他/关闭右侧/全部关闭"逐项验证页签数量与落点（全部关闭 → /dashboard） |
| T9 | 会话清理 | logoutViaUi → 重新 login → `.tags-view-item` 仅当前页 1 项（上一会话页签被清，D4） |
| T-VERIFY | 证据核验 | 同 M-VERIFY 口径：零 console error（favicon 404 噪音白名单）/ pageerror / ≥400 /api / 网络失败 / 非 favicon 资产 404（覆盖背景图） |

**实现注意**：harness 复用（login/logoutViaUi/waitDialogGone/waitTableIdle/shot）；ep 弹窗 hidden 语义等待；T4/T5 涉及全局态的操作后必须还原（fullscreen 退出、theme 回 light、折叠回展开）——本脚本内顺序即 T3→T4→T5 各自闭环还原，脚本结束态=浅色+展开，双保险。

## 移交备忘（下一阶段规划前必读）

1. 设计 §8 全部 9 条（keep-alive 缓存语义翻案条件、同名多 fullPath、水印防篡改、tags 持久化、headless 全屏、换图流程、index.html 双源、/redirect 迁移、搜索换动态数据源）**随设计文档一体生效**。
2. **稳定类名是内部契约**：`.sidebar-brand/.navbar-*/.tags-view*/.menu-search-*/.app-watermark` 被 e2e 依赖，改名必须同步改脚本（同 commit）。
3. 既有三脚本与 T 系列通过"共享 admin 会话但独立浏览器 context"解耦；`npm run e2e` 顺序 user→role→menu→scaffold（scaffold 零造数，放最后不影响前三者）。
4. 登录背景图为二进制资产入库：后续若建 CI，注意 artifacts/lfs 策略（当前 300KB 级直存无碍）。
5. F4 的 defineOptions 命名约束将随每个新页面延续：**新页面必须 defineOptions name = route.name 才能进缓存**（写进 /frontend-page 技能的候选修订，由主控决定是否沉淀）。

## 计划自检记录

1. **Spec 覆盖**：D1 名称→F2/F7；D2 深色→F1；D3 品牌/折叠→F2；D4/D5/D6/D7 tags+缓存+刷新→F4；D8 水印→F6；D9 全屏→F3；D10 搜索→F5；D11 背景图→F7；D12 e2e 兼容→F8 适配清单逐条对应。设计 §6 边界表逐项落入各任务验收。
2. **依赖顺序**：F1（store/基建）→F2（消费 store）→F3→F4（最大，含路由/命名）→F5/F6（独立增强）→F7（登录页收口）→F8（回归收尾）；F2 完成即可单跑 menu M1 提前暴露最高风险项。
3. **风险预案**：M1 精确串断言（品牌区隔离，F2 提前验）；defineOptions 命名坑（F4 红字步骤）；keep-alive 对既有脚本零影响的依据（全部 goto/reload 驱动）；headless 全屏未定（T4 SKIP 设计）；图库不可达（CSS 降级+回报主控，不擅自引外链）。
4. **纪律**：不新增依赖；不改契约消费；场景脚本与功能代码同 commit；artifacts 不进 git；T 系列零后端写。

## 给 frontend-agent 的任务清单（可直接粘发）

按 F1→F8 串行执行；对齐基线=设计 `docs/superpowers/specs/2026-10-06-scaffold-upgrade-design.md`（**D1-D12 必读**）+ 本计划前端章 + `/frontend-page` 技能。要点重申：

- **F1 深色基建**：`stores/app.ts` + `utils/storage.ts` 增 `cloud-web:app` 单键（容错镜像 getAuth）+ `main.ts` 引 `element-plus/theme-chalk/dark/css-vars.css`（红线唯一例外，注释写论证）+ 挂载前 `html.dark` 防 FOUC + Navbar `.navbar-theme` 按钮；自定义样式只用 EP CSS 变量
- **F2 名称+品牌+折叠**：`constants/app.ts`(APP_TITLE) + `constants/menus.ts`(MENU_ITEMS，Sidebar 改引)；`.sidebar-brand` 必须 el-menu **兄弟节点**（menu e2e M1 精确串断言）；el-menu `:collapse` + aside 64/200 响应；Navbar `.navbar-collapse` 汉堡；router afterEach 设 document.title
- **F3 全屏**：Navbar `.navbar-fullscreen`，requestFullscreen/exitFullscreen + fullscreenchange 同步态，**catch 静默**（M-VERIFY 零 console）
- **F4 tags+缓存+刷新**：`stores/tags.ts` + `TagsView.vue`（el-tag 页签 + `.tags-actions` 五动作下拉；全部关闭→/dashboard；无固定签）；**四个视图必须 `defineOptions({ name })` = route.name**（script setup 推断名全是 "index"，include 失效红字坑）；keep-alive `:include="cachedNames"` 且**不加 :key**；刷新 = removeCached → `/redirect/:path(.*)` 中转回；login onMounted `closeAll()`（会话清理）；Layout 内单 watcher 同步 addTag（跳过 Redirect/public）
- **F5 搜索**：`MenuSearch.vue`，`.navbar-search` 按钮 + Ctrl/Shift+K（preventDefault）+ `el-dialog` 外层 **v-if 默认零 DOM**；过滤 constants/menus 的 title；↑↓/Enter/Esc；`@opened` 聚焦
- **F6 水印**：`Watermark.vue` canvas 200×120 平铺 dataURL；fixed + inset:0 + **pointer-events:none** + z-index 9999；颜色随主题 watch 重绘；account 空 v-if 不渲染
- **F7 背景图**：Pexels/Unsplash（免商用 License）深蓝科技图，curl -L -o 入库 `src/assets/login-bg.jpg` **≤300KB**（超限降 w/q 重下）；import 处注释记来源+License；`.login-page` 底色 `#0b1e3f` 降级；标题三处统一（login 卡片/`index.html` title/品牌区）；**登录页既有钩子类名与 placeholder 全保留**；两源不可达→CSS 降级+回报主控
- **F8 e2e**：连续两次 `npm run build`；`e2e:user/role/menu` 既有回归（预期零适配，红了按计划适配清单定位修复，同 commit）；新建 `run-scaffold-e2e.mjs`（T1-T9+T-VERIFY，admin 会话零后端写，弹窗只开不提交，全局态操作后还原）+ package.json `e2e` 链尾追加 + `e2e:scaffold`；`npm run e2e` 四脚本全 PASS；截图 ≥5 张 analyze_image 视觉核对（结论文字记录，截图不进 git）
- **红线**：Element Plus 按需（唯一例外 dark/css-vars.css）；**不新增 npm 依赖**；不改 cloud-base 与既有契约消费（auth/user/role/menu api 调用零改动）；场景脚本与功能代码同一 commit；e2e 测试数据纪律（不碰 admin/种子）；中文注释；组件文件结构与既有平铺风格一致；发现设计与实测冲突回报主控，不自行翻案
