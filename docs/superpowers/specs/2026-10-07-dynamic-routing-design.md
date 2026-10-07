# 业务动态路由技术方案（sys_menu 扩路由字段 + 当前用户导航树端点 + 前端 addRoute 动态构建）

- 日期：2026-10-07
- 状态：已确认（轨道决议：**全栈方案 A**，主控与用户定案）
- 轨道：全栈（后端 cloud-system + 前端 cloud-web + e2e cloud-e2e）；网关/sso/common 零改动
- 关联：契约 `docs/superpowers/contracts/2026-10-07-menu-nav-api.md`（导航域新端点 + 菜单管理契约 v2 增补）；实施计划 `docs/superpowers/plans/2026-10-07-dynamic-routing.md`
- 基线：main@cc03174（脚手架升级已合并）；e2e 四脚本（user 15 项 + role + menu + scaffold T 系列）全 PASS

## 1. 需求与范围

**做**：

| # | 功能 | 内容 |
|---|---|---|
| 1 | sys_menu 扩路由字段 | 新增 `path`（前端路由路径）与 `icon`（图标名）两列；component 不落库（D1） |
| 2 | 新端点"当前用户导航树" | `GET /system/menu/user-nav`，仅认证无 @PreAuthorize，实时查库按当前用户角色聚合可见 M/C 树（D2-D4） |
| 3 | 前端动态路由 | 路由两层结构（静态核心层 + 动态层 addRoute）、守卫"等菜单再放行"、404/无权限兜底、登出清态、F5 重建（D5-D7） |
| 4 | RBAC 闭环 | 角色分配了什么菜单，登录后就看到什么路由/侧边菜单（e2e N 系列验证） |
| 5 | 菜单管理页适配 | MenuFormDialog 加 path/icon 字段（C 型必填 path）；MenuTreeNode 出参 additive 扩展 |
| 6 | e2e | 新增 N 系列（动态路由场景）；既有 32+ 场景兼容策略（D10 + 计划适配清单） |

**明确不做**（防蔓延）：按钮级权限指令（v-perms，移交备忘）、菜单缓存/持久化（每次 F5 实时拉取，见 D4 论证）、component 字段落库、在线用户页面（种子 21 保持无 path 不进导航）、面包屑按 M 目录层级展示（保持 matched 两级）、后端菜单/角色内置保护（3008+ 另案）、`/me` 权限清单端点、i18n。

## 2. 现状事实（2026-10-07 侦察核实，设计前提）

1. **sys_menu 无 path/icon/component 列**（`cloud-base/scripts/sql/cloud_system.sql`，DDL 已核）；字段仅 id/parent_id/name/perms/type/sort/status/审计/deleted
2. **admin 角色（id=1）种子绑定全部菜单**：`INSERT INTO sys_role_menu SELECT 1, id FROM sys_menu`——按"用户→角色→菜单"聚合查询天然返回全量，**无需 admin 特判**；但菜单管理页新建的菜单不会自动绑给 admin 角色（须在角色页"分配权限"补绑，诚实 RBAC 语义，写入契约已知语义）
3. **登录响应仅 token 三字段**（accessToken/refreshToken/expiresIn）；前端拿不到 perms 清单；X-User-Perms 只在网关→服务 header 链路
4. **`/menu/tree` 是管理端全量树**（`system:menu:list`，普通用户 403），不能复用为导航源
5. **工作台（/dashboard）不在 sys_menu**——纯前端静态页；侧边菜单现状为静态 `MENU_ITEMS` 常量 4 项（用户/角色/菜单/工作台，平铺无目录层级）
6. **在线用户（id 21，sso:online:list，C 型）在 sys_menu 且绑给 admin**，但前端无此页面
7. **menu e2e M1 精确串断言**：`.el-menu .el-menu-item` labels join === `'用户管理,角色管理,菜单管理,工作台'`；S6 断言 labels includes 用户管理/工作台 + 点击工作台可跳 /dashboard；R1 断言 用户管理→角色管理 相邻
8. **ArchitectureGuardTest 不强制 @PreAuthorize 存在**（只拦两行式/隐式 @PathVariable/Wrapper/Map 接参等）——无 @PreAuthorize 的"仅认证"端点机械可行；方法命名白名单 `list` 前缀覆盖新方法；新 SQL 含 `sys_menu` 必须 `deleted` 条件（已纳入 D3 SQL 设计）
9. **MySQL 无客户端**——DDL 执行走"java 单文件源码 + mysql-connector-j"通路（CLAUDE.md 末节，jar 在本地 maven 仓库，路径实现时验证）

## 3. 总体架构（文字版）

```
登录（既有，零改动）                        导航数据流（本方案新增）
┌──────────┐   token    ┌───────────┐      ┌─────────────────────────────────────────┐
│ cloud-web │ ────────▶ │ 网关 18080 │ ───▶ │ GET /system/menu/user-nav（cloud-system）│
│ (Vue3)    │           │ 验签+透传  │      │ X-User-Account → 按用户聚合实时查库        │
└──────────┘           └───────────┘      │ user→user_role→role(启用)→role_menu       │
     │                                     │   →menu(启用, M或C且C有path) → 剪枝组树    │
     │ F5/登录后首次导航                     └─────────────────────────────────────────┘
     ▼
router.beforeEach（守卫）
  ├─ 未登录 → /login（既有）
  ├─ 已登录 && 菜单未加载 → menuStore.ensureLoaded()
  │     └─ GET user-nav → buildRoutes()（addRoute 挂 Layout children）→ return to.fullPath 重新匹配
  └─ 已加载 → 放行

前端路由终态
  静态核心层（写死）：/login（public）、'/'→Layout（redirect /dashboard）
      └─ children：/dashboard（工作台，恒可见兜底页）、/redirect/:path(.*)、/:pathMatch(.*)*→NotFound、/menu-error（加载失败页）
  动态层（登录后按 nav 注册）：/system/user、/system/role、/system/menu …（path 来自 sys_menu，
      component 来自前端 VIEW_REGISTRY 显式注册表，name 由 path 派生）

侧边菜单 = 动态树（M→el-sub-menu，C→el-menu-item）+ 静态"工作台"尾挂
菜单搜索 = 动态树扁平化 C 项 + 工作台（constants/menus.ts 删除，单一来源迁至 menu store）
```

**RBAC 闭环**：角色分配菜单（AssignMenuDialog，既有）→ 用户登录 → user-nav 按 role_menu 聚合 → 前端只见所绑菜单 → 侧边菜单/路由/搜索三处一致；未绑路由直链落 NotFound 兜底。

## 4. 设计决策（D 系列，每项含备选与弃选理由）

### D1 DDL：sys_menu 扩 `path` + `icon` 两列；component 不落库（前端显式注册表）

- **定案**：
  ```sql
  ALTER TABLE sys_menu
      ADD COLUMN path VARCHAR(100) NOT NULL DEFAULT '' COMMENT '前端路由路径，C型菜单有效（以/开头），空串=不进导航' AFTER type,
      ADD COLUMN icon VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '菜单图标名（@element-plus/icons-vue 组件名），空串=默认图标' AFTER path;
  ```
  增量脚本 `cloud-base/scripts/sql/2026-10-07-menu-nav.sql`；同时同步基线 DDL `cloud_system.sql`（CREATE TABLE 加两列 + 种子 INSERT 带 path/icon 初值，新环境一次成型）。种子只 UPDATE 补新列值（10→icon Setting；11→/system/user+User；12→/system/role+UserFilled；13→/system/menu+Menu；20→icon Lock；**21 不给 path**——无前端页面，保持不进导航），不动 name/perms/type/sort/status。
- **component 落库 vs 前端映射——选前端**：前端 `src/router/viewRegistry.ts` 显式注册表 `path → component`（静态 import，与现有路由写法一致）。理由：
  1. **新增页面无需后端感知**是主控明示的评估点——页面文件本身就是前端交付物，注册表与页面同 commit，编译期可查（拼错路径 TS 构建即红）；component 字符串落库则 DB 值必须与前端模块路径永远同步，菜单管理页一个手滑即白屏，且该错误只在运行时暴露
  2. 路径与组件非 1:1（/dashboard 与 views/dashboard；将来详情页 /system/user/:id 与列表页同目录），DB 存 component 字符串反而引入第二套路径体系
  3. sys_menu 的语义边界干净：**"有什么、叫什么、在哪、什么顺序、什么图标"是业务配置；"怎么渲染"是前端实现**
- 备选 A（component 落库 + `import.meta.glob` 约定映射，RuoYi 模式）——弃：glob 映射要求 component 串=文件相对路径，等于把前端目录结构写进数据库；收益仅"老页面配新菜单免前端发版"，但注册表方式下这也只需前端一个 release，且换来编译期检查
- 备选 B（不扩列，前端按 perms 反推路由）——弃：perms 与路由无稳定映射（目录 M 无 perms），且把 RBAC 数据复制一份到前端，违背"角色定菜单"的需求本源

### D2 新端点：`GET /system/menu/user-nav`，仅认证、无 @PreAuthorize、实时查库

- **定案**：
  - 路径挂 `SysMenuController`（`/menu` 域）：`GET /system/menu/user-nav`——资源语义是"菜单按当前用户投影的导航树"，归菜单域；复用既有 controller 不新增类
  - 权限：**无 @PreAuthorize**——网关已验 token（无/坏 token 在网关 401），任何已登录用户可访问；返回的是**该用户自己的**可见菜单，无越权面（与 `/menu/tree` 的 `system:menu:list` 形成对照：树=管理视角全量，nav=用户视角投影）
  - 时效：**实时查库**（每次调用聚合当前 role_menu 绑定），非快照（D4 详述）
- 备选 A（`GET /system/user/nav` 用户域）——弃：SysUserController 是用户 CRUD 域，导航数据源是 sys_menu，跨域引用 menu mapper 破坏 Controller→Service→Mapper 单向依赖的整洁性
- 备选 B（登录响应捎带导航树）——弃：改 sso 登录契约（非 additive，违反"不改既有端点语义"红线）；且权限变更后导航无法刷新（token 有效期内树被冻死）
- 备选 C（新增 perms 如 `system:menu:nav` + 入种子）——弃：普通用户必须可访问，任何 perms 门槛都会让"零管理权限用户"拿不到导航；权限标识应表达"管理动作"，浏览自己的导航不是管理动作

### D3 导航树构建规则（SQL 过滤 + Java 剪枝）与 admin 全量语义

- **定案**（SQL 一次 JOIN 成型，范式沿用 `selectPermsByAccount`/`listPermsByAccount`）：
  ```sql
  SELECT DISTINCT m.id, m.parent_id, m.name, m.type, m.path, m.icon, m.sort
    FROM sys_user u
    JOIN sys_user_role ur ON ur.user_id = u.id
    JOIN sys_role r  ON r.id = ur.role_id AND r.status = 0 AND r.deleted = 0
    JOIN sys_role_menu rm ON rm.role_id = r.id
    JOIN sys_menu m  ON m.id = rm.menu_id AND m.status = 0 AND m.deleted = 0
                     AND (m.type = 'M' OR m.path != '')
   WHERE u.account = #{account} AND u.deleted = 0
   ORDER BY m.sort, m.id
  ```
  服务层 `listUserNav()`：account 取 `SecurityUtils.currentAccount()`（网关 X-User-* 注入；空则返回空列表防御）→ `NavTreeBuilder.build(menus)`（util 包，镜像 MenuTreeBuilder 风格）：
  1. **F 型 SQL 层排除**（按钮不进导航）
  2. **C 型 path 空串 SQL 层排除**（如种子 21 在线用户——配了菜单但页面未开发，属于"绑而不可导航"的合法态）
  3. 按 parentId 组树，同级 sort 升序（null 靠后；DDL NOT NULL 下恒有值）
  4. **M 目录自底向上剪枝**：所有可见子级被 2/5 滤空的目录不渲染（空目录=噪声；种子 20 认证管理因 21 无 path 被剪，admin 导航只含 系统管理）
  5. **孤儿提升**：C 的父 M 不在可见集（直连 API 只绑 C 不绑 M 的脏数据）→ 挂根级，与 MenuTreeBuilder 孤儿语义一致（数据问题不致菜单消失）
  6. 返回 `List<UserNavVo>`（vo 包；字段见契约 §2——**不含 perms/status/审计**，最小暴露面）
- **admin 全量**：无特判。种子 role 1 已绑全部菜单 → admin 聚合查询天然全量。新建菜单须到角色页给 admin 角色补绑才可见——诚实 RBAC 语义，写入契约已知语义（不是缺陷）
- 备选（admin 走 `/menu/tree` + 前端本地滤 F/停用）——弃：前端复现可见性规则=双实现漂移；且 tree 出参含审计字段，暴露面大

### D4 时效语义：导航可见性实时 vs 操作权限快照——两个维度，显式分离

- **定案**（写入契约 §3，前端/e2e 以此为断言依据）：
  - **导航可见性（本端点）= 实时**：角色补绑/解绑菜单、菜单停用/删除、path 变更——刷新页面（守卫重拉 user-nav）即生效，无需重登
  - **操作权限（perms）= 登录快照**（既有语义，契约 v2 §1 不变）：X-User-Perms 来自登录时 OnlineSession 快照，变更需重登/refresh
  - 两维度组合出的合法中间态：**"菜单可见但按钮越权被 403"**（绑了菜单但 perms 快照未含，或反之"解绑后菜单消失但快照 perms 仍在、直链 API 仍 200"）——均如实呈现，不做前端发明的一致性补偿
- 为什么导航不沿用快照（备选：登录时把导航树快照进 OnlineSession/Redis）——弃：a) 快照需改 sso 登录链路与 Redis 契约（跨服务改动，红线内但代价大）；b) 导航树无安全语义（看见≠能操作，操作由 perms+服务层 403 守住），实时化收益（管理端改完刷新即见）远大于一致性收益；c) 每次 F5 一次轻量 JOIN 查询（分表索引可支撑，当前量级无压力）
- **不做导航缓存/持久化**：menu store 纯内存，F5 重拉——实时性即由此获得；localStorage 缓存会把"实时"退化为"登录时"，还引入跨账号残留问题（镜像 tags D4 结论）

### D5 前端两层路由结构与兜底

- **静态核心层**（`router/index.ts` 写死，未登录也存在）：
  - `/login`（public，既有）
  - `/`→Layout，**redirect 由 `/system/user` 改为 `/dashboard`**（理由：dashboard 恒存在恒可见，零菜单用户也有落点；原 redirect 目标是动态路由，零菜单用户会陷入 `'/'→'/system/user'→catchAll→'/'` 重定向环）
  - Layout children：`/dashboard`（工作台）、`/redirect/:path(.*)`（刷新中转，既有）、`/:pathMatch(.*)*`→**NotFound 页**（`views/error/NotFound.vue`，文案"页面不存在或无访问权限"+ 返回工作台按钮；挂 Layout 内渲染侧边栏）、`/menu-error`（导航加载失败页，D6）
  - `/system/user|role|menu` **从静态表移除**，改由动态层注册（这是 RBAC 闭环的必要条件——静态保留则未授权用户可路由到页面壳）
- **动态层**（menu store `buildRoutes()`）：对 nav 树中每个 C 节点 `router.addRoute('Layout'子路由, {...})`——path=节点 path、name=path 派生（D9）、component=VIEW_REGISTRY 查表（**未注册的 path → NotFound 组件复用**，菜单仍显示、点击落"未开发"文案——"绑定即可见"语义诚实，不为注册表滞后藏菜单）、meta={title: name, icon}
- 404 catchAll 与 addRoute 顺序无竞争：vue-router 4 按匹配特异性评分，catchAll 恒最低，静态注册在前不影响动态路由命中
- 备选（catchAll redirect '/' 保留现状）——弃：无权限直链会经 `'/'→dashboard` 静默吞掉，用户无感知落点且丢失"你无权访问"的反馈；NotFound 页可同时承接"未开发 path"与"无权限直链"两类兜底

### D6 守卫：等菜单再放行、竞态、失败路径（防重定向环）

- **定案**守卫流程：
  ```
  beforeEach(to):
    if (to.name === 'MenuError') return true          // 错误页恒放行（防回弹环，见下）
    if (to.meta.public)  return logged ? {path:'/'} : true
    if (!logged)         return { path:'/login', query:{redirect: to.fullPath} }
    if (!menuStore.loaded) {
      ok = await menuStore.ensureLoaded()             // in-flight promise 缓存：并发首跳/重定向链共用一次请求
      if (!ok) return { name:'MenuError', query:{ redirect: to.fullPath } }
      return to.fullPath                              // 动态路由已注册，重新匹配（刷新/直链深路径的关键）
    }
    return true
  ```
- **竞态**：`ensureLoaded()` 把进行中的 Promise 存 state，重复调用 await 同一个——守卫重定向链（如 `/`→`/dashboard` 首跳、多标签页首跳）只发一次 user-nav 请求
- **失败路径必须专用页而非跳 /login**：若失败跳 `/login`，登录页守卫的 `public && logged → {path:'/'}` 会把已登录用户弹回 `'/'`，加载再失败再跳——**无限重定向环**（vue-router 检测到环会抛错白屏）。`/menu-error` 静态注册 + 守卫最优先放行（不回弹），页内"重试"按钮 = `menuStore.reset()` + `router.push(redirect 目标或 '/')`；"重新登录"链接 = 清 token 跳 /login。e2e 不覆盖此路径（无法确定性触发），手工验收（计划 F 任务验收标准含此项）
- **加载时机**：守卫内惰性加载（首次认证导航触发），不在 login action 内——login action 保持纯 token 职责（auth store 不依赖 menu store，无循环依赖）

### D7 菜单 store 与消费方切源；constants/menus.ts 删除

- **定案**：`stores/menu.ts`（新增）成为菜单单一来源：
  - state：`navTree: UserNavNode[]`、`loaded: boolean`、`_loading: Promise<boolean> | null`（in-flight 缓存）、`_addedNames: string[]`（动态路由登记，reset 用）
  - getters：`menuItems`（扁平可导航项 = 树内全部 C 节点 + 静态 `DASHBOARD_ITEM` 尾挂）——MenuSearch 数据源
  - actions：`ensureLoaded()`（调 `api/menu.userNav()` → 存树 → `buildRoutes()`；失败 reset loaded 让下次可重试）、`buildRoutes()`（C 节点逐个 addRoute；**去重**：同 path 或同派生 name 只注册首个（sort 序）；**保留字跳过**：/login、/dashboard、/redirect、/menu-error、/ 及静态已注册 name）、`reset()`（清 state + 逐个 `router.removeRoute(name)` + tagsStore 不动（登录页已清））
  - `MenuItem` 接口与 `DASHBOARD_ITEM` 常量迁入本文件；**`constants/menus.ts` 删除**（消费方仅 Sidebar/MenuSearch 两处，全量切换；build 即刻暴露漏改）
- **登出/重登清态**：复用脚手架 D4 收敛点——`views/login/index.vue` onMounted 追加 `menuStore.reset()`（手动退出/401 清态/直接访问三路径统一覆盖；401 → request.ts redirectToLogin → 登录页 onMounted → 动态路由被移除）
- 备选（reset 放 auth.logoutAction / 401 拦截器）——弃：auth store 依赖 menu store 引入 store 间耦合；401 拦截器职责是清 token 跳转，不该懂路由重建
- **Sidebar 嵌套渲染**（D10 详述 DOM 兼容）；`stores/menu.ts` 顶部 `import router from '../router'` 与 router 导入 store 构成模块环——双方均只在函数体内使用对方引用（ESM live binding），与 request.ts↔router 既有环同模式，安全

### D8 图标体系：curated ICON_MAP，禁 `import *`

- **定案**：`src/constants/icons.ts`——从 `@element-plus/icons-vue` **具名导入**约 16 个常用图标（Setting/User/UserFilled/Menu/Monitor/Lock/Bell/Document/Files/PieChart/DataAnalysis/OfficeBuilding/Cpu/Connection/Key/Link，存在性实现时验证）组 `ICON_MAP: Record<string, Component>`（markRaw），`resolveIcon(name)` 未知名→兜底 `Menu` 图标（不报错不 console——e2e 零 console error 纪律）
- 备选 A（`import * as Icons` 动态解析）——弃：全量 icon 包进 bundle（~290 组件），与 Element Plus 按需红线同源的体积纪律
- 备选 B（icon 落库存 SVG/URL）——弃：资产入库治理成本高；DB 存组件名+前端白名单是"配置归后端、渲染归前端"的边界（同 D1 component 论证）
- MenuFormDialog icon 字段为自由文本（el-input，maxlength 50，placeholder 提示可用图标名）——icon picker 组件超范围，移交备忘

### D9 动态路由名派生与 keep-alive/defineOptions 契约延续

- **定案**：route.name = path 派生——`'/system/user'` → 段首大写驼峰拼接 `'SystemUser'`；既有 4 视图 defineOptions name 与派生结果一致（SystemUser/SystemRole/SystemMenu/Dashboard），**约定延续**：新增页面的视图文件必须 `defineOptions({ name })` = path 派生名才能进 keep-alive 缓存（写入 /frontend-page 技能候选修订，随脚手架 F4 备忘已有此方向）
- **去重**（buildRoutes 内）：两菜单同 path（脏数据）→ 先到（sort 小）者注册，后者跳过；派生 name 撞静态 name（保留字）→ 跳过——均静默处理不报错（数据治理项，写入契约已知语义）
- 未注册 path 的 C 菜单 → NotFound 组件复用，其 route.name 派生照常（页签可建、缓存按名匹配，无特殊分支）

### D10 e2e 兼容：M1 精确串在嵌套菜单下继续成立（设计保真，零适配）

- **断言依赖**：`.el-menu .el-menu-item` innerText join === `'用户管理,角色管理,菜单管理,工作台'`（DOM 顺序）
- **成立条件（四条，全部为设计行为而非巧合）**：
  1. 系统管理(M) 渲染为 `el-sub-menu`——**其标题是 `.el-sub-menu__title` 不入断言域**，子 C 项仍是 `.el-menu-item` 且在 DOM 中先于后继根项
  2. 种子 21 无 path → SQL 层排除 → admin 导航无"在线用户"；种子 20 因无可见子级被剪枝 → 不产生空目录项
  3. 静态"工作台"作为**根级 el-menu-item 尾挂**（模板中动态树之后）→ 恰为第 4 项且居末
  4. `el-menu :default-openeds` = 根级 M 节点 id 列表（默认展开目录）——子项初始即在渲染树内（innerText 稳定，不赌 EP 折叠动画的实现细节）
- **选择：设计保真优于改断言**。备选（改 M1 断言适配嵌套结构）——作为兜底记录：若实现中发现 EP 菜单 DOM 与本推演不符（如 collapse 模式 teleport 差异），允许将 M1 断言改为 `.el-menu :is(.el-menu-item, .el-sub-menu__title)` 语义等价形式，**须与实现同 commit 且在计划适配清单登记**——但当前展开态推演（EP 2.x el-menu--inline 子菜单内联渲染）有实测把握，预期零改动
- S 系列：S6 labels.includes(用户管理/工作台) + 点击工作台跳 /dashboard（静态路由恒在）✓；S7 F5 高亮保持（守卫重拉后重匹配同路由）✓；S1/S4 redirect 回跳链路不变 ✓。R1 相邻断言：用户/角色同 sub-menu 内相邻 ✓。T8 全部关闭落 /dashboard ✓。**'/' redirect 改 /dashboard 的唯一行为差异**：无 redirect 参数登录后落 dashboard 而非用户管理——核查全部脚本无此断言（harness login 仅断言"离开 /login"），计划适配清单登记核查结论
- **必须适配项（唯一）**：run-menu-e2e M3/M4 新增/编辑 C 菜单——MenuFormDialog 新增 path 必填校验，脚本须补填路由路径（`/e2e/page${stamp}` 风格），详见计划 E1

### D11 菜单管理页与 AssignMenuDialog 影响面

- **MenuFormDialog**：新增 path/icon 字段——C 型显示 path（**必填**，格式 `/` 开头 + 字母数字/-/_，maxlength 100，与 perms 同款"前端约定格式、后端不校验"宽松语义）；M/F 型隐藏 path 提交 `''`；icon 对 M/C 显示（选填 maxlength 50），F 隐藏提交 `''`；编辑回显两字段、全量提交八写字段 + id（v2 部分更新语义规避口径延续）
- **表头不变**（10 列，M1 断言）——path/icon 经 MenuTreeNode 行数据进编辑弹窗回显，不新增表格列
- **AssignMenuDialog 零改动**：树数据源 `/menu/tree` 出参 additive 加 path/icon（JSON 多字段运行时忽略），勾选/半选/提交逻辑不涉新列——**红线确认：既有消费不破坏**
- MenuTreeNode（dto，树端点出参）additive +path +icon，见契约 §5

### D12 后端分层与守护测试合规（天然合规设计）

- Controller：`userNav()` 两行式返回 `R<List<UserNavVo>>`、javadoc、无 @PreAuthorize（Guard 不强制）、无 @PathVariable
- Service：`SysMenuManageService.listUserNav()`（list 前缀合规）；只读不加事务；无 catch 转BizException 场景（无业务异常路径——空数据是合法态）
- Mapper：`SysMenuMapper.listNavByAccount(account)`（list 前缀合规）；XML 手写 JOIN、`#{}`、`<if>` 不涉（无动态列）、sys_menu/sys_user/sys_role 语句均含 deleted（守护规则）
- VO 隔离：UserNavVo 在 vo 包原生 setter 转换（NavTreeBuilder 直构 VO，无三方拷贝）
- 实体：SysMenu +path +icon（String）；StatusEnum 不动
- DDL：两列均带 COMMENT（守护扫描 cloud_system.sql）；增量脚本不进守护域但同标准书写

## 5. 错误处理与边界

| 场景 | 处理 |
|---|---|
| 零菜单用户（无角色/角色无菜单绑定） | user-nav 返回 `[]`；侧边仅"工作台"；'/'→/dashboard 有落点，不出现空菜单死局 |
| C 菜单 path 为空串 | SQL 层排除（绑而不可导航）；契约已知语义 |
| C 菜单 path 无注册组件 | 菜单显示、路由注册、组件落 NotFound（"未开发/无权限"文案）——不藏菜单 |
| 两菜单同 path / 派生 name 撞保留字 | buildRoutes 去重静默跳过（先到优先），数据治理项 |
| 只绑 C 未绑父 M（直连 API 脏数据） | 孤儿 C 提升根级（与 MenuTreeBuilder 同语义） |
| M 目录全部子级被滤空 | 自底向上剪枝不渲染 |
| 导航接口失败（网络/5xx/旧后端 404） | 守卫落 /menu-error 专用页（**不可跳 /login——已登录会被弹回成环**），页内重试/重新登录 |
| user-nav 401（token 过期） | 走 request.ts 既有清态跳登录（与所有 API 一致） |
| 刷新/直链深路径 | 守卫 ensureLoaded → addRoute → return to.fullPath 重匹配；catchAll 特异性恒低无竞争 |
| 并发首跳（重定向链/多标签页） | in-flight promise 共享，单次请求 |
| 登出/401/重登 | 登录页 onMounted → menuStore.reset()（removeRoute 全部动态路由）——重登用户菜单集即刻反映新角色绑定 |
| account header 缺失（理论不达） | service 防御返回空列表 |
| 未知 icon 名 | resolveIcon 兜底 Menu 图标，零 console |

## 6. 测试策略

- **后端**：NavTreeBuilder 单测（剪枝/孤儿/排序/DISTINCT 语义）；MapperXmlBindingTest 自动覆盖新语句绑定；`MVN clean install` 全绿（守护测试零豁免）；curl 冒烟（ASCII，经网关 18080：admin 全量树形状、普通用户子集、无 token 401）
- **前端**：每任务 `npm run build`（vue-tsc strict）绿；收尾连续两次 build（components.d.ts 陷阱）；手工冒烟覆盖 D5/D6 各边界（直链无权限/刷新/登出重登/menu-error 重试）
- **e2e**：新增 `run-nav-e2e.mjs` N 系列（N0-N6 + N-VERIFY：admin 全量可见且形状正确、测试角色只绑部分菜单→测试用户只见对应路由、直链无权限兜底、F5 保持、越权按钮 403 两维语义、登出重登清态、删净清理）；既有四脚本全量回归（M1/S/R/T 兼容论证 D10 + M3/M4 唯一适配点）；测试数据纪律：e2e 前缀+时间戳、绝不改 admin 与种子菜单、种子只 UPDATE 新列值、结束删净
- **9202 联调卡点**：后端任务完成 → 请用户重启 9202（计划显式步骤）→ e2e 才可跑 N 系列

## 7. 已知取舍与移交备忘（下一步规划前必读）

1. **按钮级权限指令未做**：前端按钮仍全显，越权由服务层 403 toast 兜底（pilot §7.1 既有取舍延续）；后续 /me 或登录响应扩展下发 perms 清单 + v-perms 指令另案
2. **导航实时 vs 快照的中间态**：见 D4——"可见不可操作/不可见仍可调 API"两态合法存在，产品化时如需一致性可考虑绑定变更时踢会话（另案）
3. **未注册 path 的菜单显示但落 NotFound**：注册表滞后是前端发版节奏问题；如需"配置即页面"须回到 component 落库方案（D1 备选 A，已论证弃选）
4. **icon picker 未做**：icon 是自由文本，配错名得兜底图标；后续可做白名单下拉（ICON_MAP 即数据源）
5. **菜单层级仅支持 M>C 两级导航渲染**（F 不进导航）；若将来允许 M 嵌套 M，Sidebar 递归渲染与 AssignMenuDialog 三层布局需同步改造（menu 域 D3"目录不嵌套"前端约定仍是前提）
6. **导航接口无缓存**：每次 F5 一查；量级上来后可加短 TTL（Redis/内存）或 ETag——当前过度设计
7. **e2e 未覆盖 menu-error 失败路径**：无法确定性触发（须停 9202），手工验收兜底；若后续做故障注入再补
8. **新建菜单不自动绑给 admin 角色**：admin 须在角色页补绑（D3 已知语义）——若产品要求"超管即时全见"，需引入角色标记位（另案，与内置保护 3008+ 同期评估）
