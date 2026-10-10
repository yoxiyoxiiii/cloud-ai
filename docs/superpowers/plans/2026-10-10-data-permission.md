# 数据权限控制 + 规则引擎实施计划（全栈：后端章 ∥ 前端章 + e2e 章）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地数据权限 MVP——部门树 + 结构化规则模型（行级五档/列级隐藏脱敏）+ 显式求值器 + 决策留痕/解释，sys_leave 试点收口列表/详情行级一致与 IDOR。

**Architecture:** 求值器 cloud-system 内聚（service/dataperm/，无注解无拦截器无 SQL 改写——Service 显式调 evaluator、mapper 显式传 DataScope、XML 显式 if/foreach）；规则表物理删、实时求值无缓存、规则不进 X-User-* header（网关/sso 零改动）。关键取舍全记设计 D1-D14（`docs/superpowers/specs/2026-10-10-data-permission-design.md`，**必读**）。

**Tech Stack:** Spring Boot 3.3.4 / MyBatis 手写 XML / Vue3+TS+EP 按需引入（cloud-web）/ e2e cloud-e2e 独立包。

**对齐基线**：契约 `docs/superpowers/contracts/2026-10-10-data-permission-api.md`（逐字段）+ 设计 D 系列 + `/backend-spec`、`/frontend-page` 技能 + CLAUDE.md 编码规范。

---

## 后端章（cloud-base/ + scripts/sql/，backend-agent）

### B1 DDL 与种子（四新表 + sys_user 加列 + 种子 + 执行落库）

**Files:**
- Create: `cloud-base/scripts/sql/2026-10-10-data-permission.sql`（增量）
- Modify: `cloud-base/scripts/sql/cloud_system.sql`（基线同步：四表 DDL + sys_user 列/索引 + 种子段同步追加）

- [ ] 步骤 1：按设计 §3 逐字落增量脚本——sys_dept（uk_parent_name；**不建 idx_parent_id**，前缀覆盖记档）、sys_data_perm_rule（uk_resource_subject，无 deleted 列）、sys_data_perm_column（uk_subject_column，无 deleted 列）、sys_data_perm_log（idx_account_time/idx_resource_time，无 deleted 列）、`ALTER TABLE sys_user ADD COLUMN dept_id ... ADD KEY idx_dept_id`；每列 COMMENT（守护机械检查）
- [ ] 步骤 2：种子段——根部门 id=1（is_builtin=1）；`UPDATE sys_user SET dept_id=1 WHERE id=1 AND deleted=0`；admin 行规则 `('leave',0,1,4)`（审计显式 admin/now）；菜单 7 行 `(15,'部门管理','system:dept:list','C','/system/dept','OfficeBuilding',5)`、`(151/152/153 按钮)`、`(16,'数据权限','system:dataPerm:list','C','/system/data-perm','Key',6)`、`(161 保存规则/'system:dataPerm:save')`、`(162 删除规则/'system:dataPerm:remove')` 全 is_builtin=1；admin 绑定 `INSERT INTO sys_role_menu SELECT 1,id FROM sys_menu WHERE id IN (15,151,152,153,16,161,162)`；头注红线（不可重放提示：种子 id 固定，重放先 DELETE 对应 id）
- [ ] 步骤 3：基线 cloud_system.sql 同步同一份定义（DROP 列表 + 四表 + 种子段；沿 dict 先例双落位）
- [ ] 步骤 4：执行落库（MySQL 无客户端——java 单文件源码 + mysql-connector-j 从 ~/.m2 取，路径实现时验证；jshell 后台会挂起禁用）；执行前核对 7 个菜单 id 与部门 id=1、规则首行 id 空闲
- [ ] 验收：四表 SHOW CREATE TABLE 与脚本一致；`SELECT` 回查种子行数（dept=1、menu+7、role_menu+7、rule=1、admin.dept_id=1）；既有表数据零破坏
- [ ] Commit

### B2 部门域（entity/mapper/xml/service/controller + 单测）

**Files:**
- Create: `cloud-system/src/main/java/com/cloudai/system/entity/SysDept.java`（继承 BaseEntity；内嵌 `StatusEnum`，Enum 后缀）
- Create: `cloud-system/src/main/java/com/cloudai/system/mapper/SysDeptMapper.java` + `resources/mapper/SysDeptMapper.xml`
- Create: `dto/DeptSaveRequest.java`、`dto/DeptTreeNode.java`（树节点 DTO，沿 MenuTreeNode 先例）、`vo/SysDeptVo.java`（如与 TreeNode 合一则记档省略）
- Create: `service/SysDeptManageService.java`、`controller/SysDeptController.java`
- Create: `util/DeptTreeBuilder.java`（全量 list → 森林；沿 MenuTreeBuilder 先例）+ `test/.../util/DeptTreeBuilderTest.java`
- Modify: `test/.../ArchitectureGuardTest.java`（master 表正则补 `\bsys_dept\b`——sys_dept 是带 deleted 的主表）

- [ ] Mapper 方法集（动词集省实体名）：`listAll`（deleted=0，ORDER BY sort ASC,id ASC——树数据源）/ `findById` / `countByParentAndName(parentId,name,excludeId)` / `countByParentId(parentId)` / `save` / `update`（动态 `<set>`）/ `deleteById`（三参逻辑删）。XML 全 `#{}`、`<if>` 换行、无 LIMIT
- [ ] Service：save（3027 parentId→1002 name→3028 查重→审计四值→DuplicateKey 兜底 3028 先 log.error）；update（3027→parentId 与库值不一致 1002「暂不支持修改上级部门」→3028→动态更新两值）；delete（3027→is_builtin 3029→countByParentId>0 或用户挂载>0 → 3030→逻辑删）；**listTree 组树含停用**（tag 区分，沿 dict §1）；事务口径：全单语句不加 @Transactional
- [ ] Controller 四端点对齐契约 §2（tree 挂 `hasAnyAuthority('system:dept:list','system:user:add','system:user:edit')`；两行式 + 显式 @PathVariable + javadoc）
- [ ] SysUserMapper 增量（本任务一并）：`countByDeptId(deptId)`（`WHERE dept_id=#{deptId} AND deleted=0`——删除校验「在职用户」含停用账号）
- [ ] 单测：DeptTreeBuilderTest（多层/孤儿子挂根森林/空表）+ SysDeptManageServiceTest（Mockito mock mapper：save 链 4 例、update 禁改上级 1 例、delete 三拦 3 例）
- [ ] 验收：`mvn -f cloud-base/pom.xml test -pl cloud-system -am` 全绿（守护正则探针：SysDeptMapper.xml 任一 select 故意去 deleted 应红）
- [ ] Commit

### B3 用户域 dept_id 增量

**Files:**
- Modify: `cloud-system/src/main/java/com/cloudai/system/entity/SysUser.java`（+deptId）、`dto/UserSaveRequest.java`（+deptId Long 可空）、`vo/SysUserVo.java`（+deptId/deptName）
- Modify: `resources/mapper/SysUserMapper.xml`（save/update 加 dept_id 列；pageList 改 `resultType=SysUserVo` + `LEFT JOIN sys_dept d ON u.dept_id=d.id AND d.deleted=0` 取 `d.name AS deptName`——VO 直出改型记档设计 D14）
- Modify: `service/SysUserManageService.java`（save/update deptId 传入时 3027 校验；pageList 去实体 convert 直返 VO——改型说明进 javadoc）、`convert/SysUserConvert.java`（findById 链路补 deptId 映射）
- Modify: `test/.../SysUserManageServiceTest.java`（增量例：deptId 无效 3027、null 不更新）

- [ ] 注意：pageList resultType 换 VO 后 `password` 列绝不可再 SELECT（allColumns 投影列改 `id,account,nickname,dept_id,status,is_builtin,create_by,...` 原全列不含 password 即可保持）；`deleted` 条件保留（守护按 sys_user 词边界检查）
- [ ] 验收：单测绿 + 既有用户管理 e2e 场景零回归（分页列不变）
- [ ] Commit

### B4 数据权限核心组件（service/dataperm/ + 三 mapper + 求值器矩阵单测）

**Files:**
- Create: `service/dataperm/DataScope.java`（final 不可变：all/accounts + 静态工厂 + isEmptyScope + allows(owner)）
- Create: `service/dataperm/ColumnScope.java`（final 不可变：hiddenColumns/maskedColumns + isHidden/isMasked + mask（null 直返 null，否则 `***`）+ isEmpty）
- Create: `service/dataperm/DataPermResources.java`（final 常量类：`LEAVE="leave"`；`configurableColumns(resource)`；`assertResource`/`assertColumn` 抛 3034）
- Create: `service/dataperm/DataPermOperation.java`（常量 LIST/DETAIL/DENY，String 域沿 LeaveStatus 先例）
- Create: `service/dataperm/DataPermDecision.java`（含内嵌 HitRule：ruleId/subjectType/subjectName/rowScope/customCount/expandedCount）
- Create: `service/dataperm/DataPermEvaluator.java`（@Service；`evaluate(resource,operation,businessKey)` 当前登录人恒留痕 + `explain(account,resource)` 不留痕 + `logDeny(resource,businessKey)` deny 补痕；方法 ≤50 行拆私有）
- Create: `entity/SysDataPermRule.java`（**不继承 BaseEntity**，自持审计四字段；内嵌 `RowScopeEnum{SELF(0),DEPT(1),DEPT_AND_CHILD(2),CUSTOM(3),ALL(4)}` + `SubjectTypeEnum{ROLE(0),USER(1)}`，Enum 后缀）、`entity/SysDataPermColumn.java`（内嵌 `ActionEnum{HIDDEN(0),MASKED(1)}`）、`entity/SysDataPermLog.java`
- Create: `mapper/DataPermRuleMapper.java`+XML、`mapper/DataPermColumnMapper.java`+XML、`mapper/DataPermLogMapper.java`+XML
- Modify: `mapper/SysUserRoleMapper.java`+XML（+`listEnabledRoleIdsByUserId(userId)`：JOIN sys_role status=0 deleted=0）
- Modify: `mapper/SysUserMapper.java`+XML（+`listEnabledAccountsByDeptIds(deptIds)`：`WHERE dept_id IN <foreach> AND status=0 AND deleted=0`，**Service 层空集合跳过调用**防 `IN ()`）
- Modify: `cloud-common/cloud-common-security-starter/.../SecurityUtils.java`（+`currentUser()` 返回 LoginUser，匿名 null——additive；currentAccount 可改委托实现）

- [ ] Mapper 方法集：Rule——`listByRoleIds(resource,roleIds)`（`WHERE resource=#{resource} AND subject_type=0 AND subject_id IN <foreach>`；**调用方 roleIds 空时跳过**）/ `listByUserId(resource,userId)` / `findBySubject(resource,subjectType,subjectId)` / `save` / `update`（row_scope/custom_accounts+审计两值）/ `deleteById` / `pageList(resource,subjectType,subjectId)` 动态 `<if>`（**无 deleted 条件——物理删表**）/ `deleteBySubject`。Column——`listByRoleIds`/`listByUserId`/`listBySubject`/`saveBatch`（foreach 批插）/ `deleteBySubject`。Log——`save` / `pageList(account,resource)` 动态
- [ ] doEvaluate 求值序（设计 §5.2）：注册表 3034 → 角色 ids（空集跳过角色段查询）→ 行规则两查 → 收敛（任一 ALL→`DataScope.all()`；否则逐档展开并集：SELF→{account}；DEPT/DEPT_AND_CHILD→查 sys_user.dept_id（NULL→空集+narrative「无部门」）→deptMapper.listAll 内存子树→listEnabledAccountsByDeptIds；CUSTOM→JSON 解析异常 log.error 空集）→ 列规则两查合并（同列取最宽松：概念序 可视>脱敏>隐藏）→ narratives（逐规则一句+收敛句+列决策句）→ persistLog 时组装留痕行 insert（**catch log.error 不抛**——D7）
- [ ] 留痕摘要生成：rule_digest（`role:{名}(id={id}){档位名}展开{N}人|user:{账号}...` 截断 500）/ scope_summary（all/accounts=N/empty）/ column_summary（`key:动作名;` 或 null）
- [ ] **DataPermEvaluatorTest 矩阵 ≥10 例**（设计 §10.1 全清单，逐例落测试方法：无规则默认 SELF / ALL 短路 / SELF+CUSTOM 并集 / DEPT 有无部门 / DEPT_AND_CHILD 子树 / CUSTOM 坏 JSON 空集降级 / 列合并宽松者胜（HIDDEN+MASKED→MASKED）/ 未知资源 3034 / 留痕 insert 调用与异常不影响返回 / explain 不触留痕）
- [ ] 验收：单测全绿；`service/dataperm` 包无 AOP/注解拦截痕迹（纯显式调用，设计 D5 自检）
- [ ] Commit

### B5 规则管理与排查接口（DataPermManageService/Controller + 单测）

**Files:**
- Create: `dto/DataPermRuleSaveRequest.java`（含内嵌 `ColumnRuleItem{columnKey,action}`）、`vo/DataPermRuleVo.java`（含内嵌 ColumnRuleVo）、`vo/DataPermRuleConfigVo.java`、`vo/DataPermResourceVo.java`、`vo/SubjectOptionVo.java`、`vo/DataPermLogVo.java`、`vo/DataPermExplainVo.java`（含内嵌 HitRuleVo/ColumnDecisionVo）、`vo/MyScopeVo.java`
- Create: `service/DataPermManageService.java`、`controller/DataPermController.java`
- Create: `test/.../DataPermManageServiceTest.java`

- [ ] Service：`pageList`（动态筛选+服务层补 subjectName：subjectType 分流批量二查角色名/用户昵称，≤2 次 IN）；`findConfig`（行规则判存 configured + 列规则 listBySubject；主体无效 3032）；`save`（校验链：注册表 3034→枚举 1002→主体 3032→CUSTOM 非空 1002+逐账号 3033→非 CUSTOM 带账号 1002→columns 去重 1002；**@Transactional**：findBySubject 判存 insert/update + 列规则 deleteBySubject+saveBatch 空列表跳过）；`delete`（3031→**@Transactional**：行规则 deleteById + 列规则 deleteBySubject 连带）；`listResources`；`listSubjectOptions(type)`（角色 listEnabledOptions 新增（status=0）/用户复用 listEnabledOptions，label 后端拼 `昵称(账号)`）；`pageLog`；`explain(account,resource)`（evaluator.explain 转 VO）；`myScope(resource)`（当前登录人求值转 label：全部/仅自己/自定义范围（含自己，共 N 人）/指定范围（N 人）/无（空范围））
- [ ] Controller 九端点对齐契约 §3（rule/page、rule/config、POST rule、DELETE rule/{id}、resources、subject-options、log/page、explain、my-scope——**my-scope 免 @PreAuthorize**，javadoc 记设计 D12 理由；其余 list/save/remove 分权）
- [ ] 单测：saveRule 校验链 7 例（3032/3033/3034×2/CUSTOM 空/非 CUSTOM 带账号/列重复）+ upsert 覆盖（二进二出）+ deleteRule 连带列规则 verify；pageList 补名分流 2 例
- [ ] 验收：单测全绿；Controller 全方法 javadoc + @PreAuthorize 映射契约（my-scope 除外）
- [ ] Commit

### B6 leave 读路径改造（试点落点）

**Files:**
- Modify: `service/LeaveManageService.java`（pageListMy→`pageList(query)` 求值版；findById 行级判定+列级；批量回填 applyUserName/approverName）
- Modify: `controller/LeaveController.java`（page 调用签名变更；detail 不变——判定在 Service）
- Modify: `mapper/SysLeaveMapper.java` + `resources/mapper/SysLeaveMapper.xml`（pageList 第三参 applyUser→`scope`（DataScope）；XML `<if test="scope.all == false">` + foreach accounts——**标签体换行**）
- Modify: `test/.../LeaveManageServiceTest.java`

- [ ] pageList：`decision = evaluator.evaluate(LEAVE, LIST, null)` → `scope.isEmptyScope()` 返回 `PageResult.of(0L, List.of())` **不查库** → mapper pageList(page, businessType, scope) → findUserNames 批量回填两昵称 → applyColumnScope（title hidden→null；reason hidden→null/masked→`***`）
- [ ] findById：查行 null→3018 → evaluate(LEAVE, DETAIL, id 串) → `!scope.allows(vo.getApplyUser())` → `evaluator.logDeny(LEAVE, id)` + throw **3026** → 通过则列级+既有译文回填
- [ ] 常量：类内 `RESOURCE_LEAVE = DataPermResources.LEAVE` 复用；错误码 3026 常量
- [ ] 单测增量：空集短路（verify mapper 零调用）/ detail 拒绝 3026+logDeny verify / 列级 hidden/masked 两例 / 通过路径昵称回填
- [ ] 验收：单测全绿；XML 变更段 `<if>` 换行（守护）；**my-scope 契约例**：admin（种子 ALL）page 全量、普通用户（无规则）仅自己——回归旧语义
- [ ] Commit

### B7 全量构建与守护验证

- [ ] `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install` 全绿（402+ 基线 + 本轮新增；ArchitectureGuardTest 含 sys_dept 探针红绿双态取证）
- [ ] 守护自检清单（backend-spec 检查清单全项）：新表 SQL 无 deleted 条件仅限三张新表（物理删/流水记档）；Controller 禁 import mapper；两行式；`#{}` only；分页无 LIMIT
- [ ] Commit（如有守护适配）

### B8【卡点：需重启 cloud-system 9202】curl 验收（经网关 18080，ASCII 入参）

- [ ] 服务由 agent 自管启停（java -jar；网关/sso 不动）；admin 重新登录取 token
- [ ] 验收链（错误码 3026-3034 逐个触发 + 契约 §10 矩阵）：dept 增删改查全链（3027/3028/3029/3030/1002）→ 建 `e2ecurldp` 前缀测试部门+用户（挂部门）→ 主管角色配 DEPT_AND_CHILD 规则（saveRule 3032/3033/3034 触发）→ 三账号 leave/page 行集三态 + my-scope 标签 → explain 复算一致 → log/page 留痕要素（list/detail/deny 各验证）→ 普通用户越权 detail 3026 → 用户管理 deptId 挂载换部门（3027）→ 数据清理（测试部门/用户/规则删净，`e2ecurldp` 前缀）
- [ ] EXPLAIN 抽查（java 单文件源码）：leave/pageList 的 apply_user IN 命中 idx_apply_user；log/pageList 命中 idx_account_time——结论记回报（设计 §12 验收）
- [ ] 完成后回报主控（解锁前端联调）

## 前端章（cloud-web/，frontend-agent）

### F1 类型字典扩展

**Files:**
- Modify: `cloud-web/src/types/api.ts`

- [ ] 增量（全部注契约节号）：`SysDeptTreeNode`（§6.1，children 递归）、`DeptSavePayload`（§2.2）、`DataPermRuleVo`+`ColumnRuleVo`（§6.2）、`DataPermRuleConfigVo`（§6.3）、`DataPermResourceVo`/`SubjectOptionVo`（§6.4/§6.5）、`DataPermLogVo`（§6.6）、`DataPermExplainVo`+`HitRuleVo`+`ColumnDecisionVo`（§6.7）、`MyScopeVo`（§6.8）；`SysUserVo` +`deptId/deptName`、用户 Payload +`deptId?`；`SysLeaveVo` 注释增量（applyUserName 恒返/reason 可脱敏）
- [ ] 常量（§7）：ROW_SCOPE_*/ACTION_*/SUBJECT_*/OP_*；id/total 一律 string（Long→String）
- [ ] Commit

### F2 api 层（两新文件 + 两增量）

**Files:**
- Create: `cloud-web/src/api/dept.ts`（treeDept/createDept/updateDept/deleteDept）
- Create: `cloud-web/src/api/dataPerm.ts`（pageRule/getRuleConfig/saveRule/deleteRule/listResources/listSubjectOptions/pageDataPermLog/explainDataPerm/getMyScope）
- Modify: `cloud-web/src/api/user.ts`（Payload +deptId）、`api/systemLeave.ts`（不变——page/detail 签名零变化，注释补语义变更说明）

- [ ] 一函数=一契约端点，JSDoc 注节号；URL 网关前缀 `/system/...`
- [ ] Commit

### F3 部门管理页 + 用户表单部门选择

**Files:**
- Create: `cloud-web/src/views/system/dept/index.vue`、`views/system/dept/components/DeptFormDialog.vue`
- Modify: `views/system/user/components/UserFormDialog.vue`（+部门 el-tree-select：数据源 treeDept，check-strictly，可清空=不挂）
- Modify: `src/router/viewRegistry.ts`（或动态路由注册位——实现时按 dict 页先例）+`'/system/dept'`

- [ ] 部门页：树形 el-table（`row-key="id"` + `:tree-props` + default-expand-all）列：名称/排序/状态 tag/创建时间/操作（新增子级·编辑·删除——builtin 行删除禁用）；`defineOptions({ name: 'SystemDept' })`；rowOf 收窄函数全页唯一断言点
- [ ] DeptFormDialog：add（parentId 由入口注入：根/选中行）/ edit（上级只读展示——契约 §2.3 禁改）；名称/排序/状态 radio；watch(modelValue) 回显+clearValidate；loading 防重；catch 留空
- [ ] 删除：ElMessageBox.confirm 文案含部门名；3029/3030 拦截器 toast 后刷新
- [ ] Commit

### F4 数据权限页（三 tab）

**Files:**
- Create: `cloud-web/src/views/system/dataPerm/index.vue`、`views/system/dataPerm/components/RuleConfigDialog.vue`
- Modify: `viewRegistry.ts` +`'/system/data-perm'`

- [ ] index：`defineOptions({ name: 'SystemDataPerm' })`；读取 route.query（subjectType/subjectId）作规则 tab 预筛选（角色页联动入口）；el-tabs：
  - **规则配置**：筛选行（资源 select 数据源 listResources + 主体类型 select）+ 新增规则按钮（v-perms system:dataPerm:save）+ 表格（资源/主体类型 tag/主体名称/行档位 tag（五档中文映射）/自定义人数/列摘要（`reason:脱敏` 串直显）/更新时间/操作：配置·删除（v-perms remove，确认框含主体名））+ 分页（total Number()）
  - **决策留痕**：筛选（账号 input + 资源 select）+ 表格（时间/账号/资源/操作 tag（list/detail/deny——deny 红）)/规则摘要/范围结论/列结论/业务键）+ 分页
  - **模拟解释**：账号 select（listSubjectOptions type=1）+ 资源 select + 解释按钮 → narratives el-timeline 逐句 + 命中规则 el-table（ruleId/主体/档位/展开数）+ 列决策 el-table；空结果 el-empty
- [ ] RuleConfigDialog：主体类型 radio（角色/用户）→ 主体 select（对应 options，编辑态锁定）→ 行档位 radio 五档（中文）→ CUSTOM 显示账号多选 el-select multiple filterable（options type=1）→ 列配置：listResources 按资源下发可配列，逐列 el-select（默认可视/脱敏/隐藏）；打开时 getRuleConfig 回显（configured=false 走新增默认 SELF）；提交 saveRule 全量 payload（非 CUSTOM 档强制清空 customAccounts）；成功 emit('success') 刷新
- [ ] Commit

### F5 角色管理联动 + leave 页增量

**Files:**
- Modify: `cloud-web/src/views/system/role/index.vue`（操作列 +「数据权限」文字按钮，v-perms system:dataPerm:list，router.push 带 query 预筛选）
- Modify: `cloud-web/src/views/system/leave/index.vue`

- [ ] leave 页：onMounted 调 getMyScope('leave') → 顶部 el-alert（type=info，closable=false）文案 `当前数据范围：{scopeLabel}` + columnSummary 有值时追加 `（{columnSummary}）`；表格 +「申请人」列（applyUserName，空降级显示 applyUser）；reason 脱敏（`***`）/隐藏（空）原样展示；**404 断言不变**——page 签名零变化
- [ ] my-scope 失败静默降级（提示条不渲染）——catch 留空
- [ ] Commit

### F6 构建与联调（agent 自管 dev 5173）

- [ ] 连续两次 `npm run build` 全绿（TS strict；components.d.ts 生成物坑）
- [ ] 后端 B8 完成后联调：契约 §10 三账号矩阵走查（admin 全部/主管部门集/员工仅自己）+ reason 脱敏 + 3026 toast + 留痕页数据对齐；后端未就绪先按契约 mock 并注明
- [ ] Commit

## e2e 章（cloud-e2e/，frontend-agent 或主控指派）

### E1 既有断言维护（+2 菜单，与功能代码同 commit）

- [ ] 全局 grep 侧边导航硬编码断言（沿 dict §0.3 清单模式：run-menu-e2e/run-nav-e2e/run-scaffold 等）补「部门管理」「数据权限」两项；admin 重登后可见
- [ ] Commit

### E2 新场景脚本 run-dataperm-e2e.mjs（+ package.json 链 `e2e:dataperm`）

- [ ] 场景序（有头 slowMo 300；`e2e` 前缀数据；**不碰 admin 种子**——admin 只读操作）：
  1. admin 登录：部门管理建树（`e2edept{ts}` 根下两级）→ 内置根删除被拦（3029 toast）→ 建测试角色 + 测试用户 A 挂子部门（主管角色）/用户 B 无规则
  2. 数据权限页：配主管角色规则（DEPT_AND_CHILD + reason 脱敏）→ 决策留痕 tab 有记录 → 模拟解释 tab 选用户 A 出 narratives
  3. 用户 A 登录：leave 页提示条「本部门及以下」→ 发起一单 → 列表见自己单 reason=`***`；用户 B 登录：提示条「仅自己」→ 列表仅自己
  4. admin 视角：列表全量（含 A/B 单）；用户 B 直接 fetch 详情 A 的单据 id → 3026 toast
  5. 清理：规则/角色/用户/部门删净（B/C 级联顺序：先解挂用户再删部门）
- [ ] 截图 ≥5 张（部门树页/规则配置弹窗/留痕 tab/解释 tab/leave 提示条+脱敏列）→ analyze_image 视觉核对（结论文字记录，artifacts 不进 git）
- [ ] `npm run e2e` 全量回归（既有场景零失败）
- [ ] Commit（脚本与功能代码同 commit 原则——补提交此处）

## 集成与验收编排（主控）

1. B 章 → B8 卡点（重启 9202 + admin 重登）→ F 章联调 → E 章回归
2. 交付物核对：三文档一致性（设计 D 系列 ↔ 契约端点 ↔ 计划任务）；契约 §10 验收矩阵全过
3. 移交备忘（下方）随 commit 归档

## 移交备忘（下一阶段规划前必读）

1. **bpmn 跨服务接入**（设计 §11.1）：求值器接口化 + cloud-system-api 抽模 + Feign + 本地缓存；接入模板设计 §7.3 三步
2. **IN 大集合优化**（设计 §11.2）：大部门账号集合分批/临时表/部门直滤列三路候选
3. **留痕治理**：采样/异步写/xxl-job 清理（沿 MqTableCleanJob 先例，保留窗口 30-90 天）——现全量同步表从空起步
4. **规则缓存与失效**：现实时求值（D10）；量大后本地缓存+主动失效，失效 bug 为经典事故源需专项设计
5. **列脱敏策略多样化 / 列级严格模式**（设计 §11.5/§11.6）
6. **部门运维增强**：改上级迁移/多部门挂载/path 列/停用部门求值收紧
7. **sys_user 列表第二试点**：拍板明确本轮不做——「子管理员只管本部门用户」启用时照 §7.3 模板接入
8. **错误码账本**：3xxx 现用至 3034，下轮 3035 起接续并在新契约声明
9. **外引规则引擎**（Drools/LiteFlow）未采用记档：风控/定价类场景出现时另案，数据权限域不回潮

## 给 backend-agent 的任务清单（可直接粘发）

按 B1→B8 执行；对齐基线 = 契约 `docs/superpowers/contracts/2026-10-10-data-permission-api.md` + 设计 `2026-10-10-data-permission-design.md`（D3/D5/D7/D9/D10 必读）+ 本计划后端章 + `/backend-spec` 技能 + CLAUDE.md 编码规范。要点重申：

- B1：增量 `2026-10-10-data-permission.sql` + 基线 cloud_system.sql 双落位；执行前核对 id 空闲（菜单 15/16/15x/16x、部门 1、admin 规则）；java 单文件源码执行 + 回查四组种子
- B2-B3：部门域全套（tree 端点 hasAnyAuthority 三权限——D12）+ 用户 deptId 增量（pageList VO 直出改型 D14，allColumns 投影不含 password 保持）
- B4：service/dataperm 六类 + 三 mapper（规则表物理删无 deleted 条件——D9；roleIds 空跳过 IN 查询防 `IN ()`）+ SecurityUtils.currentUser() additive + 求值矩阵 ≥10 例
- B5：九端点（my-scope 免 @PreAuthorize——D12 记档）+ saveRule @Transactional upsert 覆盖语义
- B6：leave 改造（空集短路不查库 / detail allows 判定 3026 + logDeny / 列级 title/reason）+ XML scope 显式 if/foreach（标签体换行）
- B7：全量 clean install 全绿 + 守护 sys_dept 探针红绿取证
- B8：【卡点】重启 9202 + admin 重登 + 契约 §10 三账号矩阵 + 错误码 3026-3034 逐个触发 + EXPLAIN 两抽查 + e2ecurldp 数据删净
- 红线：与契约不符回报主控不自行猜测；不碰网关/sso/既有种子；网关 AuthGlobalFilter 与 OnlineSession 零改动（D8）；完成后回报解锁前端联调

## 给 frontend-agent 的任务清单（可直接粘发）

按 F1→F6 + E1-E2 执行；对齐基线 = 契约 `2026-10-10-data-permission-api.md`（§6 VO 表逐字段）+ 设计 `2026-10-10-data-permission-design.md`（D11/D12/§8 页面表）+ 本计划前端章 + `/frontend-page` 技能。要点重申：

- F1-F2：类型全量注节号 + 常量禁魔法数；id/total 一律 string
- F3：部门树页（el-table tree-props）+ DeptFormDialog（edit 上级只读——契约 §2.3）+ UserFormDialog 部门 el-tree-select
- F4：数据权限页三 tab + RuleConfigDialog（getRuleConfig 回显 configured 分流；非 CUSTOM 清空 customAccounts）
- F5：角色页「数据权限」按钮（v-perms + query 预筛选）+ leave 页 my-scope 提示条（失败静默）+ 申请人列
- F6：连续两次 build 全绿 + dev 5173 自管联调（后端未就绪按契约 mock 注明）
- E1-E2：导航断言 +2 维护；run-dataperm-e2e.mjs 五段场景 + `e2e:dataperm` 链 + 全量回归；截图 ≥5 视觉核对（结论文字记录）；e2e 前缀数据 + 不碰 admin 种子 + 清理干净
- 红线：EP 按需引入（禁全量）；契约与实测不符回报主控；场景脚本与功能代码同一 commit；e2e 黑盒禁 import 前端内部代码
