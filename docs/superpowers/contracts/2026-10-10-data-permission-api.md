# 数据权限 API 契约（cloud-system，网关前缀 /system）

- 日期：2026-10-10
- 状态：**数据权限域现行版（v1）**——由架构-agent 定稿，配合数据权限需求（设计 `docs/superpowers/specs/2026-10-10-data-permission-design.md`，计划 `docs/superpowers/plans/2026-10-10-data-permission.md`）
- **与既有契约的关系**：本契约为**新域**（部门/数据权限/决策留痕）+ **两处既有端点语义变更**（§4 leave、§5 用户增量）；pilot/menu/role/dict/perms/leave 既有契约端点除 §4/§5 列明者外**零触碰**。通用约定（R 结构 / HTTP 恒 200 + body.code / Long→String / 时间 `yyyy-MM-dd HH:mm:ss` / 错误码分段 / 前端处理策略）沿用 pilot §1
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对既有契约）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | leave 分页 `GET /system/leave/page` | 行级语义：恒按申请人 → **按当前登录人数据权限规则求值**（无规则用户=仅自己，行为不变；admin 种子=全部）；出参行新增 `applyUserName/approverName` 恒返（原仅详情回填）；`reason/title` 可能被列规则置 null/`***` | **语义变更**（bpmn-leave-api §5.2 / approval-projection-api §4.1 的「恒按申请人」描述自本文档起由 §4.1 取代） |
| 2 | leave 详情 `GET /system/leave/{id}` | 行级语义：无归属校验 → **行级判定**（归属账号不在范围 → **3026**）；列级同列表 | **语义变更 + IDOR 收口**（approval-projection-api §4.1 §5.3 的详情描述由 §4.2 取代） |
| 3 | 用户新增/修改/分页 | `UserSaveRequest` 增量 `deptId`（可选）；`SysUserVo` 增量 `deptId/deptName` | additive（pilot §用户域） |
| 4 | 错误码段 | 数据权限域**占用 3026-3034**（§8）；system 段现用至 3025 | 接续声明 |
| 5 | 种子数据 | sys_menu +7 行（C 15/16 + F 151/152/153/161/162）+ admin 绑定 +7；sys_dept 种子根部门；sys_user.admin dept_id=1；admin 角色行规则种子 leave/ALL → admin 导航 +2（实时）、perms 快照 +7（重登生效） | 数据扩张 |
| 6 | e2e 断言 | 既有导航硬编码断言须同步维护（沿 dict §0.3 先例，计划 E 章） | 非契约变更 |

## 1. 域语义（数据权限域特有）

- **功能权限 vs 数据权限**：sys_menu.perms 管「能不能调」（@PreAuthorize）；数据权限规则管「调了之后看到哪些行/列」——两者正交，本域只做后者
- **资源（resource）**：数据权限的作用对象标识，取值由后端资源注册表硬约束（试点仅 `leave`）；非法值 → 3034
- **主体（subject）**：规则的绑定对象，`subjectType` 0=角色 / 1=用户；同主体同资源至多一条行规则（uk）；用户的有效范围 = 全部启用角色规则 ∪ 用户直绑规则，**并集宽松者胜**；**无任何规则命中 → 默认行级=仅自己 + 列级=全可见**（admin 靠种子规则得全部档，代码无特例）
- **行范围档位（rowScope）**：`0` 仅自己 / `1` 本部门 / `2` 本部门及以下 / `3` 自定义集合 / `4` 全部。部门档按 `sys_user.dept_id` 求值时实时展开为账号集合（用户未挂部门 → 该规则展开为空集）；自定义集合为账号数组（`customAccounts`）
- **列动作（action）**：`0` 隐藏（VO 字段置 null）/ `1` 脱敏（整值替换 `***`，单一策略）；同列多规则冲突取**最宽松**（可视 > 脱敏 > 隐藏）；可配列由资源注册表下发（leave → `title`、`reason`）
- **决策留痕**：每次真实查询求值（list/detail）落 `sys_data_perm_log` 一条；详情被拒补记 `operation=deny` 一条；**explain 模拟与 my-scope 自查不留痕**
- **部门树**：`parent_id=0` 为根；内置根部门（id=1「总公司」）禁删；同级重名禁（uk）；MVP 禁改上级部门；部门删除前置校验「无子部门且无在职用户挂载」
- **权限快照时效**（沿用 dict §1）：菜单种子落库后 admin **导航刷新即见**（实时）但**操作须重新登录**（快照）；数据权限规则**即时生效**（每请求实时求值，无快照无缓存——与功能权限的快照语义不同，两端与 e2e 注意）

## 2. 部门端点（/system/dept）

### 2.1 部门树 `GET /system/dept/tree`

- 权限：`hasAnyAuthority('system:dept:list','system:user:add','system:user:edit')`（部门管理与用户表单共同数据源——设计 D12）
- 入参：无
- 行为：全量未删除部门（含停用，tag 区分——沿 dict §1 口径），按 `sort ASC, id ASC`，内存组树返回森林
- 返回 `R<List<SysDeptTreeNode>>`（§6.1）
- 错误码：401/403（通用）

### 2.2 新增部门 `POST /system/dept`

- 权限：`system:dept:add`
- 入参（JSON body，DeptSaveRequest）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| parentId | string | 是（缺失/空 → 1002） | 父部门 id（`"0"`=根下）；不存在或已删 → 3027 | `"0"` |
| name | string | 是（非空白，否则 1002） | 部门名称（DDL VARCHAR(30)），同层级唯一 | `"yanfakai"` |
| sort | number | 否（null 落库默认 0） | 排序号 | `1` |
| status | number | 否（null 落库默认 0） | 0 正常 / 1 停用 | `0` |

- 行为：校验链（parentId 存在 3027 → name 非空白 1002 → 同层重名 3028）→ 审计四值显式 → `DuplicateKeyException` 兜底转 3028（uk_parent_name 墓碑占键沿 sys_role 先例）
- 返回 `R<Long>`——新部门 id 字符串
- 错误码：1002（父部门不能为空/部门名称不能为空）/ 3027 / 3028

### 2.3 修改部门 `PUT /system/dept`

- 权限：`system:dept:edit`
- 入参（JSON body，同 2.2 字段集 + id；部分更新语义 null 不更新）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | string | 是 | 目标部门 id（不存在或已删 → 3027） |
| parentId | string | **MVP 禁改**：传入与库中现值不同 → 1002「暂不支持修改上级部门」；不传/等值放行 | — |
| name | string | 空白串拦截（1002）；null 不更新 | 同层重名 → 3028 |
| sort / status | number | 否（null 不更新） | — |

- 行为：requireDept(3027) → parentId 一致性校验(1002) → 查重(3028) → 动态更新 + update 审计两值 → DuplicateKey 兜底 3028
- 返回 `R<Void>`
- 错误码：3027 / 3028 / 1002
- 前端消费：**是**（编辑弹窗上级部门只读展示）

### 2.4 删除部门 `DELETE /system/dept/{id}`

- 权限：`system:dept:remove`
- 入参：路径参数 `id`（显式命名）
- 行为：requireDept(3027) → `is_builtin=1` → **3029**；未删子部门 count>0 → **3030**；挂载在职用户 count>0（sys_user.dept_id=id AND deleted=0，含停用账号）→ **3030**；否则逻辑删除（deleted=1 + update 审计两值）
- 返回 `R<Void>`
- 错误码：3027 / 3029 内置部门禁止删除 / 3030 部门下存在子部门或在职用户，禁止删除
- 前端消费：**是**（确认框文案含部门名；3029/3030 拦截器 toast）

## 3. 数据权限端点（/system/data-perm）

### 3.1 规则分页 `GET /system/data-perm/rule/page`

- 权限：`system:dataPerm:list`
- 入参（query，全部可选——组合筛选）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| resource | string | 否 | 资源标识筛选（精确；非法值不报错返回空集——筛选宽松语义） | `"leave"` |
| subjectType | number | 否 | 主体类型筛选：0 角色 / 1 用户 | `0` |
| subjectId | string | 否 | 主体 id 精确筛选（角色管理页联动入口带参） | `"2"` |
| pageNum / pageSize | number | 否 | 默认 1 / 10（maxLimit 200） | — |

- 行为：动态条件查询，`ORDER BY id DESC`；服务层补 subjectName（角色名/用户昵称按 subjectType 分流批量二查）
- 返回 `R<PageResult<DataPermRuleVo>>`（total 字符串，rows 见 §6.2）
- 错误码：401/403

### 3.2 规则配置回显 `GET /system/data-perm/rule/config`

- 权限：`system:dataPerm:list`
- 入参（query，三项全必填）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| resource | string | 是（缺失 → 1002；非法 → 3034） | 资源标识 |
| subjectType | number | 是（0/1，否则 1002） | 主体类型 |
| subjectId | string | 是（主体不存在/停用 → 3032） | 主体 id |

- 行为：查行规则 + 该主体该资源全部列规则；**无行规则返回 `configured=false` 且字段默认**（rowScope=null、customAccounts/columns 空数组），前端弹窗据此走新增态
- 返回 `R<DataPermRuleConfigVo>`（§6.3）
- 错误码：1002 / 3032 / 3034

### 3.3 保存规则（upsert 全量覆盖）`POST /system/data-perm/rule`

- 权限：`system:dataPerm:save`
- 入参（JSON body，DataPermRuleSaveRequest）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| resource | string | 是（非法 → 3034） | 资源标识 | `"leave"` |
| subjectType | number | 是（0/1，否则 1002） | 主体类型 | `0` |
| subjectId | string | 是（主体不存在/停用 → 3032） | 主体 id | `"2"` |
| rowScope | number | 是（0-4，否则 1002） | 行范围档位 | `2` |
| customAccounts | string[] | **条件必填** | rowScope=3 时必填非空（1002）且逐账号存在启用（3033）；其余档位必须空/null（1002「仅自定义范围档可配置账号集合」） | `["zhang3","lisi4"]` |
| columns | ColumnRuleItem[] | 否（null/[] = 清空列规则） | 列规则列表，columnKey 不重复（1002） | `[{"columnKey":"reason","action":1}]` |

ColumnRuleItem：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| columnKey | string | 是（不在资源可配列清单 → 3034） | 列标识（VO 字段名，如 `reason`） |
| action | number | 是（0/1，否则 1002） | 0 隐藏 / 1 脱敏 |

- 行为：校验链（resource/columnKey 注册表 3034 → 枚举合法 1002 → 主体有效 3032 → CUSTOM 账号有效 3033/联动 1002）→ 事务内：行规则按 uk 判存 insert 或 update（审计四值/两值）+ 列规则按 (resource,subjectType,subjectId) 物理全删后批插（空列表仅删不插）；**upsert 语义=该主体该资源配置整体覆盖**
- 返回 `R<Void>`
- 错误码：1002 / 3032 / 3033 / 3034
- 设计注：规则表物理删除无墓碑（设计 D9）；customAccounts 上限由 VARCHAR(1000) 承载（约 30 账号，超长 DB 报错兜底，前端多选计数提示）

### 3.4 删除规则 `DELETE /system/data-perm/rule/{id}`

- 权限：`system:dataPerm:remove`
- 入参：路径参数 `id`（行规则 id，显式命名）
- 行为：行规则存在性（3031）→ 事务内物理删行规则 + **连带物理删**同 (resource,subjectType,subjectId) 全部列规则
- 返回 `R<Void>`
- 错误码：3031 数据权限规则不存在

### 3.5 资源注册表 `GET /system/data-perm/resources`

- 权限：`system:dataPerm:list`
- 入参：无
- 行为：返回全部已注册资源与可配列清单（配置弹窗「资源」下拉与列配置动态渲染的数据源）
- 返回 `R<List<DataPermResourceVo>>`（§6.4；试点单元素：`{resource:"leave", columns:["title","reason"]}`）

### 3.6 主体选项 `GET /system/data-perm/subject-options`

- 权限：`system:dataPerm:list`
- 入参（query）：`type` number 必填（0 角色 / 1 用户，否则 1002）
- 行为：启用主体选项（角色 status=0；用户 status=0）；label 后端拼好（角色=角色名；用户=`昵称(账号)`）
- 返回 `R<List<SubjectOptionVo>>`（§6.5；id 为字符串 Long→String）

### 3.7 决策留痕分页 `GET /system/data-perm/log/page`

- 权限：`system:dataPerm:list`
- 入参（query，筛选可选）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| account | string | 否 | 决策对象账号（精确） | `"zhang3"` |
| resource | string | 否 | 资源标识（精确） | `"leave"` |
| pageNum / pageSize | number | 否 | 默认 1 / 10 | — |

- 行为：动态条件 `ORDER BY id DESC`；命中 `idx_account_time` / `idx_resource_time`
- 返回 `R<PageResult<DataPermLogVo>>`（§6.6）
- 错误码：401/403

### 3.8 模拟解释 `GET /system/data-perm/explain`

- 权限：`system:dataPerm:list`
- 入参（query）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| account | string | 是（非空白 1002） | 被模拟账号（目标用户不存在/停用 → 3032） |
| resource | string | 是（非法 → 3034） | 资源标识 |

- 行为：以该账号身份执行完整求值（**不执行业务查询、不留痕**），返回决策全过程
- 返回 `R<DataPermExplainVo>`（§6.7）
- 错误码：1002 / 3032 / 3034

### 3.9 我的数据范围 `GET /system/data-perm/my-scope`

- 权限：**免 @PreAuthorize**（登录即可——语义=自查本人范围；网关鉴权保登录态。设计 D12 记档）
- 入参（query）：`resource` string 必填（非法 → 3034）
- 行为：以当前登录人求值（**不留痕**），返回轻量范围摘要（leave 页提示条专用）
- 返回 `R<MyScopeVo>`（§6.8）：`scopeLabel` 取值——全部 / 仅自己 / 自定义范围（含自己，共 N 人） / 指定范围（N 人） / 无（空范围）；`columnSummary` 如 `"reason:脱敏"`（null=无列动作）
- 错误码：3034

## 4. leave 端点语义变更（/system/leave，取代 bpmn-leave-api §5.2 与 approval-projection-api §4.1/§5.3 对应描述）

### 4.1 分页 `GET /system/leave/page`（行级 + 列级）

- 权限：`system:leave:list`（不变）
- 入参：PageQuery（不变，无搜索参数）
- **行为变更**：恒按申请人 → **按当前登录人数据权限求值**：
  - 命中全部档（或 admin 种子规则）→ 全量行集
  - 无规则 → 默认仅自己（**普通用户行为与旧版一致**，向后兼容）
  - 展开为空集（如未挂部门用户仅有本部门档规则）→ 返回空页（不查库）
- **出参变更**：行内 `applyUserName`/`approverName` 由「仅详情回填」改为**列表恒返**（服务层批量回填，管理员视角需辨识申请人）；`title` 可能 null（列隐藏）、`reason` 可能 null 或 `"***"`（列脱敏）——前端展示空/原样
- 返回 `R<PageResult<SysLeaveVo>>`（SysLeaveVo 字段集不变，语义增量见上）
- 错误码：401/403

### 4.2 详情 `GET /system/leave/{id}`（行级判定 + IDOR 收口）

- 权限：`system:leave:list`（不变）
- 入参：路径参数 `id`（不变）
- **行为变更**：无归属校验 → 读出行后按求值结果判定 `apply_user` 可见性：
  - 行不存在/已删 → 3018（不变，真不存在）
  - 归属账号不在范围 → **3026 无权访问该数据**（新码；同时后端补记 deny 留痕——设计 D13）
  - 可见 → 列级同列表应用（title/reason）
- 返回 `R<SysLeaveDetailVo>`（结构不变）
- 错误码：3018 / **3026** / 401/403
- 前端消费：**是**（3026 由拦截器统一 toast；详情弹窗打不开即为无权，不做二次提示）

## 5. 用户端点增量（/system/user，pilot 契约 additive）

### 5.1 新增/修改用户（POST / PUT /system/user）

- 入参增量（UserSaveRequest）：`deptId` string **可选**（null=不挂部门；传入时部门不存在/已删 → 3027）；修改 null 不更新该列（部分更新语义）
- 其余字段与校验不变；返回不变

### 5.2 用户分页（GET /system/user/page）出参增量

- `SysUserVo` 新增：`deptId` string|null（Long→String）、`deptName` string|null（LEFT JOIN sys_dept 派生；挂靠部门被删后 null，不阻断）——行集与既有字段零变化

## 6. VO 字段表

### 6.1 SysDeptTreeNode（部门树节点）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 部门 id | `"1"` |
| parentId | string | 是 | 父部门 id（"0"=根级） | `"0"` |
| name | string | 是 | 部门名称 | `"zonggongsi"` |
| sort | number | 是 | 排序 | `0` |
| status | number | 是 | 0 正常 / 1 停用 | `0` |
| builtin | boolean | 是 | 内置根部门标记（前端禁删提示） | `true` |
| createTime | string \| null | 是 | `yyyy-MM-dd HH:mm:ss` | `"2026-10-10 10:00:00"` |
| children | SysDeptTreeNode[] | 是 | 子部门（叶子为 `[]`） | `[]` |

### 6.2 DataPermRuleVo（规则分页行）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 行规则 id | `"3"` |
| resource | string | 是 | 资源标识 | `"leave"` |
| subjectType | number | 是 | 0 角色 / 1 用户 | `0` |
| subjectId | string | 是 | 主体 id | `"2"` |
| subjectName | string | 是 | 主体名称（角色名/用户昵称；主体已删时为 `"(已删除)"` 降级） | `"zhuguan"` |
| rowScope | number | 是 | 0-4 档位 | `2` |
| customAccounts | string[] | 是 | CUSTOM 档账号集合（其余档 `[]`） | `["zhang3"]` |
| columns | ColumnRuleVo[] | 是 | 列规则（无则 `[]`） | 见下 |
| updateBy | string \| null | 是 | 更新人 | `"admin"` |
| updateTime | string \| null | 是 | 更新时间 | `"2026-10-10 11:00:00"` |

ColumnRuleVo：`columnKey` string / `action` number（0 隐藏 1 脱敏）

### 6.3 DataPermRuleConfigVo（配置回显）

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| configured | boolean | 是 | 是否已有行规则（false=新增态） |
| resource | string | 是 | 回显入参 |
| subjectType | number | 是 | 回显入参 |
| subjectId | string | 是 | 回显入参 |
| rowScope | number \| null | 是 | 现行档位（configured=false 时 null） |
| customAccounts | string[] | 是 | 同 6.2 |
| columns | ColumnRuleVo[] | 是 | 同 6.2 |

### 6.4 DataPermResourceVo

`resource` string（资源标识）/ `columns` string[]（可配列清单，如 `["title","reason"]`）

### 6.5 SubjectOptionVo

`id` string（Long→String）/ `label` string（角色名 或 `昵称(账号)`）

### 6.6 DataPermLogVo（决策留痕行）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 留痕 id | `"42"` |
| account | string | 是 | 决策对象账号 | `"zhang3"` |
| resource | string | 是 | 资源 | `"leave"` |
| operation | string | 是 | `list` / `detail` / `deny` | `"list"` |
| ruleIds | string \| null | 是 | 命中行规则 id 逗号串（null=无规则默认档） | `"3,5"` |
| ruleDigest | string \| null | 是 | 决策摘要 | `"role:zhuguan(id=2)本部门及以下展开8人"` |
| scopeSummary | string | 是 | `all` / `accounts=12` / `empty` | `"accounts=8"` |
| columnSummary | string \| null | 是 | 列结论 | `"reason:脱敏"` |
| businessKey | string \| null | 是 | detail/deny 时目标单据 id | `"1234567890"` |
| createTime | string | 是 | 决策时间 | `"2026-10-10 12:00:00"` |

### 6.7 DataPermExplainVo（模拟解释）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| account | string | 是 | 被模拟账号 | `"zhang3"` |
| resource | string | 是 | 资源 | `"leave"` |
| hitRules | HitRuleVo[] | 是 | 命中规则明细（无则 `[]`） | 见下 |
| rowAll | boolean | 是 | 行范围=全部（过滤豁免） | `false` |
| rowAccountCount | number | 是 | 展开账号集合数（rowAll=true 时 0） | `9` |
| selfIncluded | boolean | 是 | 集合是否含自己 | `true` |
| columns | ColumnDecisionVo[] | 是 | 列决策（无动作 `[]`） | 见下 |
| narratives | string[] | 是 | 中文解释句（每命中规则一句+收敛结论+列结论） | `["命中角色[zhuguan]：本部门及以下，展开 8 人", …]` |

HitRuleVo：`ruleId` string / `subjectType` number / `subjectName` string / `rowScope` number / `customCount` number（CUSTOM 配置数）/ `expandedCount` number（该规则展开账号数）
ColumnDecisionVo：`columnKey` string / `action` number（0 隐藏 1 脱敏）

### 6.8 MyScopeVo

`scopeLabel` string（全部/仅自己/自定义范围（含自己，共 N 人）/指定范围（N 人）/无（空范围））/ `columnSummary` string|null

**scopeLabel 判定序（主控裁决 2026-10-10，五态取值按命中档位语义分类而非展开结果形状）**——scopeLabel 是排查锚点，反映「配置了什么档」而非「展开后集合长什么样」；「仅自己」专属 SELF 档/无规则命中（部门档展开恰={自己}时若标「仅自己」会误导排查以为未配规则，且部门进人后档位标签漂移）：

1. 行范围=全部（ALL）→ `全部`
2. 展开集合为空 → `无（空范围）`
3. 命中任一部门档（DEPT/DEPT_AND_CHILD）→ `指定范围（N 人）`（N=展开账号数，**N=1 亦然**——部门档语义优先于展开形状）
4. 命中任一 CUSTOM 档 → 展开集合含自己 → `自定义范围（含自己，共 N 人）`；不含自己 → `指定范围（N 人）`
5. 兜底（部门/CUSTOM 均未命中，只剩 SELF 档或无规则）→ `仅自己`

## 7. 前端类型（types/api.ts 增量，函数名动词开头、JSDoc 注节号）

- Payload/Query：`DeptSavePayload`（§2.2）、`DataPermRulePageQuery`（§3.1）、`RuleConfigQuery`（§3.2）、`DataPermRuleSavePayload`（§3.3，含内嵌 `ColumnRuleItem`）、`DataPermLogPageQuery`（§3.7）、`ExplainQuery`（§3.8）、`MyScopeQuery`（§3.9）；用户增量 `deptId?: string` 进既有 UserPayload
- api 模块：`api/dept.ts`（treeDept/createDept/updateDept/deleteDept）、`api/dataPerm.ts`（pageRule/getRuleConfig/saveRule/deleteRule/listResources/listSubjectOptions/pageDataPermLog/explainDataPerm/getMyScope）
- 档位/动作/主体类型常量：`ROW_SCOPE_SELF=0 / DEPT=1 / DEPT_AND_CHILD=2 / CUSTOM=3 / ALL=4`、`ACTION_HIDDEN=0 / MASKED=1`、`SUBJECT_ROLE=0 / SUBJECT_USER=1`、`OP_LIST='list' / OP_DETAIL='detail' / OP_DENY='deny'`（禁魔法数）

## 8. 错误码总表（system 段 3026-3034，本轮占用；文案即后端 msg）

| 码 | 文案（`{x}` 为动态值） | 触发点 |
|---|---|---|
| 3026 | 无权访问该数据 | leave 详情行级拒绝（§4.2） |
| 3027 | 部门不存在 | 部门三端点存在性 / 用户保存 deptId 校验（§2/§5.1） |
| 3028 | 同层级下已存在同名部门: {name} | 部门查重 / DuplicateKey 兜底（§2.2/§2.3） |
| 3029 | 内置部门禁止删除 | §2.4 |
| 3030 | 部门下存在子部门或在职用户，禁止删除 | §2.4 |
| 3031 | 数据权限规则不存在 | §3.4 |
| 3032 | 规则主体不存在或已停用: {name} | §3.2/§3.3 主体校验；§3.8 explain 目标用户无效 |
| 3033 | 自定义范围包含无效账号: {accounts} | §3.3 |
| 3034 | 无效的资源或列标识: {value} | §3.2/§3.3/§3.8/§3.9 注册表校验 |

1002 通用业务校验文案（本轮新增）：父部门不能为空 / 部门名称不能为空 / 暂不支持修改上级部门 / 主体类型无效 / 行范围档位无效 / 列动作无效 / 列标识重复 / 仅自定义范围档可配置账号集合 / 自定义账号集合不能为空 / 账号不能为空。

## 9. 种子与权限快照（对前端/e2e 的可观察影响）

1. 菜单 +7（§0.5）：admin 侧边「系统管理」下新增**部门管理**（排序 5，位于字典管理后）与**数据权限**（排序 6）——导航刷新即见；按钮操作（保存/删除规则、部门增删改）须**重新登录**（perms 快照 +7）
2. admin 行为变化：leave 列表由「仅自己」→ **全部**（种子规则 leave/ALL）；无 seed 规则的其他用户不变（默认仅自己）
3. e2e 断言维护清单（计划 E 章）：既有侧边/导航硬编码断言 +2 项同步

## 10. 验收口径（联调与 e2e 共同遵守）

- 三账号矩阵：admin（全部）/ 主管角色+本部门及以下（挂部门）/ 普通员工（无规则=仅自己）——同一 leave/page 行集三态
- 列级：给主管角色配 reason=脱敏 → 主管视角列表与详情 reason=`***`；用户直绑 title=隐藏 → 该用户 title 空
- 排查链：查留痕（list/detail 各一条、要素齐）→ explain 复算一致 → 越权详情 3026 + deny 留痕落库
- my-scope 三账号标签与实际行集一致；用户管理挂/换部门后**不重登**即影响部门档求值（实时语义）
