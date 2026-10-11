# 数据权限 L2 谓词操作符扩展 API 契约（九操作符 + title 双注册 + 字典翻译）

- 日期：2026-10-11；设计 `docs/superpowers/specs/2026-10-11-dataperm-operators-design.md`（D41-D50）；计划 `docs/superpowers/plans/2026-10-11-dataperm-operators.md`
- **承接关系**：本契约是 `2026-10-11-dataperm-predicate-api.md`（下称 R7）的**增量契约**——R7 文件零改动仍有效，本文仅列变更面；未提及条目一律沿 R7 与其上游 `2026-10-10-data-permission-api.md` / `2026-10-10-dataperm-component-api.md` 原文。实现与联调以「R7 原文 + 本契约变更面」的合并视图为准
- **端点面零新增**：全部复用既有端点（配置台 save/config/page/resources/explain/my-scope、leave/审批读写、/inner 求值）——本轮是**值域与出参字段 additive 扩容**，无新路径、无新方法

## 0. 变更点清单（相对 R7——冻结句变更全部显式列出）

| # | 面 | 变更 | R7 出处 |
|---|---|---|---|
| C1 | 操作符封闭集 | 五 → **九**（+GT/LT/NE/PREFIX）；**R7 §1.2「明确不在值域」冻结句废止**：`GT/LT/NE` 解禁、`前缀类模糊` 解禁右前缀一枚（PREFIX）；LIKE 中缀/后缀与 IS-NULL 仍禁 | §1.2 → 本文 §1.1 |
| C2 | 类型矩阵 | 终态：NUMBER/DATE/DATETIME×8（+GT/LT/NE）；STRING×4（EQ/IN+**NE/PREFIX**）——R7 §1.2「STRING 禁 BETWEEN/GTE/LTE」扩为「STRING 禁 BETWEEN/GTE/LTE/GT/LT」；新增「非 STRING 禁 PREFIX」 | §1.2 → 本文 §1.1 |
| C3 | 可过滤列清单 | 双清单各 +`title`（STRING/标题/无字典）；**R7 §1.4「不含 …title/reason」冻结句半废止**：title 进清单（PREFIX 兑现后前提失效），reason 仍不含 | §1.4 → 本文 §1.4 |
| C4 | FilterColumnVo | +`dictKey`（additive，可空）——配置台前端字典下拉依据 | §5.2 → 本文 §5.2 |
| C5 | digest 符号 | 新增 `>` `<` `!=` `^` 四符号（GT/LT/NE/PREFIX）；既有五符号与文法逐字不变 | §1.6 → 本文 §1.6 |
| C6 | **条件中文摘要值译文（冻结句变更）** | 字典列（dictKey 非空）的值文本自本轮起显示字典标签：R7 §1.6 示例 `请假类型等于1` → `请假类型等于事假`；narratives 同（`命中条件：请假类型 等于 1` → `… 等于 事假`）。**非字典列逐字不变**；digest（rule_digest ∧ 段）**不翻译恒为原值** | §1.6 → 本文 §1.6 |
| C7 | PredicateItem/PredicateVo | 结构零变化；`operator` 值域扩至九 name（additive） | §5.1/§5.3 → 本文 §3/§5 |
| C8 | 校验链 | v2 → v3：矩阵与值规则**内容**扩容（形态与错误码零变化） | §2.3 → 本文 §2.3 |

**零变更红线**（与设计 D50 对齐）：无条件规则与仅旧操作符规则的全链行为、wire 结构、ScopeVo/RowGroupVo/PredicateVo/MyScopeVo 结构、端点路径与权限标识、错误码数值与文案、既有 digest/summary 格式（非字典列）、三既有 e2e 脚本断言。

## 1. 域语义变更（承接 R7 §1）

### 1.1 操作符封闭集 v2（设计 D41——取代 R7 §1.2 表与「明确不在值域」句）

| operator | 中文 | digest 符号 | 值数 | 适用类型 |
|---|---|---|---|---|
| `EQ` | 等于 | `=` | 1 | NUMBER / STRING / DATE / DATETIME |
| `IN` | 属于 | `:`（值逗号连接） | 1~30 | NUMBER / STRING / DATE / DATETIME |
| `BETWEEN` | 介于 | `:`（`v0~v1` 闭区间） | 2（有序） | NUMBER / DATE / DATETIME |
| `GTE` | 大于等于 | `>=` | 1 | NUMBER / DATE / DATETIME |
| `LTE` | 小于等于 | `<=` | 1 | NUMBER / DATE / DATETIME |
| **`GT`** | **大于** | **`>`** | 1 | NUMBER / DATE / DATETIME |
| **`LT`** | **小于** | **`<`** | 1 | NUMBER / DATE / DATETIME |
| **`NE`** | **不等于** | **`!=`** | 1 | NUMBER / STRING / DATE / DATETIME |
| **`PREFIX`** | **前缀** | **`^`** | 1 | STRING（右模糊 `x%`） |

- **矩阵终态**（校验 3036 与前端镜像的唯一依据）：NUMBER/DATE/DATETIME 开放除 PREFIX 外全部 8 种；STRING 开放 EQ/IN/NE/PREFIX 4 种
- **明确不在值域**（→ 1002）：LIKE/中缀后缀模糊（`%x`/`%x%`）、REGEXP、IS-NULL
- **PREFIX 语义与转义**（设计 D43，MySQL 5.7.24 探针实证）：行匹配 = `column LIKE '{前缀}%'`，前缀值中的 `%`/`_`/`|` 一律**字面**（SQL 侧三层 REPLACE 竖线转义链，`|`→`||` doubling 先行）；值内反斜杠是普通字符（无需转义）；大小写不敏感（ci）；**尾随空格敏感**（LIKE 非 PAD SPACE，与 EQ/IN 的尾空格不敏感刻意不同——探针 P2-P5）。前端输入器提交时 trimEnd（软规范，后端不校验）
- **GT/LT/NE 语义**（设计 D44）：严格不等（开边界——GT 边界值不命中，与 GTE 差异）；NE=对应 EQ 的求反；**NULL 恒不满足任何谓词（含 NE）**（探针 P7）

### 1.2 类型与值格式（R7 §1.3 增量）

R7 §1.3 表全部有效。增量：`GT/LT/NE/PREFIX 值数=1`；PREFIX 值即 STRING 规则（≤50 字符，**不禁** `%`/`_`/`|`——转义消解而非拒绝）；不校验字典值域口径不变（下拉软约束，D38）。

### 1.3 detail 判定语义增量（R7 §1.5 增量，设计 D43/D44）

R7 §1.5 全部有效。行值判定新增：GT/LT 严格比较（NUMBER BigDecimal compareTo / 时间 isAfter/isBefore 同构）；NE 求反（STRING×NE = ci+尾空格不敏感等值的求反）；PREFIX = startsWithIgnoreCase **双侧不 stripTrailing**（对齐 LIKE 非 PAD SPACE，D34 新增规则⑥——与规则④的尾空格不敏感刻意不对称，实证记档设计 §2.2）。3026/4018 与 deny 留痕语义不变。

### 1.4 可过滤列双清单 v2（设计 D42——取代 R7 §1.4 清单表；`columns` 可配列清单零变化）

| 资源 | filterColumns（field / column / type / label / **dictKey**） |
|---|---|
| `leave` | `id`/`id`/NUMBER/单号/null；`leaveType`/`leave_type`/NUMBER/请假类型/**system_leave_type**；`startDate`/`start_date`/DATE/开始日期/null；`endDate`/`end_date`/DATE/结束日期/null；**`title`/`title`/STRING/标题/null** |
| `bpmn_approval` | `id`/`id`/NUMBER/审批单号/null；`status`/`status`/NUMBER/状态/**bpmn_approval_status**；`businessType`/`business_type`/STRING/业务类型/null；`createTime`/`create_time`/DATETIME/发起时间/null；**`title`/`title`/STRING/标题/null** |

- **dictKey**（新增列属性，D45）：非空 = 值域有字典（摘要/narratives 值译为标签；前端值输入器用字典下拉）；null = 原值显示与既有输入器。种子字典键与 `sys_dict_type` 现存值精确对齐（`system_leave_type` 1事假/2病假/3年假；`bpmn_approval_status` 0审批中/1已通过/2已拒绝/3已撤销/4发起失败）
- 仍不含 apply_user（账号维专属列）与派生列；**reason 仍不含**（长文本列无过滤价值）；title 进清单依据 = PREFIX 兑现（R7 排除前提失效）

### 1.5 留痕与摘要格式 v2（R7 §1.6 增量——C5/C6 两处冻结句变更）

- `rule_digest` ∧ 段：新增四符号——GT `{column}>{v}`、LT `{column}<{v}`、NE `{column}!={v}`、PREFIX `{column}^{v}`（如 `∧id>100`、`∧title^e2e`、`∧leave_type!=2`）；**值恒为配置原值（字典列也不翻译）**——digest 是机器面向审计格式；文法（单值直附/多值冒号引导、`;` 谓词分隔、`∧` 规则分隔、500 截断）不变
- `scope_summary`：三态 + `accounts=N;cond=K` 逐字不变（条件态判定与操作符种类无关）
- **条件中文摘要（config/rule/my-scope 共用）值文本译文规则**：谓词值所属列 dictKey 非空 → 显示字典标签（译文缺失回退原值）；dictKey=null → 原值。新操作符摘要形态：`标题前缀e2e`、`审批单号大于100`、`请假类型不等于事假`（NE 值也译）；**R7 示例 `请假类型等于1` 自本轮起产出 `请假类型等于事假`**（e2e 断言计划内维护）；非字典列示例不变（`业务类型属于leave` 守恒）
- narratives 条件句同规则：`命中条件：标题 前缀 e2e` / `命中条件：请假类型 等于 事假`

## 2. 配置台端点变更（R7 §2 各节原文有效，增量如下）

### 2.1-2.2 / 2.5 规则分页 / 配置回显 / 模拟解释（R7 §2.1/§2.2/§2.5 原文有效）

出参结构与字段零变化；`predicates` 可含新 operator name；`predicateSummary`/narratives 条件句按本文 §1.5 译文规则与新操作符中文（大于/小于/不等于/前缀）生成。

### 2.3 保存规则（R7 §2.3 原文有效；**校验链 v3 全量重述**——结构与 R7 v2 相同，矩阵与值规则内容扩容）

- **校验链顺序 v3**：resource/columnKey 注册表 **3034** → **逐条件：field ∈ filterColumns 3035（title 自本轮可配）→ operator 枚举 1002（九 name，非法 → 「操作符无效」）→ 操作符×类型矩阵 3036 → 值形态 3037 → 同 field 重复 1002「条件列重复」→ 条件数 ≤5 3037** → **规则级：predicates JSON 序列化 ≤2000 → 3037「条件组超长」** → 主体有效 3032 → CUSTOM 账号有效 3033/联动 1002
- **矩阵违例明细（3036）终态**：STRING × {BETWEEN, GTE, LTE, **GT, LT**}；{NUMBER, DATE, DATETIME} × **PREFIX**
- **值违例明细（3037）增量**：值数量与操作符不符（现含 GT/LT/NE/PREFIX ≠1 值）；PREFIX 值 >50 字符；其余（NUMBER 格式/DATE 格式/BETWEEN 无序/IN>30/条件数>5/序列化>2000）逐字不变
- 行为（upsert 全量覆盖、不传即清空、列规则全删全插）零变化

### 2.4 资源注册表（R7 §2.4 原文有效，出参增量）

`DataPermResourceVo.filterColumns` 每项为 FilterColumnVo v2（+dictKey，见 §5.2）；leave/bpmn_approval 各 +title 项（§1.4）。

## 3. 服务间求值出参变更（R7 §3 原文有效——前端不消费）

`DataPermScopeVo` / `RowGroupVo` / `PredicateVo` **结构零变化**；`PredicateVo.operator` 值域扩至九 name，`values` 可承载新操作符值形态（类型化语义见 §1.1/§1.3）。`toRowFilter()` 消费方路径与 fail-closed 语义零变化（list SQL/detail 判定对新操作符的分支由消费方 mapper XML 与 api 模块 RowPredicate.test() 承载——升级时序见 §6）。

## 4. 试点端点行为变更（R7 §4 红线不变：无条件规则行为零变化）

- **leave 分页/详情（R7 §4.1/§4.2 增量）**：title 谓词（EQ/IN/NE/PREFIX）参与行集与 detail 判定——如 `ALL + title PREFIX 'e2e'` 规则下，标题以 e2e 开头的**他人**单可见；`leaveType NE 2` 规则下病假单不可见（含本人）；`id GT x` 边界严格（=x 不命中）。3026/3018/deny 留痕不变
- **审批分页/详情/图数据（R7 §4.3 增量）**：同款（title 谓词 + status GT/LT/NE + createTime GT/LT + businessType NE + id GT/LT）；4018/deny/fail-closed 1002 不变
- 出参结构零变化

## 5. VO 字段表

### 5.1 PredicateItem（R7 §5.1 表逐字有效）

`operator` 说明行值域更新为本文 §1.1 九 name（结构零变化）。

### 5.2 FilterColumnVo v2（R7 §5.2 增量——**+dictKey additive**）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| field | string | 是 | 列标识（entity 字段名） | `"leaveType"` |
| column | string | 是 | DB 列名（前端不消费） | `"leave_type"` |
| type | string | 是 | `NUMBER` / `STRING` / `DATE` / `DATETIME` | `"NUMBER"` |
| label | string | 是 | 中文列名 | `"请假类型"` |
| **dictKey** | string \| null | 是 | 值域字典键（非空=值输入器用字典下拉、摘要值译标签）；null=无字典 | `"system_leave_type"` |

### 5.3 RowGroupVo / PredicateVo / MyScopeVo（R7 §5.3/§5.4 逐字有效——零结构变化）

## 6. 兼容性与升级时序（设计 D50——跨服务 jar 时间窗）

- **旧 consumer × 新 provider**（system 先行升级、bpmn 仍用 R7 版 system-api jar）：provider 产出的含 `GT/LT/NE/PREFIX` 的 PredicateVo 在旧 jar `RowPredicate.test()` 中操作符解析失败 → 恒 false → 该组不满足——**fail-closed 安全方向（不放大授权）**，表现为「新操作符规则暂不生效」；bpmn 升级 system-api jar 后生效。**不要求同步发版**；旧操作符规则全程不受影响
- **新 consumer × 旧 provider**：同恒 false（安全方向）
- 求值期降级（坏 JSON/越界字段/归一失败 → 该规则整体不授权）对新操作符天然适用（R7 语义零变化）

## 7. 错误码（零新增——system 段仍至 3037，下轮 3038；bpmn 段仍至 4018）

| 码 | 文案 | 本轮触发面扩容 |
|---|---|---|
| 1002 | 操作符无效 | operator 非 `EQ\|IN\|BETWEEN\|GTE\|LTE\|GT\|LT\|NE\|PREFIX`（九 name 外） |
| 3036 | 条件操作符与列类型不匹配: {field}:{operator} | §1.1 矩阵终态违例（含 STRING×GT/LT、任意类型×PREFIX） |
| 3037 | 条件值非法: {detail} | 值数≠1（GT/LT/NE/PREFIX）等 §2.3 明细 |

## 8. 前端类型（types/api.ts 增量——双处冻结点更新：本文 §1.1 与前端常量）

- 常量增量：`OPERATOR_GT='GT' / OPERATOR_LT='LT' / OPERATOR_NE='NE' / OPERATOR_PREFIX='PREFIX'`；`OPERATOR_OPTIONS` +4 项（大于/小于/不等于/前缀）；`operatorsForType` 矩阵镜像 v2（NUMBER/DATE/DATETIME 八操作符、STRING 四操作符）
- `FilterColumnVo` +`dictKey: string | null`；`PredicateOperator` 联合类型扩九值
- RuleConfigDialog 值输入器分支 v2：**dictKey 非空列** → `el-select`（数据源既有 `getDictItems(dictKey)`；EQ/NE/GTE/GT/LTE 单选、IN 多选、BETWEEN 双选有序；filterable + allow-create 兜底存量原值）；**PREFIX（非字典 STRING 列）** → 单行文本输入器，提交时 `trimEnd`；其余沿类型驱动既有输入器。api 模块零新函数
- JSDoc 注节号随本文更新（§1.1/§2.3/§5.2）

## 9. 验收口径（联调与 e2e 共同遵守，沿 R7 §9 全部条目 + 增量）

- **PREFIX owner 无关证明**：U 配 `leave/ALL + title PREFIX 'e2e'` → U 可见**他人**标题以 e2e 开头的单；e2e 前缀外单（含本人）不可见
- **PREFIX 转义证明**：造单标题 `e2e%x`/`e2e_x`/`e2e|x` 三单，配 PREFIX `e2e%` → 仅 `e2e%x` 可见（`%` 字面，未转义则会全命中=过度授权）；PREFIX `e2e_` 同款
- **NE 证明**：`leave/ALL + leaveType NE 2` → 病假（含他人与自己）全部不可见；`bpmn/businessType NE 'leave'` 对称
- **GT/LT 边界严格证明**：`id GT x` 规则下 id=x 的单不可见、id=x+1 可见（与 GTE 差异）
- **字典译文证明**：`leaveType EQ 1` 规则 → predicateSummary=`请假类型等于事假`、my-scope=`条件范围（请假类型等于事假）`、narratives=`命中条件：请假类型 等于 事假`；**digest 仍 `∧leave_type=1`（原值）**；businessType（无字典）摘要仍原值
- **校验链**：STRING×GT→3036、NUMBER×PREFIX→3036、GT 双值→3037、PREFIX 值>50→3037、`LIKE`→1002
- **兼容红线**：清空全部新操作符/字典列规则 → 三账号矩阵、摘要、留痕与 R7 终态**逐字一致**；既有 e2e:dataperm / e2e:dataperm-component / e2e:bpmn 零回归；run-dataperm-predicate-e2e.mjs 仅计划内 7 处译文断言维护（predicates JSON/digest/businessType 断言零维护）
