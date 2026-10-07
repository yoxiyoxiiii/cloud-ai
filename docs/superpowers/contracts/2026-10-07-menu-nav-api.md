# 菜单导航 API 契约（cloud-system，网关前缀 /system）

- 日期：2026-10-07
- 状态：**导航域现行版**——由架构-agent 定稿，配合动态路由需求（设计 `docs/superpowers/specs/2026-10-07-dynamic-routing-design.md`，计划 `docs/superpowers/plans/2026-10-07-dynamic-routing.md`）
- **与菜单管理契约 v2 的关系**：本文档包含两部分——§2-§4 为**新增导航端点**（`2026-10-06-menu-management-api.md` 未覆盖的新域）；§5 为 **v2 的增补章节**（sys_menu 扩列对既有 4 个菜单端点的 additive 影响）。v2 端点语义/字段/错误码除 §5 列明处外**一概不变**；两文冲突以本文档为准（仅限 §5 范围）。通用约定（R 结构/HTTP 恒 200/Long→String/时间格式/错误码分段/前端处理策略）沿用 pilot §1
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 1. 域语义（导航域特有）

- **type 语义**：同 v2 §1（M 目录 / C 菜单 / F 按钮）；**导航树只含 M 与 C**（F 是按钮，SQL 层排除）
- **path 语义**：`path` 是 sys_menu 新列（§5），仅对 C 型有意义——以 `/` 开头的绝对路由路径（如 `/system/user`）；**空串 = 绑而不可导航**（菜单绑定关系存在但不出现在导航与路由中，典型如种子"在线用户"：页面未开发）
- **icon 语义**：sys_menu 新列，`@element-plus/icons-vue` 组件名字符串（如 `"User"`）；空串/未知名由前端兜底默认图标，后端不做存在性校验

## 2. 新端点：当前用户导航树 `GET /system/menu/user-nav`

- 权限：**仅认证（无 @PreAuthorize）**——网关验 token（无/坏/过期 token 为真实 HTTP 401 + R body，通用例外）；任何已登录用户可访问，返回**该用户自己的**可见菜单投影，无越权面。与 `/menu/tree` 对照：tree = 管理视角全量（`system:menu:list`），user-nav = 用户视角投影（仅登录）
- 入参：无（当前用户经网关注入的 X-User-Account 解析，不由前端传账号）
- 行为：
  1. account = SecurityUtils.currentAccount()（空则返回空列表，防御路径）
  2. 实时查库一次 JOIN 聚合（**非登录快照**，时效语义见 §3）：`sys_user → sys_user_role → sys_role(启用未删) → sys_role_menu → sys_menu(启用未删 AND (type='M' OR path != ''))`，DISTINCT（多角色共享菜单去重）
  3. Java 组树（NavTreeBuilder）：同级 sort 升序（null 靠后，DDL NOT NULL 下恒有值）→ **M 目录自底向上剪枝**（可见子级全被滤空的目录不返回）→ **孤儿 C 提升根级**（父 M 不在可见集时挂根级，parentId 保留原值，与 /menu/tree 孤儿语义一致）
- 返回 `R<List<UserNavVo>>`——节点字段：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 菜单 id（Long→String） | `"10"` |
| parentId | string | 是 | 父菜单 id，根为 `"0"`；孤儿提升后保留原 parentId | `"0"` |
| name | string | 是 | 显示名（侧边菜单/路由标题/页签标题） | `"系统管理"` |
| type | string | 是 | `"M"` 目录 / `"C"` 菜单——**无 `"F"`** | `"M"` |
| path | string | 是 | 路由路径；**M 恒空串 `""`**，C 以 `/` 开头（进入本树的 C 恒非空，§1） | `"/system/user"` |
| icon | string | 是 | 图标名；空串 = 前端默认图标 | `"User"` |
| sort | number | 是 | 排序号，同级升序（DDL NOT NULL DEFAULT 0，恒有值） | `1` |
| children | UserNavVo[] | 是 | 子节点数组；**叶子为空数组 `[]`，非 null**（与 MenuTreeNode 同口径）；M 的 children 只含 C，C 的 children 恒 `[]` | `[]` |

- **出参不含** perms / status / 审计四字段（最小暴露面：可见性已由过滤规则保证，导航渲染不需要它们）

种子数据下 admin 的返回示例（seed 后预期形状）：

```json
{ "code": 200, "msg": "操作成功", "data": [
  { "id": "10", "parentId": "0", "name": "系统管理", "type": "M", "path": "", "icon": "Setting", "sort": 1,
    "children": [
      { "id": "11", "parentId": "10", "name": "用户管理", "type": "C", "path": "/system/user", "icon": "User", "sort": 1, "children": [] },
      { "id": "12", "parentId": "10", "name": "角色管理", "type": "C", "path": "/system/role", "icon": "UserFilled", "sort": 2, "children": [] },
      { "id": "13", "parentId": "10", "name": "菜单管理", "type": "C", "path": "/system/menu", "icon": "Menu", "sort": 3, "children": [] }
    ] }
] }
```

（"认证管理"(20) 因其唯一 C 子级"在线用户"(21) 无 path 被剪枝，不出现；所有 F 节点不出现。）

- **空态**：用户无角色/角色无菜单绑定/全部角色停用 → `data: []`（HTTP 200，非错误）——前端侧边仅剩静态"工作台"，`'/'` 落 `/dashboard`
- **admin 全量语义（已知语义，非缺陷）**：种子角色 1（admin）绑定全部菜单（seed `INSERT ... SELECT 1, id FROM sys_menu`），聚合查询天然全量、**无 admin 特判**；菜单管理页**新建**的菜单不会自动绑给任何角色（含 admin）——admin 要看到新菜单须在角色页"分配权限"补绑，补绑后刷新页面即见（§3 实时语义）
- 错误码：401（通用，网关）；**无本域业务错误码**（空数据是合法态，无 3xxx 分配）

## 3. 时效语义（导航可见性 vs 操作权限——两个维度，与权限快照契约的关系）

- **导航可见性（本端点）= 实时查库**：角色补绑/解绑菜单、菜单停用/删除、path/icon/sort/name 变更——**刷新页面（前端守卫重拉 user-nav）即生效**，无需重新登录
- **操作权限（perms）= 登录快照（既有语义不变，v2 §1 / pilot §7.5）**：sys_menu 的 perms 变更、角色绑定变更不实时生效于在线会话（X-User-Perms 来自登录时 OnlineSession 快照），需重新登录或 refresh 生效
- **两维度组合的合法中间态**（前端/e2e 不做一致性断言，如实呈现）：
  1. "菜单可见但操作被拒"——绑定了菜单但 perms 快照未含对应操作权限 → 页面可进，按钮点击得 HTTP 200 + body 403 toast
  2. "菜单不可见但快照权限仍在"——解绑后刷新菜单消失，但已登录会话直连 API 仍可能 200 至 token 过期/重登
- e2e 纪律：**不断言"改完绑定立即可操作"**（操作权限是快照）；**可以断言"改完绑定刷新即可见"**（导航是实时）——N 系列按此设计（绑定发生在测试用户登录之前，两维度同时满足）

## 4. 错误码汇总（本域）

| code | 含义 | 出现端点 |
|---|---|---|
| 200 | 成功（含空树 `[]`） | GET /menu/user-nav |
| 401 | 未认证（HTTP 真实 401，网关） | 同上 |
| 403 | **不出现**（本端点无 @PreAuthorize） | — |

**错误码占位声明**：本轮**零新增**业务错误码（3xxx 段继续用至 3007）。**3008 起仍整体预留给 backlog「内置角色/菜单保护」另案**，本契约不占用——该另案与本轮如有并行，3008+ 归其所有。

## 5. 菜单管理契约 v2 增补（sys_menu 扩列的 additive 影响）

sys_menu 新增两列（DDL 与种子值见设计文档 D1）：`path VARCHAR(100) NOT NULL DEFAULT ''`、`icon VARCHAR(50) NOT NULL DEFAULT ''`。对 v2 四端点的影响全部为 additive：

### 5.1 `GET /system/menu/tree`（v2 §2.1）—— MenuTreeNode 出参新增 2 字段

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| **path** | string | 是（v2 增补） | 路由路径；M/F 通常空串（前端约定不采编），C 为 `/` 开头或空串 | `"/system/user"` |
| **icon** | string | 是（v2 增补） | 图标名，空串 = 默认图标 | `"User"` |

- 既有 7+5 字段不变；AssignMenuDialog 等既有消费 JSON 多字段运行时忽略，**不受影响**（与 v2 变更点 1 同模式）
- tree 端点**不过滤** path 空串与停用菜单（管理页须看到全量）——与 user-nav 的过滤是两个视角，勿混

### 5.2 `POST /system/menu`（v2 §2.2）与 `PUT /system/menu`（v2 §2.3）—— 入参新增 2 字段

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| path | string | 否（null 落默认 `''`/不更新；**后端不校验非空与格式**） | 路由路径；"C 型前端必填、以 / 开头 1-100 位"为**前端约定**（宽松语义，同 perms 口径） | `"/system/xxx"` |
| icon | string | 否（同上） | 图标名；存在性为**前端约定**（ICON_MAP 白名单兜底） | `"Document"` |

- 行为不变：POST 动态列插入（null 省略交列默认值）、PUT null 不更新（部分更新语义）；仍以 SysMenu 实体接收，超集字段不落库
- 错误码不变：1002 仅 name 空白；path/icon 无后端校验故无新错误码

### 5.3 `DELETE /system/menu/{id}`（v2 §2.4）—— 零影响（逻辑删除与解绑逻辑不涉新列）

### 5.4 宽松语义清单追加（接 v2 §4）

6. **path 无非空/格式/唯一性校验**（同 perms 模式）：空串合法（绑而不可导航）；两条 C 菜单可同 path——前端动态路由注册时去重（先到优先），属数据治理项
7. **icon 无存在性校验**：未知名入库合法，前端兜底默认图标

## 6. 前端消费映射

| 端点 | 消费方 | 备注 |
|---|---|---|
| GET /menu/user-nav | `api/menu.ts userNav()` → `stores/menu.ts`（导航单一来源）→ Sidebar（嵌套渲染）/ MenuSearch（扁平化）/ 动态路由注册 | 登录后守卫惰性加载，F5 重拉（实时语义 §3） |
| GET /menu/tree（增补） | 菜单管理页列表 + MenuFormDialog 编辑回显（path/icon 经行数据）；AssignMenuDialog（忽略新字段） | 表头列数不变（10 列） |
| POST/PUT /menu（增补） | MenuFormDialog 提交（八写字段 + id 全量） | C 型 path 前端必填校验 |

## 7. TypeScript 类型字典增补（types/api.ts）

| 类型 | 定义 |
|---|---|
| `UserNavNode` | `{ id: string; parentId: string; name: string; type: 'M' \| 'C'; path: string; icon: string; sort: number; children: UserNavNode[] }`（叶子 children 为 `[]`） |
| `MenuTreeNode`（扩展） | 追加 `path: string; icon: string`（additive，其余字段不变） |

## 给 backend-agent / frontend-agent 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-07-dynamic-routing.md`（后端章 B1-B6 / 前端章 F1-F7 / e2e 章 E1-E4）。要点：

- **backend**：B1 增量 DDL + 种子 UPDATE + 基线 DDL 同步（java 单文件源码执行，路径实现时验证）；B2 SysMenu 实体 + SysMenuMapper.xml 扩列；B3 listNavByAccount SQL + NavTreeBuilder + listUserNav + user-nav 端点（两行式/VO/无 @PreAuthorize）；B4 MenuTreeNode +path/icon；B5 单测 + 全量构建；B6【卡点】请用户重启 9202 + curl 冒烟
- **frontend**：F1 类型与 api；F2 viewRegistry + icons 常量；F3 stores/menu.ts；F4 路由重构（静态核心层/守卫/NotFound/menu-error/'/'→dashboard）；F5 Sidebar 嵌套 + MenuSearch 切源 + 删 constants/menus.ts；F6 MenuFormDialog path/icon；F7 登录页 reset；集成与 e2e 见 E 章
- 红线：契约定稿后两端不得单方改；v2 既有端点 additive only（AssignMenuDialog 消费不破坏）；Long→String、HTTP 恒 200 + body code；Element Plus 按需；e2e 黑盒纪律
