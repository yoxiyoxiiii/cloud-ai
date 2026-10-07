# cloud-bpmn 阶段 4：Flowable 请假工作流 实施计划（全栈：后端章 ∥ 前端章 + e2e 章）

- 日期：2026-10-07
- 需求：Flowable 7.2 引擎接入（process starter）+ 请假审批闭环（发起/待办/办理/撤销/时间线）+ 三前端页 + 状态/类型内置字典 + 30 段菜单种子 + dict e2e 宽松化（候选②随轮前置）+ 真实跨服务 Feign 链路验收
- 设计：`docs/superpowers/specs/2026-10-07-bpmn-phase4-leave-workflow-design.md`（D1-D12；重点：D1 PoC 门禁三条、D3 同事务 IT、D4 4xxx 分段与 3xxx 占位取代、D12 清扫新纪律）
- 契约：`docs/superpowers/contracts/2026-10-07-bpmn-leave-api.md`（bpmn 域 v1——字段表/错误码 4001-4007/权限 7 个/种子 SQL 以契约为准，本计划不重复）
- 轨道：**全栈 A 级**——后端章 B1→B9 串行 ∥ 前端章 F1→F6（F6 联调在 B9 后）；e2e 章 E1 在 B5+B7 后（种子双落库才迁移断言），E2 起需 B9+F6

```
主控：① 派发 backend-agent（B1→B9）∥ frontend-agent（F1→F5）
      ② B1【PoC 门禁】三条标准通过才放行后续；失败即停（备选 Docker MySQL 8 单独呈用户）
      ③ B5/B7 种子落库 + B8 全绿 → B9【卡点】用户启动 9203 + curl 验收（admin 需重登录取新 perms）
      ④ F6 联调（B9 后，5173 agent 自管）
      ⑤ e2e：E1 种子与导航断言迁移（dict 宽松化 + menu/role/nav 适配，B5+B7 后）→ E2 第七脚本 → E3 七脚本全量 → E4 文档核对
      ⑥ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

- 总红线：既有端点 additive-only；保护矩阵/错误码 3013-3017 零变化；admin/user_status/common_status 种子零触碰；**common 模块零触碰**（translate 两 starter 只引用不改本体）；服务启停归用户（9203 卡点，9201/9202/18080 本轮零重启）；EP 按需（el-steps/el-tabs 内置，零新 npm 依赖）；e2e 黑盒；测试数据 `e2ebpmn${stamp}` 前缀

## 后端章（cloud-base/，backend-agent，B1→B9 串行）

> 规范来源：CLAUDE.md 编码规范 + `/backend-spec` 技能（**索引：BpmnLeaveMapper 查询清单两语句——findById、pageList（apply_user+deleted 过滤，id 倒序），命中 `idx_apply_user(apply_user, deleted)`，设计 D6 取舍；事务：发起/撤销/办理三编排方法 `@Transactional(rollbackFor=Exception.class)`**）。ArchitectureGuardTest 本轮起在 cloud-bpmn 执法（B2 复制）。

### B1【PoC 门禁】Flowable 接入最小实证（MySQL 5.7.24 一票否决项）

- 文件：
  - `cloud-base/pom.xml`（改）：properties +`flowable.version=7.2.0`；dependencyManagement +`org.flowable:flowable-spring-boot-starter-process`
  - `cloud-bpmn/pom.xml`（改）：+process starter、+cloud-common-mybatis-starter、+cloud-common-translate-starter、+cloud-common-translate-remote-starter、+mysql-connector-j（引法沿 cloud-system/pom.xml 同款——实现时验证版本管理方式）
  - `cloud-bpmn/src/main/resources/processes/leave_approval.bpmn20.xml`（新建，契约 §1/设计 D7 模型：start→approval(assignee=${approver})→exclusiveGateway→endApprove/endReject）
  - `cloud-bpmn/src/test/java/com/cloudai/bpmn/it/FlowablePoCIT.java`（新建，@SpringBootTest(webEnvironment=NONE) + @Tag("it") 连本机真库，**不进默认 surefire**）
  - Nacos 配置发布（不落仓库）：`cloud-bpmn.yaml` 全文见设计 D2——经 Open API curl 发布（dataId=cloud-bpmn.yaml, group=DEFAULT_GROUP, 无鉴权实例直发）
  - 建库：java 单文件源码 + mysql-connector-j 通路执行 `CREATE DATABASE IF NOT EXISTS cloud_bpmn DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`（**不含任何表**——ACT_* 由引擎自动建、bpmn_leave 在 B3）
- PoC 三条通过标准（设计 D1）：
  1. 上下文加载成功：Flowable+MP+security+translate 两 starter 自动配置并存、唯一 DataSourceTransactionManager
  2. 对空库 cloud_bpmn 启动后 ACT_* 自动建表成功且落在 cloud_bpmn 库（`nullCatalogMeansCurrent=true` 防跨 catalog 误检的实证）——MySQL 5.7.24 兼容实证
  3. leave_approval 定义已部署 + RuntimeService 启动实例 → TaskService 查到 assignee 任务 → complete(approve) → 实例结束、HistoryService 有痕、endActivityId 正确
- **IT 隔离 pom 机制（默认构建不跑真库 IT）**：cloud-bpmn pom properties `<surefire.excluded.groups>it</surefire.excluded.groups>` + surefire 配置 `<excludedGroups>${surefire.excluded.groups}</excludedGroups>`——默认 `install`/`test` 排除 @Tag("it")；触发命令 `mvn test -pl cloud-bpmn -Dgroups=it -Dsurefire.excluded.groups=`（属性占位空串覆盖放行；若空串覆盖不生效，备选 profile 装配——**实现时验证并记档所选通路**）
- 验收：`$MVN -f cloud-base/pom.xml test -pl cloud-bpmn -Dgroups=it -Dsurefire.excluded.groups=` 三条全绿并记录；**任一失败 → 停止后续任务，回报主控**（附失败明细；备选 Docker MySQL 8 届时单独呈用户）
- 注意：本任务不加 @MapperScan/@EnableFeignClients（B3/B6 加）；B8 核对项含「默认全量构建零 IT 执行」（B1 所选通路生效证明）

### B2 工程化收口：守护测试前移 + 启动类改造

- 文件：
  - `cloud-bpmn/src/test/java/com/cloudai/bpmn/ArchitectureGuardTest.java`（新建：自 cloud-system 复制，扫描根改 `com/cloudai/bpmn`，检查项全集保留——内联 R.ok/隐式 @PathVariable/Wrapper/BaseMapper/controller 依赖 mapper/XML `${}`/单行 `<if>`/Map 接参/实体内嵌枚举无 Enum 后缀等）
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/BpmnApplication.java`（改）：+`@MapperScan("com.cloudai.bpmn.mapper")`（B3 建包同步）+`@EnableFeignClients`（B6 client 同批）——两注解一次到位，包/client 未建时扫描空不报错
- 验收：`-pl cloud-bpmn -am` 编译绿；ArchitectureGuardTest 绿（空包零违规）

### B3 cloud_bpmn 建库脚本 + bpmn_leave DDL/实体/mapper

- 文件：
  - `cloud-base/scripts/sql/cloud_bpmn.sql`（新建：CREATE DATABASE + USE + bpmn_leave 建表 DDL 全文见设计 D6——每列 COMMENT；**仅 DROP/CREATE bpmn_leave，ACT_* 不进脚本**（引擎自建）；头注释注明开发环境重置口径与 utf8 混排记档）
  - 落库：java 通路执行 bpmn_leave 建表 + 回查（SHOW COLUMNS 15 列、idx_apply_user 存在、0 行）
  - `entity/BpmnLeave.java`（新建：BaseEntity 继承 + 嵌套 `StatusEnum{APPROVING(0),APPROVED(1),REJECTED(2),CANCELLED(3)}` 与 `TypeEnum{PERSONAL(1),SICK(2),ANNUAL(3)}`——Enum 后缀 + Integer 字段规范）
  - `mapper/BpmnLeaveMapper.java` + `resources/mapper/BpmnLeaveMapper.xml`（新建：findById / pageList(Page)（WHERE apply_user=#{applyUser} AND deleted=0 ORDER BY id DESC）/ insert / updateStatusById（status+update 审计两值+process_instance_id 关联列，WHERE id AND deleted=0）——语句计数 4，allColumns 片段含全部业务列）
  - `test/.../mapper/MapperXmlBindingTest.java`（新建：沿 system 版，计数算式注释 = 4；防 id 漂移）
- 验收：cloud-bpmn 编译+单测绿（两守护测试绿）；XML `<if>` 换行、`#{}` only、LIMIT 不写（分页插件）

### B4 流程编排 Service + 同事务 IT

- 文件：
  - `service/LeaveWorkflowService.java`（新建，编排层，@Transactional 三方法）：
    - `saveLeave(LeaveCreateRequest, String applyUser)`：参数校验（日期 4006）→ Feign 审批人比对（4004，B6 client 就绪前先留接口 mock 位）→ insert leave(审批中) → `runtimeService.startProcessInstanceByKey("leave_approval", businessKey=id 字符串, vars={leaveId,applyUser,approver,title})` → 回填 process_instance_id（同事务；定义缺失 catch FlowableObjectNotFoundException → 4007）
    - `cancelLeave(Long id, String opUser)`：findById（4001）→ 本人校验（4003）→ 状态校验（4002）→ `runtimeService.deleteProcessInstance`（实例已不存在容忍——历史终态防御）→ updateStatus(已撤销)
    - `completeTask(TaskCompleteRequest, String opUser)`：`taskService.addComment` → `complete(taskId, vars={approve})` → `historyService...finished().getEndActivityId()`：endApprove→已通过 / endReject→已拒绝（未结束=未来多节点模型不回写，记档）；引擎异常 catch → log.error → 4005/1002
  - `service/BpmnLeaveManageService.java`（新建：pageListMy/findByIface/详情时间线拼装——三源 leave 行 + `taskService.getProcessInstanceComments` + HistoricProcessInstance endActivityId/result 文案，契约 §2.3 ApprovalStepVo 三步骤）
  - `service/TaskAppService.java`（新建：todo/done 两查询——ACT_RU_TASK/HIST_TASKINST assignee=当前人 + businessKey 批量回查 leave（mapper in 查询或循环 findById——单测 mock 即可，实现取简）+ comment/approve 拼装）
  - `test/.../it/LeaveWorkflowTxIT.java`（新建 @Tag("it")：@Transactional 方法内 insert+startProcess 后抛 RuntimeException → 断言 leave 0 行且 ACT_RU_EXECUTION 无该实例——同事务原子性实证，设计 D3）
  - Service 单测（mock 引擎 API/mapper）：状态机全迁移 + 4001-4007 全触发 + endActivityId 两分支 + 终态不可撤销
- 验收：单测绿 + `-Dgroups=it` 同事务 IT 绿（leave 回滚 + 实例回滚双断言）

### B5 内置字典种子（cloud_system 域增量，零 system 代码）

- 文件：
  - `cloud-base/scripts/sql/2026-10-07-bpmn-leave-seed.sql`（新建增量：**不写显式 id**，INSERT...SELECT 关联，is_builtin=1，契约 §6 两类型 7 项；头注释幂等核对——`SELECT id FROM sys_dict_type WHERE dict_key IN ('bpmn_leave_status','bpmn_leave_type') AND deleted=0` 应 0 行）
  - `cloud-base/scripts/sql/cloud_system.sql`（改基线同步：dict_type id=3/4、dict_data id=5-11 显式，头注释注明 id 非契约、与增量语义等价）
  - 落库：java 通路执行增量 + 回查（两类型 is_builtin=1 status=0；项 4+3 计数与文案逐条；user_status/common_status/admin 原样——红线核对）
- 验收：回查全过；**本任务不起停任何服务**（字典实时生效，翻译缓存首次 miss 回源即得）

### B6 三 Controller + Feign client + VO 翻译 + 单测

- 文件（新建 12 个）：
  - `client/SystemUserClient.java`：`@FeignClient(name="cloud-system", contextId="bpmnSystemUserClient", path="/inner/user")` + `@GetMapping("/all") R<List<UserEntry>> listAll()`（契约 §8；UserEntry 复用 common domain；无 fallback——调用方 Service catch 转 1002）
  - `dto/LeaveCreateRequest.java`（Bean Validation 全集：@NotBlank/@Size/@Pattern 日期，契约 §2.1）/ `dto/TaskCompleteRequest.java` / `dto/PageQuery 沿用 common`
  - `vo/LeaveVo.java`（@TranslateVO：status→statusLabel @DictTrans bpmn_leave_status、leaveType→leaveTypeLabel @DictTrans bpmn_leave_type、applyUser/approver→*Name @UserTrans——字段表契约 §2.2）/ `vo/ApprovalStepVo.java` / `vo/LeaveDetailVo.java` / `vo/TaskVo.java`（@TranslateVO leaveType/applyUser 同款）/ `vo/TaskDoneVo.java` / `vo/DefinitionVo.java` / `vo/UserOptionVo.java`
  - `controller/LeaveController.java` / `TaskController.java` / `DefinitionController.java`：@PreAuthorize 七权限（契约 §9）、两行式返回、@PathVariable 显式命名、方法 javadoc（契约逐条对齐）
  - DefinitionController 数据源：`RepositoryService.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionKey().asc()` + PageQuery 手动分页（listPage/total）
- 单测：三 controller 委托 verify（mock service）；LeaveWorkflowService 审批人校验真实调用 client mock（4004/1002 两分支）
- 验收：cloud-bpmn 全单测绿 + ArchitectureGuardTest 绿（javadoc/两行式/显式命名自动受检）

### B7 菜单种子（sys_menu 30 段 + admin 绑定）

- 文件：
  - `cloud-base/scripts/sql/2026-10-07-bpmn-menus.sql`（新建增量：契约 §9 全文——7 行菜单 is_builtin=1 + `INSERT INTO sys_role_menu SELECT 1,id FROM sys_menu WHERE id IN (30,31,32,33,311,312,321)`；头注释幂等核对 30 段 id 未占用）
  - `cloud-base/scripts/sql/cloud_system.sql`（改基线同步：同款 7 行进基线 INSERT 块，admin 全量式 SELECT 天然覆盖绑定）
  - 落库：java 通路执行 + 回查（30 段 7 行 is_builtin=1；sys_role_menu admin 绑定 7 行；23 行既有种子原样——红线核对）；**幂等核对含墓碑**：执行前 `SELECT id FROM sys_menu WHERE id IN (30,31,32,33,311,312,321)`（**不带 deleted=0——墓碑行也算占用**，历史 e2e AUTO_INCREMENT 可能已过 311）应 0 行，占用即停回报主控（id 是契约 §9 内容，不自行换段）
- 验收：回查全过；**perms 快照时序记档**——admin 须重新登录后新 perms 才生效（B9 curl 前提）

### B8 全量构建 + 守护核对

- 命令：`D:/software/apache-maven-3.8.4/mvn -f cloud-base/pom.xml clean install`（Windows 绝对路径）
- 核对：ArchitectureGuardTest 两服务（system+bpmn）全绿；MapperXmlBindingTest：system 46 不变 + bpmn 4 新增；全部单测绿（既有 60+ 零改动应全绿）；surefire 3.2.5 生效；common 模块零触碰（git diff 核对）
- 验收：BUILD SUCCESS 全绿

### B9【卡点】用户启动 9203 + curl 验收（请用户执行启动）

- 前置：B1-B8 全绿、两批种子已落库、Nacos cloud-bpmn.yaml 已发布；**用户启动 cloud-bpmn（9203，首次 Flowable 形态，首启自动建 ACT_* 预期 30-60s）**；admin 重新登录（取 bpmn perms 快照）
- curl 清单（token 经网关登录获取；请求体全 ASCII——中文仅出现在响应断言）：
  1. **发起闭环**：`GET /bpmn/leave/approvers` → 含 admin 投影；`POST /bpmn/leave`（title=accept178xxx, type=3, 起止明日, approver=admin）→ code 200 返回 id 字符串
  2. **待办/办理**：`GET /bpmn/task/todo`（admin token）→ 1 行 taskId/leaveTitle；`POST /bpmn/task/complete`（approve=true, comment="ok"）→ 200；`GET /bpmn/leave/page` → status="1" + statusLabel="已通过"；`GET /bpmn/task/done` → 1 行 approve="true"
  3. **拒绝路径**：再发起一单 → complete approve=false → status="2" 已拒绝
  4. **撤销路径**：再发起一单 → `PUT /bpmn/leave/cancel/{id}` → 200；page status="3"；todo 无此单
  5. **时间线**：`GET /bpmn/leave/{id}`（已通过单）→ steps 三步（apply/approval/end，comment 与 result 文案断言）
  6. **翻译形态**：page 行 statusLabel/leaveTypeLabel/applyUserName/approverName 全非 null
  7. **错误码全触发**：4001（GET /bpmn/leave/999999）/ 4002（对已通过单 cancel）/ 4003（**单测覆盖，curl/e2e 均无路径**——移交备忘 12 选型 a）/ 4004（approver=nobody）/ 4005（complete 同 taskId 二次）/ 4006（end<start）
  8. **权限/认证**：`GET /bpmn/definition/page` → 200 含 leave_approval 行（version="1"）；无 token 直调 `/bpmn/leave/page` → HTTP 401（网关层）
  9. **引擎表实证**：java 通路回查 cloud_bpmn 库 ACT_RE_DEPLOYMENT ≥1、bpmn_leave 行数与 curl 操作一致
- 验收：清单逐条通过并记录；发现问题回报主控（不自行改契约）

## 前端章（cloud-web/，frontend-agent，F1→F6；F1-F5 与后端章并行）

> 规范来源：`/frontend-page` 技能（分层/请求封装铁律/EP 按需/弹窗与列表页模式）。字段/降级链/权限以契约为唯一依据（bpmn-leave-api §2/§3/§4/§10）。

### F1 types + api 模块

- 文件：`cloud-web/src/types/api.ts`（改 additive：契约 §10 八类型 + 两本地降级常量 LEAVE_STATUS_MAP/LEAVE_TYPE_MAP）；`cloud-web/src/api/bpmn.ts`（新建：八端点函数，注释引契约节号——沿 api/dict.ts 样板）
- 验收：`npm run build` 绿

### F2 我的申请页（/bpmn/leave）

- 文件：`cloud-web/src/views/bpmn/leave/index.vue`（列表：title/leaveTypeLabel 降级链/起止日期/statusLabel tag（状态→tag type 映射：审批中 warning/已通过 success/已拒绝 danger/已撤销 info）/applyUserName/approverName/createTime/操作=详情+撤销（仅审批中且本人行显示，v-perm `bpmn:leave:cancel`）；「发起请假」按钮 v-perm `bpmn:leave:add`）+ `components/LeaveFormDialog.vue`（title/leaveType 下拉——`GET /system/dict/data/type/bpmn_leave_type`/起止 el-date-pickerdaterange/reason/approver 下拉——approvers 端点 option account+nickname；提交调 POST）+ `components/LeaveDetailDialog.vue`（LeaveVo 字段平铺 + el-steps 时间线——steps 三步，comment/result/time 展示）
- 验收：build 绿；dev 页面空态/弹窗开关正常（联调在 F6）

### F3 待办任务页（/bpmn/task）

- 文件：`cloud-web/src/views/bpmn/task/index.vue`（el-tabs 两页签：待办（TaskVo 列：leaveTitle/leaveTypeLabel/applyUserName/createTime/操作=办理）+ 已办（TaskDoneVo 增 endTime/approve 结果 tag/comment/leaveStatusLabel）；办理按钮 v-perm `bpmn:task:complete`）+ `components/CompleteDialog.vue`（radio 同意/拒绝 + comment textarea ≤200 + 提交 POST /task/complete + 成功刷新两 tab）
- 验收：build 绿

### F4 流程定义页（/bpmn/definition）

- 文件：`cloud-web/src/views/bpmn/definition/index.vue`（只读分页：key/name/version/deploymentTime；无任何写按钮——契约 §4 只读语义）
- 验收：build 绿

### F5 路由接入

- 文件：`cloud-web/src/router/viewRegistry.ts`（改：+三键 `/bpmn/leave|/bpmn/task|/bpmn/definition`，静态 import 三视图；视图 defineOptions name=path 派生名——keep-alive 契约）；`cloud-web/src/constants/icons.ts`（改：+Tickets 具名导入与 ICON_MAP 一行——**仅此一个**，Bell/Document/Files 已在）
- 验收：build 绿；三 path resolveView 命中（未注册落 NotFound 的反面验证）

### F6 双 build + dev 联调（B9 后）

- 命令：`cd cloud-web && npm run build` ×2；dev server 5173 agent 自管
- 联调（B9 后 admin 重登录）：侧边栏出现「流程管理」目录三菜单 → 发起→待办→办理→时间线→撤销全流程走查 + 翻译/降级链/权限按钮显隐 + 定义页
- 验收：build 绿 + 联调与 B9 curl 形态一致

## e2e 章（cloud-e2e/，E1 可 B5 后先行；E2 起需 B9+F6）

### E1【前置】种子与导航断言迁移（候选② dict 宽松化 + B7 菜单种子引发的既有脚本适配，共 4 文件）

> **核对结论（阶段二审查后实测脚本）**：B7 落库后必红点 = menu(:576 恰 23 行)/role(:294-302 R5a 23 id)/nav(:167-176 N1 根级形状 + :203 侧边精确串)/dict(:213 侧边精确串)；run-e2e S6 与 run-scaffold 全部为相对断言（includes/hasText/标题）——**零适配**（此结论记档，E3 回归验证兜底）。

- **共享常量决策：各自维护 + 注释互指**（30 id 清单不进 lib/harness.mjs——harness 保持业务零知识；menu/role 两脚本注释双向互指，改种子段时 grep 两个脚本）。
- 文件 1 `cloud-e2e/run-dict-e2e.mjs`（**候选②本体**）：
  - 种子清单常量化：`const SEED_KEYS = ['user_status','common_status','bpmn_leave_status','bpmn_leave_type']`（+对应名称映射；后续加字典种子只改此处）
  - D1 断言迁移：「恰 2 行/共 2 条」→「行数 ≥ SEED_KEYS.length 且 seenKeys ⊇ SEED_KEYS 且各行带徽标+状态列正常」；分页总数断言改 ≥（不锁上限——宽松语义）；user_status 种子弹框断言（自身 2 项）零改动
  - CLEANUP 迁移：「残留 dictKey 集合恰 {user_status,common_status}」→「残留 ⊇ SEED_KEYS 且无 e2e 前缀残留」
  - D5 补一条：`GET /system/dict/data/type/bpmn_leave_status` → 恰 4 项（value "0"-"3" 文案断言）
  - **:213 侧边精确串迁移**：`'用户管理,角色管理,菜单管理,字典管理,工作台'` → `'用户管理,角色管理,菜单管理,字典管理,我的申请,待办任务,流程定义,工作台'`（B7 后 admin 侧边新形态）
- 文件 2 `cloud-e2e/run-menu-e2e.mjs`：
  - 种子清单常量化：`const SEED_MENU_IDS = [...既有 23, '30','31','32','33','311','312','321']`（30 项；注释互指 run-role-e2e R5a 同步义务）
  - CLEANUP :576 迁移：「恰 23 行」→「行数 ≥ 30 且 seenIds ⊇ SEED_MENU_IDS 且无 e2e 前缀残留」；:10 头注释「恰 23 行」表述同步
- 文件 3 `cloud-e2e/run-role-e2e.mjs`：
  - R5a :294 SEED_MENU_IDS 扩为 30 项（同上清单，注释互指 menu 脚本）；:300 「恰 23」→「恰 30」断言与文案同步（R5a 语义=绑定 id **全量精确**，不宽松——admin 绑定是封闭集，非清单断言）
- 文件 4 `cloud-e2e/run-nav-e2e.mjs`：
  - N1 :167-176 user-nav 形状迁移：「根级恰 1 节点 M 系统管理」→「根级恰 2 节点：M 系统管理（icon Setting，子级 4 C 逐字段不变）+ M 流程管理（icon Tickets，子级 3 C 依次 我的申请(/bpmn/leave)/待办任务(/bpmn/task)/流程定义(/bpmn/definition)）」；:187/:199 无 F/剪空断言零改动（30 段无 F 进导航歧义）
  - :203 侧边精确串迁移（同 dict 文件 1 新串）；N2/N3 受限用户断言零改动（新用户不绑 30 段——不受影响）
- 验收：B5+B7 落库后四脚本单跑全绿（dict 新种子徽标/禁用 + menu/role/nav 新形态）；E3 前完成

### E2 第七脚本 run-bpmn-e2e.mjs

- 文件（新建 + 改 1 个）：
  - `cloud-e2e/run-bpmn-e2e.mjs`（harness 复用；头注释写明**清扫纪律新形态**：业务表不清零、断言本轮 stamp 全终态、ACT_HI 允许残留）：
    - 数据：`e2ebpmn${stamp}` title 前缀；admin 双角色（申请人+审批人——单人闭环，e2e 环境最小依赖）
    - BP1 发起弹窗：类型下拉恰 3 项（字典消费）+ 审批人下拉含 admin（**跨服务 Feign 链路实证**，inner-api §7 欠账兑现）
    - BP2 发起（同意路径单）→ 我的申请行出现：statusLabel「审批中」+ leaveTypeLabel 译文 + approverName 非空（UI 断言）+ 页内 fetch `/bpmn/leave/page` 断言原字段与译文字段并存（API 断言——E2 双层模式）
    - BP3 待办出现 → 办理弹窗（同意+意见）→ 我的申请变「已通过」；已办 tab 1 行 approve=true
    - BP4 拒绝路径：发起 → 拒绝 → 「已拒绝」；BP5 撤销路径：发起 → 本人撤销 → 「已撤销」+ 待办无此单
    - BP6 流程定义页：leave_approval 行（key/version/deploymentTime）
    - BP7 详情时间线：已通过单三步骤（发起/审批意见含文案/结束 result）
    - BP8 防御面：无 token 直调 `/bpmn/leave/page` → 401；对已通过单再撤销 → toast 4002；approver=nobody 发起 → toast 4004；定义页无写按钮断言
    - CLEANUP/VERIFY：本轮 stamp 单全终态断言（同意/拒绝/撤销三单各自终态）+ badResponses/console 零污染核对
  - `cloud-e2e/package.json`（改：scripts +`e2e:bpmn` 单跑 + e2e/e2e:headless 串行清单追加 run-bpmn-e2e.mjs）
- 验收：`node run-bpmn-e2e.mjs` 全绿（B9+F6 后）

### E3 七脚本全量回归

- 命令：`cd cloud-e2e && npm run e2e`（有头全量七脚本）
- 核对：六既有脚本零回归（**dict/menu/role/nav 四脚本按 E1 新口径绿**；run-e2e/run-scaffold 零改动应绿——E1 核对结论兜底）；BP 场景全绿；种子终态（admin/23+7 菜单/4 内置字典类型/user_status 2 项原样）
- 验收：七脚本全绿 + 结果记录在案

### E4 文档同步核对

- 核对：本计划/契约/设计三文档与实现一致（错误码 msg 逐字、菜单 7 id、字典 7 项、DDL COMMENT）；`docs/superpowers/` 无遗留 TODO；git diff 核对 common 模块零触碰
- 验收：无差异或差异已回报主控修订

## 移交后续阶段的备忘

1. **流程图渲染延后**：bpmn-js 高亮/后端 SVG（ProcessDiagramGenerator）两方案候选，需求出现时另案（设计 D12 无图口径）
2. **设计器/流程上传/挂起管理面延后**：当前定义仅 classpath 自动部署；definition 端点只读
3. **待办/已办分页**：当前 list() 直出，量大后 additive 演进（TaskQuery.count/listPage）
4. **history-level 性能**：ACT_HI 增长后评估 level 降级（audit→activity）——R10 移交
5. **database-schema-update 生产收敛**：导出前置 SQL + 置 false（phase1 备忘留白正式移交）
6. **async executor 开启条件**：引入定时边界/异步/多实例节点时再开（D11）
7. **approvers 含停用账号**（宽松语义）：过滤需 system /inner/user/all 扩 status 投影——inner 契约 additive 另案
8. **会签/多实例/委派/加签/消息事件**：引擎能力均未暴露，需求出现另案
9. **e2ebpmn 留档数据**：业务表允许终态残留（时间戳隔离）；环境重置跑 cloud_bpmn.sql 仅重建 bpmn_leave，ACT_* 由引擎维护不动
10. **候选①嵌套 VO 翻译增强 / neg 负缓存**：维持挂账未随轮（阶段一拍板）
11. **拒绝必填意见**：当前同意/拒绝意见均可空（宽松）；收紧属契约 additive
12. **4003 e2e 无路径（选型 a，已拍板口径）**：非本人撤销仅单测覆盖（mock verify code+msg，B4/B6 单测）；e2e 不建第二 bpmn 账号（user 脚本绑定先例成本不成比例，4003 为简单身份校验风险低）——未来多人 e2e 场景出现时 additive 补 BP 步骤
13. **种子面 e2e 迁移义务**：后续任何新增菜单/字典种子的轮次，E1 同款迁移义务（menu 行数清单/role 绑定全量/nav 形状与精确串/dict SEED_KEYS）——本轮 E1 已把 dict 侧收敛为清单常量、menu/role 侧为 30 id 清单（两处互指注释）；新增时 grep 两脚本 + nav 精确串

## 给 backend-agent / frontend-agent / e2e 的任务清单（可粘发）

- **backend（B1→B9 串行）**：B1【PoC 门禁】依赖全集+Nacos cloud-bpmn.yaml 发布（设计 D2 全文）+建库+leave_approval.bpmn20.xml+FlowablePoCIT 三条标准（**失败即停回报**）；B2 ArchitectureGuardTest 复制+启动类双注解；B3 cloud_bpmn.sql（仅 bpmn_leave）+实体双 Enum+mapper 4 语句+MapperXmlBindingTest(4)；B4 LeaveWorkflowService 三编排方法（@Transactional+4001-4007+endActivityId 映射）+时间线拼装+同事务 IT+单测；B5 字典种子（增量不写 id+基线 id 3/4/5-11+java 通路回查）；B6 三 controller+7 权限+Feign client+VO @TranslateVO 全注解+单测；B7 菜单种子（30 段 7 行+admin 绑定增量+回查）；B8 全量构建（system 46+bpmn 4；common 零触碰）；B9【卡点】用户起 9203+curl 九组
- **frontend（F1→F6）**：F1 types 八类型+两降级常量+api/bpmn.ts；F2 我的申请页+发起弹窗（字典下拉+approvers 下拉）+详情弹窗（el-steps 时间线）；F3 待办任务页（el-tabs+办理弹窗）；F4 流程定义只读页；F5 viewRegistry 三键+icons +Tickets；F6 双 build+联调（B9 后 admin 重登录）
- **e2e（E1 在 B5+B7 后 → E2 → E3 → E4）**：E1 种子与导航断言迁移（dict 宽松化 SEED_KEYS + menu 30 行清单 + role R5a 30 id 全量 + nav/dict 侧边精确串与新根级形状；run-e2e/run-scaffold 零适配已核对记档）；E2 run-bpmn-e2e.mjs（BP1-BP8+清扫新纪律+package.json 串行）；E3 七脚本全量；E4 文档核对
- 红线：契约定稿后两端不得单方改；既有端点 additive-only；保护矩阵/3013-3017 零变化；admin/user_status/common_status 零触碰；common 零触碰；9203 用户启停；EP 按需零新依赖；e2e 黑盒 e2ebpmn 前缀
