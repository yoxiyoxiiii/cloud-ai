# 数据权限 L2 结构化谓词实施计划（后端 ∥ 前端，E 章 e2e 收口）

- 日期：2026-10-11；设计 `docs/superpowers/specs/2026-10-11-dataperm-predicate-design.md`（D29-D40）、契约 `docs/superpowers/contracts/2026-10-11-dataperm-predicate-api.md`
- 轨道：全栈 A 级。**B 章（后端）与 F 章（前端）独立可并行**；E 章（e2e）依赖 B+F 完成后收口
- 任务时序：B1（api 模块纯增量）→ B2（system 核心）→ B3（leave 试点）∥ B4（bpmn 试点）→ B5（联调冒烟）；F1 任意时点开工（联调验收依赖 B2 落库）
- 红线（全文有效，验收逐条核对）：无条件规则全链行为零变化（D40）；既有三 e2e 脚本零回归；契约既有面 additive；fail-closed 无放行分支；D1-D28 机制零破坏

## B 章：后端

### B1 cloud-system-api 谓词组件（纯增量，先行）

**做什么**：api 模块 `api/dataperm/` 增谓词终态与声明断言组件 + domain 增 ScopeVo 谓词字段与转换。

**文件清单**：
- 新增 `cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/PredicateOperatorEnum.java`——EQ/IN/BETWEEN/GTE/LTE（code 摘要符号 `=`/`in`/`~`/`>=`/`<=` + `label()` 中文 + `of(String)` 解析，设计 D29）
- 新增 `.../api/dataperm/FilterColumnTypeEnum.java`——NUMBER/STRING/DATE/DATETIME：`matches(Class<?>)` 字段类型匹配（NUMBER→Long/Integer、STRING→String、DATE→LocalDate、DATETIME→LocalDateTime）+ `normalize(String)` 值字符串→类型化对象（BigDecimal/原串/LocalDate/LocalDateTime，格式校验 `yyyy-MM-dd`/`yyyy-MM-dd HH:mm:ss`）——配置校验（system）与终态转换（api）单一实现两处消费（D34 归一器）
- 新增 `.../api/dataperm/FilterColumn.java`——record `{String field, String column, FilterColumnTypeEnum type, String label}`（D32 声明单元）
- 新增 `.../api/dataperm/FilterColumns.java`——`assertDeclared(Class<?> entityClass, List<FilterColumn> columns)`：field ∈ entity 实例字段（**遍历类层级含父类**——与 DataPermColumnApplier 仅 getDeclaredFields 的差异 javadoc 记档）+ 字段类型与 type 匹配；violation → IllegalStateException（D17 fail-fast 口径）
- 新增 `.../api/dataperm/RowPredicate.java`（不可变）——`{field, column, operator(String=枚举 name), values(List<Object> 已类型化)}` + `test(Object entityValue)`（D34 五对齐规则：null→false；NUMBER BigDecimal compareTo；时间型 compareTo；STRING `stripTrailing()+equalsIgnoreCase`；EQ/IN/BETWEEN 闭区间/GTE/LTE）；工厂 `of(String field, String column, PredicateOperatorEnum op, List<Object> values)`
- 新增 `.../api/dataperm/RowGroup.java`（不可变）——`{allAccounts, Set<String> accounts, List<RowPredicate> predicates}`
- 新增 `.../api/dataperm/RowFilter.java`（不可变）——`{rowAll, List<RowGroup> groups}` + `isEmptyScope()` + `allowsDetail(String ownerAccount, Function<String,Object> columnValues)`（组匹配=(allAccounts||∈accounts)&&predicates 全过；rowAll 短路；取值 null→组失败 fail-closed）
- 新增 `.../api/dataperm/EntityFieldResolver.java`——`static Function<String,Object> of(Object bean)`：ConcurrentHashMap 字段缓存（D16 同款惰性）+ **父类遍历**；未知字段 → null
- 新增 `.../api/domain/RowGroupVo.java` / `.../api/domain/PredicateVo.java`——@Data + @NoArgsConstructor + @AllArgsConstructor（沿 ScopeVo 注解口径，Feign 反序列化）
- 修改 `.../api/domain/DataPermScopeVo.java`——additive 增 `rowGroups`（javadoc 记档 accounts 语义收窄：账号维并集非行集语义，D35）+ `toRowFilter()`（rowAll 短路；RowGroupVo→RowGroup；PredicateVo.values 字符串→类型化经 FilterColumnTypeEnum.normalize）；`toDataScope()/toColumnScope()` 零触碰
- 测试（`cloud-base/cloud-api/cloud-system-api/src/test/java/com/cloudai/system/api/`）：
  - 新增 `dataperm/RowPredicateTest.java`——D34 对齐矩阵：null 恒 false；NUMBER 三形态（Long/Integer/"1"）归一等值与 BigDecimal 精度；DATE/DATETIME 解析比较；STRING `"ABC "` vs `"abc"` 命中（ci+stripTrailing）；EQ/IN 任一/BETWEEN 闭区间/GTE/LTE 全操作符；IN 空集不命中
  - 新增 `dataperm/RowFilterTest.java`——rowAll 短路；单组无谓词 allowsDetail 与账号语义等价（等价性用例，红线证据）；allAccounts+谓词 owner 无关放行；组内 AND（账号过谓词不过→拒）；isEmptyScope
  - 新增 `dataperm/FilterColumnsTest.java`——红绿两态：绿=SysLeave.class 四列（leave 用真 system entity？**不可**——api 模块不依赖 system；用测试内建静态内部类 FixtureEntity（含父类字段模拟 BaseEntity createTime）覆盖绿态+父类遍历；红=不存在字段/类型不匹配 → IllegalStateException
  - 新增 `dataperm/EntityFieldResolverTest.java`——typed/字符串双形态取值、父类字段、未知字段 null
  - 新增 `dataperm/PredicateOperatorEnumTest.java` / `FilterColumnTypeEnumTest.java`——of 解析红绿、matches 矩阵、normalize 格式红绿
  - 新增 `domain/DataPermScopeVoTest` 增量——toRowFilter 转换（类型化/rowAll 短路/多组保序/空数组）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-system-api -am` 全绿；api 模块依赖面不变（仅 core-starter+openfeign 沿现状，新增类零新依赖）；既有 ScopeVo/Applier/Columns 测试零回归。

### B2 system 侧：DDL + 注册表 + 求值器 + 配置台 + 可观察面

**做什么**：规则表增列、注册表双清单、求值器 RowFilter 代数、转换层 label v2、save/config/resources/checker 扩展。

**文件清单**：
- 新增 `cloud-base/scripts/sql/2026-10-11-dataperm-predicate.sql`——前置断言（`SHOW COLUMNS FROM sys_data_perm_rule LIKE 'predicates'` 应 0 行）+ `ALTER TABLE sys_data_perm_rule ADD COLUMN predicates VARCHAR(2000) NULL COMMENT '...' AFTER custom_accounts`（设计 §3 全文）
- 修改 `cloud-base/scripts/sql/cloud_system.sql`——基线建表语句同步加列（双落位先例）
- 修改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysDataPermRule.java`——增 `predicates` String 字段（javadoc：JSON 数组串，NULL=无条件）
- 修改 `cloud-base/cloud-system/src/main/resources/mapper/DataPermRuleMapper.xml`——allColumns 片段 / save INSERT / update SET 三处增 predicates 列
- 修改 `.../system/service/dataperm/DataPermResources.java`——① register 签名扩 `(resource, voClass, configurableColumns, entityClass, filterColumns)`、registerRemote 扩 `(resource, configurableColumns, filterColumns)`（前置校验同款；本地走 `FilterColumns.assertDeclared`）；② static 块试点声明：`register(LEAVE, SysLeaveVo.class, List.of("title","reason"), SysLeave.class, List.of(单号 id/请假类型 leaveType/开始日期 startDate/结束日期 endDate 四 FilterColumn))` + `registerRemote(BPMN_APPROVAL, List.of("title"), List.of(审批单号 id/状态 status/业务类型 businessType/发起时间 createTime 四 FilterColumn))`（清单与类型逐项按契约 §1.4）；③ 新增 `getFilterColumns(resource)`（未注册空列表，先 assertResource 口径同 getConfigurableColumns）
- 修改 `.../system/service/dataperm/DataPermEvaluator.java`——resolveRowScope 重构为 resolveRowFilter（私有）：①逐规则 parsePredicates（坏 JSON → log.error 该规则条件恒不满足，D39）；②豁免判定（任一 ALL 且无条件 → RowFilter 豁免态，与现状 ALL 短路一致）；③逐规则建组（既有 expandRule 复用展开账号 + 谓词 enrichment：注册表 FilterColumn 补 column + values 类型化）；④无条件规则账号并成单组、死组（!allAccounts && accounts 空）丢弃（D31 不变式②③）；⑤ `decision.setRowFilter(...)` + `decision.setDataScope(账号维并集——非 ALL 活规则展开并集)`；narratives 增条件句、digest/scopeSummary 增量分支（D36）；既有公共方法签名（evaluate/evaluateFor/explain/evaluateForSelf/logDeny）零变化
- 修改 `.../system/service/dataperm/DataPermDecision.java`——增 `rowFilter` 字段（javadoc：行集判定唯一权威；dataScope 语义收窄记档——**豁免态保持 `DataScope.all()` 取值**，设计 D31 记档零 diff）
- 修改 `.../system/controller/feign/InnerDataPermController.java`——toScopeVo 收敛升级（**provider 侧 rowGroups 落点**，主控审查补）：① rowAll 判定切 `decision.getRowFilter().isRowAll()`（豁免语义不再经 dataScope）；② rowGroups 自 decision.getRowFilter() 收敛（RowGroup→RowGroupVo、RowPredicate→PredicateVo 自含 column/type/values 字符串）；③ accounts 沿账号维并集、rowAll 时置空（既有行为不变）——既有 `new DataPermScopeVo(rowAll, accounts, hidden, masked)` 四参构造随 @AllArgsConstructor 增参**编译面强制**改五参
- 修改 `.../system/service/dataperm/DataPermRegistryConsistencyChecker.java`——collectViolations 增第三维度：全表规则 predicates 逐条 JSON 解析失败或 field ∉ filterColumns → 越界清单行（D39）
- 修改 `.../system/convert/DataPermConvert.java`——① `toPredicatesJson(List<PredicateItem>)`/`parsePredicates(String)`（坏数据空列表降级，customAccounts 同款）；② `toPredicateSummary(...)`（D36 中文摘要，label 自注册表 getFilterColumns）；③ `resolveScopeLabel` 判定序 v2（第 3 步插条件态，其余分支逐字保持）；④ digest `∧predDigest` 与 scope_summary `accounts=N;cond=K` 增量；⑤ config/rule VO 转换增 predicates/predicateSummary
- 修改 `.../system/dto/DataPermRuleSaveRequest.java`——增 `predicates: List<PredicateItem>`；新增 `.../system/dto/PredicateItem.java`（field/operator/values）
- 修改 `.../system/vo/DataPermRuleVo.java` / `DataPermRuleConfigVo.java`——增 `predicates` + `predicateSummary`；修改 `.../system/vo/DataPermResourceVo.java` 增 `filterColumns`；新增 `.../system/vo/FilterColumnVo.java`；修改 `.../system/vo/DataPermExplainVo.java`——HitRuleVo 增 `predicates`
- 修改 `.../system/service/DataPermManageService.java`——① save 谓词校验链（契约 §2.3 顺序：3035/1002 枚举/3036 矩阵/3037 值/1002 重复/3037 条件数/3037 序列化长度 ≤2000 防超列宽 DB 500——校验私有方法 ≤50 行拆分）+ JSON 落库；② findConfig 回显增量；③ pageListRules 行回填 predicateSummary；④ resources 端点双清单
- 测试（`cloud-base/cloud-system/src/test/java/com/cloudai/system/`）：
  - `service/dataperm/DataPermEvaluatorTest` 增量（代数矩阵 ≥10 例，设计 §8.2 清单）
  - `convert/DataPermConvertTest` 增量（六态 label——**无条件五态文案快照逐字回归**；digest/summary 新格式；JSON 编解码坏降级）
  - `service/DataPermManageServiceTest` 增量（校验链逐码触发：3035/3036/3037 各形态/1002 操作符无效/1002 条件列重复/条件数>5/IN>30/BETWEEN 无序/条件组序列化超长【多条 IN×30 长值拼装 → 3037】；config 回显；resources 双清单）
  - `controller/feign/InnerDataPermControllerTest` 增量（主控审查补）——rowFilter→rowGroups 收敛断言（豁免态 rowAll=true+rowGroups 空+accounts 空；含条件组 PredicateVo 自含 column/type；非豁免态 accounts 并集）；**既有两 evaluate 用例 decision 助手机械补 rowFilter stub**（toScopeVo 改读 rowFilter 后 null 即 NPE——机械切换，断言零变化）
  - `service/dataperm/DataPermRegistryConsistencyCheckerTest` 增量（孤儿条件列/坏 JSON → 清单行）
  - `service/dataperm/DataPermResourcesTest` 增量（getFilterColumns 生效；register 断言触 FilterColumns 红态）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿（既有单测除谓词新增与 InnerDataPermControllerTest 机械补 rowFilter stub 外零修改）；增量 SQL 执行成功（java 单文件源码查库验证列存在与基线同步）；my-scope/explain 无条件路径冒烟与 v1 逐字一致。

### B3 leave 试点：mapper XML 条件块 + detail 判定切换

**做什么**：SysLeaveMapper 签名换 RowFilter、XML 组 OR 分组 + 四列谓词分支、LeaveManageService list/detail 切换。

**文件清单**：
- 修改 `.../system/mapper/SysLeaveMapper.java`——`pageList(Page<SysLeaveVo> page, @Param("businessType") String businessType, @Param("filter") RowFilter filter)`（DataScope 参数退役）
- 修改 `cloud-base/cloud-system/src/main/resources/mapper/SysLeaveMapper.xml`——pageList 按设计 §5.4 canonical 模板重写（组骨架 + leaveType/startDate/endDate/id 四列 × 类型合法操作符硬编码分支，DATE 列 `&gt;=`/`&lt;=` 转义；`<if>/<choose>/<when>` 标签体全部多行；#{} only）；头注更新（谓词轮 D33 说明 + 索引命中记档：id 走 PK、leave_type 低选择性全表扫记档）
- 修改 `.../system/service/LeaveManageService.java`——① list：`decision.getRowFilter().isEmptyScope()` 短路 + mapper 传 rowFilter；② detail：`decision.getRowFilter().allowsDetail(vo.getApplyUser(), EntityFieldResolver.of(vo))` 替代 `getDataScope().allows(...)`（VO 字符串形态归一——D34 双形态输入的 leave 侧验证点）；logDeny/3026/列级 applier 调用零变化
- 测试：`service/LeaveManageServiceTest`——**既有用例零修改（红线证据）**；新增谓词用例（list 传 RowFilter 形态断言——mock mapper 捕获参数；detail 谓词拒绝 3026+deny 调用；VO 字符串形态 `leaveType="1"` 归一放行/拒绝两例）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿；无条件配置下 leave/page 与 detail 行为与 v1 一致（冒烟三账号矩阵）；grep 全仓 `SysLeaveMapper.pageList` 调用点签名一致。

### B4 bpmn 试点：远程谓词消费 + detail 判定切换

**做什么**：BpmnApprovalMapper XML 条件块、ApprovalQueryService 判定升级、消费方断言扩展。

**文件清单**：
- 修改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/mapper/BpmnApprovalMapper.java`——`pageList(Page<BpmnApproval> page, @Param("filter") RowFilter filter)`
- 修改 `cloud-base/cloud-bpmn/src/main/resources/mapper/BpmnApprovalMapper.xml`——pageList 按设计 §5.4 同型重写（无别名：`apply_user IN` / `id BETWEEN` 等；id/status/businessType/createTime 四列分支——businessType 为 STRING 仅 EQ/IN 两分支）；头注更新
- 修改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/service/ApprovalQueryService.java`——① pageListMy：`scopeVo.toRowFilter()` 空集短路 + mapper 传 filter；② `assertRowVisible` 升级：`scopeVo.toRowFilter().allowsDetail(approval.getApplyUser(), EntityFieldResolver.of(approval))`（entity typed 形态——D34 双形态 bpmn 侧验证点）；4018/denyQuietly/evalScope（fail-closed）零变化
- 修改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/config/DataPermColumnDeclarationRunner.java`——增一行 `FilterColumns.assertDeclared(BpmnApproval.class, APPROVAL_FILTER_COLUMNS)`（本地常量清单与 provider registerRemote 行同款漂移防护，D22/D32）
- 测试：`service/ApprovalQueryServiceTest`——既有用例零修改；新增（toRowFilter 判定/谓词拒绝 4018+deny/typed 归一/`id BETWEEN` 行属性行为）；Runner 红态自证同款（临时错列名启动失败→复原）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-bpmn -am` 全绿；无条件配置审批三端点行为与 v1 一致；grep `toDataScope().allows` 全仓清零（唯一旧调用点 assertRowVisible 已切换——**注意**：api 的 `toDataScope()` 本体保留不删，仅试点调用点退役）。

### B5 后端联调冒烟与 EXPLAIN 抽查

**做什么**：起 Nacos/Redis/MySQL + sso/system/bpmn/gateway，按契约 §9 验收口径冒烟。

**步骤与验收**：
1. 增量 SQL 执行；四服务起服；system 日志确认一致性检查零越界 warn
2. leave 谓词矩阵（经网关 ASCII）：配角色 `leave/ALL + leaveType=1` → 挂角色用户列表见他人事假、不见病假；detail 他人事假 200/病假 3026 + deny 留痕；换配 `本部门 + leaveType=1` → 仅本部门事假；叠加部门档无条件角色 → 并集（部门病假∪全部事假，D3 不变式）
3. bpmn 四型谓词：`id BETWEEN`/`status EQ`/`businessType IN`/`createTime GTE` 各一例行集验证 + detail/diagram 4018；title 列级叠加复证
4. 可观察：my-scope `条件范围（…）`；explain 条件句 + HitRule predicates 回显；留痕 digest `∧` 段 + `accounts=N;cond=K`；配置台 3035/3036/3037/1002 逐码触发
5. **向后兼容红线复证**：删除全部条件规则 → 三账号矩阵/五态 label/留痕三态与 v1 逐字一致
6. fail-closed：taskkill system → bpmn 列表/详情 1002 拒绝；重启恢复
7. **EXPLAIN 抽查记档**：`leave_type = ?`（DECIMAL bind 是否走 idx——记档结论）；`id BETWEEN ? AND ?`（PK range）；`start_date >= ?`（全表扫记档）；`business_type = ?`（uk 最左前缀取舍记档）
8. 留痕页核对：含条件决策行要素齐（digest/summary 新格式）

## F 章：前端（cloud-web）

### F1 RuleConfigDialog 条件段 + 规则表条件列 + 双清单消费

**做什么**：规则弹窗增条件段（类型驱动输入器）、规则表格增条件摘要列、types 增量；my-scope label 后端自含——**前端零改动**（仅联调验证展示宽度）。

**文件清单**：
- 修改 `cloud-web/src/types/api.ts`——① 类型：`PredicateItem`（契约 §5.1）、`FilterColumnVo`（§5.2）；`DataPermRuleVo/DataPermRuleConfigVo/HitRuleVo` 增量字段；`DataPermResourceVo` 增 `filterColumns`；② 常量：`OPERATOR_EQ/IN/BETWEEN/GTE/LTE`、`FILTER_TYPE_NUMBER/STRING/DATE/DATETIME`、上限 `PREDICATE_MAX=5`/`IN_VALUE_MAX=30`/`STRING_VALUE_MAX=50`；③ `OPERATOR_OPTIONS`（value/label 中文——与后端 label 对齐，双处冻结点 JSDoc 记档）+ `operatorsForType(type)`（矩阵前端镜像：STRING 仅 EQ/IN）
- 修改 `cloud-web/src/api/dataPerm.ts`——零新函数（getMyScope/saveRule/getRuleConfig/listResources 复用）；JSDoc 补契约 §2/§6 节号与谓词语义
- 修改 `cloud-web/src/views/system/dataPerm/components/RuleConfigDialog.vue`——① 条件段 UI（档位 radio 下方，分组标题「行级条件（与档位同时生效）」）：条件行列表（≤5 行，增删按钮）——列 select（resources 接口 filterColumns，label 显示中文名）→ 操作符 select（`operatorsForType` 按列类型过滤）→ 值输入器类型驱动：NUMBER=el-input-number（或 input+数值校验）；DATE=el-date-picker（`value-format="YYYY-MM-DD"`）；DATETIME=el-date-picker 带 time（`value-format="YYYY-MM-DD HH:mm:ss"`）；STRING=el-input（≤50）；BETWEEN=双输入（起/止，前端校验有序）；IN=多值 tag 输入（≤30）；② 回显：config 接口 predicates → 条件行（configured=false 空列表）；③ 提交：条件行转 `predicates`（空列表=清空条件随档位保存）；④ 前端校验兜底（同列禁重复、必填完整、上限——后端 3035-3037 为权威）；⑤ 表单 javadoc 更新（契约 §2.3）
- 修改 `cloud-web/src/views/system/dataPerm/index.vue`——规则表格增「条件」列（`predicateSummary`，null 显示 `-`；列宽预留摘要长度）
- 联调预检：`views/system/leave/index.vue` 与 `views/bpmn/approval/index.vue` 提示条对第六态 label（`条件范围（…）`）为纯字符串透传——零代码改动，仅视觉核对（长摘要换行/截断）

**验收标准**：条件段四类型输入器交互正确（Playwright MCP：配一条 `leaveType=1` 条件规则全流程——弹窗配置→保存→表格摘要列→回显）；类型矩阵前端镜像生效（STRING 列操作符仅 等于/属于）；3035-3037 后端拒绝时 toast 正常；`npm run build` 通过、既有 cloud-web 测试零回归；无条件规则弹窗与 v1 视觉零差异（条件段空态）。

## E 章：e2e（cloud-e2e，依赖 B+F 收口）

### E1 新脚本 run-dataperm-predicate-e2e.mjs + 既有三脚本回归

**做什么**：新增谓词矩阵黑盒 e2e；跑既有 e2e:dataperm / e2e:dataperm-component / e2e:bpmn 验零回归。

**文件清单**：
- 新增 `cloud-e2e/run-dataperm-predicate-e2e.mjs`（沿 run-dataperm-e2e.mjs 骨架：lib 登录/建号/清理复用；e2e 前缀账号，种子 admin 只碰配置台）+ `cloud-e2e/package.json` 增 `"e2e:dataperm-predicate": "node run-dataperm-predicate-e2e.mjs"`
- 用例序：建部门/账号（U1 挂条件角色、U2 挂部门档+条件角色双规则、U3 无规则）→ U1 配 `leave/ALL + leaveType=1` → U1/U2 各发起事假与病假 → U1 列表=全部事假（含他人——owner 无关证明）不含病假 → U1 detail 他人事假 200 / 任意病假 3026 → U2 列表=部门全部单∪全部事假（D3 不变式）→ my-scope `条件范围（请假类型等于1）` 断言 → explain 条件句断言 → 留痕页 digest `∧` 段与 `accounts=N;cond=K` 断言 → bpmn：配 `id BETWEEN`（取实际两单 id 区间）+ `businessType IN` → 行集与 detail 4018 → 配置台错误码 3035（改 field）/3036（STRING×BETWEEN）/3037（坏日期）触发断言 → 清理
- **既有维护点预判（契约 §0.6 记档口径）**：第六态 label 仅在配条件规则时出现——`run-dataperm-e2e.mjs`/`run-dataperm-component-e2e.mjs` 不配条件规则 → **零维护**（scopeLabel 五态断言原样通过）；`run-bpmn-e2e.mjs` 不涉数据权限断言零维护；规则分页若断言全表总数字段需核对（本轮零种子，总数不变）

**验收标准**：`npm run e2e:dataperm-predicate` 有头全绿；`npm run e2e:dataperm` + `npm run e2e:dataperm-component` + `npm run e2e:bpmn` 零回归（零 diff）。

## 移交后续阶段的备忘（下一阶段规划前必读）

1. PREFIX 右模糊回潮条件（LIKE 首版不做，拍板）——加操作符 additive 路径与 idx_title 补索引/全表扫记档要求，见设计 §11.1
2. GT/LT/NE 回潮条件；OR 组合/嵌套表达式**永久不做**（L3 红线）
3. 字典值域校验与条件摘要字典翻译（本轮值原样展示）
4. 日期/字符串谓词列索引回潮（量级触发 EXPLAIN 复查补列索引）
5. 条件规则数增长的 SQL OR 分组膨胀（与规则缓存账本同窗评估）
6. 规则缓存与失效 / IN 大集合 / 留痕治理——沿原账本续期
7. 错误码账本：system 段现用至 **3037**（下轮 3038 接续）；bpmn 段仍至 4018

---

## 给 backend-agent 的任务清单（直粘）

按序执行 B1→B2→B3∥B4→B5，规范依据：设计 `docs/superpowers/specs/2026-10-11-dataperm-predicate-design.md`（D29-D40）+ 契约 `docs/superpowers/contracts/2026-10-11-dataperm-predicate-api.md`（§2 校验链/§5 VO 逐字段/§6 label v2/§7 错误码）。

1. **B1** api 模块增量：dataperm 包八类（PredicateOperatorEnum/FilterColumnTypeEnum【matches+normalize 单一实现】/FilterColumn/FilterColumns【断言锚 entity 走类层级】/RowPredicate【test 五对齐规则】/RowGroup/RowFilter【isEmptyScope+allowsDetail】/EntityFieldResolver【字段缓存+父类遍历，未知字段 null】）+ domain 两 VO + ScopeVo 增 rowGroups 与 toRowFilter()【accounts 语义收窄 javadoc 记档】+ 六组测试（对齐矩阵为重）。
2. **B2** system：增量 SQL（前置断言+ALTER）与基线双落位；SysDataPermRule+Mapper XML 三处增列；DataPermResources 签名扩展+试点双清单声明+getFilterColumns；Evaluator resolveRowFilter（代数四步：解析降级/豁免判定/逐规则建组+enrichment/合并死组丢弃）+Decision 增 rowFilter【豁免态 dataScope 保持 all() 零 diff】+**InnerDataPermController toScopeVo 收敛**【rowAll 判定切 rowFilter.isRowAll+rowGroups 收敛（PredicateVo 自含 column/type）+四参构造改五参+既有测试机械补 stub】；Convert（JSON 编解码/中文摘要/label v2【无条件五态逐字保持】/digest∧段/summary cond=K）；ManageService 校验链（3035→1002 枚举→3036→3037→1002 重复→3037 数量→3037 序列化长度≤2000）+config/resources 增量；Checker 第三维度；单测增量（代数 ≥10 例+校验链逐码+toScopeVo 收敛）。
3. **B3** leave：SysLeaveMapper 签名换 RowFilter+XML canonical 模板（四列×操作符硬编码分支，DATE 转义，标签体多行）；LeaveManageService list 短路/传参+detail allowsDetail+EntityFieldResolver（VO 字符串形态）；既有测试零修改+谓词新增用例。
4. **B4** bpmn：BpmnApprovalMapper 同款（businessType STRING 仅 EQ/IN 两分支）；ApprovalQueryService toRowFilter 判定（entity typed 形态）；Runner 增 FilterColumns 断言行；既有测试零修改+新增；grep `toDataScope().allows` 调用点清零（api 方法本体保留）。
5. **B5** 联调冒烟八步（含向后兼容红线复证【删条件规则后与 v1 逐字一致】、fail-closed 复证、EXPLAIN 四项抽查记档）。
6. 红线：无条件规则全链零变化；契约 additive；fail-closed 无放行分支（归一失败/未知字段→拒绝）；守护测试零豁免（新 XML #{} only/标签体多行；service 新公共方法白名单前缀内）；mvn 全量构建全绿；错误码账本 3035-3037。

## 给 frontend-agent 的任务清单（直粘）

执行 F1（可与后端并行，联调验收等 B2 落库后），规范依据：契约 `docs/superpowers/contracts/2026-10-11-dataperm-predicate-api.md` §2/§5/§6/§8 + `/frontend-page` 技能。

1. `cloud-web/src/types/api.ts`：PredicateItem/FilterColumnVo 类型 + OPERATOR_*/FILTER_TYPE_*/上限常量 + OPERATOR_OPTIONS 与 `operatorsForType(type)` 矩阵镜像（STRING 仅 EQ/IN）——操作符中文与后端 label 对齐（双处冻结点 JSDoc 记档，禁散落字面量）。
2. `cloud-web/src/views/system/dataPerm/components/RuleConfigDialog.vue`：条件段（≤5 行增删；列 select←filterColumns 中文 label→操作符 select←operatorsForType→值输入器类型驱动【NUMBER 数值/DATE YYYY-MM-DD/DATETIME 带时分秒/STRING ≤50/BETWEEN 双值有序/IN 多值 tag ≤30】）；回显 config.predicates；提交 predicates（空=清空）；前端校验兜底（同列重复/必填/上限）；表单 javadoc 更新。
3. `cloud-web/src/views/system/dataPerm/index.vue`：规则表格「条件」列（predicateSummary，null 显示 `-`）。
4. `cloud-web/src/api/dataPerm.ts`：零新函数，JSDoc 补节号；leave/approval 提示条零改动（第六态纯透传，联调视觉核对即可）。
5. 验收：Playwright MCP 全流程（配 `leaveType=1` 条件规则：弹窗→保存→摘要列→回显；类型矩阵过滤；3035-3037 toast）；`npm run build` 与既有测试零回归；无条件规则弹窗视觉零差异；按 /frontend-page 测试规范留证。
