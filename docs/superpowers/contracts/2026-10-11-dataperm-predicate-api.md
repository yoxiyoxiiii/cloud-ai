# 数据权限 L2 结构化谓词 API 契约（条件三元组 + 双试点行为变更）

- 日期：2026-10-11
- 状态：**定稿**——由架构-agent 出品，配合谓词需求（设计 `docs/superpowers/specs/2026-10-11-dataperm-predicate-design.md` D29-D40，计划 `docs/superpowers/plans/2026-10-11-dataperm-predicate.md`）
- **与既有契约的关系**：本契约是 `2026-10-10-data-permission-api.md`（配置台 v1）与 `2026-10-10-dataperm-component-api.md`（跨服务 v1）的**谓词增量版**——四处**既有端点 additive 变更**（§2）+ **一处既有语义变更**（§6 scopeLabel 判定序 v2，显著标注）+ 两试点端点行为变更描述（§4）。数据权限配置台 9 端点路径/权限标识零变化；通用约定（R 结构 / HTTP 恒 200 + body.code / Long→String / 时间 `yyyy-MM-dd HH:mm:ss` / 错误码分段）沿用 data-permission-api §1
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对既有两契约）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | 保存规则 `POST /system/data-perm/rule` | 入参增量 `predicates`（条件三元组数组，null/[]=无条件）；校验链增 3035/3036/3037/1002 段 | **既有端点 additive**（§2.3） |
| 2 | 配置回显 `GET /system/data-perm/rule/config` | 出参增量 `predicates` + `predicateSummary` | **既有端点 additive**（§2.2） |
| 3 | 规则分页 `GET /system/data-perm/rule/page` | 行出参增量 `predicates` + `predicateSummary` | **既有端点 additive**（§2.1） |
| 4 | 资源注册表 `GET /system/data-perm/resources` | 出参增量 `filterColumns`（可过滤列双清单） | **既有端点 additive**（§2.4） |
| 5 | 模拟解释 `GET /system/data-perm/explain` | HitRuleVo 增量 `predicates` 回显；narratives 增条件句 | **既有端点 additive**（§2.5） |
| 6 | **my-scope `GET /system/data-perm/my-scope`** | **scopeLabel 新增第六态「条件范围（…）」，判定序 v2**（§6 全量重述）——**既有契约语义变更**：无条件配置五态文案与判定序逐字不变，新态仅在命中含条件规则时出现（存量 e2e 断言零维护） | **语义变更（显著标注）** |
| 7 | 服务间求值出参 `DataPermScopeVo`（component §5.1） | 增量 `rowGroups`（行级分组并集，行集判定唯一权威）；`accounts` 语义记档收窄（账号维并集，含条件时非行集语义） | **既有契约 additive**（§3） |
| 8 | leave 分页/详情、审批分页/详情/图数据 | 行级语义增量：条件谓词过滤 + detail 行属性判定（3026/4018 码不变）；**无条件规则行为零变化** | 行为变更（§4） |
| 9 | 错误码段 | system 段**占用 3035-3037**（现用至 3037，下轮 3038 接续）；bpmn 段零新增（仍至 4018） | 接续声明 |
| 10 | 留痕 | rule_digest 增 `∧条件段`、scope_summary 含条件时 `accounts=N;cond=K`（无条件三态逐字不变） | 数据面扩展（§1.6） |

## 1. 域语义增量（承接 data-permission-api §1 / component-api §1）

### 1.1 条件谓词（本轮核心）

- **三元组**：`{field（列）, operator（操作符）, values（值数组，统一字符串承载——Long→String 既有约定防精度）}`——规则的条件组，**组内多条件 AND**；与档位的关系为**行内 AND**（可见行 = 档位账号集合 ∧ 条件组全满足）
- **row_scope 语义细化（不增档位）**：`全部档 + 无条件 = 过滤豁免`（现状不变）；`全部档 + 条件 = 条件过滤后的全部行`（owner 无关授权，「只能看 id=1~100」即此配置）；非全部档 + 条件 = 账号集 ∧ 条件
- **跨规则并集（设计 D31）**：用户全部命中规则各自求值出各自行集取**并集**——多规则宽松者胜（D3）不变式保持：加规则永不收紧
- **谓词对象**：field 为 **entity 字段名**（camelCase，如 `leaveType`——注意与列级可配列的 VO 字段名锚点不同）；上限：条件数 ≤5/规则、IN 值 ≤30、STRING 值 ≤50 字符、同 field 至多一条

### 1.2 操作符封闭集（设计 D29）

| operator | 中文 | 值数 | 适用类型 |
|---|---|---|---|
| `EQ` | 等于 | 1 | NUMBER / STRING / DATE / DATETIME |
| `IN` | 属于 | 1~30 | NUMBER / STRING / DATE / DATETIME |
| `BETWEEN` | 介于 | 2（有序，闭区间） | NUMBER / DATE / DATETIME |
| `GTE` | 大于等于 | 1 | NUMBER / DATE / DATETIME |
| `LTE` | 小于等于 | 1 | NUMBER / DATE / DATETIME |

**明确不在值域**（配了 → 1002 操作符无效）：LIKE/前缀类一切模糊操作符（首版不做，回潮条件见设计 §11.1）、GT/LT/NE/IS-NULL。**STRING 禁 BETWEEN/GTE/LTE**（→ 3036）。

### 1.3 类型与值格式（校验依据，设计 D38）

| type | 可过滤列类型 | 值格式 | 示例 |
|---|---|---|---|
| `NUMBER` | BIGINT/TINYINT/INT | `^-?\d+(\.\d+)?$`（禁科学计数） | `["1"]` / `["1","100"]` |
| `STRING` | VARCHAR | 长度 ≤50；比较语义=大小写不敏感+尾随空格不敏感（对齐 MySQL utf8mb4_general_ci） | `["leave"]` |
| `DATE` | DATE | `yyyy-MM-dd` | `["2026-01-01"]` |
| `DATETIME` | DATETIME | `yyyy-MM-dd HH:mm:ss` | `["2026-01-01 00:00:00"]` |

**不校验字典值域**（如 leaveType=9 合法但永不命中——explain 可排查，记档）；BETWEEN 两值必须有序（v0 ≤ v1，同型比较）。

### 1.4 可过滤列双清单（设计 D32）

资源注册表自本轮起携带两清单，命名与锚点显式区分：`columns`（**可配列**——列级动作，VO 字段名，String only，既有语义零变化）与 `filterColumns`（**可过滤列**——行级条件，entity 字段名 + 裸 DB 列名 + 类型 + 中文名）。试点清单：

| 资源 | filterColumns（field / column / type / label） |
|---|---|
| `leave` | `id`/`id`/NUMBER/单号；`leaveType`/`leave_type`/NUMBER/请假类型；`startDate`/`start_date`/DATE/开始日期；`endDate`/`end_date`/DATE/结束日期 |
| `bpmn_approval` | `id`/`id`/NUMBER/审批单号；`status`/`status`/NUMBER/状态；`businessType`/`business_type`/STRING/业务类型；`createTime`/`create_time`/DATETIME/发起时间 |

**不含** apply_user（账号维专属列）、派生列（sys_leave 的投影 status）、title/reason（无 LIKE 下字符串精确匹配密度不足）。

### 1.5 detail 判定语义（设计 D34）

详情行级判定从「归属账号 ∈ 集合」升级为「任一组匹配 = (组为全部账号 或 归属 ∈ 组账号) 且 组条件全满足于该行」——行属性参与判定（如 ALL+`id BETWEEN` 规则下，单号 50 的他人单可见、单号 150 的本人单**不可见**）。行值判定规则：NULL 不满足任何谓词；NUMBER 数值比较；日期时间比较；STRING 大小写+尾随空格不敏感。3026/4018 错误码与 deny 留痕语义不变。

### 1.6 留痕与摘要格式（无条件零 diff，设计 D36）

- `rule_digest`：命中规则摘要追加 `∧{predDigest}`，predDigest 为 ASCII 紧凑型 `field符号值`（操作符符号 `=` `in` `~` `≥`→`>=` `≤`→`<=`，多谓词 `;` 连接），如 `role:主管(id=2)本部门及以下∧id:1~100;leave_type=1`；**IN 片段格式冻结（实现期回流补例 2026-10-11）：`field:v1,v2` 值逗号连接，如 `business_type:leave,oa`**——文法自洽（单值操作符直附 `field=v`/`field>=v`，多值操作符冒号引导：BETWEEN `v0~v1` / IN `v1,v2`），逗号与谓词分隔 `;`、规则分隔 `∧` 无冲突；500 截断不变
- `scope_summary`：无条件三态 `all` / `accounts=N` / `empty` **逐字不变**；任一命中规则含条件 → `accounts=N;cond=K`（N=账号维并集数，K=含条件规则数）
- 条件中文摘要（config/rule/my-scope 共用）：每谓词 `{列label}{操作符中文}{值}`（值原样配置值，IN 顿号连接、BETWEEN `v0~v1`），多谓词分号连接，如 `请假类型等于1` / `审批单号介于1~100;状态等于0`

## 2. 配置台端点变更（/system/data-perm，data-permission-api §3 逐节增量）

### 2.1 规则分页（§3.1 原文有效，出参增量）

`DataPermRuleVo` 增量字段：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| predicates | PredicateItem[] | 是 | 条件组配置原样（无条件 `[]`） | `[{"field":"leaveType","operator":"EQ","values":["1"]}]` |
| predicateSummary | string \| null | 是 | 条件中文摘要（后端单源生成，无条件 null） | `"请假类型等于1"` |

### 2.2 配置回显（§3.2 原文有效，出参增量）

`DataPermRuleConfigVo` 增量 `predicates: PredicateItem[]`（configured=false 时空数组）+ `predicateSummary: string|null`——前端弹窗据此回显条件段。

### 2.3 保存规则（§3.3 原文有效，入参增量 + 校验链扩展）

`DataPermRuleSaveRequest` 增量：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| predicates | PredicateItem[] | 否（null/[] = 无条件，档位即全量语义） | 条件组（组内 AND，与档位行内 AND）；条件数 ≤5 | 见 §5.1 |

PredicateItem：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| field | string | 是（不在资源可过滤列清单 → **3035**） | 列标识（entity 字段名，如 `leaveType`） |
| operator | string | 是（非枚举 name → 1002「操作符无效」） | `EQ` / `IN` / `BETWEEN` / `GTE` / `LTE` |
| values | string[] | 是 | 值数组（统一字符串）；EQ/GTE/LTE=1、BETWEEN=2（有序）、IN=1~30；格式/长度/有序违例 → **3037** |

- **校验链顺序（v2 全量）**：resource/columnKey 注册表 3034（既有）→ **逐条件：field ∈ filterColumns 3035 → operator 枚举 1002 → 操作符×类型矩阵 3036 → 值形态 3037 → 同 field 重复 1002「条件列重复」→ 条件数 ≤5 3037** → **规则级：predicates JSON 序列化长度 ≤2000 → 3037「条件组超长」（聚合上限，防超列宽 DB 500）** → 主体有效 3032（既有）→ CUSTOM 账号有效 3033/联动 1002（既有）
- **矩阵违例明细（3036）**：STRING × BETWEEN/GTE/LTE；操作符合法但与列 type 不匹配
- **值违例明细（3037）**：NUMBER 非纯十进制 / DATE 非 `yyyy-MM-dd` / DATETIME 非 `yyyy-MM-dd HH:mm:ss` / 值数量与操作符不符 / BETWEEN 无序 / STRING >50 字符 / 条件数 >5 / IN >30 / **predicates JSON 序列化长度 >2000（条件组超长——逐项上限外的聚合上限）**
- 行为：校验通过 → predicates JSON 串随行规则同行落库（upsert 全量覆盖语义不变——不传即清空条件）；列规则全删全插不变

### 2.4 资源注册表（§3.5 原文有效，出参增量）

`DataPermResourceVo` 增量：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| filterColumns | FilterColumnVo[] | 是 | 可过滤列清单（条件段「列」下拉与类型驱动输入器的数据源） | 见 §5.2 |

（`columns` 字段语义零变化——列级动作可配列。）

### 2.5 模拟解释（§3.8 原文有效，出参增量）

`DataPermExplainVo.HitRuleVo` 增量 `predicates: PredicateItem[]`（命中规则的条件配置回显，无条件 `[]`）；`narratives` 增每条件一句（如 `命中条件：请假类型 等于 1`），含条件时收敛结论句改述「行范围收敛：并集含条件过滤（N 条规则，K 条含条件）」。

## 3. 服务间求值出参变更（dataperm-component-api §5.1 增量——前端不消费）

`DataPermScopeVo` 增量字段：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| rowGroups | RowGroupVo[] | 是 | **行级分组并集（行集判定唯一权威，设计 D31/D35）**；rowAll=true 时 `[]`；无条件规则=单组无谓词 | 见 §5.3 |

- **`accounts` 字段语义收窄记档（既有字段，非删除）**：自本轮起 = 「账号维并集」——含条件规则时**不再是行集语义**（行集判定一律以 rowGroups 为准），仅供调试/摘要；rowAll/hiddenColumns/maskedColumns 语义零变化
- **消费方判定切换**：list SQL 与 detail 判定改用 `toRowFilter()`（组间 OR 并集、组内 IN+谓词 AND、值按 type 类型化）；fail-closed 语义零变化（求值失败整 ScopeVo 不可得即拒绝，谓词无单独降级分支）
- `RowGroupVo{allAccounts, accounts[], predicates[]}` / `PredicateVo{field, column, operator, type, values[]}` 字段表见 §5.3——**PredicateVo 自含 column/type**（消费方零注册表依赖，provider 经注册表声明 enrich）

## 4. 试点端点行为变更（无条件规则行为零变化——向后兼容红线）

### 4.1 leave 分页 `GET /system/leave/page`（data-permission-api §4.1 增量）

- 行级语义增量：命中规则含条件时，行集 = 各规则行集并集（账号组 OR 条件组）；`ALL 档+条件` 规则可放行**非本人发起**但满足条件的行（owner 无关授权）
- 其余（默认 SELF、空集空页、列级、fail 路径）零变化；出参零变化

### 4.2 leave 详情 `GET /system/leave/{id}`（§4.2 增量）

- 判定升级（§1.5）：单据存在 → 按行属性判定（组匹配=账号维 ∧ 条件维）→ 不通过 **3026**（不变）+ deny 补痕（不变）；单据不存在 3018（不变）

### 4.3 审批分页/详情/图数据（dataperm-component-api §3.1-§3.3 增量）

- 同款行级语义增量（含条件规则、owner 无关放行）；detail/diagram 判定升级，不通过 **4018**（不变）+ deny 补痕（不变）；fail-closed 1002（不变）；title 列级（不变）

## 5. VO 字段表

### 5.1 PredicateItem（§2.3 入参 / §2.1-§2.2/§2.5 出参共用）

| 字段 | 类型 | 必返/必填 | 说明 | 示例 |
|---|---|---|---|---|
| field | string | 是 | 列标识（entity 字段名） | `"leaveType"` |
| operator | string | 是 | 操作符（§1.2 封闭集） | `"EQ"` |
| values | string[] | 是 | 值数组（§1.3 格式） | `["1"]` |

### 5.2 FilterColumnVo（§2.4 出参）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| field | string | 是 | 列标识（entity 字段名——条件配置与回显的 key） | `"startDate"` |
| column | string | 是 | DB 列名（调试/排查参考，前端不消费） | `"start_date"` |
| type | string | 是 | `NUMBER` / `STRING` / `DATE` / `DATETIME`（操作符候选与值输入器的类型驱动依据） | `"DATE"` |
| label | string | 是 | 中文列名（下拉与摘要文案） | `"开始日期"` |

### 5.3 RowGroupVo / PredicateVo（§3 出参——服务间契约，前端不消费）

RowGroupVo：

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| allAccounts | boolean | 是 | true=组不限归属账号（ALL+条件组；此时 accounts `[]`） |
| accounts | string[] | 是 | 组账号白名单（allAccounts=true 时 `[]`；空数组+false=组恒假——求值器已丢弃死组，正常不出现） |
| predicates | PredicateVo[] | 是 | 组条件（组内 AND；无条件组 `[]`） |

PredicateVo：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| field | string | 是 | entity 字段名（detail 取值键） | `"id"` |
| column | string | 是 | 裸 DB 列名 | `"id"` |
| operator | string | 是 | 操作符 name | `"BETWEEN"` |
| type | string | 是 | 值类型（消费方类型化转换依据） | `"NUMBER"` |
| values | string[] | 是 | 值数组（字符串承载；类型化在消费方 toRowFilter 完成） | `["1","100"]` |

### 5.4 MyScopeVo（**结构零变化**）

`scopeLabel` 取值域扩展（§6）；`columnSummary` 零变化。

## 6. scopeLabel 判定序 v2（**既有契约语义变更，显著标注**——取代 data-permission-api §6.8 判定序）

**变更内容**：新增第三优先级「条件范围」态；**无条件配置时五态判定序与文案逐字不变**（新态仅命中含条件规则时出现——存量 e2e 断言零维护，设计 D36/D40）。

1. 行范围豁免（任一 **ALL 档无条件** 规则命中）→ `全部`
2. 行范围空集（无任何可匹配组）→ `无（空范围）`
3. **任一命中规则含条件（predicates 非空）→ `条件范围（{条件中文摘要}）`**（摘要=全部命中条件规则的谓词摘要分号连接，§1.6 格式，如 `条件范围（审批单号介于1~100;状态等于0）`）
4. 命中任一部门档（DEPT/DEPT_AND_CHILD）→ `指定范围（N 人）`（N=1 亦然，既有口径）
5. 命中任一 CUSTOM 档 → 含自己 `自定义范围（含自己，共 N 人）`；不含 `指定范围（N 人）`
6. 兜底（只剩 SELF 档或无规则）→ `仅自己`

判定哲学不变（沿主控 2026-10-10 裁定）：scopeLabel 是排查锚点，反映「配置了什么」——条件态优先于账号档位标签（条件授权最特异、最需锚点；含条件时「N 人」不再能描述行集）。

## 7. 错误码总表（system 段本轮占用 3035-3037）

| 码 | 文案（`{x}` 为动态值） | 触发点 |
|---|---|---|
| 3035 | 无效的过滤条件列: {field} | §2.3 save——field 不在资源 filterColumns |
| 3036 | 条件操作符与列类型不匹配: {field}:{operator} | §2.3 save——矩阵违例（如 STRING×BETWEEN） |
| 3037 | 条件值非法: {detail} | §2.3 save——值格式/数量/有序/长度/条件数/IN 数量/条件组序列化超长（>2000） |

1002 通用文案新增：`操作符无效`（operator 非 `EQ|IN|BETWEEN|GTE|LTE`）、`条件列重复`（同 field 多条）。既有 3026-3034 语义零变化；bpmn 段零新增。

## 8. 前端类型（types/api.ts 增量，函数名动词开头、JSDoc 注节号）

- 类型：`PredicateItem`（§5.1）、`FilterColumnVo`（§5.2）；`DataPermRuleVo/DataPermRuleConfigVo/HitRuleVo` 增量 `predicates?/predicateSummary?`；`DataPermResourceVo` 增量 `filterColumns`
- 常量（禁魔法数/散落字面量）：`OPERATOR_EQ='EQ' / IN / BETWEEN / GTE / LTE`、`FILTER_TYPE_NUMBER='NUMBER' / STRING / DATE / DATETIME`、条件上限 `PREDICATE_MAX=5 / IN_VALUE_MAX=30 / STRING_VALUE_MAX=50`；操作符候选表 `OPERATOR_OPTIONS`（含按 type 过滤函数——矩阵前端镜像）；操作符中文映射（与后端 label 对齐，**双处冻结点记档**：契约 §1.2 与前端常量，任一变更即契约变更）
- api 模块 `api/dataPerm.ts` 零新函数（saveRule/getRuleConfig/pageRule/listResources 复用，payload 增量自然携带）；JSDoc 补 §2 节号

## 9. 验收口径（联调与 e2e 共同遵守）

- **owner 无关证明**：用户 U 配角色 `leave/ALL + leaveType=1` → U 的列表可见**他人**事假、不可见病假；detail 他人事假单可见、病假单 3026
- **行内 AND 证明**：`leave/本部门 + leaveType=1` → 仅本部门事假可见（部门成员的病假不可见）
- **并集不变式证明（D3）**：U 同时命中 `部门档无条件` 与 `ALL+条件` 两规则 → 行集 = 部门全部单（含病假）∪ 全部事假——加条件规则未收紧部门档可见性
- **detail 行属性判定**：ALL+`id BETWEEN a~b` 规则下，id∈[a,b] 的他人单可见、id∉[a,b] 的**本人**单 3026/4018
- **跨服务**：bpmn `id BETWEEN`/`status EQ`/`businessType IN`/`createTime GTE` 四型谓词各一例；detail/diagram 4018；fail-closed（停 system 拒绝）复证
- **可观察**：my-scope `条件范围（…）`标签与行集一致；explain 条件句与命中规则回显；留痕 digest `∧` 段与 `accounts=N;cond=K`
- **向后兼容红线**：清空全部条件规则后，三账号矩阵/my-scope 五态/留痕三态与 v1 行为**逐字一致**；既有 e2e:dataperm / e2e:dataperm-component / e2e:bpmn 零回归
- 错误码 3035/3036/3037/1002（操作符无效/条件列重复）逐个触发
