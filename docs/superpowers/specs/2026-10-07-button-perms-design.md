# 按钮级权限指令（v-perms）技术方案（perms 下发端点 + 前端指令挂载三管理页 + e2e）

- 日期：2026-10-07
- 状态：已确认（轨道决议：**全栈方案 A**，主控与用户定案）
- 轨道：全栈（后端 cloud-sso + 前端 cloud-web + e2e cloud-e2e）；**网关 / cloud-system / common 模块零改动**
- 关联：契约 `docs/superpowers/contracts/2026-10-07-perms-api.md`（perms 下发新端点 + menu-nav §3 按钮层增补）；实施计划 `docs/superpowers/plans/2026-10-07-button-perms.md`
- 基线：main@e103086（业务动态路由已合并）；e2e 五脚本全 PASS（含 nav N 系列）
- 语义基线：`2026-10-07-menu-nav-api.md` §3——**导航可见性实时 vs 操作权限登录快照两维分离**；本轮把"操作权限快照"从后端执法延伸到前端按钮显隐，可见性与执法**同源**（同一份 OnlineSession 快照）

## 1. 需求与范围

**做**：

| # | 功能 | 内容 |
|---|---|---|
| 1 | perms 下发端点 | `GET /sso/auth/me`：按 accessToken 的 jti 读 Redis `sso:online:{jti}` 的 OnlineSession，返回该会话权限快照（D1） |
| 2 | 前端 perm store | `stores/perm.ts`（镜像 menu store 范式），守卫内与 user-nav **并行原子**加载（D3） |
| 3 | v-perms 指令 | 手写自定义指令（零新依赖），不命中即从 DOM 移除（v-if 语义，D2/D4） |
| 4 | 三管理页挂载 | user/role/menu 三页共 **12 个挂载点**逐一挂指令（D5，perms 值对照种子 111-133 核实） |
| 5 | e2e | N5 重设计（按钮隐藏 + page.request 直连 API 断 403 兜底）、N2 粒度断言增补、N4/N6/N1 增补、既有场景兼容论证（D7） |

**明确不做**（防蔓延）：登录/refresh 响应加 permissions 字段（D1 弃选 c）、操作列整列隐藏（已知外观缺口，移交备忘）、通配权限（`system:user:*`，后端 @PreAuthorize 本就精确匹配）、指令函数式简写/修饰符、菜单管理页 perms 配置 UI 变化、`SecurityUtils.currentAuthorities()`（D1 弃选 b 的伴随物）、i18n。

## 2. 现状事实（2026-10-07 侦察核实，设计前提）

1. **前端拿不到 perms**：登录响应仅 accessToken/refreshToken/expiresIn（pilot §2.1）；X-User-Perms 只存在于网关→服务 header 链路；`GET /sso/auth/online` 虽含 permissions 但需 `sso:online:list` 管理权限且非"我自己"语义（pilot §7.1 已记缺口）
2. **OnlineSession.permissions 已存在**（`cloud-sso/domain/OnlineSession.java`）：登录时快照写 Redis `sso:online:{tokenId}`（tokenId=JWT jti，TTL=accessTokenTtl），网关每次请求读同一键注入 X-User-Perms——**这就是执法数据源**
3. **sso 已有全部现成范式**：`TokenService.logout()` 解析 Authorization header→JwtUtil.parseToken→claims.getId()（jti）；`refresh()` 按 tokenId 读 `sso:online:{jti}` 键；`SecurityConstants.ONLINE_KEY_PREFIX` 共享常量——/me 是两个既有范式的拼接，无新依赖
4. **system 侧执法链就绪**：HeaderAuthFilter 把 X-User-Perms 转 authorities 进 SecurityContext（@PreAuthorize 用）；`SecurityUtils` 现仅 `currentAccount()`
5. **守卫惰性加载范式已定型**（`router/index.ts` + `stores/menu.ts`）：`ensureLoaded()` in-flight promise 共享 → 失败按矩阵分流（401→login / 其余→MenuError）→ `return to.fullPath` 重匹配；登录页 onMounted 是会话清理收敛点（tags/menu 两 reset）
6. **e2e N5 现状**断言"按钮可见+可点+提交 403"（run-nav-e2e.mjs:368-392）——本轮按钮转隐藏，场景必须重设计；**N2 测试用户**（绑 F 122 角色修改 + 半选父 10/12）登录快照 = `["system:role:list", "system:role:edit"]`（无 add/remove/assignMenu）——恰是按钮粒度天然测试素材
7. **种子 perms 值核实**（`scripts/sql/cloud_system.sql`）：user 页 111-115（add/edit/remove/resetPwd/assignRole）、role 页 121-124（add/edit/remove/assignMenu）、menu 页 131-133（add/edit/remove）；C 节点 perms 为 list（11/12/13）
8. **既有按钮交互全部由 admin 发起**（run-user/role/menu + nav 的建数据步骤）——admin 全量 perms 全显，兼容零影响；唯一受限用户按钮交互是 N5
9. **前端零指令基础设施**：无 `src/directives/` 目录、无 hasPerm/v-perms 占用；localStorage 登录态单键 `cloud-web:auth`（JSON { accessToken, refreshToken, account }）——e2e 直连 API 取 token 的来源

## 3. 总体架构（文字版 + 数据流）

```
登录（既有，零改动）                     perms 数据流（本方案新增）
┌──────────┐ token   ┌───────────┐      ┌────────────────────────────────────────────┐
│ cloud-web │ ─────▶ │ 网关 18080 │ ───▶ │ GET /sso/auth/me（cloud-sso）              │
│ (Vue3)    │        │ 验签+在线态 │      │ Authorization → jti → Redis sso:online:{jti}│
└──────────┘        └───────────┘      │ → OnlineSession.permissions → CurrentUserVo │
                                        └────────────────────────────────────────────┘
  同源性证明：网关每次请求读的也是 sso:online:{jti} → X-User-Perms → @PreAuthorize；
  /me 读同一个键 → 按钮显隐数据 == 后端执法数据（零第二真相源）

router.beforeEach（守卫，在既有 menu 门上扩为并行原子门）
  ├─ MenuError 恒放行 / public / 未登录（既有，零改动）
  └─ 已登录 && 菜单未加载：
       const [menuOk, permOk] = await Promise.all([
         menuStore.ensureLoaded(),   // GET /system/menu/user-nav（实时，导航）
         permStore.ensureLoaded(),   // GET /sso/auth/me（快照，按钮显隐）—— 两请求同批并行
       ])
       失败（任一）→ 沿既有矩阵分流（401 已清态 → /login；否则 → /menu-error）
       成功 → return to.fullPath 重新匹配（组件挂载前 perms 恒已就位——指令读取时序的保证）

组件渲染层
  <el-button v-perms="'system:role:add'">新增角色</el-button>
     └─ directives/perms.ts：mounted+updated 双钩子 → permStore.hasPerm(value)
        不命中 → el.parentNode.removeChild(el)（v-if 语义）
  快照内 → 显示；快照外 → 移除；越权残留防线 → 服务层 @PreAuthorize 403（不变，最终防线）
```

**RBAC 闭环延伸**：角色绑定哪些 F 菜单 → 登录快照含哪些 perms → 网关执法（既有）+ 按钮显隐（本轮）同源生效；权限变更需重登/refresh 的既有语义不变（menu-nav 契约 §3）。

## 4. 设计决策（D 系列，每项含备选与弃选理由）

### D1 perms 下发通道（核心决策）：**a) sso 新端点 `GET /sso/auth/me`，读 Redis 快照**

- **定案**：AuthController 新增 `GET /auth/me`（外部 `/sso/auth/me`）——`@RequestHeader("Authorization")` 取 accessToken（logout 同款）→ TokenService.`findCurrentUser(accessToken)`：JwtUtil.parseToken 取 jti → 读 `sso:online:{jti}` → OnlineSession → 转换 `CurrentUserVo { account, permissions }`（dto 包，原生 setter）。仅认证（无 @PreAuthorize，任何已登录用户读自己的会话投影，无越权面——与 user-nav 的"仅认证"论证同构）。字段与错误路径见契约 §2。
- **评估维度对照**：

| 维度 | a) sso /me（定案） | b) system 端点读 SecurityContext | c) 登录/refresh 响应加字段 |
|---|---|---|---|
| 语义同源性 | **读执法同一 Redis 键**，零间接层 | 网关已把快照转 header，再由服务转回——同值但隔一层传输变换（逗号串拆分） | 登录时快照的**副本**，与 Redis 快照分叉（refresh 轮换后旧副本滞留） |
| 最小改动面 | sso 三文件（VO/Service 方法/Controller 方法），零 common/零 system | 需 controller 直取 SecurityContextHolder（违背现有 SecurityUtils 风格）**或**改 common-starter 加 `currentAuthorities()`（共享库，sso/system 都要连带重建验证） | LoginResult 改形 + login/refresh 两链路 + 登录页/auth store/e2e harness 全消费方过一遍 |
| F5/多标签 | 守卫重拉（与 user-nav 同批），内存态无残留 | 同 a | **F5 丢失 → 必须 localStorage 持久化**：新增第二 storage 键或塞 `cloud-web:auth`（破坏单键契约）；跨账号残留/401 清态遗漏风险 |
| 编排 | 守卫 Promise.all 并行（本轮 D3） | 同 a（但失败域多一个服务） | 无守卫编排，但重登/refresh 时序需各自覆盖 |
| 契约影响 | **新契约新端点**，pilot §2.1-2.5 零触碰 | 新契约新端点 | **改 pilot §2.1/§2.2 出参**（虽 additive，动登录契约本体） |
| 服务重启 | 9201（本轮唯一重启） | 9202（刚为 nav 重启过，再动一次） | 9201 |

- **弃选 b 详由**：除上表外——快照的 owner 是 sso 域（OnlineSession 是 sso 的 Redis 契约），system 侧出"我的权限"端点是跨域借道；且 header 是传输工件（逗号串），权限清单大时有 header 尺寸隐患（当前量级无碍，但架构上不该以 header 为数据源）。"who am I / 我的会话"天然归认证域，未来扩展（昵称等）也落 sso。
- **弃选 c 详由**：快照时效上 c 反而最弱——副本与 Redis 真相源分叉：refresh 会重查最新权限写新快照（TokenService.issueTokens），localStorage 副本若不同步覆盖则滞后；持久化引入的跨账号残留正是动态路由 D4"不做导航缓存"同款否决理由；且改 LoginResult 形状对既有消费方的审阅成本高于一个全新按需端点。
- **时效语义澄清（重要）**：/me 是请求时读 Redis——**运行中会话的快照不可变**（登录快照写入后仅 refresh/重登重写；MVP 无静默刷新，401 即重登），故"读 Redis 实时值"与"登录快照"在前端会话生命周期内**恒等**，不违反"禁止实时查库"红线（Redis 会话键 ≠ 业务库；与 user-nav 的实时 JOIN 查库是两码事）。若未来上静默刷新：token 轮换后下次 F5 守卫重拉 /me 即得新快照，指令语义自洽。

### D2 指令语义：`v-perms`，值为单值或数组（数组=任一命中），隐藏=移除 DOM

- **定案**：
  - 指令名 **`v-perms`**（需求原文指定；与 sys_menu.perms 字段名对齐，团队直觉零成本）
  - 值类型 `string | string[]`：单值 = 快照含该 perms 即显示；**数组 = 任一命中（some）即显示**。理由：按钮多 perms 场景语义是"或"（如一个入口按钮有 edit 或 add 之一即可进操作流），与 @PreAuthorize 单按钮单 hasAuthority 的现状对应；"全命中（every）"无现实按钮需求，确需时模板层用两个 v-perms 叠加或 hasPerm 组合，不为假想需求引入模式双轨
  - **隐藏 = 从 DOM 移除**（`el.parentNode?.removeChild(el)`，v-if 语义），非 disabled。理由：① 快照已保证"可见即可操作"，disabled 会暴露功能存在性并诱导点击（点击→403 toast 的旧噪声回归）；② Element Plus 相邻按钮间距用 `.el-button + .el-button` 兄弟选择器——`display:none` 的元素仍占据选择器位，隐藏中间按钮会留下多余间距，移除则布局自然收拢；③ 与路由层"未绑菜单不注册路由"（藏而非灰）语义一致
  - 注册：`main.ts` `app.directive('perms', vPerms)` 全局注册（三页 + 未来页面通用；局部 `directives` 选项注册会造成每页样板，无收益）
  - TS：指令实现类型 `Directive<HTMLElement, PermValue>`（`PermValue = string | string[]`）；vue-tsc 对模板指令表达式值**默认不做类型校验**（Vue 3 现状，如实声明），强类型需求由导出的 `hasPerm()` 便利函数兜底（可在 script/模板 v-if 中使用，返回 boolean 有完整类型）
- **备选**：值支持 `{ perms: string[], mode: 'any' | 'all' }` 对象——弃：本轮零 all-match 用例，YAGNI；`display:none` 切换——弃（上表②间距论证）；保留字 `'*'` 超管通配——弃：后端 @PreAuthorize 无通配语义，前端单独发明会造成显隐与执法分叉。

### D3 perms 前端态：独立 `stores/perm.ts`，守卫内与 user-nav **并行 + 原子门**

- **定案**：`stores/perm.ts` **独立 store**（不并入 menu/auth store）：
  - state：`perms: string[]`、`loaded: boolean`、`_loading: Promise<boolean> | null`（in-flight 缓存，镜像 menu store D7 范式）
  - getter `hasPerm(value?: PermValue): boolean`（未加载恒 false → 见 D4；单值 includes；数组 some；undefined/空数组/空串 false）
  - 便利函数 `hasPerm()`（store 文件导出，内部 `usePermStore().hasPerm(...)`，供指令与 v-if 场景）
  - actions：`ensureLoaded()`（调 `api/auth.getMe()` → 存 perms → loaded=true；失败返 false **不 toast**，与 menu 同款——反馈归 MenuError 页/401 拦截器）；`reset()`（清三态）
  - **不并入 menu store**：menu store 自述"菜单导航单一来源"（导航域，实时语义）；perms 是操作权限域（快照语义）——两域时效不同（menu-nav §3 的两维正是分界），混入会让 reset/重试/未来演进互相牵扯。**不进 auth store**：动态路由设计 D6 已明示 auth store 纯 token 职责
- **加载时机：守卫内并行 + 原子门**（既有 `!menuStore.loaded` 块改造）：
  ```
  const [menuOk, permOk] = await Promise.all([
    menuStore.ensureLoaded(),
    permStore.ensureLoaded(),
  ])
  const ok = menuOk && permOk        // 原子：任一失败即按既有矩阵分流
  ```
  - **并行**：两请求同批发出，首屏时延不叠加（登录后首跳 / F5 各一次）
  - **原子**（任一失败 → 既有失败矩阵：401 已清态→/login；否则→/menu-error）：不留"导航成功但按钮全隐/全显"的半态——半态要么让用户面对无按钮页面（隐）要么越权暴露（若放行），两坏取其一不如显式失败页给重试入口。备选"perm 失败降级为按钮全隐 + 放行导航"——弃：静默降级掩盖故障且无重试触点（用户只看到按钮消失，不知道为什么）
  - **指令时序保证**：守卫成功后 `return to.fullPath` 重匹配，组件挂载发生在两个 store loaded 之后——`mounted` 钩子读 store 恒得已加载态（这是"守卫必须保证 perms 先于路由组件就位"的机制实现）
  - 失败域说明：a 方案下 sso 故障会连带头 /me 失败落 MenuError（既有行为中 sso 挂了登录/登出也全挂，会话本就已残）；MenuError 页文案微调为"菜单或权限加载失败"（路由名/路径 `/menu-error` 不动），重试按钮 `menuStore.reset()` + `permStore.reset()` 双清
- **F5**：两 store 纯内存，守卫各重拉一次（快照重取无语义变化）；**登出/401/重登**：登录页 onMounted 收敛点追加 `permStore.reset()`（与 tags/menu 并列第三件套），覆盖手动退出/401 清态/直接访问三路径——重登另一账号按钮集即刻反映新快照，无残留

### D4 指令实现细节：mounted+updated 双钩子，未加载 fail-closed

- **定案**（`src/directives/perms.ts`）：
  - `mounted` 与 `updated` 双钩子都执行 `check(el, binding)`：不命中 → 移除。理由：updated 覆盖同元素因响应式更新重渲染（el-table 行数据刷新、keep-alive 复活后的重渲染路径）——**perms 会话内不变**（D1 澄清），故 updated 实际是防御性冗余，成本一段函数复用，收益是时序边界零假设
  - **未加载防御 = fail-closed（保守隐藏）**：`hasPerm` 在 `!loaded` 时恒 false。理由：① 与后端执法同向——宁可少显（用户少个按钮，可反馈）不可多显（引导点击后吃 403，回到本轮要消除的噪声）；② 守卫原子门已保证当前 UI 下该分支**理论不可达**（组件挂载前 loaded 恒 true），本条是纯防御，覆盖未来 public 页误用指令、指令在守卫未覆盖的渲染时机（如动态组件）触发等极端时序。**对 MenuError 的影响**：MenuError 页自身按钮（重试/重新登录）不挂 v-perms，不受 fail-closed 影响——页面恒可用
  - el-table 操作列写法：指令直接挂在列模板的 `el-button` 上（`<el-button v-perms="'system:user:edit'" link ...>`），行级天然生效——每行按钮是独立 vnode，mounted 逐行触发；**操作列本身不隐藏**（用户仅 list 权限时操作列呈空单元格——已知外观缺口，见移交备忘，勿在本轮擅自扩为列级 v-if）
  - **EP 组件根元素约定**：指令挂在 el-button（组件）上时作用于其根元素（el-button 单根，安全）；多根组件上自定义指令会被 Vue 忽略并告警——约定 v-perms 只用于原生元素/单根组件（写入指令文件头注释）
  - DOM 移除的 patch 旁路说明：removeChild 绕过 Vue patch，同 vnode 后续 patch 理论上有边界——业界标准实现（vue-element-admin v-perms 同款）多年验证 + updated 钩子复检兜底；Vue 对已 detach 元素的 patch 操作无副作用路径（不插入 DOM），风险可忽略。备选 `display:none`（无 patch 旁路）——因 D2 间距论证弃选
- **备选**：`beforeMount` 提前移除（避免闪烁）——弃：mounted 在首帧渲染前同步执行（同一 tick，无闪烁窗口），beforeMount 无收益反增心智。

### D5 挂载面清单（12 挂载点，perms 值对照种子 SQL 逐一核实）

| 页面 | 位置 | 按钮文案 | v-perms 值 | 种子 F 节点 |
|---|---|---|---|---|
| views/system/user/index.vue | 表头 | 新增用户 | `system:user:add` | 111 |
| 同上 | 行内 | 编辑 | `system:user:edit` | 112 |
| 同上 | 行内 | 重置密码 | `system:user:resetPwd` | 114 |
| 同上 | 行内 | 分配角色 | `system:user:assignRole` | 115 |
| 同上 | 行内 | 删除 | `system:user:remove` | 113 |
| views/system/role/index.vue | 表头 | 新增角色 | `system:role:add` | 121 |
| 同上 | 行内 | 编辑 | `system:role:edit` | 122 |
| 同上 | 行内 | 分配权限 | `system:role:assignMenu` | 124 |
| 同上 | 行内 | 删除 | `system:role:remove` | 123 |
| views/system/menu/index.vue | 表头 | 新增菜单 | `system:menu:add` | 131 |
| 同上 | 行内 | 编辑 | `system:menu:edit` | 132 |
| 同上 | 行内 | 删除 | `system:menu:remove` | 133 |

- 页面级访问（C 节点 list perms，如 `system:user:list`）**不挂指令**：页面可达性由动态路由/导航管（绑了 C 菜单才注册路由），list 权限随绑定进入快照——不重复设卡
- 弹窗内部（UserFormDialog 等）不挂：弹窗只能经入口按钮打开，入口已收口
- **在线用户页（种子 21）无前端页面**，不涉挂载；未来新增页面按本表模式接续（约定入 /frontend-page 技能候选修订，随移交备忘）

### D6 契约：新契约 `2026-10-07-perms-api.md` + 两处既有契约指针

- **定案**：
  - 新契约 `contracts/2026-10-07-perms-api.md`：§2 `GET /sso/auth/me` 逐字段表（account/permissions）+ 防御路径错误语义 + 错误码零新增声明；§3 与 pilot §2 的关系（**additive 新端点**，§2.1-2.5 与 LoginResult 三字段零变化——D1 弃选 c 的落款）；§4 前端消费映射与 TS 字典；§5 **menu-nav §3 按钮层收窄增补**（本文档为该语义的唯一修订入口）
  - menu-nav 契约 §3 追加一行指针（架构角色是契约变更唯一入口，最小编辑）：原第 1 条中间态"菜单可见但操作被拒——按钮点击得 403 toast"**收窄**为"按钮已随快照隐藏（v-perms）；快照滞后（权限变更未重登）或直连 API 越权仍由服务层 403 最终防线兜底"；e2e 纪律补充"可断言按钮随登录快照显隐；不可断言改绑定刷新即改显隐（快照须重登）"
  - pilot §7.1（按钮级权限缺口）追加一行指针：已由本轮 /me + v-perms 解决，详见新契约
  - **错误码：零新增**（如实声明）：本域成功 200；401 网关既有（无/坏/过期 token，HTTP 真实 401）；防御路径 body 401（Redis 会话竞态缺失——理论不达，复用既有 401 语义触发前端清态跳登录，不占新码）；3xxx 段 3008+ 仍归菜单/角色保护另案（沿用 menu-nav §4 占位声明）
- **弃选**：不改 pilot §2.1 登录响应（c 方案配套，已弃）；不修订 menu-nav §3 全文重写（增量指针优于重写，两文冲突以新契约为准的范围仅限按钮层语义）。

### D7 e2e：并入 run-nav-e2e.mjs（fixture 复用），N5 重设计 + 三处增补

- **定案：并入 nav 脚本，不新建 P 系列脚本**。理由：① N2 已产出本轮全部所需账号态（受限用户快照恰为 list+edit）——新建脚本要重建角色/绑权限/建用户/登入登出全套 fixture，纯重复；② 本轮语义是 menu-nav §3 两维时效在按钮层的延伸，同源场景同脚本内聚；③ 脚本数与 CI 串行时长不增
- **N5 重设计**（行为翻转点，唯一必改场景）：
  1. UI 断言翻转：受限用户角色页——表头"新增角色"按钮 `count() === 0`（隐藏证据，替代原"点击开弹窗"）
  2. **后端 403 兜底改由 page.request 直连 API 断言**（黑盒纪律允许：page.request 不 import 前端内部代码；token 取自 `localStorage['cloud-web:auth']` 的 accessToken——浏览器态读取）：`page.request.post(BASE + '/api/system/role', { headers: { Authorization: Bearer ... }, data: { name, roleKey } })` → 断言 HTTP 200 + `body.code === 403` + msg 非空——**"前端隐藏"与"后端仍执法"双证据并存，缺一不可**（防止指令成为唯一防线后被绕过的回归）
  3. 原 UI 提交路径（弹窗填写→保存→403 toast）整体删除；TEST_403 探针角色常量保留用于直连 payload，CLEANUP 兜底删除保留（零成本防意外落库）
  4. page.request 的响应不经 page 网络事件（Playwright APIRequestContext 与 page 流量隔离），不污染 N-VERIFY 的 badResponses 统计
- **N2 增补 N2f（粒度证明）**：受限用户角色页——行内"编辑"可见（122 在快照）+"分配权限"/"删除"隐藏（124/123 不在）——同页不同按钮不同显隐，证明粒度到按钮而非页面
- **N4 增补**：F5 后按钮隐藏保持（表头新增角色仍 count 0）+ `/api/sso/auth/me` 恰 +1（与 user-nav 同批重拉证据）
- **N6 增补**：admin 重登后角色页全部按钮可见（表头+行内全显）——admin 全量快照回归零影响
- **N1 增补**：admin reload 后 me 调用恰 1 次 + 响应形状（code 200、account=admin、permissions 含 `system:role:add`/`sso:online:list` 等）
- **N3 适配（微）**：MenuError 文案改"菜单或权限加载失败"后，N3 原断言 `getByText('菜单加载失败')` 变空转——改为 URL 不含 `menu-error`（语义更直接）
- **既有场景兼容论证**：run-user（15 项）/run-role/run-menu/run-scaffold 全部按钮交互由 admin 发起（全量 perms → 全显）零影响；nav 脚本内建数据步骤（N2 2a-2c、CLEANUP）均为 admin 操作零影响；唯一受限用户按钮交互即 N5（已重设计）。e2e 断言库里无其他对三页按钮的受限用户断言（grep 核实）
- **测试数据纪律延续**：e2e 前缀+时间戳、种子零触碰、结束删净

### D8 后端分层与合规（天然合规设计）+ 重启卡点

- **Controller（AuthController.me）**：javadoc；`@RequestHeader("Authorization") String authorization`（logout 同款，网关保证非白名单路由必带）；两行式返回 `R<CurrentUserVo>`；无 @PreAuthorize（Guard 不强制，语义=仅认证）；无 @PathVariable
- **Service（TokenService.findCurrentUser）**：`find` 前缀合规（单查）；只读不加事务；JwtException catch → `log.error` 记根因后转 `BusinessException(401, "会话已失效，请重新登录")`（规范第 4 条）；Redis 值 null / parseSession null → 同码同文案（防御路径，契约 §2 错误语义）；无唯一性查重/多表写场景
- **VO**：`dto/CurrentUserVo`（sso 无 vo 包，dto 包与 LoginResult 同居）——**最小暴露面**：仅 account + permissions，不含 userId/ip/loginTime/tokenId（对齐 user-nav 出参不含审计的取舍）；Service 层原生 setter 转换，禁三方拷贝（规范第 9 条）
- **无 mapper/XML/DDL/种子变更**：零 SQL 改动（对照 /backend-crud 技能——本端点非 CRUD，无表操作）；ArchitectureGuardTest 零豁免
- **单测**：TokenServiceTest 增 findCurrentUser 用例（既有 Mockito 范式：合法 token+Redis JSON → 字段透传；Redis null → 401；坏 token → 401）
- **重启卡点（仅 9201）**：后端任务完成 → `MVN clean install` 全绿 → **请用户重启 9201**（java -jar cloud-sso）→ curl 冒烟（无 token 401 / admin token 全量 perms）→ 前端联调与 e2e。**网关 18080 / system 9202 零改动零重启**（D1a 恰好避开刚重启过的 9202）
- **前端合规**：Element Plus 按需红线不涉（无新 EP 组件）；**零新增 npm 依赖**（指令手写）；vue-tsc strict build 绿

## 5. 错误处理与边界

| 场景 | 处理 |
|---|---|
| /me 无 token / 坏 token / 过期 token | 网关真实 HTTP 401 + R body（通用例外，不达 sso） |
| 会话被强退/注销后调 /me | 网关查在线态失败 → HTTP 401（同上） |
| Redis 值竞态缺失（网关刚过、sso 读时被删） | 防御路径：BusinessException(401) → HTTP 200 + body 401 → 前端 request.ts 既有清态跳登录（理论不达） |
| token 合法但 JWT 解析异常（防御） | log.error + BusinessException(401)（同上语义） |
| perms 空数组（零角色/零绑定用户） | `permissions: []`（HTTP 200 合法态）——按钮全隐、页面可达性由导航管 |
| perms 响应慢/失败（守卫首跳） | 原子门失败 → MenuError（401 区分路径沿既有矩阵）；重试双 reset |
| keep-alive 复活页面的按钮 | perms 会话内不变 + 移除是持久 DOM 操作 → 复活即保持；updated 钩子防御重渲染 |
| 登出 → 重登另一账号 | 登录页 onMounted 三件套 reset（tags/menu/perm）→ 守卫按新账号重拉 → 按钮集无残留 |
| 多标签页 | 各标签独立内存态，各自 F5/首跳拉取；快照同源无分叉 |
| 指令值传空/undefined | hasPerm false → 隐藏（fail-closed，不抛错不 console——e2e 零 console error 纪律） |
| v-perms 误挂多根组件 | Vue 忽略并告警（开发期可见），约定+注释双防 |
| 直连 API 越权（绕过前端） | 服务层 @PreAuthorize 403（**不变，最终防线**）——N5 直连断言固化此防线 |

## 6. 测试策略

- **后端**：TokenServiceTest 增 3 用例（透传/Redis null/坏 token）；`MVN -f cloud-base/pom.xml clean install` 全绿（守护测试零豁免）；curl 冒烟（ASCII，经网关：无 token 401、admin 全量 perms 形状、受限用户子集可选）
- **前端**：每任务 `npm run build`（vue-tsc strict）绿；收尾连续两次 build（components.d.ts 陷阱）；手工冒烟覆盖：admin 全显 / 受限用户粒度显隐 / F5 保持 / 登出重登切换 / MenuError 重试（e2e 不覆盖失败路径的惯例延续）
- **e2e**：run-nav-e2e.mjs 改造（D7：N5 重设计 + N2f/N4/N6/N1 增补 + N3 微适配）；全量 `npm run e2e` 五脚本回归（兼容论证 D7）；删净纪律延续
- **卡点**：后端章完成 → 请用户重启 9201 → 前端联调（F4 起依赖 /me 存在，详见计划依赖标注）→ e2e

## 7. 已知取舍与移交备忘（下一步规划前必读）

1. **操作列整列不隐藏**：仅 list 权限的用户看到空"操作"列——外观缺口；如需收口可对 el-table-column 加 `v-if="hasPerm([...该列全部 perms])"`（helper 已备），随本轮 helper 一并可用，未纳入挂载面（YAGNI，避免列/按钮双卡口径分裂）
2. **perms 无通配/层级语义**：精确匹配（对齐 @PreAuthorize hasAuthority）；若未来后端引入通配，前端 hasPerm 须同步——显隐与执法不可分叉（本轮同源性是硬约束）
3. **登录响应仍不含 perms**（pilot §2.1 不变）：首屏依赖守卫并行拉取，登录页本身无按钮权限需求；若未来要登录即渲染带权限的首页骨架，可评估 c 方案复活（评估表在 D1）
4. **me 端点最小暴露**：仅 account/permissions；昵称/头像等"用户资料"扩展留给真正的用户中心需求，勿往 /me 堆字段（保持会话语义纯净）
5. **静默刷新未做**（pilot §7.2 取舍延续）：若上线静默刷新，token 轮换后守卫 F5 重拉 /me 自然取新快照，指令无需改——但 refresh 单活跃模型的多标签冲突先解决
6. **menu-error 失败路径仍无 e2e**（动态路由 D6/E 惯例延续）：本轮 /me 失败路径并入同一页，手工验收兜底
7. **3008+ 错误码段仍归内置角色/菜单保护另案**（menu-nav §4 占位声明延续，本轮零占用）
8. **技能修订候选**：/frontend-page 技能可补"操作按钮挂 v-perms + 新页面接续"约定（本轮 D5 表为范本）；/backend-crud 不涉（无 CRUD）
