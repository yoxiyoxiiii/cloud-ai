# 数据权限 L2 结构化谓词（行级条件过滤）技术方案（全栈 A 级：条件三元组 + 逐规则并集代数 + 双试点）

- 日期：2026-10-11
- 状态：**定稿**——架构-agent 出品，配合拍板结论（阶段一《需求分析》十问，2026-10-11）；契约 `docs/superpowers/contracts/2026-10-11-dataperm-predicate-api.md`、计划 `docs/superpowers/plans/2026-10-11-dataperm-predicate.md`
- 拍板结论映射：① **LIKE 首版不做**（操作符封闭集 EQ/IN/BETWEEN/GTE/LTE，PREFIX 需求出现即 additive 补，回潮条件记档 §11）；② **跨规则叠加 = 逐规则并集**（各规则分别求值出各自行集取并集，保 D3「加规则永不收紧」不变式，XML 逐规则 OR 分组的一次性复杂度接受）；③ **范围全包**（最小操作符集 + sys_data_perm_rule 增 JSON 条件列【档位∧条件】+ 双试点 sys_leave/bpmn_approval + 断言锚 entity + 首版不落：OR 组合/嵌套表达式/跨字段函数/派生列/大小写敏感开关）
- 架构倾向一并采纳：行内恒 AND；注册行第三段声明可过滤列+类型（双清单显式区分）；scopeLabel 第六态；值校验不校验字典值域；谓词值字符串承载（Long→String 既有约定）；一致性检查器第三维度；fail-closed 零变化；MyBatis 隐式转型不确定性以「值类型化绑定」消解（D33）
- **与既有设计的关系**：本文档是 `2026-10-10-data-permission-design.md`（D1-D14）、`2026-10-10-dataperm-registry-refactor-design.md`（D15-D17）、`2026-10-10-dataperm-component-design.md`（D18-D28）的**谓词演进轮**——D1-D28 全部有效，决策编号 **D29-D40 接续**；D4 预埋的「其他资源若按别的列过滤需扩展 DataScope 形态」本轮兑现（D31）；D8（规则不进 header）、D9（物理删）、D21（fail-closed）、D26（leave 本地调用）机制零破坏

## 1. 需求与范围

### 1.1 目标

在主体白名单（DataScope{all, accounts}，四档+CUSTOM 收敛为账号集合、过滤列恒 apply_user）之上，支持**按数据行自身属性的条件谓词**：规则配置「字段/操作符/值」三元组（受约束条件生成器、封闭操作符集、类型安全矩阵），求值输出结构化谓词，mapper XML 显式拼条件（#{} 占位，不搞 SQL 改写器——D5 显式哲学延续）。用户初始诉求「只能看 id=1~100」= ALL 档 + `id BETWEEN 1~100`（D30）；L1 单据清单被 IN 操作符自然覆盖，不独立做。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| 操作符封闭集 | EQ / IN / BETWEEN / GTE / LTE（D29） |
| 规则模型 | sys_data_perm_rule 增 `predicates` JSON 条件列；行内恒 AND（档位账号集 ∧ 条件组）；ALL+条件=「条件过滤后的全部行」（D30） |
| 求值代数 | 逐规则分组求值、跨规则并集（D3 不变式保持）；终态 RowFilter{rowAll, groups}（D31） |
| 注册表 | register/registerRemote 行扩展第三段可过滤列（FilterColumn{field, column, type, label}，断言锚 entity）；双清单显式区分（D32） |
| 试点 | sys_leave（id/leave_type/start_date/end_date）+ bpmn_approval（id/status/business_type/create_time）双试点：list XML 条件块 + detail Java 谓词判定（D33/D34） |
| 跨服务 | ScopeVo 增 rowGroups 谓词字段（自含 column/type，消费方零注册表依赖）；fail-closed 零变化（D35） |
| 可观察 | scopeLabel 第六态「条件范围」、rule_digest/scope_summary 增量格式（无条件零 diff）、explain 谓词回显与 narratives（D36） |
| 配置台 | save/config/resources 三端点增条件段与双清单；错误码 3035-3037（D38）；前端 RuleConfigDialog 条件段类型驱动输入器 |
| 防线 | 一致性检查器第三维度（存量谓词列孤儿检测）+ 求值期解析失败降级恒不满足（D39） |

### 1.3 本轮不落（红线 + 移交）

**零变更红线**：无条件规则的求值/XML/留痕/label/digest 与现状**逐字等价**（D40，存量 e2e 三脚本零回归）；网关/sso 零改动；列级权限（sys_data_perm_column/D16 applier）零触碰；D9 物理删与 upsert 语义零触碰；错误码 bpmn 段零新增（4018 复用）。**明确不落**（拍板③）：LIKE/PREFIX 等一切模糊操作符（回潮条件 §11.1）、GT/LT/NE/IS-NULL、OR 组合与嵌套表达式（L3 红线，永久不做回潮）、跨字段函数（谓词列裸列 only）、大小写敏感开关（统一 ci 对齐，D34）、派生列可过滤（sys_leave 的投影 status）、字典值域校验（D38 记档）。移交项见 §11。

## 2. 现状核实（2026-10-11 逐项实读复核）

1. **detail 判定面**：`LeaveManageService` L91 `decision.getDataScope().allows(vo.getApplyUser())`；`ApprovalQueryService.assertRowVisible` L171-177 `scopeVo.toDataScope().allows(...)`——两处均为「归属账号 ∈ 集合」单维判定，谓词域需升级为「归属 ∈ 组账号 ∧ 行满足组条件」（D34）；`DataScope.allows` 本体保留不动（api 向后兼容，仅试点读路径切换调用）
2. **list XML**：`SysLeaveMapper.xml` / `BpmnApprovalMapper.xml` 的 pageList 均为 `<if test="scope.all == false"> AND apply_user IN <foreach>`——签名 `@Param("scope") DataScope` 本轮换 RowFilter（D33）；findById 两表均无 scope 条件（detail 判定在 Service）
3. **detail 行对象形态分裂（D34 关键输入）**：leave detail 加载的是 `SysLeaveVo`（leaveType/startDate 为 **String**——投影轮 CAST 直出契约形态）；bpmn detail 加载的是 `BpmnApproval` **entity**（typed Integer/LocalDateTime）——Java 谓词测试器必须同时接受 typed 值与字符串形态（归一解析，D34）
4. **实体审计字段在父类**：SysLeave/BpmnApproval 的 createTime 等继承 BaseEntity——`DataPermColumnApplier` 的 `getDeclaredFields` 不走父类（VO 无继承前提）；新断言/取值器必须**遍历类层级**（D32 记档差异，createTime 可过滤列的断言锚点）
5. **规则 mapper**：`DataPermRuleMapper.xml` 以 `allColumns` 片段集中列清单——增列须同步 allColumns/save/update 三处；求值查询（listByRoleIds/listByUserId）路径零变化，uk 最左前缀命中不变
6. **scopeLabel 五态**：`DataPermConvert.resolveScopeLabel`（契约 §6.8 判定序）——第六态插入点与无条件零 diff 保障见 D36；留痕 `scope_summary VARCHAR(200)` / `rule_digest VARCHAR(500)` 容量对增量格式充足
7. **守护测试核对**（ArchitectureGuardTest）：`$` 占位禁令（列名不可参数化→XML 硬编码分支是硬约束，D33）、单行 `<if>` 禁令（条件块全部多行）、方法命名白名单（`evaluate/explain/logDeny/allows/mask/assert/apply/collect/of` 等已放行——本轮 service 层新公共方法沿用白名单前缀，新增私有方法不受限）、实体内嵌枚举 Enum 后缀（SysDataPermRule 若内嵌谓词相关枚举须遵守——本轮谓词枚举落 api 模块顶层 `PredicateOperatorEnum`/`FilterColumnTypeEnum`，不在 entity 目录扫描面）
8. **custom_accounts 先例**：JSON 数组串进 VARCHAR(1000) + 解析异常 log.error 降级空集——predicates 列的承载与降级方向全盘沿此例（D30/D39）
9. **错误码账本**：system 段现用至 3034（本轮占用 3035-3037）；bpmn 段至 4018（本轮零新增）
10. **种子**：admin 两资源规则均为 ALL 无条件 → 行为零变化；本轮**零新菜单/零新权限标识/零种子 SQL**（配置台复用 `system:dataPerm:{list,save,remove}`）

## 3. 数据设计（增量 `scripts/sql/2026-10-11-dataperm-predicate.sql` 与基线 `cloud_system.sql` 同步落同一份定义）

```sql
ALTER TABLE sys_data_perm_rule
    ADD COLUMN predicates VARCHAR(2000) NULL
        COMMENT '行级条件组(JSON数组串,如 [{"field":"leaveType","operator":"EQ","values":["1"]}];NULL/空=无条件,档位即全量语义;组内多条件AND,跨规则并集;条件数≤5,IN值≤30)'
        AFTER custom_accounts;
```

- 执行前置断言（沿增量脚本惯例）：`SHOW COLUMNS FROM sys_data_perm_rule LIKE 'predicates';` 应 0 行
- 基线 `cloud_system.sql` 的 sys_data_perm_rule 建表语句同步加列（双落位先例）
- **零新表、零新索引、零种子**（索引审查见 §9）

## 4. 设计决策（D29-D40，接续 D1-D28）

### D29 操作符封闭集：EQ/IN/BETWEEN/GTE/LTE 五枚举

- **落选记档**：LIKE（拍板①——模糊操作符整体不做；「标题/编号前缀分权」真实场景出现时 additive 补 PREFIX 右模糊操作符，封闭枚举加项对存量规则零影响，同步补 idx_title 或全表扫记档，§11.1）；GT/LT（GTE/LTE 已覆盖单边区间，严格不等在权限域密度极低）；NE（白名单式思维下「排除某值」可用 IN 其余值替代）；IS-NULL（试点列全 NOT NULL）
- **枚举落位**：api 模块 `dataperm/PredicateOperatorEnum`（顶层枚举，不在 entity 扫描面）：`EQ("=") / IN("∈") / BETWEEN("~") / GTE("≥") / LTE("≤")`——code 为 digest/摘要符号；`label()` 中文：等于/属于/介于/大于等于/小于等于；`of(String name)` 解析（未知 → IllegalArgumentException，配置态前置校验拦截）
- **类型矩阵**（合法组合，校验依据 D38）：NUMBER×{EQ,IN,BETWEEN,GTE,LTE}；DATE/DATETIME×同 NUMBER 集；STRING×{EQ,IN}（ci 排序规则下字符串大小比较语义不稳，禁比较符类）

### D30 规则模型与行内语义：predicates JSON 列 + 档位∧条件

- **承载**：`sys_data_perm_rule.predicates` JSON 数组串，元素 `{field, operator, values[]}`——field 为 **entity 字段名**（camelCase，与可配列用 VO 字段名的先例同构但锚点不同，D32）；operator 为枚举 name；values 为**字符串数组**（wire/持久化统一字符串承载，Long→String 既有约定防精度）；NULL/空 = 无条件（存量规则语义零变化）
- **行内恒 AND**（拍板采纳）：一行规则的可见行 = `档位展开账号集合 ∧ 条件组全满足`——「主管：本部门及以下 且 单号 1~100」的组合表达力即诉求本体
- **row_scope 语义细化（不增档位）**：`ALL + 无条件 = 过滤豁免`（现状不变）；`ALL + 条件 = 条件过滤后的全部行`（owner 无关授权的表达形态——「只能看 id=1~100」即此配置）；非 ALL 档 + 条件 = 账号集 ∧ 条件。**不引入第六档**：档位单选会禁止「本部门 且 id 段」组合（功能缺陷，阶段一 Q4 已析）
- **上限与唯一性**：条件数 ≤5/规则；IN 值 ≤30（customAccounts 同款量级）；STRING 值长度 ≤50；同 field 至多一条（1002「条件列重复」——重复条件 AND 语义冗余，禁）；predicates JSON 序列化总长 ≤2000（列宽——逐项上限外的**聚合上限**，防极端组合超宽落库 DB 500，D38 校验）

### D31 求值代数与终态 RowFilter：逐规则分组、跨规则并集

- **代数**（拍板②）：用户命中规则 R1..Rn，各规则行集 `RowSet(Ri) = (ALL 或 apply_user ∈ accounts_i) ∧ (∀p ∈ preds_i: 行满足 p)`；有效行集 = `∪ RowSet(Ri)`——**D3「加规则永不收紧」不变式精确保持**（新增授权永不剥夺既有可见性），代价是 list SQL 逐规则 OR 分组（D33 接受）
- **终态**：`RowFilter{ boolean rowAll; List<RowGroup> groups }`，`RowGroup{ boolean allAccounts; Set<String> accounts; List<RowPredicate> predicates }`——组语义「(allAccounts || apply_user ∈ accounts) && predicates 全满足」
- **组合不变式**：①`rowAll=true ⇔ 存在 ALL 无条件规则`（此时 groups 空，过滤豁免——与现状 ALL 短路一致）；②无条件规则的账号集合**并成单组**（典型单规则用户退化为 `(apply_user IN (...))`——与现状 SQL 行集语义逐字等价，仅多一层括号）；③死组丢弃：`!allAccounts && accounts 空` 的组恒假（如未挂部门的 DEPT 档 + 条件——AND 语义下条件救不回账号空集），求值器直接不产出；④`isEmptyScope()` = groups 空（Service 短路不进 mapper，与现状口径一致）
- **DataPermDecision 增 rowFilter 字段**；`dataScope` 保留为**账号维并集摘要**（label 计数/digest/空集判据服务）——语义记档：含条件规则时 dataScope **不再是行集语义**（行集由 rowFilter 表达），label/summary 走 D36 增量格式。**豁免态取值记档（主控审查补，零 diff 红线）**：存在 ALL 无条件规则（rowAll 豁免态）时 dataScope = `DataScope.all()` 保持既有取值——scope_summary `all` 三态与既有 DataPermEvaluatorTest 的 `isAll()` 断言不动；「非 ALL 活规则展开并集」**仅非豁免态适用**

### D32 注册表可过滤列：register 行第三段 + 断言锚 entity + 双清单

- **声明单元**：api 模块 `dataperm/FilterColumn`（record `{String field, String column, FilterColumnTypeEnum type, String label}`）——field=entity 字段名（断言锚 + Java 取值键）；column=**裸 DB 列名**（XML 硬编码分支由各 mapper 自加别名前缀，如 `l.leave_type` vs bpmn 无别名 `status`）；type=NUMBER/STRING/DATE/DATETIME；label=中文名（配置台下拉与摘要文案用，沿 RowScopeEnum.label() 先例）
- **register 签名扩展（单行自包含）**：`register(resource, voClass, configurableColumns, entityClass, filterColumns)` / `registerRemote(resource, configurableColumns, filterColumns)`——本地资源 entityClass 可见走断言；远程资源纯声明（消费方断言下沉，D22 双层防护同构）
- **断言锚 entity（阶段一冲突 1 的修正落地）**：`FilterColumns.assertDeclared(Class<?> entityClass, List<FilterColumn> columns)`——①field ∈ entity 真实实例字段（**遍历类层级含父类**——createTime 在 BaseEntity，与 DataPermColumnApplier 仅 getDeclaredFields 的差异记档）；②字段 Java 类型与声明 type 匹配（NUMBER→Long/Integer、STRING→String、DATE→LocalDate、DATETIME→LocalDateTime）；violation → IllegalStateException 启动失败（D17 fail-fast 口径）。**不复用** D17「VO 字段且 String」断言（可过滤列天然非 String，且 leave VO 的 leaveType 是 String/DB 是 TINYINT——锚 entity 才类型可断言）
- **试点可过滤列清单**（刻意裁剪，类型矩阵四型全覆盖）：
  - leave：`id(单号,NUMBER)` / `leaveType(请假类型,NUMBER)` / `startDate(开始日期,DATE)` / `endDate(结束日期,DATE)`
  - bpmn_approval：`id(审批单号,NUMBER)` / `status(状态,NUMBER)` / `businessType(业务类型,STRING)` / `createTime(发起时间,DATETIME)`
  - **不含 apply_user**（账号维专属列，防与主体白名单语义重复配置）；**不含派生列**（sys_leave 的投影 status——拍板不落）；**不含 title/reason**（本轮无 LIKE，字符串精确匹配在权限域密度不足，减 XML 分支面）
- **双清单显式区分**：`getConfigurableColumns`（列级动作，VO 字段，String only——现状不动）与 `getFilterColumns`（行级条件，entity 字段，带类型——新增）；resources 端点双清单下发（契约 §3.5 变更）
- **D22 双层防护延续**：bpmn 侧 `DataPermColumnDeclarationRunner` 扩一行 `FilterColumns.assertDeclared(BpmnApproval.class, APPROVAL_FILTER_COLUMNS)`——FilterColumn 清单字面量在 provider（registerRemote 行）与 consumer（断言行）各出现一次，互相漂移由消费方断言兜底（provider 清单 ⊋ consumer 声明时多余列配了规则但 XML 无分支不命中——e2e 兜底，记档同 D22）

### D33 XML 显式条件形态：组间 OR 分组 + 列名硬编码分支 + 值类型化绑定

- **mapper 签名**：`pageList(Page<T> page, ..., @Param("filter") RowFilter filter)`（DataScope 参数退役，两试点同步）
- **组形态**（canonical，SysLeaveMapper 节选，全模板见 §5.4）：

```xml
WHERE l.deleted = 0
<if test="filter.rowAll == false">
    AND
    <foreach collection="filter.groups" item="grp" open="(" separator=" OR " close=")">
        <choose>
            <when test="grp.allAccounts">
                1 = 1
            </when>
            <otherwise>
                l.apply_user IN
                <foreach collection="grp.accounts" item="account" open="(" separator="," close=")">
                    #{account}
                </foreach>
            </otherwise>
        </choose>
        <!-- 此处逐谓词 choose 分支，见 §5.4b -->
    </foreach>
</if>
```

- **列名硬编码分支（`$` 禁令的必然对价）**：DB 列名不可参数化、`${p.column}` 被守护测试禁止——每谓词 `<choose>` 外层按 `p.field` 内层按 `p.operator` 硬编码分支（§5.4b）；分支量受类型矩阵约束（每资源 = Σ列×该列合法操作符数：leave 2×5+2×5=20、bpmn 2×5+5+2=17）——膨胀是 D5 显式性（SQL 里看得见每个条件）的对价，受可过滤列清单裁剪约束可控
- **值类型化绑定（消解隐式转型不确定性，主控验证项的方案答案）**：RowPredicate.values 在 api 层转换阶段按声明 type 转为 **BigDecimal/LocalDate/LocalDateTime/String**（D34 归一器同源）——#{} 以类型化参数绑定（DECIMAL/DATE/TIMESTAMP bind），不依赖 MySQL 字符串隐式转型（`leave_type = '1'` 的 float 比较索引行为不确定一类问题整体消除）；EXPLAIN 抽查进计划 B5 验证
- **无条件退化**：单组无谓词时渲染 `(apply_user IN (...))`——与现状 `apply_user IN (...)` 行集语义等价（仅括号），黑盒零差异（D40）

### D34 Java 谓词测试器与 SQL 语义对齐规则（detail 判定面升级）

- **判定面**：`RowFilter.allowsDetail(String ownerAccount, Function<String, Object> columnValues)` 替代 `dataScope.allows(owner)`——组匹配 = `(grp.allAccounts || accounts.contains(owner)) && grp.predicates 全过`；rowAll 短路直 true。两试点 detail 路径切换（leave L91 / bpmn assertRowVisible），`DataScope.allows` 保留不动（api 兼容面）
- **取值器**：api 模块 `EntityFieldResolver.of(Object bean)` → `Function<String, Object>`——反射字段缓存（ConcurrentHashMap 惰性，D16 同款）+ **遍历类层级**（BaseEntity 的 createTime）；未知字段 → null（→ 谓词不满足 → 组失败 → 拒绝，**fail-closed 方向**：配置漂移时宁可拒绝不放大授权）
- **对齐规则（主控点名项）——RowPredicate.test(Object value) 与 MySQL 语义逐条对齐**：
  1. **NULL 恒不满足**（SQL `NULL = x` / `NULL >= x` 结果 NULL 视为不命中——Java `value == null → false`）
  2. **NUMBER**：双侧归一 BigDecimal 后 `compareTo`（实体 Long/Integer、VO 字符串 "1" 均归一——双形态输入，现状核实 §2.3）
  3. **DATE/DATETIME**：归一 LocalDate/LocalDateTime 后 `compareTo`（字符串按声明类型格式解析：`yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss`——与 Jackson 全局格式对齐）
  4. **STRING**（EQ/IN）：**大小写不敏感 + 尾随空格不敏感**——`a.stripTrailing().equalsIgnoreCase(b.stripTrailing())`，对齐 MySQL 5.7 `utf8mb4_general_ci` PAD SPACE ci 语义（LIKE 不做，但 EQ/IN 字符串列仍有此分裂——list 与 detail 判定一致性由此保障）
  5. 操作符应用：EQ 单值等值；IN 归一后任一匹配；BETWEEN `v0 ≤ x ≤ v1`（闭区间，与 SQL BETWEEN 一致）；GTE/LTE 闭端比较
- **归一器单一实现**：字符串→类型化值的解析逻辑在 api 模块单点实现（转换 RowPredicate 与测试器共用），格式非法在**配置态**已被 3037 拦截，求值/测试期非法值理论不可达（DB 脏数据 → 归一失败按不满足处理，log.error，D39 同方向）

### D35 跨服务传播：ScopeVo 增 rowGroups（自含元数据，fail-closed 零变化）

- **ScopeVo 增字段** `rowGroups: List<RowGroupVo>`（`{allAccounts, accounts[], predicates: List<PredicateVo>}`，`PredicateVo = {field, column, operator, type, values[]}`——**自含 column/type**：消费方 XML 与取值器零注册表依赖，provider 求值时经注册表声明enrichment 完成契约模型）；值在 wire 上为字符串（Feign JSON），`toRowFilter()` 在 api 模块完成字符串→类型化转换（D33/D34 归一器）
- **rowAll 语义不变**；**accounts 字段保留** = 账号维并集（javadoc + 契约记档：含条件规则时**非行集语义**，仅供调试/摘要——行集判定一律 rowGroups）；`hiddenColumns/maskedColumns` 零触碰
- **fail-closed 零变化**（拍板）：Feign 失败/熔断打开期整 ScopeVo 不可得 → 拒绝访问（D21 既有路径），谓词无单独降级分支；deny 补痕路径不动
- **D19 窄契约原则符合性**：谓词正是「消费方读路径所需」（list SQL 与 detail 判定都要），契约面最小原则下正当扩展；Decision/HitRule/narratives 仍不出 system

### D36 可观察面：scopeLabel 第六态 + 留痕/解释增量格式（无条件零 diff）

- **scopeLabel 判定序 v2**（契约 §6.8 全量重述，**既有契约语义变更——显著标注**）：
  1. rowAll（豁免）→ `全部`
  2. rowFilter 空集 → `无（空范围）`
  3. **任一命中规则含条件 → `条件范围（{条件摘要}）`**（新态，插在部门档前——条件授权最特异、最需排查锚点；摘要 = 全部命中条件规则的谓词摘要分号连接）
  4. 命中任一部门档 → `指定范围（N 人）`
  5. CUSTOM → `自定义范围（含自己，共 N 人）` / `指定范围（N 人）`
  6. 兜底 → `仅自己`
  **无条件配置时 1/2/4/5/6 五态判定序与文案逐字不变**（新态仅在第 3 步命中——不配条件即零影响，存量 e2e 断言零维护，D40）
- **条件摘要格式**：每谓词 `{列label}{操作符中文}{值}`（值**原样配置值**不做字典翻译——字典值域校验与翻译一并移交 §11.4）：如 `审批单号介于1~100`、`状态等于1`；多值 IN 顿号连接；多谓词分号连接
- **rule_digest**：命中规则摘要追加 `∧{predDigest}`（ASCII 紧凑型：`id:1~100;leave_type=1`，操作符符号 =/in/~/>=/<=；**IN 片段 = `field:v1,v2` 值逗号连接，如 `business_type:leave,oa`——契约 §1.6 格式冻结点，实现期回流补例 2026-10-11**），500 截断不变——无条件规则 digest 逐字不变
- **scope_summary**：无条件三态 `all` / `accounts=N` / `empty` 逐字不变；**任一命中规则含条件时** → `accounts=N;cond=K`（N=账号维并集数、K=含条件规则数，200 字符内）
- **explain**：`HitRuleVo` 增 `predicates` 回显（配置原样）；narratives 每条件一句（如 `命中条件：请假类型 等于 1`），收敛结论句在含条件时改述「并集含条件过滤」

### D37 DDL 与索引审查：单列 ALTER、零新索引

见 §3 与 §9。要点：sys_data_perm_rule 查询路径零变化（uk 最左前缀不变）不建索引；业务表可过滤列——id 谓词走 PK range（BETWEEN/GTE/LTE 天然索引）；leave_type/status 低选择性**禁建**（规范明令）；start_date/end_date/create_time/business_type 无索引走全表扫+过滤（内部系统量级可接受，EXPLAIN 抽查记档进计划 B5；量大时补列索引为回潮条件 §11.5）

### D38 错误码 3035-3037 与配置校验链

- **错误码（system 段接续占用）**：`3035 无效的过滤条件列: {field}`（field 不在资源可过滤列清单）；`3036 条件操作符与列类型不匹配: {field}:{operator}`（矩阵违例）；`3037 条件值非法: {detail}`（值格式/数量【EQ/GTE/LTE=1、BETWEEN=2、IN 1~30】/BETWEEN 有序/长度>50/条件数>5/序列化长度>2000 条件组超长）。1002 通用新增文案：`操作符无效`（非枚举 name）、`条件列重复`
- **save 校验链顺序**（既有前置段不动，谓词段插在 resource 3034 之后）：resource 3034 → 逐条件：field ∈ filterableColumns 3035 → operator 枚举 1002 → 操作符×类型矩阵 3036 → 值形态 3037 → 同 field 重复 1002 → 条件数 ≤5 3037 → **规则级：predicates JSON 序列化长度 ≤2000 → 3037「条件组超长」（主控审查补——聚合上限，防超列宽 DB 500）** → （既有）主体 3032 → CUSTOM 账号 3033/联动 1002
- **值格式**：NUMBER 正则 `^-?\d+(\.\d+)?$`（BigDecimal 可解析且禁科学计数）；DATE `yyyy-MM-dd`；DATETIME `yyyy-MM-dd HH:mm:ss`；**不校验字典值域**（拍板记档：leaveType=9 配置合法但永不命中——explain 可排查，值域校验随字典翻译一并移交）
- **回显/出参**：config/rule 分页 VO 增 `predicates`（配置原样）+ `predicateSummary`（中文摘要，后端单源生成——前端零拼装）；resources 端点增 `filterColumns` 双清单

### D39 一致性检查与求值降级：第三维度 + 恒不满足

- **Checker 第三维度**（D17 既有两路之外新增）：全表规则 predicates 逐条——JSON 解析失败 或 field ∉ 该资源 filterColumns → 越界清单行（「疑似孤儿条件，请人工核对处置」warn 不阻断）；`collectViolations()` 可测签名扩展
- **求值期解析失败降级**：规则 predicates JSON 坏数据 → log.error + **该规则条件恒不满足**（组贡献空，规则整体不授权）——与 customAccounts 空集降级同方向（fail-safe 不放大授权，防坏配置炸读路径）

### D40 向后兼容红线（拍板③，实现与验收共同遵守）

1. **无条件规则行为零变化**：求值（组退化）、XML（行集语义等价，仅括号）、留痕（digest/summary 逐字）、label（五态判定序不变）、detail 判定（单组无谓词 allowsDetail ≡ allows）——全链逐字/语义等价
2. **契约既有面 additive**：save 入参、resources 出参、ScopeVo、HitRuleVo 均加字段不删不改；scopeLabel 加态不改既有态文案与判定序——**既有消费者不发送/不读取新字段时行为零变化**；§6.8 判定序更新在契约显著标注
3. **存量 e2e 三脚本零回归**（e2e:dataperm / e2e:dataperm-component / e2e:bpmn）——新脚本条件用例独立落 run-dataperm-predicate-e2e.mjs
4. **既有单测零修改**为行为等价证明（除两 mapper 签名处、detail 判定调用点与 InnerDataPermControllerTest 既有用例机械补 rowFilter stub【断言零变化】外——主控审查补第三机械点）：LeaveManageServiceTest/ApprovalQueryServiceTest 既有用例不改即绿 = 红线达成证据

## 5. 核心组件设计（关键示意，每段 ≤30 行）

### 5.1 cloud-system-api `api/dataperm/` 增量（消费方引 jar 即得）

```java
/** 行级过滤终态（不可变，D31）：rowAll=true 过滤豁免（groups 空）；否则 groups 并集 */
public final class RowFilter {
    private final boolean rowAll;
    private final List<RowGroup> groups;          // 已合并无条件组、已丢弃死组
    public boolean isEmptyScope() { return !rowAll && groups.isEmpty(); }
    /** detail 行级判定（D34）：组匹配=(allAccounts||账号∈组)&&谓词全过；取值器未知字段 null→拒 */
    public boolean allowsDetail(String ownerAccount, Function<String, Object> columnValues) { ... }
}

/** 规则组（D31）：allAccounts=true 时 accounts 空（ALL+条件组）；predicates 组内 AND */
public final class RowGroup {
    private final boolean allAccounts;
    private final Set<String> accounts;
    private final List<RowPredicate> predicates;
}

/** 谓词终态（D33/D34）：operator 为枚举 name 字符串（XML OGNL 直比免枚举边界）；values 已类型化 */
public final class RowPredicate {
    private final String field;      // entity 字段名（取值键）
    private final String column;     // 裸 DB 列名（XML 硬编码分支对照）
    private final String operator;   // PredicateOperatorEnum.name()
    private final List<Object> values; // BigDecimal/LocalDate/LocalDateTime/String（归一后）
    /** SQL 语义对齐判定（D34 五规则）：null 恒 false；NUMBER→BigDecimal；时间型→compareTo；STRING ci+stripTrailing */
    public boolean test(Object entityValue) { ... }
}
```

配套：`PredicateOperatorEnum`（EQ/IN/BETWEEN/GTE/LTE，code 符号 + label + of）、`FilterColumnTypeEnum`（NUMBER/STRING/DATE/DATETIME，含字段类型匹配与值解析归一）、`FilterColumn`（record 声明单元，D32）、`FilterColumns.assertDeclared(entityClass, columns)`（断言锚 entity 走类层级，D32）、`EntityFieldResolver.of(bean)`（反射取值器，字段缓存+父类遍历，D34）。

### 5.2 ScopeVo 扩展（api/domain，契约 §3 变更面）

```java
// DataPermScopeVo 既有四字段零触碰，additive：
/** 行级分组并集（D31/D35；rowAll=true 时空数组）——行集判定唯一权威 */
private List<RowGroupVo> rowGroups = new ArrayList<>();
/** accounts 语义记档（D35）：账号维并集，含条件规则时非行集语义（行集=rowGroups），仅调试/摘要 */
public RowFilter toRowFilter() { /* rowAll 短路；RowGroupVo→RowGroup，PredicateVo 值字符串→类型化（FilterColumnTypeEnum 归一） */ }
```

`RowGroupVo{allAccounts, accounts[], predicates[]}` / `PredicateVo{field, column, operator, type, values[]}`（@Data + @NoArgsConstructor + @AllArgsConstructor 沿 ScopeVo 注解口径）。

### 5.3 求值器增量（DataPermEvaluator，方法 ≤50 行拆私有）

```java
// resolveRowScope 重构为 resolveRowFilter（私有，既有 resolveRowScope 语义并入）：
// 1. 逐规则 parsePredicates（D39：JSON 坏→该规则条件恒不满足，log.error）
// 2. 豁免判定：任一 ALL 且无条件 → RowFilter 豁免态（groups 空，与现状 ALL 短路一致）
// 3. 逐规则建组：tier 展开（既有 expandRule 复用）+ 谓词 enrichment（注册表 FilterColumn 补 column/type/值归一）
// 4. 无条件规则账号并成单组（D31 不变式②）；死组丢弃（不变式③）
// 5. decision.setRowFilter(...)；decision.setDataScope(非豁免态=非 ALL 活规则展开并集；豁免态保持 all()——D31 记档零 diff)
// narratives/digest/summary 按 D36 增量；持久化 persistLog 复用（字段增量）
// provider 收敛落点 = InnerDataPermController.toScopeVo（D35，主控审查补）：rowAll 判定切
//   decision.getRowFilter().isRowAll()（豁免语义不再经 dataScope）；rowGroups 自 rowFilter 收敛
//   （RowPredicate→PredicateVo 自含 column/type/values 字符串）；accounts 沿账号维并集、rowAll 时
//   置空（既有行为不变）——既有四参构造随 ScopeVo 增参编译面强制改五参
```

### 5.4 XML canonical 模板（SysLeaveMapper.pageList；bpmn 同型无别名）

a) 组骨架见 §4-D33；b) 逐谓词分支（每列一段，leaveType 示例——其余列同型复制，DATE 列比较符转义 `&gt;=`/`&lt;=`）：

```xml
<foreach collection="grp.predicates" item="p">
    <choose>
        <when test="p.field == 'leaveType'">
            <choose>
                <when test="p.operator == 'EQ'">
                    AND l.leave_type = #{p.values[0]}
                </when>
                <when test="p.operator == 'IN'">
                    AND l.leave_type IN
                    <foreach collection="p.values" item="v" open="(" separator="," close=")">
                        #{v}
                    </foreach>
                </when>
                <when test="p.operator == 'BETWEEN'">
                    AND l.leave_type BETWEEN #{p.values[0]} AND #{p.values[1]}
                </when>
                <when test="p.operator == 'GTE'">
                    AND l.leave_type &gt;= #{p.values[0]}
                </when>
                <when test="p.operator == 'LTE'">
                    AND l.leave_type &lt;= #{p.values[0]}
                </when>
            </choose>
        </when>
        <when test="p.field == 'startDate'">
            <!-- DATE 列五分支同型：l.start_date -->
        </when>
    </choose>
</foreach>
```

（`<if>/<choose>/<when>` 标签体全部多行——守护测试单行 if 禁令；#{} only。）

### 5.5 转换层（DataPermConvert 增量）

`toPredicatesJson(List<PredicateItem>)` / `parsePredicates(String)`（坏数据空列表降级，customAccounts 同款）；`toPredicateSummary(...)`（D36 中文摘要，label 自注册表）；`resolveScopeLabel` 判定序 v2（第 3 步插条件态，其余逐字保持）；digest/scopeSummary 增量分支。

### 5.6 配置与一致性（system 侧）

- `DataPermResources`：register/registerRemote 签名扩展 + `getFilterColumns(resource)` + 断言委托 `FilterColumns.assertDeclared`；试点声明行（leave 本地带 SysLeave.class、bpmn registerRemote 纯声明）
- `DataPermManageService.save`：谓词校验链（D38 顺序）+ predicates JSON 落库；`findConfig` 回显 predicates + predicateSummary；`pageListRules` 行回填 predicateSummary；resources 端点双清单
- `DataPermRuleMapper.xml`：allColumns/save/update 三处增 predicates 列
- `DataPermRegistryConsistencyChecker`：第三维度（D39）

## 6. 数据流（双试点增量）

### 6.1 leave 列表/详情（本地调用保持，D26）

```
pageList：decision = evaluator.evaluate(LEAVE, LIST, null)
  → decision.getRowFilter().isEmptyScope() → 空页短路
  → leaveMapper.pageList(page, businessType, rowFilter)      // XML 组 OR 分组（D33）
  → 列级 applier 不变
detail：vo 加载（4010 不变）→ decision = evaluate(LEAVE, DETAIL, id)
  → !rowFilter.allowsDetail(vo.getApplyUser(), EntityFieldResolver.of(vo))
      → logDeny + 3026（D13 语义不变；判定面升级 D34——VO 字符串形态归一）
  → 列级 applier 不变
```

### 6.2 bpmn 列表/详情（Feign 远程，D35）

```
pageList：evalScope（fail-closed 不变）→ scopeVo.toRowFilter() → 空集短路
  → approvalMapper.pageList(page, rowFilter)                 // XML 同型（无别名）
  → title 列级不变
detail/diagram：assertRowVisible 升级
  → !scopeVo.toRowFilter().allowsDetail(approval.getApplyUser(), EntityFieldResolver.of(approval))
      → denyQuietly + 4018（D24 语义不变；entity typed 形态归一）
```

### 6.3 配置台保存（system）

```
save(DataPermRuleSaveRequest{..., predicates})
  → 校验链（D38）→ predicates JSON 串 → 行规则 insert/update（单行含条件列，upsert 全量覆盖语义不变）
  → 列规则全删全插不变
```

## 7. 错误处理

| 层 | 语义 | 失败形态 |
|---|---|---|
| 配置态校验 | 3035 列 / 3036 矩阵 / 3037 值 / 1002 枚举与重复 | BusinessException，前端 toast |
| 求值期降级 | 规则 predicates JSON 坏 | 该规则条件恒不满足（不授权方向），log.error 不炸读路径（D39） |
| detail 取值 | 未知字段/归一失败 | null → 谓词不满足 → 组失败 → 拒绝（fail-closed 方向，D34） |
| 跨服务 | system 不可用/熔断 | fail-closed 拒绝（D21 零变化，谓词无单独分支） |
| 一致性检查 | 存量孤儿条件/坏 JSON | 启动 warn 逐条不阻断（D39） |
| 留痕 | insert 异常 | catch log.error 不抛（D7 口径不变） |

## 8. 测试策略

### 8.1 cloud-system-api 单测（新增测试面）

- **RowPredicateTest（D34 对齐规则矩阵，核心）**：null 恒 false；NUMBER（Long/Integer/字符串"1" 三形态归一等值；BigDecimal 精度）；DATE/DATETIME 解析与 compareTo；STRING ci+stripTrailing（"ABC " vs "abc" 命中）；EQ/IN/BETWEEN 闭区间/GTE/LTE 全操作符；IN 任一命中
- **RowFilterTest**：rowAll 短路；单组无谓词 allowsDetail ≡ 旧 allows 语义（等价性用例）；allAccounts+谓词 owner 无关放行；组内 AND（账号过但谓词不过 → 拒）；死组已丢弃；isEmptyScope
- **FilterColumnsTest 红绿两态**：绿——SysLeave.class 四列全过（含类型匹配）；红——不存在字段/类型不匹配（如 startDate 声明 NUMBER）→ IllegalStateException；**父类字段**（BpmnApproval.createTime 在 BaseEntity——绿态必须覆盖）
- **EntityFieldResolverTest**：entity typed 形态（BpmnApproval.status→Integer）；VO 字符串形态（SysLeaveVo.leaveType→"1"）；父类字段（createTime）；未知字段 → null
- **DataPermScopeVoTest 增量**：toRowFilter 转换（值字符串→类型化；rowGroups 空+rowAll 短路；多组保序）
- **PredicateOperatorEnum/FilterColumnTypeEnum**：of 解析、label、矩阵查询函数

### 8.2 cloud-system 单测

- **DataPermEvaluatorTest 增量（代数矩阵，≥10 例）**：ALL+无条件 → 豁免（现状不变）；ALL+条件 → 非豁免、allAccounts 组；DEPT+条件 → 账号集∧条件；未挂部门 DEPT+条件 → 死组丢弃；多规则（无条件+含条件）→ 并集单组+条件组并存；条件 JSON 坏 → 该规则不授权；无条件规则组形态与现状等价（groups 单组断言）；predicates enrichment（column/type 注入）
- **DataPermConvertTest 增量**：label 判定序 v2 六态（含条件态摘要格式；**无条件五态文案快照逐字回归**）；digest `∧` 段；scope_summary `accounts=N;cond=K`；predicates JSON 编解码（坏数据空降级）
- **DataPermManageServiceTest 增量**：save 校验链（3035/3036/3037 各形态/1002 重复与枚举/条件数>5/IN>30/BETWEEN 无序）；config 回显 predicates+summary；resources 双清单
- **DataPermRegistryConsistencyCheckerTest 增量**：第三维度（孤儿列/坏 JSON → 清单行）
- **LeaveManageServiceTest**：既有用例零修改（红线证据）；新增谓词用例（list RowFilter 传参断言、detail 谓词拒绝 3026+deny、VO 字符串形态归一放行）

### 8.3 cloud-bpmn 单测

- **ApprovalQueryServiceTest**：既有用例零修改；增量（toRowFilter 判定、谓词拒绝 4018+deny、entity typed 归一）
- **DataPermColumnDeclarationRunner 扩一行断言**（红态自证同款）

### 8.4 联调冒烟（B5，经网关 ASCII）

leave：配 ALL+`leaveType=1` 角色 → 他人事假可见/病假不可见（owner 无关证明）+ detail 事假可见/病假 3026；配 DEPT 角色 + ALL+条件角色双规则 → 并集（部门病假 + 全部事假，D3 不变式证明）；my-scope `条件范围（请假类型等于1）`；explain 条件句；留痕 digest/summary 新格式。bpmn：`id BETWEEN` 行集 + detail 4018。fail-closed 复证。**EXPLAIN 抽查**：`leave_type = ?`（DECIMAL bind 走索引与否记档）、`id BETWEEN`（PK range）、`start_date >= ?`（全表扫记档）。存量三 e2e 脚本零回归。

## 9. 索引审查（规范强制章节）

| 表/列 | 谓词路径 | 结论 |
|---|---|---|
| sys_data_perm_rule | 查询路径零变化（增列不改 WHERE） | 零新索引（uk 最左前缀不变） |
| sys_leave.id / bpmn_approval.id | BETWEEN/GTE/LTE | PK range scan 天然命中，零动作 |
| sys_leave.leave_type / bpmn_approval.status | EQ/IN | 低选择性**禁建**（规范明令），全表扫+过滤量级可接受 |
| sys_leave.start_date/end_date、bpmn_approval.create_time | BETWEEN/GTE/LTE | 无索引全表扫——内部量级可接受，EXPLAIN 抽查记档（B5）；量大补列索引为回潮条件（§11.5） |
| bpmn_approval.business_type | EQ/IN | uk_business 最左前缀理论可用（与 apply_user 过滤叠加时优化器取舍）——EXPLAIN 抽查记档，不预建 |

## 10. 红线核对清单（实现与验收共同遵守）

1. D1-D28 机制零破坏：D3 并集不变式保持（D31）；D5 显式（XML 硬编码条件块、无 ${}、无 SQL 改写）；D9 物理删/upsert 兼容（predicates 随行规则单行覆盖）；D17/D22 双层防护形态延续（断言锚 entity 为修正落地，阶段一冲突 1 已拍板）
2. 无条件规则全链行为零变化（D40 四条）；既有三 e2e 脚本与既有单测（除机械切换两处）零回归
3. 契约既有面变更全部 additive 且显著标注（scopeLabel 判定序 v2 / resources 出参 / save 入参 / ScopeVo）
4. fail-closed 无任何放行分支（谓词归一失败/未知字段 → 拒绝方向）
5. 错误码账本：system 段占用 3035-3037（现至 3037，下轮 3038 接续）；bpmn 段零新增（仍至 4018）

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **PREFIX 右模糊回潮条件（拍板①）**：出现「标题/编号前缀分权」真实场景即补——PredicateOperatorEnum 加项 + 类型矩阵 STRING 扩 PREFIX + 各资源 XML 补分支 + **idx_title 补索引或全表扫记档** + Java 测试器前缀比较（ci 对齐）+ 配置台输入器 + e2e；封闭枚举 additive 对存量规则零影响
2. **GT/LT/NE 回潮条件**：单边严格不等/排除值真实场景出现即补（同上 additive 路径）
3. **OR 组合/嵌套表达式**：L3 红线，**永久不做回潮**（决策黑盒化违背可观察哲学，阶段一已定）
4. **字典值域校验与摘要翻译**：值配置不校验字典值域（配错=永不命中，explain 可排查）；摘要显示配置原值不做字典翻译——两者随「配置体验增强」轮一并评估
5. **日期/字符串列索引**：谓词路径全表扫的量级回潮条件——数据量增长后 EXPLAIN 复查补 `idx_start_date` 类列索引（ALTER 增量）
6. **条件规则性能面**：条件规则数与组数增长后（多角色多条件用户），SQL OR 分组膨胀——届时评估求值结果展平缓存（与规则缓存 §11.1 原账本同窗设计）
7. **错误码账本**：system 段现用至 **3037**（下轮 3038 接续）；bpmn 段仍至 4018
8. **apply_user 可过滤**：刻意排除（账号维专属）——若出现「按申请人账号前缀分权」类需求（账号域谓词），随 PREFIX 回潮一并重估
