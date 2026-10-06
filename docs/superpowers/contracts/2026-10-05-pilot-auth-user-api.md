# cloud-web 试点：认证 + 用户/角色/菜单 API 契约

- 日期：2026-10-05（2026-10-06 补录：§4.2-4.7 角色端点 + §5.1 菜单树细化 + §6/§7 更新，为角色管理页对齐用；补录与代码逐字核对——`SysRoleController`、`SysRoleManageService`、`SysRoleMapper.xml`、`SysMenuController`、`MenuTreeBuilder`）
- 状态：**源自已验收后端**（cloud-base 阶段 1-3，2026-10-05 端到端验收通过），整理成契约为前端对齐用——本契约**忠实记录既有行为，不新增、不修改**后端接口；字段/错误码与代码核对过（`AuthController`、`LoginResult`、`OnlineSession`、全局 Jackson 配置）
- 消费方：cloud-web 前端（设计文档 `docs/superpowers/specs/2026-10-05-cloud-web-frontend-design.md`，实施计划 `docs/superpowers/plans/2026-10-05-cloud-web-pilot.md`）
- 约定：前端实现与本文档冲突时，**以本文档（即后端实测行为）为准**；发现文档与实测不符，回报主控修订契约，不自行猜测

## 1. 通用约定

| 项 | 约定 |
|---|---|
| Base URL | 经网关 `http://localhost:18080`；前端 dev 经 Vite 代理 `/api`（`/api/sso/x` → 网关 `/sso/x`） |
| 统一返回 | 所有接口 HTTP 恒 **200**，业务状态看 `body.code`（200 成功）；结构 `R<T> = { code: number, msg: string, data: T }` |
| 例外 | **网关层鉴权失败为真实 HTTP 401** + `R` JSON body（code=401）；网关屏蔽 `/inner/**` 返回 403 |
| 服务层权限拒绝 | HTTP 200 + `body.code = 403`（`@PreAuthorize`） |
| 认证方式 | 需登录的接口带 header `Authorization: Bearer <accessToken>` |
| 时间格式 | `yyyy-MM-dd HH:mm:ss` 字符串（如 `2026-10-05 21:30:00`）；例外：在线列表 `loginTime` 为 epoch 毫秒（见 §2.4） |
| **Long→String** | 后端 Jackson 全局将 Long 序列化为字符串（防 JS 精度丢失）：`id`、`total`、`expiresIn`、`userId` 等一律按 string 处理；分页 total 需 `Number()` 转换后再给组件 |
| 权限标识 | `<svc>:<entity>:<action>`（如 `system:user:add`）；权限在 JWT 内由网关解析，**登录响应不含权限清单** |
| 种子账号 | `admin / admin123`（超管，全部权限） |

**错误码分段**（前端处理策略：仅分流 `code===200` 与 401，其余统一 toast `msg`，不逐码分支）：

| code | 含义 | 出现场景 |
|---|---|---|
| 200 | 成功 | — |
| 401 | 未认证 | 无/坏/过期 token、已注销、已被强退（HTTP 真实 401） |
| 403 | 无操作权限 | 服务层 `@PreAuthorize` 拒绝（HTTP 200）；网关屏蔽 `/inner` |
| 1002 | 通用业务失败 | `R.fail(String)` 默认码 |
| 2001 | 账号或密码错误 | 登录 |
| 2002 | 认证服务不可用 | sso Feign 调 system 兜底 |
| 2003 | 账号已停用 | 登录（status=1） |
| 2005 | refreshToken 无效 | 刷新（不存在/已被覆盖/过期） |
| 3xxx | system 业务错误段 | 2026-10-06 与代码核对落定：3001 用户不存在 / 3002 账号已存在 / **3003 角色标识已存在** / **3004 角色不存在** / 3005 存在子菜单 / 3006 菜单不存在 / 3007 父菜单非法；前端按 msg 提示 |

## 2. 认证接口（cloud-sso，网关前缀 /sso）

### 2.1 登录 `POST /sso/auth/login`

- 权限：匿名（网关白名单）
- 入参（JSON body）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| account | string | 是 | 登录账号 | `"admin"` |
| password | string | 是 | 明文密码（后端 BCrypt 比对） | `"admin123"` |

- 返回 `R<LoginResult>`：

| 字段 | 类型 | 说明 | 示例 |
|---|---|---|---|
| accessToken | string | JWT 访问令牌（有效期 2h，声明 userId/account/tokenId） | `"eyJhbGciOiJIUzUxMiJ9..."` |
| refreshToken | string | 刷新令牌（UUID，7 天；每用户单活跃，后登录覆盖前者） | `"a3f8...-uuid"` |
| expiresIn | string | accessToken 有效期（**秒**；Long→String） | `"7200"` |

```json
{ "code": 200, "msg": "操作成功", "data": { "accessToken": "eyJ...", "refreshToken": "c9b0...", "expiresIn": "7200" } }
```

- 错误码：2001 账号或密码错误 / 2003 账号已停用 / 2002 服务不可用
- 前端消费：**是**（登录页）

### 2.2 刷新 `POST /sso/auth/refresh`

- 权限：匿名
- 入参：`{ "refreshToken": string }`
- 返回：`R<LoginResult>`（同 2.1；新旧 token 均轮换，旧 accessToken 立即失效）
- 错误码：2005 refreshToken 无效（含被后登录覆盖）
- 前端消费：**MVP 否**（设计文档 §6 推荐方案 B：401 跳登录；单活跃 refreshToken 模型下静默刷新与多标签页冲突）

### 2.3 注销 `POST /sso/auth/logout`

- 权限：需登录（header `Authorization: Bearer <accessToken>`）
- 入参：无 body
- 返回：`R<Void>`（data 为 null）
- 行为：注销后该 token **立即失效**（Redis 在线会话删除），后续使用得 401
- 前端消费：**是**（顶栏退出；调用失败也照常清本地态跳登录）

### 2.4 在线会话列表 `GET /sso/auth/online`

- 权限：`sso:online:list`
- 入参：无
- 返回 `R<List<OnlineSessionVo>>`：

| 字段 | 类型 | 说明 | 示例 |
|---|---|---|---|
| tokenId | string | 会话标识（强退用） | `"5f2a...-uuid"` |
| userId | string | 用户 ID（Long→String） | `"1"` |
| account | string | 登录账号 | `"admin"` |
| permissions | string[] | 该会话权限快照 | `["system:user:list", ...]` |
| loginTime | string | 登录时间（**epoch 毫秒，数字内容**；展示需自行格式化，非通用 yyyy-MM-dd 格式） | `"1759692600000"` |
| ip | string | 登录 IP | `"192.168.1.10"` |

- 前端消费：**MVP 否**（契约先行，随后续在线管理页）

### 2.5 强退 `DELETE /sso/auth/online/{tokenId}`

- 权限：`sso:online:kick`
- 入参：路径参数 `tokenId`（string）
- 返回：`R<Void>`；行为：被强退用户的 token 立即失效（401）
- 前端消费：**MVP 否**

## 3. 用户接口（cloud-system，网关前缀 /system）

字段类型说明：`SysUserVo` 见 §6；status 语义 `0=正常 1=停用`。

### 3.1 用户分页 `GET /system/user/page`

- 权限：`system:user:list`
- 入参（query）：

| 参数 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| pageNum | number | 是 | 页码（1 起） | `1` |
| pageSize | number | 是 | 每页条数（后端分页插件 maxLimit 200） | `10` |

- **无搜索/过滤参数**（契约现状；前端 MVP 不做搜索框）
- 返回 `R<PageResult<SysUserVo>>`：

```json
{ "code": 200, "msg": "操作成功", "data": {
  "total": "2",
  "rows": [
    { "id": "1", "account": "admin", "nickname": "管理员", "status": 0,
      "createBy": "system", "createTime": "2026-10-05 20:00:00",
      "updateBy": null, "updateTime": null }
  ] } }
```

- 前端消费：**是**

### 3.2 用户详情 `GET /system/user/{id}`

- 权限：`system:user:*`（具体标识**实现时验证**，预计 system:user:list）
- 入参：路径参数 `id`（string 数字）
- 返回：`R<SysUserVo>`
- 前端消费：**MVP 否**（编辑弹窗以表格行数据回显，字段已够用）；契约先行

### 3.3 新增用户 `POST /system/user`

- 权限：`system:user:add`
- 入参（JSON body）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| account | string | 是 | 账号（唯一） |
| nickname | string | 是 | 昵称 |
| password | string | 是 | 初始密码（后端 BCrypt 加密存储） |
| status | number | 是 | 0 正常 / 1 停用 |

- 返回：`R<Long>`——data 为**新用户 id 字符串**（如 `"3"`）
- 错误码：账号已存在走业务码 + msg（3xxx 段/1002，前端按 msg 提示）
- 前端消费：**是**

### 3.4 修改用户 `PUT /system/user`

- 权限：`system:user:edit`
- 入参（JSON body）：`{ id: string, nickname: string, status: number }`——**account 与 password 不可改**
- 返回：`R<Void>`
- 前端消费：**是**

### 3.5 删除用户 `DELETE /system/user/{id}`

- 权限：`system:user:remove`
- 入参：路径参数 `id`
- 返回：`R<Void>`；行为：逻辑删除（前端表现为列表不再出现）
- 前端消费：**是**

### 3.6 重置密码 `PUT /system/user/password/{id}`

- 权限：`system:user:resetPwd`
- 入参：路径参数 `id` + JSON body `{ password: string }`（新密码明文，后端加密）
- 返回：`R<Void>`
- 前端消费：**是**

### 3.7 分配角色 `PUT /system/user/role`

- 权限：`system:user:assignRole`
- 入参（JSON body）：

| 字段 | 类型 | 说明 |
|---|---|---|
| userId | string | 用户 id（Long→String） |
| roleIds | string[] | 角色 id 数组（全量覆盖语义；空数组即清空角色） |

- 返回：`R<Void>`
- 前端消费：**是**

### 3.8 查用户已有角色 `GET /system/user/{id}/roles`

- 权限：随用户查询权限（**实现时验证**，预计 system:user:list 或 assignRole）
- 入参：路径参数 `id`
- 返回：`R<List<Long>>`——data 为角色 id 字符串数组（如 `["1","2"]`），用于分配弹窗回显
- 前端消费：**是**

## 4. 角色接口（cloud-system，网关前缀 /system）

`SysRoleVo` 全字段见 §6；status 语义 `0=正常 1=停用`。停用角色（status=1）的两层语义：不出现在 §4.1 下拉候选；登录权限聚合 SQL 排除停用角色（`JOIN sys_role ... AND r.status = 0`）——即停用后该角色不再贡献权限，但**已发会话的权限快照不变**（重新登录/refresh 才生效，见 §7.5）。

### 4.1 角色列表（仅启用） `GET /system/role/list`

- 权限：`system:role:list`
- 入参：无（不分页）
- 行为：**仅返回启用角色**（`status=0 AND deleted=0`，按 id 升序）——2026-10-06 修正原文"全量列表"表述，以代码 `listEnabled` 为准
- 返回 `R<List<SysRoleVo>>`，前端消费字段 id / name / roleKey / status
- 前端消费：**是**（用户分配角色弹窗的候选列表）

### 4.2 角色分页 `GET /system/role/page`

- 权限：`system:role:list`
- 入参（query）：

| 参数 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| pageNum | number | 前端始终传（后端默认 1） | 页码（1 起） | `1` |
| pageSize | number | 前端始终传（后端默认 10，maxLimit 200） | 每页条数 | `10` |

- 行为：**含停用角色**（`deleted=0` 全量，与 §4.1 差异），按 **id 倒序**（新角色在前）；无搜索/过滤参数（契约现状）
- 返回 `R<PageResult<SysRoleVo>>`：

```json
{ "code": 200, "msg": "操作成功", "data": {
  "total": "2",
  "rows": [
    { "id": "2", "name": "运营", "roleKey": "ops", "status": 0,
      "createBy": "admin", "createTime": "2026-10-06 10:00:00",
      "updateBy": "admin", "updateTime": "2026-10-06 10:30:00" }
  ] } }
```

- 前端消费：**是**（角色管理页列表）

### 4.3 新增角色 `POST /system/role`

- 权限：`system:role:add`
- 入参（JSON body；后端以实体接收写字段，其余字段传入也不落库）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| name | string | 前端必填（后端不校验，缺省会 SQL 500，见 §7.6） | 角色名称（DDL VARCHAR(30)） |
| roleKey | string | 是（后端校验非空白） | 权限标识，库级唯一（uk_role_key，DDL VARCHAR(30)；逻辑删除墓碑仍占键） |
| status | number | 否（缺省落库默认 0） | 0 正常 / 1 停用 |

- 返回：`R<Long>`——data 为**新角色 id 字符串**（如 `"3"`）
- 错误码：1002 角色标识不能为空（null/空白）/ 3003 角色标识已存在: {roleKey}（Service 预检 + 唯一键冲突兜底）
- 前端消费：**是**

### 4.4 修改角色 `PUT /system/role`

- 权限：`system:role:edit`
- 入参（JSON body）：`{ id: string, name: string, roleKey: string, status: number }`
- 行为：**部分更新语义**——null 字段不更新（动态 set）；**roleKey 可修改**（与用户 account 不可改不同），修改时触发唯一性校验（排除自身 id）
- 返回：`R<Void>`
- 错误码：3004 角色不存在（含已删）/ 3003 角色标识已存在 / 1002 roleKey 空白（空串非 null 也拦）
- 前端消费：**是**（编辑弹窗始终全量提交三写字段）

### 4.5 删除角色 `DELETE /system/role/{id}`

- 权限：`system:role:remove`
- 入参：路径参数 id（string 数字）
- 返回：`R<Void>`
- 行为：单事务内——逻辑删除角色（deleted=1）+ **物理删除**该角色的 sys_role_menu 与 sys_user_role 绑定（该角色与用户、菜单的关联即时解除）；**无内置角色保护**（admin 亦可删，见 §7.7）
- 错误码：3004 角色不存在
- 前端消费：**是**（确认框文案需含解绑提示）

### 4.6 分配角色菜单 `PUT /system/role/menu`

- 权限：`system:role:assignMenu`
- 入参（JSON body）：

| 字段 | 类型 | 说明 |
|---|---|---|
| roleId | string | 角色 id（Long→String） |
| menuIds | string[] | 菜单 id 字符串数组；**全量覆盖语义**——先清后插，null 或空数组即清空；后端 distinct 去重，不校验 menuId 存在性（前端只从菜单树取 id，不触达脏数据路径） |

- 返回：`R<Void>`
- 错误码：3004 角色不存在（停用角色可正常分配——只校验存在性）
- 前端消费：**是**（分配菜单弹窗：勾选与半选父节点合并提交）

### 4.7 查角色已绑菜单 `GET /system/role/{id}/menus`

- 权限：`system:role:list`
- 入参：路径参数 id
- 返回：`R<List<Long>>`——data 为菜单 id 字符串数组（如 `["10","11","111"]`），分配弹窗回显用
- 行为：**无角色存在性校验**——角色不存在/已删除返回空数组
- 前端消费：**是**（分配弹窗回显；回显须过滤为叶子节点 id，见设计文档）

## 5. 菜单接口（cloud-system，网关前缀 /system）

### 5.1 菜单树 `GET /system/menu/tree`

- 权限：`system:menu:list`
- 入参：无
- 行为：全量未删除菜单（`deleted=0`，**不过滤 status**——停用菜单也在树中，且出参无 status 字段前端无法区分）；同级按 sort 升序（null 靠后）；父节点缺失的孤儿挂根级
- 返回 `R<List<MenuTreeNode>>`（字段语义 2026-10-06 与代码核对落定）：

| 字段 | 类型 | 说明 | 示例 |
|---|---|---|---|
| id | string | 节点 id | `"12"` |
| parentId | string | 父节点 id（根为 `"0"`） | `"10"` |
| name | string | 菜单/按钮名 | `"角色管理"` |
| perms | string | 权限标识（目录节点为空串 `""`） | `"system:role:list"` |
| type | string | 节点类型枚举：`"M"` 目录 / `"C"` 菜单 / `"F"` 按钮 | `"C"` |
| sort | number | 排序号（同级升序） | `2` |
| children | MenuTreeNode[] | 子节点；**叶子为空数组 `[]`，非 null** | `[]` |

```json
{ "code": 200, "msg": "操作成功", "data": [
  { "id": "10", "parentId": "0", "name": "系统管理", "perms": "", "type": "M", "sort": 1,
    "children": [
      { "id": "11", "parentId": "10", "name": "用户管理", "perms": "system:user:list", "type": "C", "sort": 1,
        "children": [
          { "id": "111", "parentId": "11", "name": "用户新增", "perms": "system:user:add", "type": "F", "sort": 1, "children": [] }
        ] }
    ] }
] }
```

- 前端消费：**是**（角色管理页"分配权限"弹窗的树数据源；侧边菜单仍为静态配置，动态路由随后续阶段）

## 6. 数据类型字典（前端 types/api.ts 对齐基线）

| 类型 | 定义（TypeScript 视角，注意全 string 化的 Long） |
|---|---|
| `R<T>` | `{ code: number; msg: string; data: T }` |
| `PageResult<T>` | `{ total: string; rows: T[] }` |
| `LoginResult` | `{ accessToken: string; refreshToken: string; expiresIn: string }`（expiresIn 单位秒） |
| `SysUserVo` | `{ id: string; account: string; nickname: string; status: number; createBy: string \| null; createTime: string \| null; updateBy: string \| null; updateTime: string \| null }` |
| `SysRoleVo` | `{ id: string; name: string; roleKey: string; status: number; createBy: string \| null; createTime: string \| null; updateBy: string \| null; updateTime: string \| null }`（2026-10-06 扩为全字段，与 SysUserVo 同构） |
| `OnlineSessionVo` | `{ tokenId: string; userId: string; account: string; permissions: string[]; loginTime: string; ip: string }` |
| `MenuTreeNode` | `{ id: string; parentId: string; name: string; perms: string; type: 'M' \| 'C' \| 'F'; sort: number; children: MenuTreeNode[] }`（叶子 children 为 `[]`） |

## 7. 已知缺口与前端约束（如实标注，不因契约掩盖）

1. **按钮级权限缺口**：后端无接口向前端下发当前用户权限清单（登录响应只含 token；permissions 仅存在于 JWT 内部由网关消费，`GET /sso/auth/online` 虽含 permissions 但需管理员权限且非按需语义）。**前端 MVP 按钮全显**；越权操作由服务层 403（HTTP 200 + code 403）toast 兜底。后续由后端补 `/me` 类接口或登录响应扩展解决（见设计文档 §10）。
2. **refresh 单活跃模型**：每用户仅一个活跃 refreshToken，后登录覆盖前者（旧端刷新得 2005）；且刷新轮换令牌后旧 accessToken 立即失效——多标签页场景静默刷新会互相踢，故 MVP 不做静默刷新。
3. **无搜索参数**：用户分页仅 pageNum/pageSize；若后续后端补过滤参数，契约先行更新再上前端搜索框。
4. **入参校验缺失**（阶段 2+3 已知取舍，Bean Validation 未上）：password 传 null 等脏数据可能得 500——前端表单做必填/长度基础校验兜底（密码 6-32 位为前端约定，后端无强约束，**实现时验证**）。
5. **权限变更不踢会话**：改角色/停用/删除用户不联动失效其在线会话（快照权限最长存活至 token 过期）——属后端已知取舍，前端无感。停用/删除角色同理：绑定即时解除但在线会话快照不回收（重新登录后生效）。
6. **角色 name/status 无后端校验**（2026-10-06 补录时发现）：新增/修改仅校验 roleKey 非空白与唯一；name 缺省提交会因 DDL `NOT NULL` 无默认值产生 SQL 500——前端表单必填 + 长度（1-30，DDL VARCHAR(30)）兜底（同 §7.4 模式）。
7. **删除角色无内置保护**：admin 种子超管角色可被删除，且事务内即时解绑其用户关联与全部菜单授权（在线会话快照存活至 token 过期，期间 admin 仍持有旧权限）——前端不做特殊拦截（不做超出契约的发明），是否补后端保护待主控裁定。
8. **宽松语义如实记录**：`PUT /role` 的 null 字段不更新（§4.4，前端全量提交规避）；`PUT /role/menu` 不校验 menuId 存在性（§4.6，前端只从树取 id）；`GET /role/{id}/menus` 无角色存在性校验（§4.7）。
