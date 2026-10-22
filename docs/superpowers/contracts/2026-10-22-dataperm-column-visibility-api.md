# 列级数据权限前端动态列 API 契约（增量修订：挂 2026-10-10-data-permission-api 体系）

- 日期：2026-10-22
- 状态：**定稿**——架构-agent 出品；设计 `docs/superpowers/specs/2026-10-22-dataperm-user-column-visibility-design.md`（D59-D66）、计划 `docs/superpowers/plans/2026-10-22-dataperm-user-column-visibility.md`
- **契约形态说明**：本文件是数据权限域**增量修订**（沿 2026-10-18-kb-permission-api §7 additive 修订声明先例）——`2026-10-10-data-permission-api.md` 及其后续修订（2026-10-11 谓词/操作符、2026-10-12 列处理注解化、2026-10-10 组件化）的全部承诺**逐条不变**，本文件只定义增量：① my-scope 出参增补结构化列清单（§1）；② resources 出参增补 columnOnly（§2）；③ 新资源 `user` 注册与列级-only 语义（§3）；④ 用户端点 mobile/email 增量与 `***` 哨兵语义（§4）。冻结点重申：隐藏=字段置 null、脱敏=整值 `***`、columnSummary 格式——本轮零触碰
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对既有契约）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | my-scope `GET /system/data-perm/my-scope` | 出参 `MyScopeVo` 增 `columns` 数组（§1.1）；`scopeLabel` 增新取值「不适用（该资源仅列级权限）」（§1.2，仅 columnOnly 资源返回） | additive |
| 2 | resources `GET /system/data-perm/resources` | 出参 `DataPermResourceVo` 增 `columnOnly` boolean（§2） | additive |
| 3 | 资源注册表 | 新资源 `user`：可配列 `mobile`/`email`，**columnOnly=true**（行档位与谓词不参与求值，§3.1）；谓词配置对该资源 3035 拒绝（filterColumns 空，既有校验链自然生效） | 数据扩张 |
| 4 | 用户分页 `GET /system/user/page` 出参 | `SysUserVo` 增 `mobile`/`email`（string\|null；可能 `***`——列脱敏，§3.2） | additive |
| 5 | 用户详情 `GET /system/user/{id}` 出参 | 同上（SysUserVo 增量随 detail 链自然携带） | additive |
| 6 | 用户新增/修改 `POST/PUT /system/user` | `UserSaveRequest` 增 `mobile`/`email`（可选，格式校验 §4.2）；**update 对值 `***` 按「不修改」处理**（§4.3 哨兵语义） | additive + 语义声明 |
| 7 | sys_user DDL | 增 mobile/email 两可空列 + admin 示例值种子（§5）；**sys_menu 零新增** | 数据扩张 |
| 8 | 错误码 | **零新增**（复用 1002 两条新文案/3034/3035）；system 段现值仍至 3042 | 无变化声明 |

## 1. my-scope 出参增量

### 1.1 MyScopeVo 增 `columns`（机器可读列动作清单——前端动态列渲染驱动源）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| scopeLabel | string | 是 | 既有五态 + 新取值「不适用（该资源仅列级权限）」（§1.2） | `"不适用（该资源仅列级权限）"` |
| columnSummary | string \| null | 是 | 既有人读摘要，格式不变 | `"email:隐藏;mobile:脱敏"` |
| **columns** | **ColumnDecisionVo[]** | **是（无列动作 `[]`）** | **当前登录人对该资源的列动作终态**（多主体规则并集宽松者胜后）；元素字段 `columnKey` string / `action` number（0 隐藏 1 脱敏）——术语与 §3.3 ColumnRuleItem、explain §6.7 ColumnDecisionVo 同源；输出按 columnKey 字典序稳定 | `[{"columnKey":"email","action":0},{"columnKey":"mobile","action":1}]` |

- 行为不变项：免 @PreAuthorize（登录即可）、不留痕、resource 非法 3034、求值实时无缓存
- **前端消费口径**：`columns` 含 action=0 的列 → 页面整列不渲染（v-if 隐藏）；action=1 的列 → 列保留显示（值已是 `***`，后端处理）；拉取失败降级=按全列渲染（后端值层防线兜底，无明文泄露）
- 拉取时机建议（非强制）：与列表分页请求并行重拉——对齐数据权限即时生效语义（规则变更后下一次加载即同步）

### 1.2 scopeLabel 新取值（仅 columnOnly 资源）

columnOnly 资源（当前仅 `user`）行级不参与求值，scopeLabel 恒返回 **`不适用（该资源仅列级权限）`**——不落入既有五态判定序；既有资源（leave/bpmn_approval）五态取值逐字不变。

## 2. resources 出参增量

`DataPermResourceVo` 增字段：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| columnOnly | boolean | 是 | 该资源是否仅列级权限（行档位与谓词不参与求值）；既有资源恒 false | `true` |

- 用途：配置弹窗对 columnOnly 资源**禁用行档位区与条件段**并提示「该资源仅列级权限生效，行范围配置不参与求值」（前端消费，§3.1）
- 试点后注册表形态：`[{resource:"leave", columnOnly:false, …}, {resource:"bpmn_approval", columnOnly:false, …}, {resource:"user", columnOnly:true, columns:["mobile","email"], filterColumns:[]}]`

## 3. user 资源（新注册，列级-only）

### 3.1 资源语义

- **资源标识** `user`；**可配列** `mobile`、`email`（仅此两列——nickname/account 不开放：编辑回填与操作文案消费面风险，设计 D59-D66 记档）；**filterColumns 空清单**（行级条件不可配）
- **columnOnly 语义**（求值与配置两端）：
  - 求值：行规则（档位）与谓词**不参与收敛**——行范围恒全部、无行过滤；列规则正常求值（并集宽松者胜）；explain/narratives 注明「该资源仅列级权限生效，行范围不适用」
  - 配置：`POST /system/data-perm/rule` 对 user 资源——`rowScope` 照收照存（前端默认 SELF 且行档区禁用，存库值不参与求值）；`predicates` 非空 → **3035**（既有「条件字段不在资源可过滤清单」校验，user 资源 filterColumns 为空故任何 field 必拒）——零新校验码
  - my-scope：scopeLabel 恒「不适用（该资源仅列级权限）」（§1.2），columns/columnSummary 照常
- 决策留痕：user/page、user/{id} 每次真实查询落一条（operation=list/detail）——column_summary 如实记录列结论；行段恒 all（rule_digest 行规则不出现）

### 3.2 用户端点出参增量（wire 形态沿既有冻结点）

`SysUserVo` 增：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| mobile | string \| null | 是 | 手机号；null=未填**或**被列规则隐藏（两态不可区分，前端展示 `-`）；`***`=被列规则脱敏 | `"13800000001"` / `"***"` / `null` |
| email | string \| null | 是 | 邮箱；取值语义同上 | `"admin@example.com"` |

- 列动作应用范围：`GET /system/user/page`、`GET /system/user/{id}`、**MCP 工具 `system_query_users`**（三入口同主体同规则同改写——JWT 主体即规则主体，设计 D62）；account/nickname 等未注册列永不被改写

## 4. 用户保存增量与 `***` 哨兵语义

### 4.1 UserSaveRequest 增量

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| mobile | string | 否 | null/空=未填（新增）/不更新（修改）；格式校验见 §4.2；修改时值 `***` 按「不修改」处理（§4.3） |
| email | string | 否 | 同上 |

### 4.2 格式校验（1002 通用业务码，两条新文案）

- 非空才验；手机号：中国大陆手机号格式（`1[3-9]` 开头 11 位）；邮箱：常规邮箱格式（含 @ 与域名段）
- 无效 → 1002，文案 `手机号格式无效` / `邮箱格式无效`（拦截器通用 toast 链，无需特判）

### 4.3 `***` 哨兵语义（修改路径，前后端口径一致）

- **后端**：`PUT /system/user` 收到 mobile/email 值等于 `***`（脱敏标记）时按 null 处理——null 走既有「不更新该列」语义，**库值保持原值**；不报错（正常业务路径，非攻击）
- **前端**：编辑弹窗预填时检测行值 `=== "***"` → 输入框置空 + placeholder「已脱敏，留空保持不变」；留空=不修改、填新值=修改
- 安全论证：`***` 不在 mobile/email 合法值域（格式校验兜底）——哨兵无歧义；**`***` 永远不是合法新值**（新增路径提交 `***` 被 §4.2 拦截 1002）
- 该语义仅适用本轮 mobile/email 两字段；未来扩可配列时逐列重审值域论证（设计 §11.2）

## 5. DDL 与种子

```sql
ALTER TABLE sys_user
    ADD COLUMN mobile VARCHAR(20) DEFAULT NULL COMMENT '手机号（可空=未填；数据权限资源 user 的可配列，可被列规则脱敏为 *** 或隐藏为 null）' AFTER nickname,
    ADD COLUMN email VARCHAR(64) DEFAULT NULL COMMENT '邮箱（可空=未填；数据权限资源 user 的可配列，可被列规则脱敏为 *** 或隐藏为 null）' AFTER mobile;
UPDATE sys_user SET mobile = '13800000001', email = 'admin@example.com' WHERE id = 1 AND deleted = 0;
```

- 增量脚本 `cloud-base/scripts/sql/2026-10-22-dataperm-user-columns.sql` + 基线 `cloud-base/scripts/sql/cloud_system.sql` 同步（沿 2026-10-10 先例）；索引：零新索引（无该两列查询路径，设计 §8 记档）
- 种子影响：admin 两条示例值（e2e/冒烟锚点）；**sys_menu 零新增**——导航断言零维护、perms 快照零变化

## 6. 前端类型增量（types/api.ts + api/dataPerm.ts）

- `MyScopeVo` 增 `columns: ColumnDecision[]`（内嵌 `ColumnDecision { columnKey: string; action: number }`——或复用 explain 既有类型，命名随工程现状）；`DataPermResourceVo` 增 `columnOnly: boolean`
- `UserPayload`（UserSaveRequest 前端形态）增 `mobile?: string` / `email?: string`；`SysUserVo` 前端类型增 `mobile: string | null` / `email: string | null`
- 常量：`DATAPERM_MASK = '***'`（脱敏标记，预填检测与哨兵口径共享；既有 ACTION_HIDDEN=0 / ACTION_MASKED=1 沿用）
- api 模块零新函数（my-scope/resources 既有函数自然携带增量）；新增 composable `usePermColumns(resource)` 非契约面（工程内部封装）

## 7. 错误码（零新增声明）

本轮**零新增错误码**：1002（两条新文案：手机号格式无效 / 邮箱格式无效）、3034（资源/列标识校验——user/mobile/email 均已注册不受影响）、3035（user 资源谓词 field 校验自然拒绝）全部复用既有。system 段现值仍至 **3042**，下轮 3043 接续。

## 8. e2e 影响面

- 新脚本 `cloud-e2e/run-dataperm-usercolumn-e2e.mjs`（`e2edpu` 前缀造数）：动态列 DOM 断言（隐藏列头不存在/脱敏列值 `***`）+ 即时生效（免重登）+ `***` 哨兵 API 直测 + my-scope columns 断言 + 清扫残留断言
- 既有回归零维护预期：四 dataperm 脚本（leave/bpmn_approval 列动作与提示条不动）、e2e:user（新增字段表单增量断言进新脚本，既有流不破）、e2e:dict（ProTable 页零关）；导航断言零维护（无新菜单）
