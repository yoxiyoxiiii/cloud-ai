# 数据权限控制 + 规则引擎技术方案（全栈 A 级：部门树 + 结构化规则模型 + 显式求值器 + 决策留痕 + sys_leave 试点）

- 日期：2026-10-10
- 状态：**定稿**——架构-agent 出品，配合拍板结论（阶段一《需求分析》五项，2026-10-10）；契约 `docs/superpowers/contracts/2026-10-10-data-permission-api.md`、计划 `docs/superpowers/plans/2026-10-10-data-permission.md`
- 拍板结论映射：① 自建结构化规则模型（Drools 等外引引擎留作风控/定价未来场景，见 §11）；② 本轮补建 sys_dept；③ 显式编程式拦截点（无注解/无拦截器/无 SQL 改写）；④ 列级权限本轮做（可见性 + 脱敏）；⑤ sys_leave 单试点（列表+详情一致治理），bpmn 跨服务留扩展点

## 1. 需求与范围

### 1.1 目标

同一接口（列表/详情）按登录人的数据权限规则返回不同行集与列形态，且**每次权限决策可观察、可排查**：命中了哪条规则、展开了什么范围、生成了什么过滤条件、为什么被过滤/拒绝——全部留痕可查、可模拟解释。

### 1.2 本轮落（MVP 边界）

| 项 | 内容 |
|---|---|
| 组织维度 | sys_dept 部门树（DDL/CRUD/前端树页）+ sys_user.dept_id 单挂可空 + 存量挂载种子 |
| 规则模型 | 行级五档（仅自己/本部门/本部门及以下/自定义集合/全部）+ 列级（隐藏/脱敏），主体=角色为主+用户直绑补充 |
| 求值器 | 显式编程式 DataPermEvaluator（cloud-system 内聚），多规则并集收敛，实时求值 |
| 可观察 | 决策留痕表 + 留痕查询页 + explain 模拟解释 API + my-scope 自查 |
| 试点 | sys_leave：分页与详情行级一致治理（替换硬编码 apply_user 过滤 + 收口详情 IDOR）+ 列级示例（title/reason） |
| 前端 | 部门管理页、数据权限页（规则配置/决策留痕/模拟解释三 tab）、角色管理联动入口、用户表单部门选择、leave 页范围提示条 |

### 1.3 本轮不落（记 §11 移交）

bpmn 跨服务域（扩展点已留）、sys_user 列表试点第二载体、导出/统计、规则热更新/复杂编排、缓存与失效（MVP 实时求值）、IN 大集合优化、留痕采样/异步/清理、列脱敏策略多样化、部门迁移（改上级）/多部门挂载。

## 2. 现状盘点（2026-10-10 逐项核实）

1. **无 sys_dept**：`sys_user` 无 dept_id；初版设计「部门/岗位」在阶段 2+3 按最小闭环砍掉——本轮补建是既定路线兑现
2. **影子数据权限三处**：`SysLeaveMapper.pageList` 硬编码 `WHERE l.apply_user = #{applyUser}`（恒"我的"）；`BpmnApprovalMapper.pageList` 同款（cloud-bpmn，本轮不动）；**`LeaveController GET /{id}` 详情无归属校验（IDOR 面）**——本轮试点对象
3. **RBAC 资产可复用**：角色/菜单/关联 5 表 + 角色管理页（AssignMenuDialog 树形配置先例）+ OnlineSession/X-User-Perms 链路（**本轮零改动**——规则不进 header，见 D8）+ 错误码 system 段现用至 3025
4. **求值输入链路**：`SecurityUtils.currentAccount()` 仅有账号——需 additive 增 `currentUser()`（返回 LoginUser 含 userId）；LoginUser 无 deptId（求值器查库获取，取舍见 D10）
5. **守护测试**：`ArchitectureGuardTest.master_table_sql_must_handle_deleted` 主表名单硬编码 `sys_user|sys_role|sys_menu|sys_leave`——sys_dept 入名单；规则/列规则/留痕表物理删/流水语义**不在名单零冲突**（沿纯关系表物理删先例，D9）
6. **翻译框架**：`TranslationCacheService.findUserNames` 批量账号→昵称（列表回填申请人昵称复用，无需扩翻译 advisor）

## 3. 数据设计（增量 `scripts/sql/2026-10-10-data-permission.sql` 与基线 `cloud_system.sql` 同步落同一份定义）

### 3.1 sys_dept（部门树主表，逻辑删除沿 sys_menu 先例）

```sql
CREATE TABLE sys_dept (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '部门ID',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父部门ID，0为根',
    name        VARCHAR(30) NOT NULL COMMENT '部门名称',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序（同级内升序）',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    is_builtin  TINYINT     NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置根部门（禁删），0=用户创建',
    create_by   VARCHAR(30) DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30) DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_parent_name (parent_id, name)
) ENGINE = InnoDB COMMENT = '部门表（树形，parent_id=0 为根；同级重名由 uk_parent_name 约束）';
```

索引取舍：`uk_parent_name` 一键三用——同级重名查重（countByParentAndName）/ 父节点子级扫描（最左前缀 parent_id，树页与删除校验）/ 求值器无按父查询路径（求值器全量 listAll 内存组树）；不另建 `idx_parent_id`（被 uk 最左前缀覆盖，沿 sys_dict_data `uk_type_value` 先例）；不建 deleted 单列（0/1 低选择性，规范明令）。

### 3.2 sys_data_perm_rule（行级规则，物理删除——D9）

```sql
CREATE TABLE sys_data_perm_rule (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    resource        VARCHAR(50)   NOT NULL COMMENT '资源标识（DataPermResources 注册表管辖，如 leave）',
    subject_type    TINYINT       NOT NULL COMMENT '主体类型：0=角色 1=用户',
    subject_id      BIGINT        NOT NULL COMMENT '主体ID（subject_type=0 时 sys_role.id，=1 时 sys_user.id）',
    row_scope       TINYINT       NOT NULL COMMENT '行范围档位：0仅自己 1本部门 2本部门及以下 3自定义集合 4全部',
    custom_accounts VARCHAR(1000) NULL     COMMENT '自定义账号集合（row_scope=3 时生效，JSON 数组字符串如 ["zhang3","lisi4"]；其余档位 NULL）',
    create_by       VARCHAR(30)   DEFAULT NULL COMMENT '创建人',
    create_time     DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_by       VARCHAR(30)   DEFAULT NULL COMMENT '更新人',
    update_time     DATETIME      DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_subject (resource, subject_type, subject_id)
) ENGINE = InnoDB COMMENT = '数据权限行级规则（配置态数据物理删除无 deleted 列；同主体同资源至多一条，upsert 依据即 uk）';
```

索引取舍：`uk_resource_subject` 兼查重与求值查询（求值器按 resource 等值 + subject 过滤，最左前缀命中）；无 deleted 列（D9：规则删除=物理 DELETE，墓碑占 uk 会废掉 upsert「删了再配」语义；配置历史观察职责归决策留痕表）。

### 3.3 sys_data_perm_column（列级规则，物理删除同 D9）

```sql
CREATE TABLE sys_data_perm_column (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '列规则ID',
    resource     VARCHAR(50) NOT NULL COMMENT '资源标识（同 sys_data_perm_rule.resource）',
    subject_type TINYINT     NOT NULL COMMENT '主体类型：0=角色 1=用户',
    subject_id   BIGINT      NOT NULL COMMENT '主体ID',
    column_key   VARCHAR(50) NOT NULL COMMENT '列标识（资源注册表 VO 字段名，如 reason；配置经 3034 校验）',
    action       TINYINT     NOT NULL COMMENT '列动作：0隐藏 1脱敏',
    create_by    VARCHAR(30) DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by    VARCHAR(30) DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_column (resource, subject_type, subject_id, column_key)
) ENGINE = InnoDB COMMENT = '数据权限列级规则（同 D9 物理删除；一主体一资源一列至多一条）';
```

索引取舍：`uk_subject_column` 兼查重与求值/回显查询（最左前缀 resource+subject 覆盖按主体取列规则）；列规则按 save 全删全插维护，无独立 update 路径。

### 3.4 sys_data_perm_log（决策留痕，只插不删的流水表——沿 mq_tx_log 先例）

```sql
CREATE TABLE sys_data_perm_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '留痕ID',
    account        VARCHAR(30)  NOT NULL COMMENT '决策对象账号（求值时的登录人）',
    resource       VARCHAR(50)  NOT NULL COMMENT '资源标识',
    operation      VARCHAR(20)  NOT NULL COMMENT '操作：list 分页查询 / detail 详情查询 / deny 详情被拒（安全审计事件）',
    rule_ids       VARCHAR(500) NULL     COMMENT '命中行规则ID集合（逗号分隔；NULL=无规则命中走默认档）',
    rule_digest    VARCHAR(500) NULL     COMMENT '决策摘要（命中规则与档位可读描述，如 role:主管(id=2)本部门及以下|user:zhang3自定义集合2人）',
    scope_summary  VARCHAR(200) NOT NULL COMMENT '行范围结论：all=过滤豁免 / accounts=N（展开账号数）/ empty=空集',
    column_summary VARCHAR(200) NULL     COMMENT '列决策结论（如 reason:脱敏;title:隐藏；NULL=无列动作）',
    business_key   VARCHAR(64)  NULL     COMMENT '业务键（detail/deny 时为目标单据 id；list 为 NULL）',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '决策时间',
    PRIMARY KEY (id),
    KEY idx_account_time (account, create_time),
    KEY idx_resource_time (resource, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='数据权限决策留痕（每次真实查询决策一条 + 详情拒绝补记 deny；排查页按账号/资源检索；只插不删，清理策略移交）';
```

索引取舍：`idx_account_time` 命中排查页主路径「按用户查决策史」（等值+时间倒序范围）；`idx_resource_time` 命中「按资源查」（排查某资源全体决策）；无 update 路径；清理任务（保留窗口）移交（§11）。

### 3.5 sys_user 增量（加列 + 索引）

```sql
ALTER TABLE sys_user
    ADD COLUMN dept_id BIGINT NULL COMMENT '部门ID（sys_dept.id；NULL=未挂部门，部门类档位求值展开为空集）' AFTER nickname,
    ADD KEY idx_dept_id (dept_id);
```

`idx_dept_id` 命中：求值器「部门(含子树)→启用账号集合」`WHERE dept_id IN (...) AND status=0 AND deleted=0`；部门删除前挂载用户 count 校验。求值器走 IN 多值 range scan；单值等值同命中。

### 3.6 种子（增量脚本 + 基线 cloud_system.sql 同步）

1. 根部门：`INSERT INTO sys_dept (id, parent_id, name, sort, status, is_builtin, ...) VALUES (1, 0, '总公司', 0, 0, 1, ...)`（is_builtin=1 禁删，内置保护沿先例）
2. admin 挂根部门：`UPDATE sys_user SET dept_id = 1 WHERE id = 1 AND deleted = 0`；**其余存量用户 dept_id 保持 NULL**（未挂部门：部门类档位对其展开为空集，语义见 D3；用户管理页可补挂）
3. admin 角色行规则种子：`INSERT INTO sys_data_perm_rule (resource, subject_type, subject_id, row_scope, ...) VALUES ('leave', 0, 1, 4, ...)`——**admin 预配 leave 全部档**（否则 admin 无规则默认 SELF，行为回退看不到全量，违背管理员预期）
4. 菜单种子（id 已核对空闲）：系统管理 10 下新增
   - `(15, 10, '部门管理', 'system:dept:list', 'C', '/system/dept', 'OfficeBuilding', 5, 1)` + F 按钮 `(151, 15, '部门新增', 'system:dept:add')`、`(152, 15, '部门修改', 'system:dept:edit')`、`(153, 15, '部门删除', 'system:dept:remove')`
   - `(16, 10, '数据权限', 'system:dataPerm:list', 'C', '/system/data-perm', 'Key', 6, 1)` + F 按钮 `(161, 16, '保存规则', 'system:dataPerm:save')`、`(162, 16, '删除规则', 'system:dataPerm:remove')`
   - 增量脚本 admin 绑定用显式 id 列表 `INSERT INTO sys_role_menu SELECT 1, id FROM sys_menu WHERE id IN (15,151,152,153,16,161,162)`；基线全量 SELECT 式天然覆盖零改动（沿 dict 先例）
5. 影响：admin 导航 +2（实时，刷新即见）、perms 快照 +7（重登生效）——既有 e2e 导航断言维护进计划 E 章（沿 dict D9 先例）

## 4. 设计决策（D 系列）

### D1 组织维度：parent_id 树 + 内存展开（不建 path 列）

sys_dept 用 `parent_id=0` 为根的邻接表（沿 sys_menu 先例）；「本部门及以下」的子树展开由求值器**全量加载部门表（listAll）后内存递归收集**——部门量级为十百级，全量+内存树零压力，避免 path 列（如 `/1/5/12/`）的维护负担（每次迁移改全子树 path）与字符串 LIKE 的可解释性弱。求值时 `DEPT_AND_CHILD` → 子树 deptId 集合 → `sys_user WHERE dept_id IN` 展开账号集合（展开时机见 D4）。存量挂载策略：admin 挂根部门，其余 NULL（挂载动作本身是用户管理操作，不造数）。

### D2 规则模型：双表结构化（行规则 + 列规则），主体=角色0/用户1

- 行规则：`(resource, subject_type, subject_id)` 唯一——**同主体同资源至多一条行规则**（档位是单选，配多条无语义），custom_accounts JSON 数组串承载 CUSTOM 档的账号集合（VARCHAR(1000)≈30 账号，超限由 3032/长度校验拦截）
- 列规则：`(..., column_key)` 唯一——一主体一资源一列至多一条动作
- 不建独立「用户组/主体组」实体：CUSTOM 档直接在规则上配账号集合（JSON 数组），少一层配置对象；组化诉求出现时再演进（§11）
- resource 取值由**资源注册表**（D11）硬约束，非自由字符串——防配错资源标识后规则永不命中

### D3 求值语义：多规则并集（宽松者胜）+ 无规则默认 SELF/列全可见

用户的有效范围 = 其全部启用角色绑定的规则 ∪ 用户直绑规则，收敛规则：

- **行级**：任一命中规则为 ALL → `all=true`（过滤豁免）；否则各规则展开为账号集合取并集——SELF→{自己}；DEPT→自己部门（dept_id 为 NULL 时展开为**空集**并记 narrative「无部门」）；DEPT_AND_CHILD→子树部门成员；CUSTOM→custom_accounts 解析（解析异常 log.error + 空集，防坏配置炸读路径）
- **列级**：同列多主体/多规则冲突取**最宽松**——可视（无规则）> 脱敏 > 隐藏（与行级并集同构，多角色叠加直觉一致：有一个角色让你看，你就能看）；严格模式（任一隐藏即隐藏）留移交（§11）
- **默认基线（fail-safe）**：无任何规则命中 → 行级=SELF（沿现状「我的请假」最小惊讶 + 安全默认）+ 列级=全可见。**admin 靠种子规则得 ALL，代码无特例**——无 is_admin 硬编码，规则面前人人平等，这是「可解释」的前提

### D4 展开时机：求值时实时展开为账号集合，SQL 统一 IN 语义

部门语义在求值器内终结：求值输出 `DataScope{all, accounts}`，mapper 只见账号集合——`apply_user IN (...)` 单一条件形态，不向 SQL 层泄漏部门树（sys_leave 无 dept_id 列，JOIN sys_user 展开会让已有 LEFT JOIN approval_projection 的查询再叠一层，复杂且难解释）。代价：部门成员变更实时生效（优点）；大部门 IN 集合大（§11 移交）。**DataScope 语义按账号列（apply_user）过滤是试点资源的既定事实，其他资源若按别的列过滤需扩展 DataScope 形态**（记档）。

### D5 拦截点：显式编程式（对「不要若依方式」的精确兑现）

三处显式，无注解、无 AOP 切面、无 MyBatis 拦截器、无 SQL 改写：

1. **Service 显式调求值器**：`DataPermDecision decision = evaluator.evaluate(RESOURCE_LEAVE, OP_LIST, null)`——调用点看得见
2. **mapper 显式传 scope**：`leaveMapper.pageList(page, businessType, decision.getDataScope())`——签名即文档
3. **XML 显式条件**：

```xml
WHERE l.deleted = 0
<if test="scope.all == false">
    AND l.apply_user IN
    <foreach collection="scope.accounts" item="acc" open="(" separator="," close=")">
        #{acc}
    </foreach>
</if>
```

（`<if>` 标签体换行、#{} only，与守护测试体系零冲突；all=true 时无过滤；空集由 Service 短路不进 mapper——XML 永不收到空集合，规避 `IN ()` 非法 SQL）

与若依的本质差异：若依的 `${params.dataScope}` 是隐式契约（XML 不写即裸奔、写了依赖切面悄悄塞值、决策黑盒）；本方案每一步在代码与 SQL 里可见，配合留痕（D7）全程可回答「为什么这条看不见」。

### D6 组件落位：cloud-system 内聚 `service/dataperm/`（不落 common-starter）

求值器依赖规则/部门/用户三组 mapper（全部在 cloud_system 库），落 common-starter 会立刻面对「starter 如何访问规则存储」的分布式问题（集中 Feign 拉取 vs 各库同步——本轮无答案也不需要答案）。落位 `com.cloudai.system.service.dataperm`（沿 `service/translate/` 子包先例）：DataPermEvaluator / DataScope / ColumnScope / DataPermDecision / DataPermResources / DataPermOperation。**触发重构条件记档**：第二个服务（如 cloud-bpmn）接入时，将 DataScope/ColumnScope/契约模型抽 cloud-system-api 模块 + 求值走 Feign + 本地缓存（§11）。规则表集中 cloud_system 库的集中式架构不因组件位置而变。

### D7 可观察三件套：决策留痕 + explain 模拟 + deny 安全事件

- **决策留痕**：每次**真实查询**的求值（list/detail）同步落 `sys_data_perm_log` 一条（决策要素快照：命中规则、展开结论、列结论）；留痕 insert 异常 catch + log.error **不抛**（可观察组件自身不得成为读路径故障源）——记档
- **explain 模拟**：管理员指定任意账号+资源，返回求值全过程（命中规则明细、展开数、收敛结论、中文 narrative 数组）——**不留痕**（模拟非真实决策，混入会污染决策史）；实现复用同一 doEvaluate（persistLog=false）
- **deny 事件**：详情被行级拒绝时**补插一条 operation=deny 留痕**（businessKey=目标单据）——「谁在何时试图越权访问哪张单」是安全审计核心问题，值得多一次 insert；同时服务日志 log.warn
- 前端排查面：数据权限页「决策留痕」tab（按账号/资源检索）+「模拟解释」tab（narrative + 命中规则表 + 列决策表）

### D8 规则不进 X-User-* header（网关/sso 零改动）

功能权限 perms 走 OnlineSession→header 是既有先例，数据权限规则不走，理由三条：① 规则含自定义账号集合（JSON 数组可达 KB 级），HTTP header 默认 8KB 上限有截断风险；② header 透传要求每类权限都改网关代码，违背开闭；③ 规则是 cloud_system 库内聚数据，求值发生在同库服务内零网络成本。网关 AuthGlobalFilter、sso OnlineSession 本轮**零改动**。

### D9 规则表物理删除（配置态数据，观察职责归留痕表）

规则/列规则表无 deleted 列、删除=物理 DELETE（沿纯关系表物理删先例，但保留审计四列）：逻辑删墓碑占 `uk(resource,subject_type,...)` 会使「删了再配」直接 DuplicateKey、upsert 需复活逻辑，复杂度不值得——规则是配置态数据（非业务台账），配置变更历史的观察职责由决策留痕表与规则页审计字段承担。守护测试主表名单不含新表名，零冲突（§2.5）；列规则 save 走「按主体全删全插」同样物理删。

### D10 MVP 无缓存实时求值（正确性优先）

每次求值实时查：规则两查（角色 IN + 用户直绑）+ 用户 dept_id 一查 + 部门全量一查（部门档时）+ 部门成员一查（部门档时）——内部系统量级下 4-5 次索引小查询可承受；**失效缓存是数据权限最经典的事故源**（改了规则不生效/串号），MVP 宁慢勿错。缓存+主动失效（规则变更事件/版本号）整体移交（§11）。LoginUser 不加 deptId 字段（避免登录快照与库漂移——部门挂载变更不重登即生效）。

### D11 列级落点与资源注册表

- **列动作应用点**：Service 读出行后、返回前，对 VO 显式应用——hidden→字段置 null（JSON null，前端展示空）；masked→整值替换 `"***"`（**单一策略 MVP**，多样化留移交）；试点可配列 = 资源 leave 的 `title`、`reason` 两个字段（reason 主打脱敏示例、title 隐藏示例——主控转述「remark」系笔误，sys_leave 实际业务列为 reason，记档）
- **资源注册表** `DataPermResources`（常量类）：`LEAVE="leave"` + 每资源可配列清单（`leave → [title, reason]`）——resource/column_key 配置与求值前均经注册表校验（3034），防自由字符串配错永不命中；新资源接入=注册表加一行 + 读路径接求值器（接入模板见 §7.3）
- 列 key 语义 = **VO 字段名**（camelCase），列表与详情共用同一列规则（一致治理）

### D12 错误码与权限标识

- 错误码 system 段**占用 3026-3034**（现用至 3025，接续分配）：3026 无权访问该数据 / 3027 部门不存在 / 3028 同层级下已存在同名部门 / 3029 内置部门禁止删除 / 3030 部门下存在子部门或在职用户，禁止删除 / 3031 数据权限规则不存在 / 3032 规则主体不存在或已停用 / 3033 自定义范围包含无效账号 / 3034 无效的资源或列标识（文案与触发矩阵见契约 §8）
- 权限标识：`system:dept:{list,add,edit,remove}`（4 个）；`system:dataPerm:{list,save,remove}`（3 个——save 为 upsert 语义单权限，explain/留痕查询/my-scope 不设管理权限：前三者归 list（同页只读能力），my-scope 免权限注解（登录即可，语义=自查本人范围，resource 经注册表校验 3034 防枚举）——记档
- 部门树端点 `/dept/tree` 挂 `hasAnyAuthority('system:dept:list','system:user:add','system:user:edit')`：树数据是部门管理与用户表单（部门选择器）的共同数据源，用户管理员无部门管理权也应能选部门——记档

### D13 详情拒绝语义：明确 3026（不伪装 404）

IDOR 收口的信息泄露考虑让位于可排查诉求（内部管理系统）：行级拒绝详情返回明确业务码 3026「无权访问该数据」+ deny 留痕，而非伪装「不存在」（3018 仅留给真不存在）。排查时管理员能区分「单据没了」与「被规则挡了」。

### D14 用户列表部门名：pageList JOIN sys_dept 一次成型（VO 直出改型）

`SysUserMapper.pageList` 由实体 resultType 改为 `SysUserVo` resultType（沿 SysLeaveMapper.pageList 派生列直出 VO 先例——JOIN 产生 dept_name 无实体承载），`LEFT JOIN sys_dept d ON u.dept_id = d.id AND d.deleted = 0`（用户挂的部门被删后 deptName 降级 null，不阻断列表）；详情路径（findById→convert）不变。不扩翻译 advisor（@DeptTrans 需动 translate-starter 三件，JOIN 更内聚）。

## 5. 核心组件设计

### 5.1 对象模型（service/dataperm/）

**DataScope**（不可变，行级求值终态）：

```java
/** 行级范围终态：all=true 过滤豁免；否则 accounts 白名单（空集=看不到任何行，Service 短路） */
public final class DataScope {
    private final boolean all;
    private final Set<String> accounts;
    public static DataScope all() { ... }
    public static DataScope of(Set<String> accounts) { ... }
    public boolean isEmptyScope() { return !all && accounts.isEmpty(); }
    /** 详情行级判定：该行的归属账号是否可见 */
    public boolean allows(String ownerAccount) { return all || accounts.contains(ownerAccount); }
}
```

**ColumnScope**（不可变，列级求值终态）：

```java
/** 列级终态：hidden 置 null / masked 整值替换 ***（单一策略）；isEmpty=无任何列动作 */
public final class ColumnScope {
    private final Set<String> hiddenColumns;
    private final Set<String> maskedColumns;
    public boolean isHidden(String columnKey) { ... }
    public boolean isMasked(String columnKey) { ... }
    public String mask(String value) { return value == null ? null : "***"; }
    public boolean isEmpty() { ... }
}
```

**DataPermDecision**（求值产物，留痕与 explain 共用）：`resource / account / operation / hitRules(List<HitRule>) / dataScope / columnScope / narratives(List<String>)`；内嵌 `HitRule{ruleId, subjectType, subjectName, rowScope, customCount, expandedCount}`——每条命中规则展开了多少账号一目了然（可排查核心）。

**DataPermResources**（资源注册表，D11）+ **DataPermOperation**（常量类：`LIST="list" / DETAIL="detail" / DENY="deny"`，沿 constant/LeaveStatus String 域先例）。

### 5.2 DataPermEvaluator（@Service，方法 ≤50 行拆私有）

对外两入口：
- `evaluate(String resource, String operation, String businessKey)`——当前登录人（`SecurityUtils.currentUser()`，additive 新增），真实决策**恒留痕**
- `explain(String account, String resource)`——模拟任意用户（查库取 userId；目标用户不存在/停用 → 3032，文案「规则主体不存在或已停用」，explain 场景即目标用户无效），**不留痕**
- `logDeny(String resource, String businessKey)`——详情被拒后补记 deny 留痕（决策对象账号内部取 `SecurityUtils.currentAccount()`）

doEvaluate 流程（示意）：

```java
// 1. 注册表校验（3034）；2. 查启用角色 ids + 用户行规则（角色 IN 一查 + 用户直绑一查，roleIds 空跳过前者）
// 3. 行规则收敛（D3）：任一 ALL → DataScope.all()；否则逐档展开并集
//    SELF→{account}；DEPT/DEPT_AND_CHILD→子树 deptIds→listEnabledAccountsByDeptIds；
//    CUSTOM→解析 custom_accounts（异常 log.error 空集）；用户 dept_id 为 NULL 的部门档→空集+narrative
// 4. 列规则收敛（D3）：Map<columnKey, action> 同列取最宽松（可视2 > 脱敏1 > 隐藏0 的概念序）
// 5. 组装 narratives（每命中规则一句 + 收敛结论一句 + 列决策一句，全中文）
// 6. persistLog=true 时组装留痕行 insert（catch log.error 不抛，D7）
```

留痕摘要字段生成：`rule_digest`（如 `role:主管(id=2)本部门及以下展开8人|user:zhang3(id=5)自定义集合2人`，截断 500）；`scope_summary`（`all` / `accounts=12` / `empty`）；`column_summary`（`reason:脱敏;title:隐藏` 或 null）。

### 5.3 规则管理（DataPermManageService）

- `pageList(query, resource?, subjectType?, subjectId?)`：规则分页（物理删表无 deleted 条件），VO 服务层补 subjectName（行级角色名/用户昵称按 subjectType 分流批量二查，≤10 行/页两 IN 查询）
- `findConfig(resource, subjectType, subjectId)`：配置回显（行规则 + 列规则列表），无规则返回 `configured=false` 空骨架
- `save(DataPermRuleSaveRequest)`：**upsert 全量覆盖**，校验链（见契约 §3.3）→ 行规则 insert 或 update（按 uk 判存）→ 列规则按主体物理全删 + 批插（空列表跳过）；多表写 `@Transactional(rollbackFor = Exception.class)`
- `delete(ruleId)`：行规则物理删 + 同主体同资源列规则连带物理删（多写 @Transactional）；3031 不存在校验
- 校验依赖：主体存在且启用（角色/用户各一查）、customAccounts 全部存在且启用（3033）、resource/columnKey 经注册表（3034）

### 5.4 SecurityUtils 增量（cloud-common-security-starter，additive）

新增 `public static LoginUser currentUser()`——从 SecurityContext 取 LoginUser（匿名返回 null，与 currentAccount 同范式）；现 currentAccount 改为委托（可选）。网关 HeaderAuthFilter 已构建 LoginUser 含 userId，零链路改动。

## 6. 接口面概览（逐端点定稿见契约）

| 域 | 端点 | 权限 |
|---|---|---|
| 部门 | GET /system/dept/tree、POST /system/dept、PUT /system/dept、DELETE /system/dept/{id} | tree=hasAnyAuthority（D12）；add/edit/remove 分权 |
| 规则 | GET /system/data-perm/rule/page、GET .../rule/config、POST .../rule、DELETE .../rule/{id} | list / save / remove |
| 支撑 | GET .../resources、GET .../subject-options?type= | list |
| 排查 | GET .../log/page、GET .../explain | list |
| 自查 | GET .../my-scope?resource= | 免注解（登录即可） |
| leave | GET /system/leave/page、GET /system/leave/{id} 语义变更（行级+列级+3026） | 沿 system:leave:list |
| 用户 | POST/PUT /system/user（+deptId 可选）、GET /system/user/page 出参增量 | 沿既有 |

## 7. 数据流（试点 sys_leave 全链）

### 7.1 列表

```
前端 leave 页 → GET /system/leave/page
  → LeaveController.page（@PreAuthorize system:leave:list）
  → LeaveManageService.pageList(query)：
      decision = evaluator.evaluate("leave", "list", null)     // 留痕 1 条
      scope.isEmptyScope() → 直接 PageResult.of(0, [])          // 空集短路不查库
      page = leaveMapper.pageList(page, "leave", scope)          // XML 显式 if/foreach
      批量回填 applyUserName/approverName（findUserNames 一次）  // D14 同款批量思路
      applyColumnScope(rows, decision.getColumnScope())          // title 隐藏→null；reason 脱敏→***
  → R<PageResult<SysLeaveVo>>
```

### 7.2 详情（IDOR 收口）

```
GET /system/leave/{id}
  → findById(id)：
      vo = leaveMapper.findById(...)；null → 3018（真不存在，不留痕）
      decision = evaluator.evaluate("leave", "detail", String.valueOf(id))  // 留痕 1 条
      !decision.getDataScope().allows(vo.getApplyUser())
          → evaluator.logDeny("leave", String.valueOf(id))       // 补 deny 留痕（D7）
          → throw BusinessException(3026, "无权访问该数据")
      applyColumnScope(vo, ...) → SysLeaveDetailVo
```

### 7.3 新资源接入模板（三步，bpmn 接入时照抄）

1. `DataPermResources` 注册资源与可配列；
2. 读路径 Service 显式调 `evaluate`（list/detail 各一）+ 空集短路 + 详情 allows 判定 + applyColumnScope；
3. mapper 增 scope 参数、XML 加显式 `<if>` 块（过滤列按资源事实定，如 bpmn_approval.apply_user 同款）。

## 8. 前端页面结构（cloud-web）

| 页面 | 路径 | 形态 |
|---|---|---|
| 部门管理 | views/system/dept/index.vue + DeptFormDialog | 树形 el-table（tree-props/row-key/default-expand-all，沿 menu 页树形态）：名称/排序/状态 tag/时间/操作（新增子级·编辑·删除）；弹窗：上级部门 el-tree-select（edit disabled——MVP 禁改上级）、名称、排序、状态 |
| 数据权限 | views/system/dataPerm/index.vue + RuleConfigDialog | el-tabs 三 tab：①规则配置（资源筛选 + 主体筛选 + 规则表格 + 新增按钮）②决策留痕（账号/资源筛选 + 留痕表格 + 分页）③模拟解释（选账号+资源 → narratives 时间线 + 命中规则表 + 列决策表） |
| RuleConfigDialog | — | 主体类型 radio（角色/用户）→ 主体下拉（subject-options）→ 行档位 radio 五档 → CUSTOM 显示账号多选（filterable）→ 列配置（resources 接口下发可配列，逐列动作 select：默认可视/脱敏/隐藏）；「配置」入口回显 config 接口 |
| 用户表单增量 | UserFormDialog | 加部门 el-tree-select（可清空=不挂；数据源 /dept/tree） |
| 角色管理联动 | role/index.vue | 操作列加「数据权限」文字按钮（v-perms system:dataPerm:list）→ router.push('/system/data-perm?subjectType=role&subjectId='+id) 预筛选直达 |
| leave 页增量 | leave/index.vue | 顶部 el-alert 范围提示条（my-scope：当前数据范围 + 列动作说明）+ 表格加申请人列（applyUserName）+ reason 脱敏/隐藏自然展示（后端已处理） |

路由：viewRegistry 注册 `/system/dept`、`/system/data-perm` 两项（沿动态路由机制，sys_menu 种子 path 驱动导航）；defineOptions name：`SystemDept` / `SystemDataPerm`。

## 9. 错误处理

- 后端：业务校验前置（1002 空值/枚举非法）→ 存在性/有效性（3027/3031/3032/3033/3034）→ 唯一查重（3028 + DuplicateKeyException 兜底，uk_parent_name 墓碑场景沿 sys_role 先例）→ 3026 仅运行时行级拒绝路径
- 留痕失败（D7 catch 不抛）与 CUSTOM 解析失败（空集降级）均为**可观察降级不阻断**，服务日志 log.error/log.warn 留证
- 前端：200/401 分流通用；3026 由拦截器 toast「无权访问该数据」；3028/3030 删除确认框文案对齐；空集范围提示条让「看不到数据」自查有门

## 10. 测试策略

### 10.1 后端单测（Mockito mock mapper，沿 SysRoleManageServiceTest 范式）

- **DataPermEvaluatorTest（核心矩阵，≥10 例）**：无规则默认 SELF；角色 ALL 短路；角色 SELF+用户 CUSTOM 并集；DEPT 有/无部门（空集+不炸）；DEPT_AND_CHILD 含子树；CUSTOM JSON 解析异常空集降级；列合并宽松者胜（角色 HIDDEN+用户 MASKED→MASKED）；同列 HIDDEN vs 无规则；未知资源 3034；留痕 insert 被调用且异常不影响返回值
- SysDeptManageServiceTest：save 重名 3028/parentId 3027、update 禁改上级 1002、delete 内置 3029/有子 3030/有用户 3030
- DataPermManageServiceTest：saveRule 校验链（3032/3033/3034/CUSTOM 空 1002/非 CUSTOM 带账号 1002）、upsert 覆盖语义（行 update+列全删全插同事务）、deleteRule 连带列规则
- LeaveManageServiceTest 增量：pageList 空集短路零查库、detail 拒绝 3026+deny 留痕、列级应用（hidden→null/masked→***）
- ArchitectureGuardTest：master 表正则补 `\bsys_dept\b`（守护自身演进，探针验证）

### 10.2 curl 验收（经网关，ASCII 入参）

部门 CRUD 全链 → 建测试用户挂部门（e2ecurl 前缀）→ 配角色规则（DEPT_AND_CHILD）→ 三账号（admin/主管/普通员工）验证 leave/page 行集差异 + my-scope 标签 + explain + log/page 留痕 + detail 越权 3026 + deny 留痕 → 清理。错误码 3026-3034 逐个触发。

### 10.3 黑盒 e2e（run-dataperm-e2e.mjs，有头 + slowMo）

部门树增删改 → 数据权限三 tab（配规则/查留痕/跑解释）→ 角色页联动入口 → leave 页范围提示条 + 多账号行集差异（员工仅自己/主管本部门）+ reason 脱敏展示。测试账号 e2e 前缀，种子 admin 不碰。既有导航断言维护（+2 菜单）。

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **bpmn 跨服务接入**：求值器 cloud-system 内聚（D6）；触发条件=第二服务接入，届时 DataScope/ColumnScope/契约模型抽 cloud-system-api + Feign 求值 + 本地缓存（版本失效），接入模板 §7.3
2. **IN 大集合优化**：大部门展开账号集合可能数百——分批 IN / 临时表 JOIN / 部门直滤列（sys_leave 加 dept_id 冗余）三路候选，量级出现时 EXPLAIN 后定
3. **留痕治理**：采样策略（按用户/资源采样或全量）、异步写（防读路径加写延迟）、xxl-job 定期清理（保留窗口建议 30-90 天，沿 MqTableCleanJob 先例）——现全量同步，表从空起步量小
4. **规则缓存与失效**：D10 实时求值；量大后加本地缓存 + 规则变更主动失效（管理接口写后失效/版本号），失效 bug 是经典事故源需慎重设计
5. **列脱敏策略多样化**：单一 `***`（D11）→ 按列可配置策略（首尾保留/正则/掩码表）；更多列开放配置随资源接入走
6. **列级严格模式**：宽松者胜（D3）→ 「任一角色隐藏即隐藏」的严格模式开关
7. **部门运维增强**：改上级（子树迁移）/多部门挂载/path 列方案/部门停用对求值语义的收紧（现语义：停用部门不可新挂用户、存量成员求值照常）
8. **my-scope 与树端点权限口径**：my-scope 免注解、dept/tree hasAnyAuthority（D12）——若出现越权面再收紧
9. **外引规则引擎**：Drools/LiteFlow 未采用（拍板①）——若未来出现风控/定价类「大规模产生式推理/热更/级联」场景另案评估，数据权限域不回潮
10. **错误码账本**：3xxx 现用至 3034；下轮从 3035 接续并在契约再声明

## 12. 索引设计总表（命中查询对照）

| 表 | 索引 | 命中查询 | 取舍 |
|---|---|---|---|
| sys_dept | uk_parent_name(parent_id,name) | countByParentAndName 查重；树页父级扫描（最左前缀） | 不另建 idx_parent_id（前缀覆盖，沿 uk_type_value 先例）；listAll 全量无 WHERE 不需索引 |
| sys_data_perm_rule | uk_resource_subject | 求值 listByRoleIds/listByUserId（resource 等值最左前缀）；findBySubject 判存；查重 | 配置态小表，无更多查询路径 |
| sys_data_perm_column | uk_subject_column | 求值/回显 listBySubject（前缀）；列动作查重 | 全删全插维护，无 update 路径 |
| sys_data_perm_log | idx_account_time / idx_resource_time | 留痕页「按用户查」「按资源查」（等值+时间倒序） | 双路径并列建；无 update；清理移交后按窗口 DELETE 需时间索引（已覆盖最左 account/resource，裸时间清理走全表低峰可接受记档） |
| sys_user（增量） | idx_dept_id(dept_id) | listEnabledAccountsByDeptIds（求值展开 IN）；countByDeptId（删除校验） | 求值高频路径，必建 |

既有索引复审：leave/pageList 的 `apply_user IN (...)` 仍命中 `idx_apply_user(apply_user, deleted)`（range scan）；admin ALL 档无过滤+ORDER BY id DESC 走 PK 反向序，量级可接受记档；EXPLAIN 抽查进计划 B8。
