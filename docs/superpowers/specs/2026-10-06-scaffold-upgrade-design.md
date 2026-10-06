# cloud-web 前端脚手架升级技术方案（系统名称 / 登录背景 / B 端增强包）

- 日期：2026-10-06
- 状态：已确认（范围决议由主控与用户定案；文中开放点均按推荐项定案并标注，可翻案项见 §9）
- 轨道：**纯前端 A 级**——仅动 `cloud-web/` 与 `cloud-e2e/`，不改 cloud-base、不新增后端接口、不改动既有 API 契约消费（auth/user/role/menu 接口调用零改动）→ **无 contracts 文档**
- 关联：实施计划 `docs/superpowers/plans/2026-10-06-scaffold-upgrade.md`；基线设计 `specs/2026-10-05-cloud-web-frontend-design.md`（分层/请求封装/storage 约定沿用）；前端规范以 `/frontend-page` 技能为唯一来源
- 基线现状（2026-10-06 侦察核实）：Layout 三段式（aside 200px / header 48px / 裸 router-view）、Sidebar 静态 4 菜单、Navbar 面包屑+退出、登录页纯色背景 + 标题"cloud-web 登录"、Pinia 仅 auth store；e2e 三脚本（user/role/menu）32 项全 PASS

## 1. 需求与范围

**做**（用户需求 + 主控确认决议）：

| # | 功能 | 内容 |
|---|---|---|
| 1 | 系统名称统一 | "CloudAI 企业基座"三处：侧边栏品牌区（展开全称/折叠缩略）+ 登录页标题（替换"cloud-web 登录"）+ `index.html` `<title>`（替换"cloud-web"）；顺带 document.title 随路由联动 |
| 2 | 登录背景图 | 深色科技感（深蓝渐变+抽象科技线条），联网下载免商用授权图库（Unsplash/Pexels License），入库 `src/assets/`（jpg，≤300KB），入库后不依赖外网 |
| 3 | 多标签页导航 | tags-view：页签切换/关闭当前/关闭其他/关闭右侧/全部关闭 + 刷新当前页；keep-alive 页面缓存 |
| 4 | 侧栏折叠 + 全屏 | 折叠后仅图标（el-menu collapse）+ 顶栏汉堡切换；全屏按钮（document.fullscreen API） |
| 5 | 深色模式 | Element Plus dark 主题 + localStorage 持久化 + 顶栏切换按钮 |
| 6 | 菜单搜索 + 水印 | Ctrl+K 唤起搜索弹层（匹配路由标题跳页，无新接口）；全局水印（当前账号名，pointer-events:none，深浅色可读） |

**明确不做**（防蔓延，后续需求另议）：新三方依赖（水印/搜索/tags 全手写）、tags 右键菜单、i18n、动态路由/动态菜单、tags 持久化（F5 重建）、通知中心/面包屑导航、移动端响应式、水印防篡改（MutationObserver 对抗）、登录页验证码。

## 2. 布局架构（改造前后）

**改造前**：

```
el-container.layout (100vh)
├─ el-aside 200px ── Sidebar: el-menu（静态 4 项，无品牌区）
└─ el-container
   ├─ el-header 48px ── Navbar: Breadcrumb ←→ 账号 dropdown（仅退出）
   └─ el-main ── <router-view>（裸，无缓存）
```

**改造后**：

```
el-container.layout (100vh)
├─ el-aside :width = collapsed ? 64px : 200px
│  └─ Sidebar
│     ├─ .sidebar-brand（高度 48px：Cloudy 图标 + APP_TITLE；折叠态仅图标）
│     └─ el-menu（:collapse=collapsed, router, default-active=route.path）  ← 4 项结构与顺序不变
└─ el-container
   ├─ el-header 48px ── Navbar
   │     ├─ 左：.navbar-collapse 汉堡按钮 + Breadcrumb（不动）
   │     └─ 右：MenuSearch（.navbar-search 按钮 + Ctrl+K 弹层）+ .navbar-theme
   │           + .navbar-fullscreen + 账号 dropdown（.navbar-account，不动）
   ├─ .tags-view（34px 独立行，非 el-header）── TagsView
   │     ├─ 页签 .tags-view-item（closable，active 高亮）
   │     └─ .tags-actions 下拉：刷新当前页 / 关闭当前 / 关闭其他 / 关闭右侧 / 全部关闭
   └─ el-main ── <router-view v-slot="{ Component }">
        └─ <keep-alive :include="tagsStore.cachedNames"><component :is="Component" /></keep-alive>

Watermark（.app-watermark）：Layout 根级兄弟节点，position:fixed 全屏覆盖、
  pointer-events:none、z-index 9999、canvas 平铺背景——不进 DOM 事件链
```

数据流：`Navbar/App 内按钮 → stores/app.ts（theme/sidebarCollapsed）→ 持久化 utils/storage.ts（单键 cloud-web:app）`；`路由变化 → Layout 内 watcher → stores/tags.ts（visitedTags/cachedNames）→ TagsView 渲染 + keep-alive include`。

## 3. 状态与持久化设计

### 3.1 localStorage 键表（storage.ts 集中管理，沿用单键 JSON 模式）

| 键 | 值 | 读写方 | 容错 |
|---|---|---|---|
| `cloud-web:auth` | `{accessToken, refreshToken, account}`（**不动**） | request.ts/auth store | 既有 |
| `cloud-web:app`（新增） | `{theme: 'light'\|'dark', sidebarCollapsed: boolean}` | stores/app.ts；main.ts 预挂载直读 | 解析失败/缺字段 → 回退 `{light, false}` 并清键（镜像 getAuth 模式） |

新增 `getAppPrefs()/setAppPrefs()` 到既有 `utils/storage.ts`（键集中管理，不另开文件）。tags 状态**不持久化**（内存态，F5 重建——D4）。

### 3.2 store 清单

| store | state | actions | 备注 |
|---|---|---|---|
| `stores/app.ts`（新增） | `theme`、`sidebarCollapsed`（初始化自 storage） | `toggleTheme()`（同步 `html.dark` 类 + 落盘）、`toggleSidebar()`（落盘） | 全局外观唯一来源；水印/自定义样式经 CSS 变量自动跟随，不额外发事件 |
| `stores/tags.ts`（新增） | `visitedTags: TagView[]`（`{path: fullPath, title, name}`）、`cachedNames: string[]` | `addTag(route)` / `removeTag(path)` / `closeOthers(path)` / `closeRight(path)` / `closeAll()` / `removeCached(name)` | 纯内存；增删同步规则见 §4 D5 |

**深色 FOUC 防护**：`main.ts` 在 `app.mount` 前同步执行 `if (getAppPrefs()?.theme === 'dark') document.documentElement.classList.add('dark')`（storage 读取是同步的，无闪烁窗口）；此后主题切换由 app store action 维护类与落盘一致性。

## 4. 设计决策（D 系列）

### D1 系统名称单一来源：`src/constants/app.ts`

新增 `export const APP_TITLE = 'CloudAI 企业基座'`，消费方：Sidebar 品牌区、登录页标题、router.afterEach 的 document.title。`index.html` 无法 import 模块——直接写死同一文案并在该文件加注释"与 constants/app.ts 保持同步"（两处文案，单一语义源）。document.title 联动：`router.afterEach((to) => { document.title = to.meta.title ? `${to.meta.title} - ${APP_TITLE}` : APP_TITLE })`（登录页无 meta.title → 全称）。备选（每处硬编码）放弃：三处漂移风险。

### D2 深色模式路径：`html.dark` + `theme-chalk/dark/css-vars.css`（按需红线唯一例外，论证如下）

- 实现：`main.ts` 静态 `import 'element-plus/theme-chalk/dark/css-vars.css'`（本机 node_modules 已核实存在该文件）；切换 = `document.documentElement.classList.toggle('dark')`；持久化见 §3.1。
- **红线论证**：前端规范红线禁止的是 `app.use(ElementPlus)` 全量注册与 `dist/index.css` 全量组件样式（实测体积代价 JS +95%/CSS +152%）。`theme-chalk/dark/css-vars.css` 是官方暗色变量文件，**已实测核实**（本机 element-plus 2.14.7）：全文 2946 字节，内容仅 `html.dark { --el-*: … }` 的 CSS 自定义属性覆盖与 color-scheme 声明——无组件样式规则、无 JS，体积与性质等同于已获准的 `element-plus/es/components/message/style/css` 按组件样式 import，它就是按需体系下的官方暗色通道，不存在"全量样式"问题。除此之外不新增任何全量样式 import。
- 自定义样式深浅适配策略：新增样式（品牌区/tags/水印/登录页）一律用 EP CSS 变量（`var(--el-bg-color)`、`var(--el-border-color-light)`、`var(--el-text-color-*)` 等）而非硬编码色值——变量随 html.dark 自动翻转，零媒体查询。唯一的例外是水印文字颜色（canvas 绘制，需 watch theme 重绘，见 D8）与登录背景图（本身深色，两态通用）。

### D3 侧栏折叠：el-menu collapse + 品牌区独立于 el-menu 之外

- `el-aside :width` 响应式 64px/200px；`el-menu :collapse="collapsed"`（折叠态 EP 自动以 tooltip 显示菜单名）；状态在 app store、持久化。
- **品牌区必须是 el-menu 的兄弟节点而非内部元素**——e2e 以 `.el-menu .el-menu-item` 断言菜单项数量与顺序（menu e2e M1 断言精确串 `'用户管理,角色管理,菜单管理,工作台'`），品牌区若做成 menu-item 会混入断言域。折叠态品牌区只显示 Cloudy 图标（`<span>` 用 `v-show` 隐藏而非 v-if，避免折叠瞬间高度跳动）。
- 折叠默认关闭（既有 e2e 全部在展开态跑，labels 断言依赖菜单文字可见）。

### D4 tags 状态：独立 tags store、内存态不持久化、登录页挂载即重置

- 备选 A：持久化 tags 到 localStorage（跨 F5 恢复页签）——放弃：缓存组件实例无法跨刷新恢复，持久化只会恢复"壳"（页签条）而页面仍重挂载，语义半吊子；且多账号共用浏览器时残留上一账号页签有信息泄漏观感。
- 备选 B：tags 并入 app store——放弃：职责混杂，tags 的增删逻辑（watcher 驱动）与外观状态（按钮驱动）变更节奏不同。
- **会话清理规则**：`views/login/index.vue` `onMounted → tagsStore.closeAll() + removeCached 全清`。登录页是所有"进入新会话"路径（手动退出/401 清态跳转/直接访问）的必经点，在此重置可统一覆盖三条路径且无组件卸载顺序竞态（比在 Layout 卸载钩子里清理可靠）。缓存的列表页组件随 cachedNames 清空被 keep-alive 自动剪枝，无跨账号数据残留。

### D5 keep-alive 缓存策略：受保护页全缓存；缓存语义 = 状态保持，不自动刷新

- **include 匹配的是组件名**：`<script setup>` SFC 的推断名取自文件名——四个页面都是 `index.vue`，推断名全为 `index`，include 将无法区分（全缓存或全不缓存）。**必须给四个视图各加 `defineOptions({ name: 'SystemUser' })`**（与 route.name 一致；Vue 3.5 支持）。这是本方案最大的隐性坑，写进计划 F4。
- 缓存范围：Layout 下全部 4 个子页面（SystemUser/SystemRole/SystemMenu/Dashboard）进 cachedNames；`router-view` 的动态组件**不设 `:key`**（同名不同 fullPath 复用同一缓存实例——当前无带 query 的页面，此语义无实际影响，记录在案）。
- **缓存语义定案（列表筛选态缓存 vs 刷新语义冲突）**：站内切页签返回 = 恢复离开时的状态（筛选/页码/弹窗开闭），**不重新拉数据**；需要最新数据点"刷新当前页"（D6）或 F5。理由：B 端标签页的核心价值就是"来回切不丢状态"；自动 onActivated 重拉会让缓存形同虚设。数据陈旧风险由显式刷新兜底。
- **tag 同步**：Layout 内 `watch(() => route.fullPath)` 单一 watcher：`route.name` 存在且非 `'Redirect'` 且非 `meta.public` → `addTag(route)`（同时维护 visited 与 cached）。Layout 是所有受保护路由的父级，watcher 生命周期与标签页一致。

### D6 刷新实现：`/redirect/:path(.*)` 中转路由（keep-alive key 复用陷阱的规避）

- 备选 A：给动态组件挂 `:key="route.fullPath + '-' + stamp"`，刷新时 bump stamp 强制重挂——放弃：keep-alive 按 key 缓存，旧 key 实例滞留内存（泄漏），且 include 按名匹配与新 key 语义纠缠。
- **定案 B**（vue-element-admin 同款成熟模式）：Layout children 加 `{ path: 'redirect/:path(.*)', name: 'Redirect', component: views/redirect/index.vue }`，组件 setup 内 `router.replace({ path: '/' + route.params.path, query: route.query })`。刷新动作 = `tagsStore.removeCached(route.name)`（keep-alive 随即剪枝旧实例）→ `router.replace('/redirect' + route.fullPath)` → 回到目标页时 D5 watcher 重新 addTag（重新入缓存）→ 全新挂载。中转路由挂在 Layout children 下（不闪布局骨架），watcher 按 name === 'Redirect' 跳过不建签。
- 刷新的可观测信号（e2e 用）：keep-alive 缓存态下切回页签**不发**列表请求、点刷新**重发**列表请求——请求计数差即断言依据，无数据依赖。

### D7 tags 操作集：单一下拉 + 页签自身 closable；无固定签；全部关闭落点 /dashboard

- 每个页签：点击 = 切换路由（`router.push(tag.path)`），自带关闭图标（el-tag closable + `@close` stop）；关闭当前签时落点 = 左邻签，无左邻取右邻，一个不剩 → `/dashboard`。
- bar 右端一个 `.tags-actions` 下拉（el-dropdown）：刷新当前页 / 关闭当前 / 关闭其他 / 关闭右侧 / 全部关闭——五个动作集中一处（备选：每签右键菜单——放弃，右键菜单实现与 e2e 成本高、收益低）。
- 不设 affix 固定签（如固定"首页"）：当前系统无首页概念（`/` redirect 到用户管理），固定签引入特例状态机；"全部关闭 → /dashboard"已定义确定性落点。
- 关闭其他/关闭右侧同步收缩 cachedNames = 剩余 visited 的 name 去重集（防止剪不掉或多剪）。

### D8 水印：canvas 平铺 dataURL + fixed + pointer-events:none + z-index 9999 + 主题自适应重绘

- 手写 `layouts/components/Watermark.vue`（不引三方库）：离屏 canvas（约 200×120）绘制 `authStore.account` 文本（rotate 约 -22°、间距平铺）→ `toDataURL()` → 单个 `div.app-watermark` 的 `background-image: url(dataURL) repeat`；`position: fixed; inset: 0; pointer-events: none; z-index: 9999; user-select: none`。
- z-index 9999 高于 EP 弹窗/message（约 2000+）：水印盖住弹窗与消息（半透明稀疏文字，不遮挡可读性）；pointer-events:none 保证不拦任何点击——**既有 32 项 e2e 在水印常驻下全过，本身就是"不挡交互"的最强回归证明**。
- 深浅色可读：文字颜色 light 态 `rgba(0,0,0,0.13)` / dark 态 `rgba(255,255,255,0.13)`；canvas 是一次性位图，**watch appStore.theme 触发重绘**。account 为空（理论不该有）则整个组件 `v-if` 不渲染。
- 不做防篡改对抗（移除 DOM 即消失）——内部管理台够用，记录移交。

### D9 全屏：document.fullscreen API，失败静默

`document.documentElement.requestFullscreen()` / `document.exitFullscreen()`；按钮图标态用 `fullscreenchange` 事件同步（监听在 Navbar 挂载/卸载生命周期内增删）。requestFullscreen 返回 promise，在不支持/被拒环境 reject——catch 吞掉不 toast、不打 console（menu e2e M-VERIFY 断言零 console error）。e2e 断言 `document.fullscreenElement`；headless 下行为不确定（已知风险，脚本设计为 headless 时 SKIP 该断言，见计划）。

### D10 菜单搜索：`constants/menus.ts` 单一数据源 + Ctrl+K 弹层

- 侧边栏静态菜单数组从 Sidebar.vue 抽到 `src/constants/menus.ts`（`MENU_ITEMS: {path,title,icon}[]`），Sidebar 与 MenuSearch 共同消费——单一来源，将来换动态菜单只改一处（备选：搜索直接遍历 router.options.routes——放弃，耦合路由内部结构且拿不到图标顺序语义）。
- `layouts/components/MenuSearch.vue`：顶栏按钮（`.navbar-search`，Search 图标）+ `window` keydown 监听 Ctrl/Shift+K（`e.preventDefault()`，页面聚焦时浏览器地址栏搜索会被拦住）唤起 `el-dialog`（width 480px，**外层 `v-if="visible"` 包裹，默认零 DOM**——e2e 断言弹层不存在）。
- 交互：输入即过滤（`title.includes(keyword)` 不区分大小写；空关键词显示全部）；结果行 `.menu-search-item`（title + path 灰字）；↑↓ 移动高亮、Enter 跳高亮项、Esc 关闭（el-dialog 默认）；dialog `@opened` 聚焦输入框。无结果 `el-empty` 短文案。跳转用 `router.push(path)` 并关闭弹层。不做拼音/模糊匹配（无依赖红线）。

### D11 登录背景图入库：免商用图库下载 + assets 入库 + CSS 底色降级

- 来源限定 **Unsplash License 或 Pexels License**（均免商用、无需署名）：frontend-agent 联网检索"深蓝渐变+抽象科技线条"类图（如 Pexels 搜 `dark blue technology background`），取 CDN 直链加尺寸参数下载（Pexels：`images.pexels.com/photos/<id>/pexels-photo-<id>.jpeg?auto=compress&cs=tinysrgb&w=1600`；Unsplash：`images.unsplash.com/photo-<id>?w=1600&q=60&fm=jpg`），curl -L -o 存为 `cloud-web/src/assets/login-bg.jpg`（二进制下载无 GBK 问题）。
- **体积红线 ≤300KB**：超限则降 `w=1400` 或 `q=50` 重下（URL 参数即压缩旋钮）；仍超限换图。**授权与出处记录**：登录页 import 处注释写来源页 URL + License 名（验收口径），不入库 LICENSE 文件（单图无需）。
- 接入：`.login-page` `background: #0b1e3f`（底色恒在，图片加载失败/未加载时优雅降级为深蓝纯色）+ `background-image: url(loginBg)` `center/cover no-repeat`；卡片保持 `var(--el-bg-color)` 两态可读。入库后前端零外网依赖（Vite 构建期会把 assets 打进产物）。
- 既有登录页 e2e 钩子**全部保留**：`.login-card`、`button.login-submit`、`.login-alert`、两个 placeholder 文案——登录页重构只动背景层与标题文案。

### D12 e2e 兼容性设计原则：新增 DOM 不进入既有选择器域

所有新功能 DOM 隔离手段（逐条对应既有断言）：

| 新增 DOM | 隔离手段 | 保护的不既有断言 |
|---|---|---|
| 品牌区 | el-menu 兄弟节点，类名 `.sidebar-brand` | `.el-menu .el-menu-item` 数量/顺序（M1 精确串断言、R1 相邻断言、S6 includes 断言） |
| 顶栏新按钮 | 独立类名 `.navbar-collapse/.navbar-theme/.navbar-fullscreen/.navbar-search`，均不在 `.navbar-account` 内 | S4/S12 `.navbar-account` innerText 含 admin |
| tags-view | 独立行 `.tags-view`，页签用 el-tag 但只在本组件内 | 全局 `.el-tag` 计数不存在断言（R/M 系列均为行内 scoped 定位） |
| tags 下拉 | 文案不含"退出登录" | logoutViaUi 按 hasText '退出登录' 定位 dropdown 项 |
| 水印 | pointer-events:none（计算样式断言 + 全量回归隐式证明） | 所有 click 类操作 |
| 搜索弹层 | `v-if` 默认零 DOM；类名 `.menu-search-dialog` 与业务弹窗文案无交集 | `.el-dialog` hasText 过滤类断言（新增用户/编辑角色等） |
| keep-alive | 既有脚本全部 `page.goto/reload` 驱动导航（整页加载必然全新挂载） | 列表加载/表头/行断言；站内往返不重发请求属新语义，无既有断言依赖 |
| dark css-vars | 仅变量覆盖，无 console 输出 | M-VERIFY 零 console error |

**新增 DOM 的稳定类名即本设计的"前端内部契约"**（e2e 依赖，实现不得随意改名）：`.sidebar-brand`、`.navbar-collapse`、`.navbar-theme`、`.navbar-fullscreen`、`.navbar-search`、`.tags-view`、`.tags-view-item`（active 态 `.active`）、`.tags-actions`、`.menu-search-dialog`、`.menu-search-item`、`.app-watermark`、`.layout-aside`（既有，折叠宽度断言用）。

## 5. 组件与文件清单

| 文件 | 动作 | 职责与关键点 |
|---|---|---|
| `src/constants/app.ts` | 新建 | `APP_TITLE`（D1） |
| `src/constants/menus.ts` | 新建 | `MENU_ITEMS`（D7 单一来源，从 Sidebar 抽出） |
| `src/stores/app.ts` | 新建 | theme + sidebarCollapsed（§3.2） |
| `src/stores/tags.ts` | 新建 | visitedTags + cachedNames + 增删动作（D5/D7） |
| `src/utils/storage.ts` | 修改 | 追加 `APP_KEY`/`getAppPrefs`/`setAppPrefs`（§3.1，容错镜像 getAuth） |
| `src/main.ts` | 修改 | dark css-vars import（D2）+ 挂载前主题初始化（§3.1 FOUC） |
| `src/layouts/Layout.vue` | 修改 | aside 宽度联动、tags 行、router-view 包 keep-alive（include=cachedNames）、路由→tag watcher、挂 Watermark |
| `src/layouts/components/Sidebar.vue` | 修改 | 品牌区 + 菜单数组改引 constants/menus + `:collapse` |
| `src/layouts/components/Navbar.vue` | 修改 | 汉堡/主题/全屏按钮 + 嵌 MenuSearch；退出逻辑不动 |
| `src/layouts/components/TagsView.vue` | 新建 | 页签条 + 操作下拉（D7） |
| `src/layouts/components/MenuSearch.vue` | 新建 | 搜索按钮 + Ctrl+K 弹层（D10） |
| `src/layouts/components/Watermark.vue` | 新建 | canvas 水印（D8） |
| `src/views/redirect/index.vue` | 新建 | 中转重定向（D6，setup 内 replace） |
| `src/views/{login,system/user,system/role,system/menu,dashboard}` 各 index.vue | 修改 | login：背景图+标题；其余四个：加 `defineOptions({ name })`；login 另加 onMounted 清 tags（D4） |
| `src/router/index.ts` | 修改 | redirect 子路由 + afterEach document.title（D1） |
| `index.html` | 修改 | `<title>CloudAI 企业基座</title>`（注释指回 constants） |
| `src/assets/login-bg.jpg` | 新建（二进制） | ≤300KB，D11 |
| `cloud-e2e/run-scaffold-e2e.mjs` | 新建 | T 系列场景（计划 §新增场景） |
| `cloud-e2e/package.json` | 修改 | `e2e` 链尾追加 scaffold；新增 `e2e:scaffold` |

不新增任何 npm 依赖（dependencies/devDependencies 零改动）。

图标选型（均已在本机 `@element-plus/icons-vue` 类型目录核实存在）：品牌 `Cloudy`、汉堡 `Fold`/`Expand`、主题 `Sunny`/`Moon`、全屏 `FullScreen`、搜索 `Search`；既有菜单图标（User/UserFilled/Menu/Monitor）不动。

## 6. 错误处理与边界

| 场景 | 处理 |
|---|---|
| fullscreen requestFullscreen reject | catch 吞掉（无 toast 无 console，D9） |
| `cloud-web:app` 脏数据/缺字段 | getPrefs 容错回退 light/false 并清键（§3.1） |
| 关闭最后一个页签 | 落点 `/dashboard`（D7），不出现空标签栏 |
| 401 清态跳登录 | 登录页 onMounted 清 tags（D4），重登后干净起步 |
| 刷新时 cached 已被剪 | removeCached 幂等（filter），redirect 往返后 watcher 重加 |
| redirect 路由被直接访问（手输 URL） | 正常中转回目标页；无循环（replace 目标是真实路由） |
| 搜索无结果/空关键词 | el-empty / 全量列表；Enter 高亮项不存在则 no-op |
| 水印 account 为空 | v-if 不渲染 |
| 背景图加载失败 | `.login-page` 深蓝底色降级（D11）；构建期本地资产，实际不会 404 |
| 折叠态点菜单 | el-menu collapse 自带 tooltip，router 模式跳转不变 |
| Ctrl+K 在输入框内按下 | 仍全局唤起（Ctrl 组合键语义为全局快捷键） |

## 7. 测试策略

- **构建门禁**：每任务 `npm run build`（vue-tsc strict）绿；全量收尾连续两次 build（components.d.ts 陷阱，/frontend-page 技能）。
- **既有回归**：user/role/menu 三脚本全量重跑（32 项不可破）；预期**零适配改动**（D12 论证），任何红项=设计违约，当任务修复。
- **新增 T 系列**（run-scaffold-e2e.mjs，编号 T1-T9 + T-VERIFY）：登录页视觉与标题 / 品牌区 / 标签页全套（含"缓存不重发 vs 刷新重发"请求计数断言、弹窗开闭跨页签保持断言）/ 折叠 / 全屏（headless SKIP）/ 深色持久化（类名 + localStorage + 变量翻转 + reload）/ 搜索（默认零 DOM、过滤、Enter 跳转、Esc）/ 水印（存在、pointer-events、背景图、点击穿透）/ console 与网络证据核验。明细见实施计划。
- **视觉核对**：截图 ≥5 张（登录页/深色 Layout/折叠态/tags 多签/搜索弹层）走 analyze_image（远程 URL 通路），结论文字记录、截图不入 git。
- **测试数据纪律**：T 系列全程仅 admin 会话的 UI 只读操作（弹窗只开不提交），零后端写、零种子触碰。

## 8. 已知取舍与移交备忘（下一步规划前必读）

1. **keep-alive 全缓存策略**（D5）：站内返回不重拉数据是刻意语义；若后续某页需要"回来必刷新"，给该页加 onActivated 重拉或将其 name 移出 cachedNames。
2. **同名多 fullPath 页签共享缓存实例**（无 :key）：当前无带 query 页面，无影响；引入详情页（如 `/system/user/:id`）时必须改 key 策略并重审 keep-alive。
3. **水印无防篡改**：DOM 可被控制台移除；需要对抗时上 MutationObserver 重挂（另立需求）。
4. **tags 不持久化**（D4）：F5 丢页签；若产品要求恢复，需配套"恢复壳 + 首访重挂载"的降级语义说明。
5. **headless 全屏断言**：requestFullscreen 无头行为未定，脚本已设计 headless SKIP；若未来 CI 无头跑全量，该项永远 SKIP 属预期。
6. **背景图换图流程**：替换 `src/assets/login-bg.jpg` 即可，注意同步更新 import 处的授权注释；体积红线 ≤300KB 持续生效。
7. **`index.html` 标题与 APP_TITLE 双源**：HTML 无法 import 常量，靠注释约束同步（D1）。
8. **`/redirect` 路由为公开实现细节**：e2e 与用户均不直接依赖；将来动态路由改造时随路由表迁移。
9. **搜索仅静态菜单域**：动态菜单上线后 MENU_ITEMS 换数据源即可，交互不变（D7 预留）。
