# 数据权限 L2 谓词操作符扩展（PREFIX/GT/LT/NE + 字典翻译）技术方案（全栈 A 级）

- 日期：2026-10-11
- 状态：**定稿**——架构-agent 出品，配合拍板结论（Round 8 阶段一《需求分析》七问，2026-10-11）；契约 `docs/superpowers/contracts/2026-10-11-dataperm-operators-api.md`、计划 `docs/superpowers/plans/2026-10-11-dataperm-operators.md`
- 拍板结论映射：① **范围单轮**做操作符扩展（PREFIX + GT/LT/NE）+ 字典翻译 + 索引复审记档 + 错误码记账；SQL 膨胀治理与规则缓存**复审记档不实施**（论证全文落 §11）；② **PREFIX 转义采 SQL 侧 REPLACE 链 + 显式 ESCAPE 子句**（探针实证后选竖线转义字符，见 D43）；③ **STRING×NE 开放**——STRING 矩阵终态 EQ/IN/NE/PREFIX；④ **title 双资源注册**（leave + bpmn_approval 各增 STRING 可过滤列）
- 无分歧项按倾向确认记档：字典翻译落层 A（FilterColumn 增 dictKey、system 侧翻译主体、FilterColumnVo +dictKey 供前端下拉、PredicateVo 不扩、digest 原值、硬值域校验不做）；SQL 膨胀维持显式硬编码不放宽守护；规则缓存记档缓做；索引零 DDL；错误码零新增
- **与既有设计的关系**：本文档是 `2026-10-10-data-permission-design.md`（D1-D14）、`2026-10-10-dataperm-registry-refactor-design.md`（D15-D17）、`2026-10-10-dataperm-component-design.md`（D18-D28）、`2026-10-11-dataperm-predicate-design.md`（D29-D40）的**操作符扩展轮**——D1-D40 全部有效，决策编号 **D41-D50 接续**；D29 封闭集扩为九枚举（additive，§11.1/§11.2 回潮条件兑现）、D32 可过滤列清单增 title 两行（当年「无 LIKE 下排除 title」前提失效）、D34 对齐规则增 PREFIX/NE 两条（探针实证）；D3/D5/D31/D33 代数与显式哲学零触碰
- **MySQL 5.7.24 探针前置已执行**（2026-10-11，java 单文件源码 + mysql-connector-j 8.0.33 直连 127.0.0.1:3306，cloud_system/cloud_bpmn 两库）：LIKE 尾随空格 / NULL×!= / ESCAPE 语法与 REPLACE 链双层转义，实测结论全文见 §2.2 与 D43/D44——**test() 对齐规则以实证为准**

## 1. 需求与范围

### 1.1 目标

兑现谓词轮移交备忘：① PREFIX 右模糊操作符（「标题/编号前缀分权」场景，LIKE 首版不做的回潮）；② GT/LT/NE 三操作符（比较族补全 + 排除值）；③ 条件值字典翻译（leaveType=1 在摘要/第六态/narratives 显示「事假」而非数字）；④ 索引复审记档；⑦ 错误码记账。操作符封闭集自五枚举扩至 **EQ/IN/BETWEEN/GTE/LTE/GT/LT/NE/PREFIX 九枚举**。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| 操作符封闭集 v2 | 九枚举：+GT(`>`)/LT(`<`)/NE(`!=`)/PREFIX(`^`)；类型矩阵终态 NUMBER/DATE/DATETIME×8、STRING×4（D41） |
| title 双注册 | leave.title + bpmn_approval.title 进 filterColumns（STRING/标题/无字典）；title 整列随矩阵开放 EQ/IN/NE/PREFIX（D42） |
| PREFIX 转义 | SQL 侧三层 REPLACE 链 + 显式 `ESCAPE '\|'`（竖线转义字符，探针实证选型）；test()=startsWithIgnoreCase **不 stripTrailing**（LIKE 非 PAD SPACE 实证，与 D34 规则④不对称记档）（D43） |
| GT/LT/NE 对齐 | 归一比较族严格比较；NE=等值求反（STRING×NE 为规则④ ci+stripTrailing 求反）；NULL 恒 false 实证（D44） |
| 字典翻译 | FilterColumn record +dictKey（leaveType→system_leave_type、status→bpmn_approval_status，种子键实核）；system 侧 predicateSummary/第六态摘要/narratives 值翻译（digest 原值）；FilterColumnVo +dictKey 供前端字典下拉（既有 getDictItems 端点）（D45） |
| 校验链 v3 | 矩阵/值规则复用 3036/3037/1002，零新码（D46） |
| XML 增量 | leave 20→36 分支、bpmn 17→32 分支（全为新 when 插入，既有分支零触碰）+ 注释规范（D47） |
| 复审记档 | 索引零 DDL（D48）、规则缓存覆核论证全文（D49）——均不实施 |

### 1.3 本轮不落（红线 + 记档）

**零变更红线**：无条件规则与仅旧操作符规则的全链行为零变化（XML 既有 when 零触碰、digest/summary 对存量**非字典列**逐字不变）；DDL 零变更（sys_data_perm_rule.predicates 列已在）；PredicateVo/RowGroupVo/ScopeVo 结构零变化（operator 值域 additive 扩）；网关/sso/InnerDataPermController 零改动；守护测试零改动（`$` 禁令不放宽）。**明确不落**：中缀/后缀模糊（`%x`/`%x%`，D29「模糊操作符整体不做」排除面仅开右前缀一道口）、OR 组合/嵌套表达式（L3 红线永久不做）、IS-NULL（试点列全 NOT NULL 未变）、字典硬值域校验（下拉软约束，D38 口径不变）、digest 翻译（机器面向原值）、SQL 膨胀治理与规则缓存（记档，§11）。

## 2. 现状核实（2026-10-11 逐项实读 + 探针实证）

### 2.1 代码面核实

1. **PredicateOperatorEnum**（api 模块）：五枚举 + `getArity()` switch + `supports()`（STRING 仅 EQ/IN）——加枚举后 `RowPredicate.testNumber/testDate/testDateTime/testString` 与 Evaluator `digestPredicate` 的 switch 穷尽性编译错 = **编译器强制全触点更新**（additive 安全网，不存在漏改分支的运行期风险）
2. **testString 防御形态**：现 `op != EQ && op != IN → false` 前置短路——STRING 矩阵扩 NE/PREFIX 后改写为 switch 四 case + default false（BETWEEN 等矩阵外组合防御不变）
3. **FilterColumn 是 record**：加 dictKey 组件=构造器签名变化，构造点仅 DataPermResources static 块两行（10 个 FilterColumn）+ api 测试 fixture——编译面强制，无反射/XML 消费（wire 模型是 FilterColumnVo，两界分离，D32 既有形态）
4. **翻译消费点四处实核**：`DataPermConvert.toPredicateSummary`（摘要共用：fillPredicates 规则页/L574 config 回显/conditionalScopeText 第六态）+ `DataPermEvaluator.predicateNarrative`（explain narratives「命中条件：…」）；digest 生成点 `Evaluator.digestPredicate` switch（值原样，不翻译）；`TranslationCacheService.findDictLabels(String dictKey, Collection<String> values) → Map<String,String>` 既有（translate-starter，Redis 30min TTL fail-open），cloud-system 已依赖该 starter（pom L48）
5. **种子字典键实核**（cloud_system.sql L396-407）：`system_leave_type`（1事假 2病假 3年假）、`bpmn_approval_status`（0审批中 1已通过 2已拒绝 3已撤销 4发起失败）——与可过滤列 leaveType/status 精确对齐；businessType/title/日期数值列无字典（dictKey=null，保持原值显示）
6. **XML 分支现状**：leave 4 列×5=20、bpmn 17（id5+status5+businessType2+createTime5）；分支块逐列 `<when test="p.field == 'xxx'">` 包操作符内层 choose——新操作符=各列块内插新 when，既有 when 零触碰
7. **enrichment 零变化**：Evaluator 单条件 enrichment（findFilterColumn→of()→值类型化）对新操作符天然工作（PREFIX 值走 STRING 归一 ≤50）；矩阵外脏数据由 XML `otherwise 1=0` 与 test() default false 双层纵深防御（现状机制）
8. **错误码账本**：system 段现用至 3037——本轮零新增（3036 矩阵/3037 值/1002 枚举全复用）；bpmn 段仍至 4018
9. **e2e 冻结点实核**（run-dataperm-predicate-e2e.mjs）：predicates JSON 回显断言（L362/376/466/480）零影响（wire 原值）；**摘要译文断言 7 处需维护**（L364/378 predicateSummary、L405/411/441/445 scopeLabel、L469 narratives——「请假类型等于1」→「等于事假」）；digest 断言（`∧leave_type=1` 等）零影响（原值）；businessType 无字典断言（L587「业务类型属于leave」/L600「属于LEAVE」）**天然成为「非字典列保持原值」的守恒断言**，零维护

### 2.2 MySQL 5.7.24 探针实测（2026-10-11，实证记录——对齐规则的唯一依据）

环境：`@@version=5.7.24`；`@@sql_mode` 含 STRICT 系列、**无 NO_BACKSLASH_ESCAPES**（字面量反斜杠转义生效）；两库 `@@collation_database=utf8mb4_general_ci`；`sys_leave.title` 与 `cloud_bpmn.bpmn_approval.title` 均 `varchar(100) utf8mb4_general_ci`。

| # | 探针 | 实测 | 结论 |
|---|---|---|---|
| P1 | `'abc' = 'abc '`、`'abc' = 'ABC '` | 1、1 | `=` 是 PAD SPACE + ci（D34 规则④基线复证） |
| P2 | `'abc' LIKE 'abc '`（模式尾空格） | **0** | **LIKE 非 PAD SPACE**：模式尾随空格显著（与 `=` 相反） |
| P3 | `'abc ' LIKE 'abc'`（值尾空格） | **0** | 值侧尾随空格同样显著 |
| P4 | `'abc ' LIKE 'abc%'` | 1 | 右模糊下值尾空格被 `%` 吸收——前缀语义不受值侧尾空格影响 |
| P5 | `'abc' LIKE 'abc %'` | 0 | **配置前缀值内的尾随空格是字面**（前缀含空格=窄化条件） |
| P6 | `'ABC' LIKE 'abc%'` | 1 | LIKE ci 实证 |
| P7 | `NULL != 1`、`NULL <> 1` | NULL、NULL | NE 对 NULL 不命中——与 test() null→false 对齐（fail-closed） |
| P8 | `'abc' != 'abc '`、`'abc' <> 'ABC'` | 0、0 | NE = ci+PAD SPACE 等值的求反——STRING×NE 对齐规则④求反 |
| P9 | 字面量 `LIKE '50\%off%'`（默认反斜杠转义） | 命中/不命中正确 | 反斜杠转义在无 NO_BACKSLASH_ESCAPES 下可用，但依赖 sql_mode |
| P10 | `LIKE '50\|%off\|%' ESCAPE '\|'`（竖线字面量） | 命中/不命中正确 | 竖线 ESCAPE 子句 5.7.24 有效 |
| P11 | **PreparedStatement 参数 + 竖线 REPLACE 链** `REPLACE(REPLACE(REPLACE(?, '\|', '\|\|'), '%', '\|%'), '_', '\|_')` + `ESCAPE '\|'` | 9 组全过：% 字面（50%off 命中/50Xoff 不命中）、_ 字面、`\|` doubling、**反斜杠原样字面无需转义**、ci、尾空格前缀 | **生产形态实证**：竖线链全形态通过且反斜杠零参与 |
| P12 | 对照：参数 + 反斜杠链 `REPLACE(REPLACE(REPLACE(?, '\\\\', '\\\\\\\\'), '%', '\\\\%'), '_', '\\\\_') ESCAPE '\\\\'` | 4 组通过（功能等价） | 可用但 wire 字面量需 `'\\'`/`'\\\\'` 双层——XML 可读性与 sql_mode 依赖均劣（弃，见 D43） |

## 3. 数据设计

**零 DDL、零新表、零新索引、零种子**。sys_data_perm_rule.predicates 列承载不变（JSON 三元组 values 字符串数组，新操作符 operator name 与新值形态天然承载）；title 不是新列（既有业务列注册进可过滤清单）；索引复审结论见 §9/D48。

## 4. 设计决策（D41-D50，接续 D1-D40）

### D41 操作符封闭集 v2：九枚举与类型矩阵终态

- **枚举增量**（api 模块 PredicateOperatorEnum，additive 追加于既有五枚举后）：

| 枚举 | digest 符号 | 中文 label | arity | 说明 |
|---|---|---|---|---|
| `GT(">")` | `>` | 大于 | 1 | 严格不等（开区间单边） |
| `LT("<")` | `<` | 小于 | 1 | 严格不等 |
| `NE("!=")` | `!=` | 不等于 | 1 | 等值求反（排除值） |
| `PREFIX("^")` | `^` | 前缀 | 1 | 右模糊（`LIKE 'x%'`）；符号取正则起始锚 `^` 语义，与既有 `=`/`in`/`~`/`>=`/`<=`/`>`/`<`/`!=` 全体无冲突 |

- **类型矩阵终态**（`supports()` v2，校验 3036 与前端镜像的唯一依据）：

| 类型 | 开放操作符 | 不开放（→ 3036） |
|---|---|---|
| NUMBER / DATE / DATETIME | EQ、IN、BETWEEN、GTE、LTE、**GT、LT、NE**（8） | PREFIX（数值/日期「前缀」是字符串化比较，无索引价值语义怪异） |
| STRING | EQ、IN、**NE、PREFIX**（4） | BETWEEN、GTE、LTE、GT、LT（ci 排序下字符串大小比较语义不稳，D29 口径不变） |

- **NE×D3 不变式论证（入档）**：D31 代数 `RowSet(Ri) = 账号维 ∧ 谓词组`、有效行集 `∪ RowSet(Ri)` **对谓词形态不可知**——新规则只增析取支，NE 与 EQ 在代数中同位，D3「加规则永不收紧」结构性保持，无需特殊论证。规则**内** NE 使该组行集缩小=授权收窄（fail-safe 方向）；修改的是该规则自身，属规则修改事件，D3 从不承诺改规则不收紧。NULL 对齐见 D44。D29 当年 NE 落选是「可用 IN 其余值替代」的密度论证——回潮正当性锚定真实场景：**NE 对新增值封闭**（「除发起失败(4)外全部」用 IN 列举，新增状态值时规则漏更新；NE 恰好免维护），非对称性美观。
- **getArity**：EQ/GTE/LTE/GT/LT/NE/PREFIX=1、BETWEEN=2、IN=-1（变长）——值数校验泛化形态零结构变化。

### D42 title 双资源注册：STRING 可过滤列清单 v2

- **注册行增量**（DataPermResources static 块）：leave 增 `new FilterColumn("title", "title", STRING, "标题", null)`、bpmn_approval 增同款——**D32 当年排除 title 的理由（「本轮无 LIKE，字符串精确匹配密度不足」）随 PREFIX 兑现失效**；title 整列随 STRING 矩阵开放 EQ/IN/NE/PREFIX 四操作符（矩阵一致性，精确/排除匹配顺带获得）。
- **title 语义注记**：leave.title 是业务快照列（e2e 锚点）、bpmn_approval.title 是审批标题快照（发起时定格）——两者皆非派生列（D32「不含派生列」红线不破）；断言锚 entity（SysLeave.title/BpmnApproval.title 均 String 字段，FilterColumns.assertDeclared 现机制覆盖，父类遍历不涉及）。
- **清单终态**：leave 五列（id/leaveType/startDate/endDate/title）、bpmn_approval 五列（id/status/businessType/createTime/title）——不含 apply_user 原则不变。

### D43 PREFIX：SQL 侧转义链与 test() 对齐（探针实证）

- **ESCAPE 字符选竖线 `|`（非反斜杠），三层 REPLACE 链顺序敏感**——`\|`→`\|\|` 先 doubling，再 `%`→`\|%`，再 `_`→`\|_`。选型依据（P11/P12 对照实证）：
  1. **反斜杠在参数值中零参与**：PreparedStatement 参数值不经历 MySQL 字面量解析，反斜杠是普通数据（P11 `a\b` 组原样命中）——竖线方案下用户值含反斜杠**无需任何转义**；反斜杠方案需 `REPLACE(?, '\\', '\\\\')` 且 wire 字面量双层（P12），mapper XML 源码里写 `'\\\\'` 对人类是谜语；
  2. **与 sql_mode 无关**：竖线链全程无反斜杠字面量，`NO_BACKSLASH_ESCAPES` 开关不影响行为（反斜杠方案的 P9 依赖当前 sql_mode 无该项——环境漂移即断）；
  3. XML 源码零转义噪音（`|` 在 XML 文本无需实体转义）。
- **XML 生产形态**（`#{}` only，守护零冲突；title 块完整模板见 §5.3）：

```xml
AND l.title LIKE CONCAT(REPLACE(REPLACE(REPLACE(#{p.values[0]}, '|', '||'), '%', '|%'), '_', '|_'), '%') ESCAPE '|'
```

- **未转义的通配符=过度授权**（值 `10%` 裸拼将匹配一切 `10` 开头行）——转义链是安全要求不是体验要求；转义在 SQL 渲染层单点完成，RowPredicate.values 保持**配置原值**（wire/digest/摘要/前端回显语义零变化，阶段一候选 B 的契约污染就此规避）。
- **test() 对齐规则（PREFIX 新增规则⑥，以 P2-P6 实证为准）**：`value != null && value instanceof String` 时 `regionMatches(true, 0, prefix, 0, prefix.length())`（即 startsWithIgnoreCase）——**双侧不 stripTrailing**。实证依据：LIKE **非 PAD SPACE**（P2/P3：`'abc' LIKE 'abc '`=0、`'abc ' LIKE 'abc'`=0）→ 与 D34 规则④（EQ/IN 的 stripTrailing）**刻意不对称**，记档为已知差异；ci 对齐（P6）；值侧尾空格被 `%` 吸收（P4）与 startsWith 行为一致；配置前缀含尾随空格是字面窄化条件（P5）。Java startsWith 无通配符语义=天然字面，与 SQL 侧转义后的字面语义一致。
- **配置 UX 软规范**：前端 PREFIX 输入器提交时 trimEnd（尾随空格几乎必是手滑）；后端**不校验**（值原样合法，P5 语义自洽）。值规则沿 STRING ≤50（3037 复用），**不禁 `%`/`_`/`|`**（转义消解而非拒绝——表达力完整）。

### D44 GT/LT/NE：比较族语义与 test() 对齐

- **NUMBER**：双侧归一 BigDecimal 后 `GT → compareTo > 0`、`LT → < 0`、`NE → != 0`（严格不等，与 SQL `>`/`<`/`!=` 一致）。
- **DATE/DATETIME**：归一时间型后 `GT → x.isAfter(v)`、`LT → x.isBefore(v)`、`NE → !x.equals(v)`（compareTo 路径同构）；BETWEEN 保持闭区间、GTE/LTE 闭端不动（D34 规则⑤增量 case）。
- **STRING×NE**：`!x.stripTrailing().equalsIgnoreCase(v.stripTrailing())`——P8 实证（`'abc' != 'abc '`=0、`'abc' <> 'ABC'`=0）NE 恰为规则④等值的求反，对齐天然成立。
- **NULL 恒不满足（含 NE）**：P7 实证 `NULL != 1` → NULL（不命中）↔ test() null→false——**NE 排除 NULL 行**，方向与全体操作符一致（fail-closed：宁可不授权）。试点 title 列 NOT NULL，运行期理论不可达，对齐规则仍记档（防御 DB 脏数据）。
- **XML 渲染**：GT `&gt;`、LT `&lt;`、NE `!=`（`!=` 与 `<>` 等价 P7/P8 双证，取 `!=` 与 digest 符号一致）；值类型化绑定不变（D33）。

### D45 字典翻译落层：FilterColumn.dictKey + system 侧翻译主体

- **声明层**：`FilterColumn` record 增组件 `String dictKey`（null=无字典）——字典归属是**列的属性**，注册表单一真相源（D11/D32 哲学延续）。对齐种子（§2.2 核实）：`leaveType → "system_leave_type"`、`status → "bpmn_approval_status"`；其余 8 列 null。构造点编译面强制同步。
- **翻译层（system 侧单源，四处消费点）**：`toPredicateSummary`（规则页/config 回显/第六态摘要共用）与 `predicateNarrative`（explain 条件句）在生成值文本时经 `declared.dictKey()` 翻译——**resolver 注入形态**：新增三参 overload（`(resource, predicates, BiFunction<String, List<String>, Map<String, String>> dictResolver)`，语义 = dictKey+值集合 → value→label 映射），Evaluator 与 ManageService 注入既有 `TranslationCacheService.findDictLabels` 构造传入；**旧两参 overload 保留并委托 null resolver（原值）——既有单测零破坏**。翻译消费口径 = 既有翻译域（类型启用∧项启用），Redis 30min TTL 的译文延迟为显示层 fail-open（记档：与 D49 权限缓存的 fail-open 论证严格区分——标签无害、权限不可）。译文缺失（值不在字典/项停用）回退原值。
- **不翻译面**：`rule_digest` 的 predDigest（机器面向 ASCII 紧凑审计格式，`leave_type=1` 原值）；`PredicateItem/PredicateVo/HitRuleVo.predicates` wire 回显（配置原样）；数据库存储（values 原值）。
- **前端层**：`FilterColumnVo` additive 增 `dictKey`（配置台契约，跨服务 PredicateVo **不扩**——判定不需要字典，D35「契约自含」边界保持）；RuleConfigDialog 值输入器分支：dictKey 非空列改 `el-select`（数据源既有 `getDictItems(dictKey)`，EQ/NE/GTE/GT/LTE 单选、IN 多选、BETWEEN 双选有序；filterable + allow-create 兜底存量原值回显——停用项/原值不在字典时可回显可手输）；dictKey=null 列保持既有类型驱动输入器。**软约束不做硬校验**（3037 不校验值域，配错=永不命中 explain 可排查——D38 口径不变）。
- **契约 §1.6 冻结格式变更（显式记档）**：条件中文摘要的值文本对字典列自本轮起显示译文——`请假类型等于1` → `请假类型等于事假`；narratives 同（`命中条件：请假类型 等于 1` → `… 等于 事假`）。非字典列逐字不变（e2e L587/L600 businessType 断言天然守恒）。

### D46 校验链 v3 与错误码（零新增）

- **校验链顺序 v3**（既有链结构零变化，仅矩阵与值规则扩容）：resource/columnKey 3034 → 逐条件：field ∈ filterColumns **3035**（title 入清单后自然可配）→ operator 枚举 **1002**（九 name）→ 操作符×类型矩阵 **3036**（终态矩阵违例：STRING×{BETWEEN,GTE,LTE,GT,LT}、{NUMBER,DATE,DATETIME}×PREFIX）→ 值形态 **3037**（GT/LT/NE/PREFIX 值数=1 沿 arity 泛化；PREFIX 值即 STRING ≤50 不禁通配符）→ 同 field 重复 1002 → 条件数 ≤5 3037 → 规则级序列化 ≤2000 3037 → 主体 3032 → CUSTOM 3033/联动 1002。
- **错误码**：零新增。3036/3037/1002 语义与文案不变（矩阵与值规则的**内容**扩容非规则**形态**扩容）——账本仍至 3037，下轮 3038 接续。

### D47 XML 分支面增量与注释规范（治理记档：维持显式硬编码）

- **分支面终态**（列×矩阵合法操作符数）：

| 资源 | 列清单（分支数） | 合计 |
|---|---|---|
| leave | id(8) / leaveType(8) / startDate(8) / endDate(8) / title(4) | **36**（现 20，+16） |
| bpmn_approval | id(8) / status(8) / businessType(4) / createTime(8) / title(4) | **32**（现 17，+15） |

- **注释规范（本轮落，零守护放宽）**：每列块头一行 `<!-- {field}（{type}）：{分支说明} -->` 锚点注释；操作符 when 按枚举声明序排列（EQ/IN/BETWEEN/GTE/LTE/GT/LT/NE/PREFIX）；块间空行。膨胀是 D5 显式性的对价（D33 口径），受可过滤列清单裁剪约束（注册表是闸门）——**`${}` 禁令不放宽**（include-property 语法本质 `${}`，守护全禁令维持；理由与候选方案权衡已录 Round 8 阶段一，拍板采维持）。
- **运行时 OR 组数膨胀（与静态分支面分治记档）**：组数 = 用户命中条件规则数（≤挂角色数）、谓词 ≤5/组——与操作符数无关，模板化治不了；内部系统角色数个位数，量化回潮阈值见 §11。

### D48 索引复审：零 DDL 记档

| 路径 | 结论 |
|---|---|
| id GT/LT（两资源） | 与 GTE/LTE 同构 PK range scan（不构成新访问路径形态），零动作 |
| start_date/end_date/create_time GT/LT | 同构 range；无索引全表扫维持 D37 取舍（内部量级），零动作 |
| leave_type/status（NE/GT/LT） | 低选择性禁建不变；NE 恒全表扫+过滤（排除形态无法用索引收窄），零动作 |
| **title PREFIX（新路径）** | `LIKE 'x%'` 建索引可走 range，当前无索引全表扫——**不预建**（两表量级 e2e/演示级）；回潮条件见 §11 |
| business_type | uk_business 最左前缀取舍记档不变 |

EXPLAIN 抽查两项进计划 B5：`title LIKE 'x%'`（全表扫记档）、`id > ?`（PK range）。

**B5 实测记档（2026-10-11，MySQL 5.7.24，实际 pageList SQL 形态含 LEFT JOIN approval_projection 投影）**：

1. **title PREFIX 转义链**（`l.title LIKE CONCAT(REPLACE(REPLACE(REPLACE('e2e','|','||'),'%','|%'),'_','|_'),'%') ESCAPE '|'`）：
   `table=l type=index key=PRIMARY possible_keys=null rows=196 filtered=1.11 Extra=Using where`（p 侧 eq_ref uk_business）。
   实际 `type=index`（PK 全索引扫）而非预判 `type=ALL`——5.7 优化器为免 `ORDER BY l.id DESC` filesort 选反向扫主键索引，**与全表扫同类非选择性访问**（无可用索引收窄，possible_keys=null 证实 title 无索引支持），成本记档口径不变；行为面 B5 第②组已实证转义正确性。
2. **id GT**（`l.id > 100`）：`table=l type=range possible_keys=PRIMARY key=PRIMARY key_len=8 rows=140 Extra=Using where`（p 侧 eq_ref uk_business）——**PK range 命中，与预判一致**（GT/LT/GTE/LTE 同构）。

### D49 规则缓存覆核：记档不实施（论证全文）

D10「实时求值无缓存」/ D18「缓存的失效复杂度论证」两次放弃后的第三次回炉，**结论：变化不足以推翻，缓做**。论证：

1. **「什么变了」逐项审视**：① 组件轮后 bpmn 读路径每请求 +1 跳 Feign——但该跳成本**只有消费方缓存能省**，D18 已论证消费方本地缓存 = 复制半个 RBAC 读模型 + 失效广播，复杂度与事故面双高（未变）；② 谓词轮求值产物变大（RowFilter 多组多谓词）——不增加 DB 查询次数（仍 4-5 次索引小查询，D10 口径未破）；③ sys_data_perm_log 随读路径频次增长——留痕（D7）是语义要求，缓存求值原料不豁免留痕写入，缓存对该成本无效。
2. **provider 侧短 TTL 缓存的致命缺口**：saveRule/delete 钩子只能覆盖**规则表**变更；部门挂载/用户停用/角色绑定变更不在钩子面 → TTL 窗口内「收回权限延迟」= **过授权窗口（fail-open 方向）**——与翻译缓存 30min fail-open（标签无害）性质不同，权限域不可接受；TTL 缩到收益趋零则不如不做。
3. **语义一致性**：数据权限现状是**实时生效**（零失效窗口是 D18 拍板①卖点）；功能权限「变更需重新登录或 refresh 生效」是菜单 perms 登录快照口径——两域并行不悖。引入任何缓存都改数据权限实时语义，**须用户显式接受，不得作为性能优化静默带入**。
4. **量化触发条件（回潮门槛）**：实测求值 p95 > 50ms、或读路径 QPS 使 system CPU 可见抬升、或 sys_data_perm_log 日增量 > 50 万行（触发留痕治理同窗评估）——三者任一出现再按「provider 侧缓存 + 全量变更钩子（规则/部门/用户/绑定四源）+ 短 TTL 兜底」另轮设计。

### D50 向后兼容红线与跨服务 jar 时序

1. **无条件规则与旧操作符规则全链零变化**：XML 既有 when 分支零触碰（增量全为新 when 插入）；digest/summary/narratives 对存量**非字典列**配置逐字不变；字典列摘要译文变更属 §1.6 冻结格式显式变更（计划内 e2e 维护 7 处，非回归）。
2. **跨服务 jar 时序（契约「兼容性」节）**：provider（system）先行升级产出含新操作符的 ScopeVo 后，**旧版 consumer jar（Round 7 版）的 `RowPredicate.test()` 对未知 operator 走 parseOperator→null→恒 false → 该组不满足**——fail-closed 方向安全（不放大授权），表现为「新操作符规则不生效直到 bpmn 升级 system-api jar」。升级顺序：system 先行无碍；bpmn 升 jar 后生效。同理旧 provider + 新 consumer：新 consumer 对未知 operator 同恒 false。**不要求同步发版**。
3. **契约既有面 additive**：PredicateVo/RowGroupVo/ScopeVo 结构零变化（operator 值域扩）；FilterColumnVo +dictKey；PredicateItem operator 值域扩；操作符表/矩阵/校验链/摘要格式变更全部显著标注。
4. **既有 e2e**：e2e:dataperm / e2e:dataperm-component / e2e:bpmn 零回归红线（三脚本不配条件规则且不涉字典列摘要断言）；run-dataperm-predicate-e2e.mjs 计划内维护 7 处译文断言 + predicates/digest/businessType 断言零维护（§2.1-9）。
5. **既有单测**：除 DataPermEvaluatorTest 构造器机械补 TranslationCacheService mock（Evaluator 增依赖的编译面强制）外零修改；DataPermConvertTest 旧用例经两参 overload 零触碰。

## 5. 核心组件设计（关键示意，每段 ≤30 行）

### 5.1 PredicateOperatorEnum / RowPredicate 增量（api 模块）

```java
// 枚举追加（D41）：GT(">", "大于") / LT("<", "小于") / NE("!=", "不等于") / PREFIX("^", "前缀")
// getArity：EQ,GTE,LTE,GT,LT,NE,PREFIX -> 1；BETWEEN -> 2；IN -> -1
/** 矩阵 v2（D41）：STRING×{EQ,IN,NE,PREFIX}；其余类型全操作符除 PREFIX */
public boolean supports(FilterColumnTypeEnum type) {
    return switch (type) {
        case STRING -> this == EQ || this == IN || this == NE || this == PREFIX;
        case NUMBER, DATE, DATETIME -> this != PREFIX;
    };
}
```

```java
// RowPredicate.testNumber / testDate / testDateTime 的 switch 增量（穷尽性编译错强制，D44）：
//   case GT -> x.compareTo(bounds.get(0)) > 0;   // 严格不等（时间型 isAfter 同构）
//   case LT -> x.compareTo(bounds.get(0)) < 0;   // isBefore 同构
//   case NE -> x.compareTo(bounds.get(0)) != 0;  // 等值求反
/** testString v2（D43/D44）：四 case + default false（矩阵外防御不变）；PREFIX 双侧不 stripTrailing（P2-P6 实证 LIKE 非 PAD SPACE） */
private boolean testString(Object entityValue) {
    PredicateOperatorEnum op = parseOperator();
    if (!(entityValue instanceof String text) || op == null) {
        return false;
    }
    String raw = String.valueOf(values.get(0));
    return switch (op) {
        case EQ -> text.stripTrailing().equalsIgnoreCase(raw.stripTrailing());          // 规则④不变
        case IN -> values.stream().anyMatch(v -> text.stripTrailing()
                .equalsIgnoreCase(String.valueOf(v).stripTrailing()));                    // 规则④不变
        case NE -> !text.stripTrailing().equalsIgnoreCase(raw.stripTrailing());          // 规则④求反（P8）
        case PREFIX -> text.regionMatches(true, 0, raw, 0, raw.length());                // 规则⑥：不 stripTrailing（P2-P5）
        default -> false;                                                                // BETWEEN/GTE/GT/... 防御
    };
}
```

### 5.2 FilterColumn 与翻译注入（api record + system 消费）

```java
/** 可过滤列声明 v2（D42/D45）：dictKey=null 无字典（显示原值）；构造点编译面强制（DataPermResources 两注册行） */
public record FilterColumn(String field, String column, FilterColumnTypeEnum type,
                           String label, String dictKey) { }
```

```java
// DataPermConvert 增量（D45）：三参 overload，旧两参委托 null（既有单测零破坏）；Evaluator/ManageService 注入
// TranslationCacheService.findDictLabels 构造 resolver 传入。译文缺失回退原值（显示层 fail-open）。
public static String toPredicateSummary(String resource, List<PredicateItem> predicates,
        BiFunction<String, List<String>, Map<String, String>> dictResolver) {
    // predicateSummaryPart 内：declared.dictKey() != null 且 resolver != null 时
    //   valueText 逐值翻译 = resolver.apply(dictKey, values).getOrDefault(v, v)
}
```

### 5.3 XML 模板（title 块完整 + NUMBER 列 GT/LT/NE 增量；标签体多行、#{} only）

```xml
<!-- title（STRING）：EQ/IN/NE/PREFIX 四分支（D42/D43）——PREFIX 三层竖线转义链（| doubling 先行，% _ 次之；
     ESCAPE '|' 实证 P10/P11；未转义通配符=过度授权（安全要求）；test()=startsWithIgnoreCase 双侧不 stripTrailing -->
<when test="p.field == 'title'">
    <choose>
        <when test="p.operator == 'EQ'">
            AND l.title = #{p.values[0]}
        </when>
        <when test="p.operator == 'IN'">
            AND l.title IN
            <foreach collection="p.values" item="v" open="(" separator="," close=")">
                #{v}
            </foreach>
        </when>
        <when test="p.operator == 'NE'">
            AND l.title != #{p.values[0]}
        </when>
        <when test="p.operator == 'PREFIX'">
            AND l.title LIKE CONCAT(REPLACE(REPLACE(REPLACE(#{p.values[0]}, '|', '||'), '%', '|%'), '_', '|_'), '%') ESCAPE '|'
        </when>
        <otherwise>
            AND 1 = 0
        </otherwise>
    </choose>
</when>
```

```xml
<!-- NUMBER/DATE/DATETIME 列 GT/LT/NE 增量（D44）——插入各列块既有 LTE when 之后（枚举声明序）；DATE 列 &gt;/&lt; 同款转义 -->
<when test="p.operator == 'GT'">
    AND l.leave_type &gt; #{p.values[0]}
</when>
<when test="p.operator == 'LT'">
    AND l.leave_type &lt; #{p.values[0]}
</when>
<when test="p.operator == 'NE'">
    AND l.leave_type != #{p.values[0]}
</when>
```

（bpmn 同型无别名前缀：`title`/`leave_type` 换 `title`/`id`/`status`/`create_time` 裸列名。）

## 6. 数据流（增量，仅列变化点）

- **list**：XML 新 when 分支渲染（GT/LT/NE 常规比较、PREFIX 转义链）——求值器 enrichment 零变化（PREFIX 值走 STRING 归一），RowFilter 结构零变化。
- **detail**：`allowsDetail` → `RowPredicate.test()` 新 case（GT/LT/NE/PREFIX 对齐规则 D43/D44）——双形态归一（VO 字符串/entity typed）机制不变。
- **配置台 save**：校验链 v3（矩阵终态 + arity 泛化值数）；predicates JSON 落库零变化。
- **可观察**：digest 新符号（`>`/`<`/`!=`/`^`，`digestPredicate` switch 编译面强制扩）；摘要/narratives 字典列值译文（resolver 注入）；第六态摘要经 toPredicateSummary 三参路径。
- **跨服务**：ScopeVo/PredicateVo 零结构变化（operator 值域扩）——InnerDataPermController 零改动。

## 7. 错误处理

| 层 | 语义 | 失败形态 |
|---|---|---|
| 配置态校验 | 3035/3036/3037/1002（矩阵与值内容扩容，形态不变） | BusinessException，前端 toast |
| 求值期降级 | 坏 JSON/越界/归一失败 → 该规则不授权 | D39 零变化（新操作符天然纳入） |
| test() 防御 | 未知操作符/矩阵外组合/NULL/不可归一 → false | fail-closed 零变化（default 分支） |
| PREFIX 转义 | 值含 %/_/\| | SQL 链转义为字面（不拒绝不剥离）；Java startsWith 天然字面 |
| 字典翻译 | 项停用/值不在字典/翻译服务异常 | 回退原值显示（fail-open 显示层，与权限 fail-open 严格区分记档） |
| 跨服务 jar 时序 | 旧 consumer 遇新操作符 | 该组恒不满足（拒绝方向），升 jar 后生效（D50-2） |

## 8. 测试策略

### 8.1 cloud-system-api 单测增量

- **RowPredicateTest**：GT/LT/NE（NUMBER 三形态/DATE/DATETIME 边界严格性——GT 边界值不命中、GTE 命中）；NE×STRING（ci+pad 求反矩阵：`"abc"` vs `"ABC "` 求反=false）；PREFIX（ci 命中、值侧尾空格命中、前缀尾空格不命中【P4/P5 对齐】、null→false、`a\b` 字面命中）；矩阵外防御（STRING×BETWEEN 恒 false）
- **PredicateOperatorEnumTest**：九枚举 of/label/symbol/arity；supports 终态矩阵全量（4×9=36 组合穷举）

### 8.2 cloud-system 单测增量

- **DataPermEvaluatorTest**：digest 新符号断言（`id>100`/`title^bug`/`leave_type!=2`）；narratives 译文（resolver mock 返回 事假 →「等于 事假」）；构造器机械补 TranslationCacheService mock；title 规则 enrichment 正常路径
- **DataPermConvertTest**：三参 overload 译文（IN 多值/单值/译文缺失回退）；两参 overload 原值（既有用例零触碰即回归证明）；PREFIX 摘要值文本
- **DataPermManageServiceTest**：校验链 v3 逐码（STRING×GT→3036、NUMBER×PREFIX→3036、GT 双值→3037、PREFIX 值>50→3037、title EQ/NE/PREFIX 正常保存+回显）；config/rule 页 predicateSummary 译文
- **DataPermResourcesTest**：双清单 v2（五列/五列、dictKey 断言——leaveType/system_leave_type、status/bpmn_approval_status）

### 8.3 cloud-bpmn 单测增量

- **ApprovalQueryServiceTest**：title PREFIX/NE 判定（toRowFilter→allowsDetail typed 形态）；`id GT` 行集用例

### 8.4 联调冒烟 B5（经网关 ASCII）+ EXPLAIN

leave：`ALL + title PREFIX 'e2e'` → 仅 e2e 前缀行可见（含他人）；转义矩阵（造单 title `e2e%x`/`e2e_x`/`e2e|x`，配 PREFIX `e2e%` → 仅 `e2e%*` 命中——`%` 字面证明；`_` 同款）；`leaveType NE 2` → 无病假；`id GT` 边界；字典译文（predicateSummary/scopeLabel/narratives 三处「事假」）。bpmn：`title PREFIX` 对称一例 + `businessType NE leave` + `createTime GT`；detail/diagram 4018 沿用。EXPLAIN 两项记档（title PREFIX 全表扫、id GT PK range）。向后兼容红线复证：删全部新操作符规则 → 三账号矩阵/摘要与 R7 终态一致。

## 9. 索引审查（规范强制章节）

见 §3 与 D48。零 DDL、零新索引；EXPLAIN 抽查两项进 B5；title 前缀索引回潮条件量化记档（§11.3）。

## 10. 红线核对清单（实现与验收共同遵守）

1. D1-D40 机制零破坏：D3 并集（NE 同位论证 D41）、D5 显式（XML 新 when 硬编码、无 `${}`、无 SQL 改写）、D31/D33 代数与模板形态、D34 规则①-⑤逐字不变（新增规则⑥ PREFIX 与 STRING×NE 求反为增量）
2. 无条件规则与旧操作符规则全链零变化；三既有 e2e 脚本零回归；run-dataperm-predicate-e2e.mjs 仅计划内 7 处译文维护
3. 契约既有面 additive 且显著标注（操作符表/矩阵/校验链/摘要格式/FilterColumnVo）；跨服务 ScopeVo 结构零变化 + jar 时序记档（D50）
4. fail-closed 无放行分支：未知操作符/矩阵外/NULL/归一失败 → false；PREFIX 转义链是安全要求（未转义=过度授权）
5. 守护测试零改动（`$` 禁令/单行 if/方法命名白名单全沿现状——新 when 满足既有规则）
6. 错误码账本：零新增（仍至 3037）；bpmn 段零新增（仍至 4018）

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **SQL 膨胀治理复审记档（不实施，拍板）**：静态分支面 leave 36/bpmn 32（终态见 D47）——维持显式硬编码 + 注释规范，**`${}` 禁令不放宽**（include-property 本质 `${}`，守护全禁令是注入面防线）；回潮条件：可过滤列清单超 ~10 列/资源或第三资源接入使单 XML 分支面 >60 时，重评「开发期生成器产出显式 XML」方向（仍不进运行期 ${}）。运行时 OR 组数膨胀与静态分支面分治：组数=条件规则数（≤角色数）与操作符无关——量化回潮阈值：单用户条件组 >10 或谓词总数 >30 时评估组展平/上限。
2. **规则缓存覆核记档（不实施，拍板）**：论证全文见 D49；量化触发（求值 p95>50ms / 读 QPS 可见抬升 / 留痕日增>50 万行）任一出现再按「provider 侧缓存 + 四源变更钩子 + 短 TTL 兜底」另轮设计；**任何缓存形态都改实时生效语义，须用户显式接受**。留痕治理（采样/异步/清理）与其同窗。
3. **title 前缀索引回潮条件**：sys_leave/bpmn_approval 行数过万且 PREFIX 规则活跃 → 补 `idx_title`（可前缀索引 `title(20)` 类）+ EXPLAIN 复查；当前全表扫记档（D48）。
4. **中缀/后缀模糊、REGEXP**：D29「模糊操作符整体不做」排除面仅开右前缀一道口，其余不回潮；IS-NULL 永久不做（试点列全 NOT NULL）。
5. **字典硬值域校验**：下拉软约束已消解主要配错面；若出现「配错值致规则静默永不命中」实际事故再评估硬校验（届时需新错误码 + 值域变更治理）。
6. **译文 30min TTL 延迟**：字典标签变更后摘要/narratives 最长 30min 滞后（TranslationCacheService 既有缓存）——显示层 fail-open 记档；不可接受时改为直查 SysDictDataMapper（每摘要一次小查询）。
7. **错误码账本**：system 段仍用至 **3037**（下轮 3038 接续）；bpmn 段仍至 4018。
