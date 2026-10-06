# 菜单管理 API 契约（cloud-system，网关前缀 /system）

- 日期：2026-10-06
- 状态：**菜单域现行版（v2）**——由架构-agent 定稿，配合菜单管理页需求（设计 `docs/superpowers/specs/2026-10-06-menu-page-design.md`，计划 `docs/superpowers/plans/2026-10-06-menu-management.md`）
- **与 pilot 契约的关系（取代声明）**：本契约覆盖 `2026-10-05-pilot-auth-user-api.md` §5（菜单接口）的全部端点，**§5.1 的 MenuTreeNode 字段表由本文档 §3 取代**；pilot §5 作为历史记录不回改，**两文冲突以本文档为准**。pilot 其余章节（通用约定/认证/用户/角色）不受影响，通用约定（R 结构/HTTP 恒 200/Long→String/时间格式/错误码分段/前端处理策略）继续沿用 pilot §1
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对 pilot §5，共 2 处）

| # | 端点 | 变更 | 性质 |
|---|---|---|---|
| 1 | GET /system/menu/tree | MenuTreeNode 出参 **additive 新增 5 字段**：status / createBy / createTime / updateBy / updateTime（§3 完整字段表） | **纯扩展**——既有 7 字段名/类型/语义不变；AssignMenuDialog 等既有消费不受影响（JSON 多字段运行时忽略，TS 接口扩展向后兼容） |
| 2 | PUT /system/menu | **新增校验**：name 传**空白串（非 null）**→ code 1002 "菜单名称不能为空"（原行为：空串会落库为空白名） | 收紧脏数据路径——name:null 仍为合法"不更新该列"（部分更新语义，与 role §4.4 同口径）；前端全量提交写字段，正常路径无感 |

**错误码无新增**：沿用 3005/3006/3007 与通用 1002/403/401（见 §5）；3008 起仍预留给后续实体。**权限标识无新增**：system:menu:list/add/edit/remove 已在种子（sql id 13/131/132/133）且 admin 已绑定。

## 1. 通用语义（菜单域特有，其余见 pilot §1）

- **权限快照时效**：sys_menu 的增/删/改（新增按钮 perms、停用/删除菜单等）**不实时生效于在线会话**——权限在登录时快照进 OnlineSession，变更需重新登录或 refresh 生效；停用/删除不踢会话。前端与 e2e 不做"改完立即可用"的断言或提示
- **status 语义**：`0=正常 1=停用`（SysMenu.StatusEnum）。/menu/tree **不过滤停用菜单**（管理页须看到全量，靠 status 区分）
- **type 语义**：`"M"` 目录 / `"C"` 菜单 / `"F"` 按钮（DDL CHAR(1)）

## 2. 端点

### 2.1 菜单树 `GET /system/menu/tree`

- 权限：`system:menu:list`
- 入参：无
- 行为：全量未删除菜单（`deleted=0`，含停用），同级按 sort 升序（null 靠后，DDL NOT NULL 下实际恒有值）；父节点缺失的孤儿挂根级（数据问题不致菜单消失）
- 返回 `R<List<MenuTreeNode>>`——节点字段见 §3；叶子 children 为空数组 `[]`
- 消费方：菜单管理页（列表数据源）、角色管理页 AssignMenuDialog（既有，不受 v2 扩展影响）
- 错误码：401/403（通用）

### 2.2 新增菜单 `POST /system/menu`

- 权限：`system:menu:add`
- 入参（JSON body；**后端以实体接收写字段，其余字段传入也不落库**——同 role §4.3 模式）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| parentId | string | 否（缺省/null 视为根） | 父节点 id（Long→String）；`"0"` 或 null = 根级；非 0 须为已存在菜单，否则 3007 | `"10"` |
| name | string | 是（非空白，否则 1002） | 名称（DDL VARCHAR(30)） | `"在线用户"` |
| perms | string | 否 | 权限标识（DDL VARCHAR(50)，无格式/唯一性校验）；目录传 `""` | `"sso:online:list"` |
| type | string | 否（null 落库默认 `"C"`） | `"M"`/`"C"`/`"F"`——**后端不校验枚举合法性**，脏值入库（前端约束枚举，§4 宽松语义） | `"C"` |
| sort | number | 否（null 落库默认 0） | 排序号（同级升序） | `1` |
| status | number | 否（null 落库默认 0） | 0 正常 / 1 停用 | `0` |

- 行为：name 非空白校验 → parentId 存在性校验（3007）→ 审计四值显式写入（createBy/createTime=当前账号/now，update 值 = create 值）
- 返回 `R<Long>`——data 为**新菜单 id 字符串**（如 `"134"`）
- 错误码：1002 菜单名称不能为空 / 3007 父菜单不存在: {id}
- **注意**：后端不校验父级类型（F 可挂 M、C 可挂根等均放行）——层级规则（M 固定根 / C 挂 M / F 挂 C）为**前端约定**（设计 D3），直连 API 产生的畸形层级由数据治理兜底

### 2.3 修改菜单 `PUT /system/menu`

- 权限：`system:menu:edit`
- 入参（JSON body，同 2.2 字段集 + id）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | string | 是 | 目标菜单 id（不存在 → 3006，含已删） |
| parentId | string | 否（null 不更新） | 变更时走环校验：自指 → 3007；新父为自身后代 → 3007；新父不存在 → 3007 |
| name | string | **空白串拦截（v2）** | `""`/纯空白 → 1002；**null 不更新该列**（部分更新语义）；正常值更新 |
| perms / type / sort / status | — | 否（null 不更新） | 同 2.2；**type 可改**（后端不拦——前端锁定，§4 宽松语义） |

- 行为：requireMenu(3006) → name 空白校验（1002）→ validateParent 环校验（3007）→ 动态更新非空列 + updateBy/updateTime=当前账号/now（createBy/createTime 不变）
- 返回 `R<Void>`（data 为 null）
- 错误码：3006 菜单不存在 / 3007 父菜单不能是自身·不能是自身的后代（会形成环）·父菜单不存在 / 1002 菜单名称不能为空
- 前端消费：**是**（编辑弹窗始终全量提交六写字段 + id，规避部分更新歧义）

### 2.4 删除菜单 `DELETE /system/menu/{id}`

- 权限：`system:menu:remove`
- 入参：路径参数 `id`（string 数字，显式命名）
- 行为：单事务内——存在未删除子级 → 3005；目标不存在（含已删）→ 3006；逻辑删除（deleted=1 + update 审计）+ **物理删除** sys_role_menu 中该菜单的全部角色绑定
- 返回 `R<Void>`
- **无种子保护**：admin 可删"系统管理"等种子菜单导致功能不可用（同角色域 §7.7 议题，保护任务已另案立项）；前端仅加强确认文案
- 错误码：3005 存在子菜单，先删除子级 / 3006 菜单不存在
- 前端消费：**是**（确认框文案含菜单名与"解除该菜单与角色的绑定"提示；不做递层级联删除）

## 3. MenuTreeNode 完整字段表（v2，取代 pilot §5.1）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 节点 id（Long→String） | `"10"` |
| parentId | string | 是 | 父节点 id，根为 `"0"` | `"0"` |
| name | string | 是 | 菜单/按钮名 | `"系统管理"` |
| perms | string | 是 | 权限标识；M 目录恒空串 `""`，C 通常为其 :list 权限，F 为操作权限 | `"system:user:list"` |
| type | string | 是 | `"M"` 目录 / `"C"` 菜单 / `"F"` 按钮 | `"M"` |
| sort | number | 是 | 排序号，同级升序（DDL NOT NULL DEFAULT 0，恒有值） | `1` |
| **status** | number | 是（v2 新增） | 0 正常 / 1 停用 | `0` |
| **createBy** | string \| null | 是（v2 新增） | 创建人（种子数据可能为 null） | `"admin"` |
| **createTime** | string \| null | 是（v2 新增） | 创建时间，`yyyy-MM-dd HH:mm:ss`（全局 Jackson；null 直出 null） | `"2026-10-05 20:00:00"` |
| **updateBy** | string \| null | 是（v2 新增） | 更新人 | `null` |
| **updateTime** | string \| null | 是（v2 新增） | 更新时间，同上格式 | `null` |
| children | MenuTreeNode[] | 是 | 子节点数组；**叶子为空数组 `[]`，非 null**（el-table 树形判空即叶子） | `[]` |

```json
{ "code": 200, "msg": "操作成功", "data": [
  { "id": "10", "parentId": "0", "name": "系统管理", "perms": "", "type": "M", "sort": 1,
    "status": 0, "createBy": null, "createTime": "2026-10-05 20:00:00",
    "updateBy": null, "updateTime": null,
    "children": [
      { "id": "11", "parentId": "10", "name": "用户管理", "perms": "system:user:list", "type": "C", "sort": 1,
        "status": 0, "createBy": null, "createTime": "2026-10-05 20:00:00",
        "updateBy": null, "updateTime": null,
        "children": [
          { "id": "111", "parentId": "11", "name": "用户新增", "perms": "system:user:add", "type": "F",
            "sort": 1, "status": 0, "createBy": null, "createTime": "2026-10-05 20:00:00",
            "updateBy": null, "updateTime": null, "children": [] }
        ] }
    ] }
] }
```

## 4. 宽松语义清单（如实记录，前端以约定兜底，不因契约掩盖）

1. **type 后端不校验**：新增/修改均不校验 M/C/F 枚举与父子类型匹配；层级规则（M 固定根 / C 挂 M / F 挂 C）为前端约定（设计 D3），后端环校验仅防 parentId 成环
2. **perms 无格式/唯一性校验**：可重复（两条菜单同 perms 均生效）、可空串；仅 DDL VARCHAR(50) 长度约束
3. **实体接参**：POST/PUT 以 SysMenu 实体接收，body 中 id（POST）/deleted/审计等超集字段不落库（审计由服务端覆写）
4. **PUT 部分更新语义**：null 字段不更新（name 空白串除外，v2 拦截）；前端全量提交写字段规避
5. **删除无种子保护**（§2.4）；**停用菜单不隐身**（§1，tree 不过滤 status）

## 5. 错误码汇总（本域，无新增分配）

| code | 含义 | 出现端点 |
|---|---|---|
| 200 | 成功 | — |
| 401 | 未认证（HTTP 真实 401） | 全部（网关） |
| 403 | 无操作权限（HTTP 200 + body 403） | 全部（@PreAuthorize） |
| 1002 | 菜单名称不能为空 | POST / PUT（name null/空白[新增]） |
| 3005 | 存在子菜单，先删除子级 | DELETE |
| 3006 | 菜单不存在（含已删） | PUT / DELETE |
| 3007 | 父菜单非法：不能是自身 / 不能是自身的后代（会形成环）/ 不存在: {id} | POST / PUT |

（3008 起预留给后续新实体，本契约未占用。）

## 6. 前端消费映射

| 端点 | 消费方 | 备注 |
|---|---|---|
| GET /menu/tree | 菜单管理页列表；AssignMenuDialog（既有）；MenuFormDialog 父级候选（页面传 prop，不另发请求） | 同一数据源 |
| POST /menu | MenuFormDialog（add） | 返回新 id（当前不消费，保留） |
| PUT /menu | MenuFormDialog（edit） | 全量提交六写字段 + id |
| DELETE /menu/{id} | 列表操作列 | 确认框含解绑提示 |

## 给 backend-agent / frontend-agent 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-06-menu-management.md`。要点：

- **backend**：B1 MenuTreeNode +5 字段 + MenuTreeBuilder.toNode 映射（+单测）；B2 SysMenuManageService.update 补 name 空白校验（+单测）；B3 构建全绿；B4 curl 验收（ASCII 冒烟、临时 id 验 1002、删净）
- **frontend**：F1 MenuTreeNode 类型扩展；F2 api/menu.ts +createMenu/updateMenu/deleteMenu（menuTree 保留不动）；F3 路由+Sidebar 追加 /system/menu（icon Menu）；F4 树表页；F5 MenuFormDialog（D3 联动：M 固定根目录/C 候选 M/F 候选 C、编辑 type 锁定、F 必填 perms）；F6 两次 build + 联通；F7 run-menu-e2e.mjs（删净纪律）+ 全量回归
- 红线：契约定稿后两端不得单方改；与实测不符回报主控；AssignMenuDialog 既有行为不得破坏
