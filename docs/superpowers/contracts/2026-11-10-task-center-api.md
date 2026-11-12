# API 契约：通用任务中心（cloud-task）

- 日期：2026-11-10（定稿）
- 设计：`docs/superpowers/specs/2026-11-10-task-center-design.md`
- 结构约定：所有端点 HTTP 恒 200，业务状态看 body `code`（`R<T>`：`{code, msg, data}`，code=200 成功）；id/时间等 Long 字段经全局 Jackson 配置以 **string** 返回（防前端精度丢失）；日期字符串 `yyyy-MM-dd HH:mm:ss`
- 术语：**任务中心**=cloud-task 服务承载的异步任务域；与 bpmn 域「待办任务」（流程审批待办，`/bpmn/task/**`）无任何关系。本文「任务」一律指异步任务。

## 0. 命名口径（避撞声明）

| 物 | 名 |
|---|---|
| 服务 / 网关前缀 / 库 / 表 | cloud-task / `/task` / cloud_task / async_task |
| 菜单（顶级/页面） | 任务中心（M 40）/ 异步任务（C 401，路由 `/task/async`） |
| 权限标识域 | `task:*`（bpmn 待办是 `bpmn:task:*`，两域不混淆） |

## 1. 网关与鉴权

| 面 | 路径 | 鉴权 |
|---|---|---|
| 管理面（前端） | `/task/page`、`/task/{id}`、`/task/{id}/cancel`、`/task/{id}/retry`（网关 → cloud-task，StripPrefix=1） | 网关 JWT 链路 + 资源端 @PreAuthorize（§10 perms） |
| 提交面（服务间） | cloud-task `/inner/task/submit`（Feign：cloud-api/cloud-task-api `TaskSubmitClient`） | inner 面共享密钥 `X-Inner-Secret`（cloud-common-inner-starter 自动携带/校验，fail-closed）；网关屏蔽 `/task/inner/**`（403）——外网不可达 |
| 演示提交（前端可调） | `/system/task-demo/submit`（cloud-system） | 网关 JWT + perms `task:demo:submit`；服务内经 Feign 转 inner 提交面 |
| 通知联动（服务间） | cloud-system `/inner/notify/send-by-code`（`2026-11-08-notify-api.md` §6 预留形态转正，本契约 §11） | 同 inner 面；网关既有 `/system/inner/**` 屏蔽不变 |

用户身份传递：inner 面无登录态，提交人由业务方从登录态取账号显式传入 `submitterAccount` 字段（审计与列表筛选依据），不信任任何 header 透传。

## 2. 任务提交（服务间，cloud-task）

`POST /inner/task/submit`（Feign：`TaskSubmitClient.submit(request)`，fallback 降级 `R.fail("cloud-task 服务不可用")`——消费方按 `code != 200` 分支透出 msg）

入参 `TaskSubmitRequest`（api 模块 domain，Controller 同 DTO 复用）：

| 字段 | 类型 | 必填 | 说明 / 约束 |
|---|---|---|---|
| taskType | string | 是 | 任务类型码（handler 注册键，现注册：`demo-batch`）；未注册 6001 |
| params | string | 否 | 任务参数，**JSON 对象文本**（如 `"{\"total\":100}"`）；非 JSON 对象 6002；透传不解析，handler 自解析 |
| bizKey | string | 否 | 业务幂等键，≤100 字符。非空时与 taskType 库级唯一：已存在（**任意状态**）即 6003（msg 含既有 taskId）。**终态后同 key 不可再提交新任务**——重跑走 §6 重试端点；换批执行换 key 或不传 |
| maxRetries | int | 否 | 自动重试上限，默认 3（0-10） |
| timeoutSeconds | int | 否 | 执行超时秒数，默认 600（10-86400） |
| submitterAccount | string | 是 | 提交人账号（业务方登录态取） |
| submitSource | string | 是 | 提交来源服务名（如 `cloud-system`） |

出参 `R<TaskSubmitResult>`：

```json
{ "code": 200, "msg": "ok", "data": { "taskId": "1234567890123" } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| taskId | string | 任务 ID（后续查询/取消/重试凭据） |

错误码：6001（类型未注册）/ 6002（params 非 JSON 对象）/ 6003（幂等键冲突，msg 形如 `幂等键已存在，任务ID: 123`）。

## 3. 任务分页（管理面）

`GET /task/page`（perms `task:asyncTask:list`），query 参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| pageNum / pageSize | int | 是 / 否 | 分页（pageSize 默认 10，上限 200） |
| status | int | 否 | 状态筛选：0排队 1执行中 2成功 3失败 4已取消 |
| taskType | string | 否 | 类型筛选 |
| submitBy | string | 否 | 提交人筛选 |

出参 `R<PageResult<TaskVo>>`（rows 按 create_time 倒序）：

```json
{ "code": 200, "msg": "ok", "data": { "total": "42", "rows": [ { "taskId": "123", "taskType": "demo-batch", "bizKey": null,
  "status": 1, "progress": 45, "phase": "批处理中", "retryCount": 0, "maxRetries": 3, "errorMsg": null,
  "submitBy": "admin", "submitSource": "cloud-system", "createTime": "2026-11-10 10:00:00",
  "startTime": "2026-11-10 10:00:01", "finishTime": null } ] } }
```

TaskVo 字段：taskId/taskType/bizKey(string 可空)/status(int)/progress(int 0-100)/phase(string 可空)/retryCount/maxRetries(int)/errorMsg(string 可空)/submitBy/submitSource(string)/createTime/startTime/finishTime(时间串可空)。（total 沿既有分页口径 string，前端 `Number()` 后用）

## 4. 任务详情（管理面）

`GET /task/{id}`（perms `task:asyncTask:list`；`@PathVariable("id") Long id`）。出参 `R<TaskDetailVo>` = TaskVo 全字段 +：

| 字段 | 类型 | 说明 |
|---|---|---|
| params | string | 提交参数 JSON 原文（可空） |
| result | string | 成功结果 JSON（可空） |
| timeoutSeconds | int | 超时配置 |
| cancelRequested | int | 0/1（执行中已请求取消） |
| executorId | string | 认领执行器标识（可空） |

错误码：6006（任务不存在）。

## 5. 取消任务（管理面）

`POST /task/{id}/cancel`（perms `task:asyncTask:cancel`），无 body，出参 `R<Void>`。

两态语义：

| 当前状态 | 行为 |
|---|---|
| 0 排队 | 直接置已取消（4），立即生效 |
| 1 执行中 | 置 cancel_requested=1，**协作式**——handler 在下一检查点响应后转已取消；期间状态仍显示执行中（前端提示文案自持：已请求取消，将在任务下一检查点生效） |
| 2/3/4 终态 | 6004 |

错误码：6004（当前状态不允许取消）/ 6006。

## 6. 重试任务（管理面）

`POST /task/{id}/retry`（perms `task:asyncTask:retry`），无 body，出参 `R<Void>`。

仅失败（3）/已取消（4）可重试：状态重置回排队（0），retry_count+1，进度/错误/结果清空，重新排队执行（at-least-once，handler 幂等）。成功（2）与活动态（0/1）→ 6005；不存在 → 6006。

## 7. 演示任务提交（cloud-system，前端入口）

`POST /system/task-demo/submit`（perms `task:demo:submit`）——种子场景「Java 侧演示性批量任务」入口，模拟业务模块经 Feign 提交（submitterAccount=当前登录账号，submitSource=`cloud-system`，bizKey 不传）。

入参 `DemoTaskSubmitRequest`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| total | int | 否 | 模拟处理条数，默认 100（1-10000） |
| failPercent | int | 否 | 注入失败百分比 0-100，默认 0（>0 时任务在约该进度处抛异常，用于验证重试/失败/告警链路） |

出参 `R<TaskSubmitResult>`（同 §2；cloud-task 不可用时 Feign 降级 `R.fail` msg 透出，HTTP 仍 200）。

## 8. 状态/进度语义（状态机与 handler 契约）

| status | 含义 | 进入条件 |
|---|---|---|
| 0 | 排队 | 提交 / 自动重试 / 手动重试 / 重启恢复回队 |
| 1 | 执行中 | 轮询认领（CAS） |
| 2 | 成功 | handler 正常返回（result=返回 JSON） |
| 3 | 失败（含超时） | handler 异常且重试耗尽 / 超时看门狗（error_msg 注明「执行超时」） |
| 4 | 已取消 | 排队中取消 / 执行中协作式取消生效 |

进度：progress 0-100 + phase 阶段文本；执行中行由前端 2 秒轮询刷新（列表页存在 status∈{0,1} 行时自动轮询，否则停止）。自动重试在引擎内部完成（重进队尾），前端可见表现为 retryCount 增长与状态回 0。

## 9. 错误码（task 段 6xxx 起用）

| 码 | 语义 | 触发端点 |
|---|---|---|
| 6001 | 任务类型未注册 | §2 |
| 6002 | 任务参数非法（params 非 JSON 对象 / 数值越界） | §2 §7 |
| 6003 | 幂等键冲突（同 taskType+bizKey 已存在，msg 含既有 taskId） | §2 |
| 6004 | 当前状态不允许取消 | §5 |
| 6005 | 当前状态不允许重试 | §6 |
| 6006 | 任务不存在 | §4 §5 §6 |

## 10. 权限标识与菜单种子

| perms | 用途 |
|---|---|
| task:asyncTask:list | 任务分页/详情（菜单可见性） |
| task:asyncTask:cancel | 取消按钮 |
| task:asyncTask:retry | 重试按钮 |
| task:demo:submit | 演示任务提交按钮（端点在 cloud-system §7） |

菜单种子（sys_menu，admin(1) 角色绑定 5 行）：

```sql
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(40,  0,   '任务中心',   '',                    'M', '',          'Timer', 5, 1, NOW()),
(401, 40,  '异步任务',   'task:asyncTask:list', 'C', '/task/async', 'List', 1, 1, NOW()),
(402, 401, '取消任务',   'task:asyncTask:cancel', 'F', '', '', 1, 1, NOW()),
(403, 401, '重试任务',   'task:asyncTask:retry',  'F', '', '', 2, 1, NOW()),
(404, 401, '提交演示任务', 'task:demo:submit',    'F', '', '', 3, 1, NOW());
```

前端路由 `/task/async` → `views/task/async/index.vue`（viewRegistry 注册）。admin 需重新登录（或 refresh）拿新权限快照。

## 11. 通知联动（失败告警）

内置通知模板种子（sys_notify_template，id 5，沿 4 之后）：

```sql
VALUES (5, 'task_fail_alert', '异步任务失败提醒', '异步任务失败提醒',
        '异步任务 ${taskType}（ID: ${taskId}）执行失败：${reason}，请前往任务中心处理。',
        2, 1, 'taskType=任务类型码；taskId=任务ID；reason=失败原因摘要', 1, now(), now());
```

（列序沿 `2026-11-09-inner-secret-rotation.sql` 模板种子段：id/code/name/标题模板/内容模板/type=2 提醒/level=1 重要/变量说明/is_builtin=1/审计两列）

服务间发送端点（`2026-11-08-notify-api.md` §6 预留形态**转正实现**，该契约 §6 由本架构同批修订标注）：

`POST /inner/notify/send-by-code`（cloud-system，Feign：cloud-system-api `NotifyClient`）
入参 `SendByCodeRequest { templateCode: string 必填, variables: map<string,string>, receiverType: int 必填(1/2/3), userIds?: Long[], roleIds?: Long[], linkUrl?: string }`（Long 线上 JSON 按全局口径为 string，Java 模型用 Long）
出参 `R<NotifySendResultVo>`（复用 notify 契约 §2.1 出参）；错误码沿用 notify 契约 §4（3044 按 code 语义）。

cloud-task 侧触发：任务**首次进入终态失败**（重试耗尽/超时）时发送，variables={taskType, taskId, reason(error_msg 截断)}，receiverType=2 roleIds=[1]，linkUrl=/task/async；发送失败 log.error 软失败（不重试不阻断）。

## 12. e2e 约定（cloud-e2e）

`run-task-center-e2e.mjs`（route-mock 零后端依赖，script `e2e:taskcenter` 不进 `e2e` 聚合）：
S1 登录 + 菜单「任务中心/异步任务」→ 列表渲染（五状态 mock 行 + 进度列 + 表头）；
S2 提交演示任务（默认参数）→ mock 提交成功 → toast → 新排队行出现；
S3 进度轮询：mock 分次返回 progress 30→70→100、状态 1→2 → 断言行内进度与状态 tag 流转；
S4 取消：排队行取消 → 状态变已取消；执行中行取消 → 提示「检查点生效」文案、状态仍执行中；
S5 重试：失败行重试 → toast → 状态回排队、errorMsg 清空；成功行无重试按钮（按钮显隐断言）；
S-VERIFY console/pageerror 卫生。
