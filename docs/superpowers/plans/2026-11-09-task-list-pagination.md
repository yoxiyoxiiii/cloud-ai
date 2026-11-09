# 待办/已办任务列表分页（B 级全栈，契约惯例化改造）

**日期**：2026-11-09
**背景**：`GET /bpmn/task/todo` 与 `/done` 当初契约明示不分页（量级口径沿 v1），现补分页——`List` → `PageResult` 沿全平台统一分页模式（PageQuery/PageResult/el-pagination 三处惯例全有先例），无新表/新端点/新 UI 模式。
**判级**：B 级（契约修订为机械模式化改动，非架构决策）；触及既有契约 `2026-10-08-approval-platform-api.md` §2.1/§2.2——须 e2e 全量回归。
**范围**：todo + done 一起分页（同页两 tab 交互一致）；工作台「我的待办」卡消费点同步（§0.9 声明一并修订）。

## 方案要点

- **引擎分页 + 补拉循环**：Flowable `taskQuery.listPage(firstResult, maxResults)` + `count()`；防御态脏行（businessKey 无审批单行）跳过后当页不足 pageSize 且未取尽 → 续拉下一批凑满（循环上限=取尽）。total = 引擎 count（含脏行——脏行属异常防御态、log.warn 观测、常态为零，偏差记档契约）。
- 入参 `PageQuery`（GET query param，默认 pageNum=1/pageSize=10——无参调用向后兼容）；返回 `R<PageResult<TaskVo>>`（total String 化沿全局 Jackson 口径）。

## 任务清单

### 任务 1：契约修订（主控已审）

**Modify** `docs/superpowers/contracts/2026-10-08-approval-platform-api.md`：
- §2.1：标题行加「分页」；入参改 `pageNum/pageSize`（PageQuery 惯例，默认 1/10）；返回改 `R<PageResult<TaskVo>>`（PageResult 字段 total/rows 说明）；补分页语义三句（任务创建时间倒序、total=引擎 count 含防御态脏行的记档、脏行跳过补拉保证页内满行）。
- §2.2：同款修订（done 分页，办理时间倒序）。
- §0.9 工作台行：「todo 前 5」表述改「todo 首页 5 条（pageNum=1&pageSize=5）」。
- 版本表加 0.10 行。

### 任务 2：后端（cloud-bpmn）

**Modify** `TaskAppService`：`listTodo/listDone` 改 `pageListTodo(String assignee, PageQuery query)` / `pageListDone(...)` 返回 `PageResult<TaskVo>` / `PageResult<TaskDoneVo>`：
- 构造 query（taskAssignee/historic + 排序）→ `long total = q.count()`
- 补拉循环：`offset = (pageNum-1)*pageSize`，`while (out.size() < pageSize && offset < total)`：`q.listPage(offset, pageSize)` 拉一批 → 逐行 findApproval 跳脏（log.warn）→ 有效行入 out → `offset += 本批原始条数`；空批（引擎越界返回空）break。
- `PageResult.of(total, out)`；`buildTaskVo`/配置缓存逻辑不动。
- 命名 pageListXxx 合 ArchitectureGuardTest 白名单。

**Modify** `TaskController`：`todo(PageQuery query)` / `done(PageQuery query)` 返回 `R<PageResult<...>>`，两行式保留，javadoc 同步。

**单测**：TaskAppServiceTest（如无则新建）——分页参数透传（listPage 首参/次参）、脏行跳过补拉（第一批含跳行→第二批续拉凑满）、末页不足 pageSize、total=count 值、offset 越界空批终止。

### 任务 3：前端（cloud-web）

**Modify** `api/bpmn.ts`：新增 `TaskPageQuery { pageNum, pageSize }`（沿 ApprovalPageQuery 形态）；`listTodoTasks(query: TaskPageQuery)` / `listDoneTasks(query)` 返回 `Promise<PageResult<TaskVo>>`（PageResult 前端类型若 bpmn.ts 未有则补：`{ total: string, rows: T[] }`——以 approval 页现有消费形态为准对齐字段类型）；注释同步（去「不分页（契约现状）」）。

**Modify** `views/bpmn/task/index.vue`：两 tab 各加分页状态（todoQuery/doneQuery/todoTotal/doneTotal）+ `el-pagination`（逐字段沿 `views/bpmn/approval/index.vue` 先例：class table-pagination、page-sizes [10,20,50]、layout total/prev/pager/next/sizes、current-change/size-change 重新拉取；tab 切换重置回第 1 页或保持各自状态按现有 handleTabChange 语义最小改动）；加载函数改传 query 取 `.rows`/`.total`。

**Modify** `views/dashboard/index.vue`：「我的待办」卡调用改 `listTodoTasks({ pageNum: 1, pageSize: 5 })` 取 `.rows`（消费形态与「我的审批」卡 getApprovalPage 对齐）；渲染逻辑不动。

### 任务 4：e2e 断言同步（cloud-e2e）

**Modify** `run-bpmn-e2e.mjs`：待办/已办场景响应结构 List→`data.rows`（断言锚不变）；涉及待办列表的其他脚本（run-approval-notify-e2e.mjs AN4 用 /bpmn/task/todo——同步 `.rows`）。grep 全仓 `task/todo`、`task/done` 消费点逐一核对。

### 任务 5：主控集成段（不派 agent）

构建全绿 → 起服 → curl 分页实证（todo 多页/末页/无参默认 1/10）→ `npm run e2e` 全量回归（契约改动必须）→ `npm run e2e:approvalnotify` 专项 → 双审 → 报告征确认提交。

## 验证标准

1. `mvn -f cloud-base/pom.xml test -pl cloud-bpmn -am` 全绿（守护+单测）。
2. `npm run build` 连续两次全绿（cloud-web）。
3. curl：`/bpmn/task/todo?pageNum=1&pageSize=5` 返回 PageResult（total=引擎 count、rows≤5）；无参默认 10；done 同款。
4. e2e 全量 `grep -c '[FAIL]'`==0；approvalnotify 专项 0 FAIL。
