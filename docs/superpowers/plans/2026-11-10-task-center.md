# 实施计划：通用任务中心（cloud-task 第一轮 Java 侧全链路）

- 日期：2026-11-10
- 关联：设计 `docs/superpowers/specs/2026-11-10-task-center-design.md`；契约 `docs/superpowers/contracts/2026-11-10-task-center-api.md`——两文档已定稿，本计划是唯一实施依据
- 轨道判定：全栈（新服务 cloud-task + cloud-system 两处增量 + 网关 + cloud-web + e2e）；架构分级 A
- **开工前置（硬约束）**：上一轮 inner 密钥轮换的全部改动仍在工作区未提交、全量 e2e 未取得绿判定（移交备忘见 `docs/superpowers/plans/2026-11-09-inner-secret-rotation.md` §3）。**本计划不阻塞设计 review，但阻塞代码开工**：实现开工前须先完成上一轮收口（全量 e2e 绿判定 → 征用户确认提交 → 清 handoff 欠账），避免两轮未提交改动叠加导致 e2e 失败归因不可分辨。
- 本仓 agent 不做任何 git 提交——工作树改动留给主控处置。

## 0. 任务总览与执行顺序

```
后端章                                                              前端章（与后端并行，契约即对齐物）
T1 SQL 种子 ─▶ T2 task-api 模块 ─▶ T3 服务骨架+网关+Nacos ─▶ T4 任务读写面 ─▶ T5 执行引擎+种子场景 ─▶ T6 通知链路 ─▶ T7 演示消费方 ─▶ T8 冒烟+回归 ─▶ T9 文档    F1 api+列表/详情页 ─▶ F2 e2e
```

依赖关系：T2/T3 仅依赖 T1 落库（服务可空转启动）；T4/T5 依赖 T3；T6/T7 依赖 T4/T5（告警在终态推进处接线、演示端点依赖提交面）；F1/F2 零后端依赖（e2e 全 route-mock）。

## 1. 后端章

### T1 SQL 种子（四段，增量 + 基线双落位）

| 文件 | 内容 |
|---|---|
| `cloud-base/scripts/sql/2026-11-10-task-center.sql`（新建） | 四段：① 建库 `cloud_task` + `async_task` DDL（设计 §4 原文，逐列 COMMENT，三索引）② 菜单种子（契约 §10 原文：40/401/402/403/404 + admin(1) 绑定 5 行）③ 通知模板 id 5 `task_fail_alert`（契约 §11；**列序与列集沿 `2026-11-09-inner-secret-rotation.sql` 模板 id 4 种子段形态逐字对齐**）④ xxl-job：组 id 5（app_name=cloud-task，沿 id 3/4 行形态）+ 任务 id 13（`taskSweepJobHandler`，CRON `0 * * * * ?`，沿 id 12 行形态，trigger_status=1 显式列、glue_updatetime=now()）。头部前置断言（表/菜单 40 段/模板 5/组 5/任务 13 均空闲，含墓碑物理判断）与不可重放声明，沿 2026-11-09 脚本形态 |
| `cloud-base/scripts/sql/cloud_task.sql`（新建基线） | 建库 + async_task DDL（与增量 ① 一致） |
| `cloud-base/scripts/sql/cloud_system.sql`（基线同步） | 追加菜单 40 段 5 行 + 模板 id 5 种子段 |
| `cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`（基线同步） | 追加组 5 + 任务 13 种子段 |

**执行**：经 pymysql 落库（中文种子参数化 SQL，charset utf8mb4；前置断言不满足停下回报，不盲跑）。
**验收**：四段落库成功；SELECT 核对菜单 40/401-404、模板 5、xxl_job_group 5、xxl_job_info 13；`SHOW CREATE TABLE cloud_task.async_task` 三索引在。

### T2 cloud-task-api 契约模块

| 文件 | 内容 |
|---|---|
| `cloud-base/cloud-api/cloud-task-api/pom.xml`（新） | 结构抄 cloud-system-api：parent cloud-api；依赖仅 cloud-common-core-starter + jakarta.validation-api |
| `.../cloudai/task/api/client/TaskSubmitClient.java`（新） | `@FeignClient(name="cloud-task", contextId="taskSubmitClient", path="/inner/task", fallbackFactory=TaskSubmitClientFallbackFactory.class)`；`@PostMapping("/submit") R<TaskSubmitResult> submit(@RequestBody TaskSubmitRequest request)` |
| `.../task/api/domain/TaskSubmitRequest.java`、`TaskSubmitResult.java`（新） | 字段按契约 §2 逐字（7 入参/1 出参）；@NotBlank 校验注解；Serializable |
| `.../task/api/fallback/TaskSubmitClientFallbackFactory.java`（新） | 模板 B：log.error 记 cause 后 `R.fail("cloud-task 服务不可用")` |
| `.../task/api/TaskApiAutoConfiguration.java` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（新） | @Bean 注册 fallbackFactory（引 jar 即生效） |
| `cloud-base/cloud-api/pom.xml`、`cloud-base/pom.xml`（改） | modules 各加一项；根 pom dependencyManagement 若需版本托管同步（沿既有 GAV 免版本号惯例核对） |

**验收**：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-api/cloud-task-api -am` 绿。

### T3 cloud-task 服务骨架 + 网关 + Nacos

| 文件 | 内容 |
|---|---|
| `cloud-base/cloud-task/pom.xml`（新） | 抄 cloud-bpmn 骨架：六 starter（core/security/mybatis/redis/xxljob/inner）+ cloud-system-api（NotifyClient）+ resilience4j + springdoc + actuator + web + nacos discovery/config + mysql + validation + cloud-task-api（自契约）；surefire 3.2.5 沿根 pom |
| `.../task/TaskApplication.java`（新） | `@SpringBootApplication` + `@MapperScan("com.cloudai.task.mapper")` + `@EnableFeignClients(clients = {NotifyClient.class})` |
| `.../task/controller/DemoController.java`（新） | 三胞胎惯例（GET /demo/ping） |
| `cloud-task/src/main/resources/application.yml`（新） | 抄 cloud-system：端口 9204、数据源 cloud_task 库、Nacos 双 import、openfeign circuitbreaker+超时三件套+disable-time-limiter、mybatis-plus 驼峰显式 |
| `cloud-base/cloud-gateway/src/main/resources/application.yml`（改） | 两条路由：`inner-block-task`（Path=/task/inner/** → SetStatus 403，**置于 lb 路由之前**，沿 inner-block-system 形态）+ `cloud-task`（lb://cloud-task，Path=/task/**，StripPrefix=1） |
| `cloud-base/cloud-gateway/src/main/java/.../filter/AuthGlobalFilter.java`（改） | 白名单加 `/task/inner/`（**尾斜杠段感知**），与 SetStatus 屏蔽路由**成对落地**——沿 `/system/inner/`、`/system/mcp` 先例：漏白名单则屏蔽段请求先被 JWT 链拒成 401 而非预期 403（冒烟断言 403 即验证此配对）。注意该文件工作区有上一轮未提交改动，上轮收口提交时已一并带出，本轮在其上继续改 |
| Nacos `cloud-task.yaml`（运维发布，双租户同内容） | `spring.data.redis.{host,port}`；`cloud.common.xxljob.enabled=true`；`xxl.job.executor.{appname=cloud-task, port=19204, ip=192.168.10.22, accessToken=default_token}`；`cloud.inner.shared-secret`（与既有三服务同值，**真值不进任何文件/回报**） |
| 守护测试（新） | 复制 cloud-system 的 `ArchitectureGuardTest`（DDL 路径改 `../scripts/sql/cloud_task.sql`）与 `MapperXmlBindingTest`；ArchitectureGuardTest 增四规则：@TaskHandler 类落 handler/ 包且注解值非空 / 禁 @Scheduled / 禁 new XxlJobSpringExecutor / @XxlJob 类落 job/ 包 |

**验收**：`mvn -f cloud-base/pom.xml clean install -pl cloud-task -am` 绿（空服务+守护测试）；起服（admin 容器在跑时）Nacos 注册可见、executor 19204 注册到 admin 组 5；网关 `curl http://localhost:18080/task/demo/ping` 通、`curl http://localhost:18080/task/inner/task/submit` 403。

### T4 任务域读写面

| 文件 | 内容 |
|---|---|
| `.../task/entity/AsyncTask.java`（新） | 设计 §4 列映射；**不继承 BaseEntity**（无 deleted，流水式，沿 mq_tx_log 实体先例处理审计列）；嵌套 `StatusEnum{QUEUED(0)/RUNNING(1)/SUCCESS(2)/FAILED(3)/CANCELED(4)}`（Integer 映射 + of） |
| `.../task/mapper/AsyncTaskMapper.java` + `resources/mapper/AsyncTaskMapper.xml`（新） | 手写 XML，方法（动词集省实体名）：`findById` / `pageList(IPage, status, taskType, submitBy)`（动态 `<if>`，ORDER BY create_time DESC）/ `countByTypeBiz(taskType, bizKey)` / `save`（useGeneratedKeys）/ `listQueuedIds(limit)`（status=0 ORDER BY create_time,id **显式 LIMIT #{limit}——认领取数语义非分页；若守护测试含 LIMIT 检查规则，加白名单注释豁免并记档，实现时验证**）/ `claimTasks(ids, executorId)`（UPDATE SET status=1,start_time=NOW(),executor_id,update_time=NOW() WHERE id IN foreach AND status=0）/ `updateProgress(id, progress, phase)`（WHERE status=1 条件写）/ `updateTerminal(id, status, errorMsg, result)`（动态列，finish_time=NOW()）/ `cancelQueued(id)`（status 0→4 条件写）/ `flagCancelRunning(id)`（cancel_requested=1 WHERE status=1）/ `retryTask(id)`（SET 回 0 清进度/错误/结果/cancel_requested/start/finish/executor_id，retry_count+1，WHERE status IN (3,4)）/ `requeueRunning()`（启动恢复：status=1→0 清 executor_id）/ `listZombieRunning(limit)`（status=1 且 TIMESTAMPADD(SECOND, timeout_seconds+300, start_time)<NOW()）/ `deleteTerminalBefore(cutoff, limit)`（物理 DELETE 终态行 LIMIT 分批）。全语句只用 #{}；`<if>` 体换行；无 deleted 列故无 deleted=0 过滤（表无逻辑删除，记档于 XML 头注） |
| `.../task/dto/TaskPageQuery.java`（新，extends PageQuery） | status/taskType/submitBy 三筛选项 |
| `.../task/vo/TaskVo.java`、`TaskDetailVo.java`、`TaskSubmitVo.java`（新） + `convert/TaskConvert.java`（新） | 字段按契约 §3/§4 逐字；原生 setter 逐字段 |
| `.../task/service/TaskSubmitService.java`（新） | 契约 §2：校验链（taskType 注册表存在→6001；params 为 null 或可解析 JSON 对象→6002；bizKey 非空查重→6003 msg 含既有 id）→ 落库（DuplicateKeyException 兜底 6003，catch 先 log.error）→ 返回 taskId。单表单语句零事务 |
| `.../task/service/TaskManageService.java`（新） | 契约 §3-§6：pageList（IPage→PageResult VO）/ detail（6006）/ cancel（先 findById：排队→cancelQueued 条件写（affected 0=并发已流转→6004）；执行中→flagCancelRunning；终态→6004）/ retry（retryTask 条件写，affected 0→6005；6006 前置）。只读不加事务 |
| `.../task/controller/TaskManageController.java`（新） | 四端点两行式 + javadoc + @PreAuthorize（task:asyncTask:list/cancel/retry，list 同时覆盖 page/detail）+ @PathVariable 显式命名 |
| `.../task/controller/feign/InnerTaskController.java`（新） | POST /inner/task/submit，入参复用 api 模块 TaskSubmitRequest，调 TaskSubmitService；inner 面由 inner-starter 过滤器守门（网关屏蔽 T3 已同轮落地） |
| 单测（新） | TaskSubmitService（6001/6002/6003/键空不查重/DuplicateKey 兜底）；TaskManageService（cancel 三态+条件写 affected 0 路径、retry 6005/6006）；convert 映射 |

**验收**：`mvn -f cloud-base/pom.xml clean install -pl cloud-task -am` 绿（守护+单测）；curl 经网关（admin token）四端点 200/错误码逐条（提交面经直连 9204 带 X-Inner-Secret 冒烟）。

### T5 执行引擎 + 种子场景 + 清扫任务

| 文件 | 内容 |
|---|---|
| `.../task/config/TaskProperties.java`（新） | `@ConfigurationProperties("cloud.task")`：pollIntervalMs=1000 / executorConcurrency=8 / progressThrottleMs=500 / retentionDays=30 |
| `.../task/runtime/TaskHandler.java` + `@TaskHandler` 注解 + `TaskCanceledException` + `TaskContext.java`（新） | 设计 §5.3 SPI 逐字：接口（taskType/execute 返回结果 JSON 文本）；注解含 @Component 元注解（value=类型码）；TaskContext（taskId/params/paramsAs/reportProgress/checkCanceled）；javadoc 写明 **handler 必须幂等（at-least-once）** |
| `.../task/runtime/TaskHandlerRegistry.java`（新） | SmartInitializingSingleton 收集建表；重复 type 启动抛 IllegalStateException；提供 `containsType`（提交校验用）；**接口形态即未来跨语言 Resolver 的本地分支（设计 §9 演进项，注释记档不实现远程分支）** |
| `.../task/runtime/TaskEngine.java`（新） | 派发执行：认领行→提交执行池→包装 handler 调用（catch 分类：TaskCanceledException→终态 4；其他异常→自动重试判定（retry_count<max_retries→重置回 0，否则终态 3+**首次终态失败触发 TaskAlertService**）；正常返回→终态 2+result）；本地运行表 Map<taskId,Future>；本地看门狗（超时行 Future.cancel(true)+终态 3「执行超时」）；进度节流（TaskProperties.progressThrottleMs） |
| `.../task/runtime/TaskPollingLoop.java`（新） | SmartLifecycle：start 先 `requeueRunning()` 孤儿恢复（log 恢复行数）再起单线程调度（pollIntervalMs）；每轮：容量=concurrency−本地运行数→listQueuedIds→claimTasks（CAS）→回查认领行派发 TaskEngine |
| `.../task/handler/DemoBatchHandler.java`（新） | @TaskHandler("demo-batch")；params `{total:100, failPercent:0}`（paramsAs 解析，非法 6002 语义→抛异常落 error_msg）；每批 20 条 sleep 200ms + reportProgress（phase「批处理中 i/n」）+ checkCanceled；failPercent>0 在约该进度抛 RuntimeException；返回 `{"processed": total}`；纯模拟天然幂等 |
| `.../task/job/TaskSweepJobHandler.java`（新） | @XxlJob("taskSweepJobHandler") 薄壳：listZombieRunning→updateTerminal 判死；deleteTerminalBefore(NOW()-retentionDays, 200) 分批；异常 log.error 后上抛（admin 红记录）；落 job/ 包 |
| 单测（新） | Registry（重复 type fail-fast）；引擎（认领 CAS 竞争：claimTasks 返回 affected<候选数只派发实际认领行；异常重试边界 retry_count=max 不再重试+触发告警回调；TaskCanceledException→终态 4；超时路径（注入时钟）；进度节流两次同值只写一次）；DemoBatchHandler（进度/取消/注入失败）；Sweep（僵尸判定含 300s 宽限、清理只删终态） |

**验收**：`mvn -f cloud-base/pom.xml clean install -pl cloud-task -am` 绿；本轮演示端点尚未落地（T7），冒烟经 inner 面手动验证——直连 9204 POST /inner/task/submit（带 X-Inner-Secret，参数纯 ASCII）提交 demo-batch：轮询 GET /task/{id} 断言进度递增至终态 2；执行中取消断言转 4；failPercent=100 断言重试 3 次后终态 3（告警此时未接线，T6 闭环）。

### T6 通知链路（inner notify 端点转正 + 告警接线）

| 文件 | 内容 |
|---|---|
| `cloud-base/cloud-system/.../controller/feign/NotifyInnerController.java`（新） | POST /inner/notify/send-by-code：SendByCodeRequest（templateCode 按 `findByCode` 查模板→3044 透传）→ NotifySendService.saveNotify(req, "task-center")（双参重载，操作人系统账号）；网关 `/system/inner/**` 屏蔽既有无需动 |
| `cloud-base/cloud-api/cloud-system-api/.../client/NotifyClient.java` + `fallback/NotifyClientFallbackFactory.java` + `domain/SendByCodeRequest.java`（新） + `SystemApiAutoConfiguration`（改：@Bean 注册新工厂） | 契约 §11 逐字（variables 为 Map<String,String>——契约模型透传负载白名单记档）；fallback `R.fail("cloud-system 服务不可用")` |
| `cloud-task/.../alert/TaskAlertService.java`（新） | 首次终态失败（retry 前置状态判断）→ NotifyClient.sendByCode（templateCode=task_fail_alert，receiverType=2 roleIds=[1]，linkUrl=/task/async，variables={taskType, taskId, reason=error_msg 截断}）；**Feign 降级/异常 catch log.error 吞掉**（软失败不阻断不重试）；TaskEngine 调用点替代 T5 的告警回调占位 |
| 单测（新） | NotifyInnerController（按 code 查模板→门面、3044、无登录态操作人）；TaskAlertService（首次终态触发/重试后再失败不重发/降级吞异常） |

**验收**：双模块构建绿；冒烟——提交 failPercent=100 任务至终态失败，admin 站内信收到 task_fail_alert（linkUrl 可跳 /task/async）；Redis/Feign 不可用时任务终态推进不受影响（log.error 留痕）。

### T7 演示消费方（cloud-system）

| 文件 | 内容 |
|---|---|
| `cloud-base/cloud-system/.../controller/TaskDemoController.java`（新） | POST /task-demo/submit（perms `task:demo:submit`，DTO DemoTaskSubmitRequest{total?, failPercent?} 校验范围）→ 组装 TaskSubmitRequest（taskType=demo-batch，params=JSON 文本，submitterAccount=SecurityUtils.currentAccount()，submitSource="cloud-system"）→ TaskSubmitClient.submit；降级/失败 msg 透出（R.fail 分支 catch 透传） |
| `cloud-system/pom.xml`（改） + `SystemApplication`（改） | pom 引 cloud-task-api；@EnableFeignClients 加 TaskSubmitClient.class（openfeign/resilience4j/超时配置 system 已有零新增） |

**验收**：`mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 绿；网关 curl（admin token）POST /system/task-demo/submit → 200 拿 taskId（全链路：用户态→Feign→inner 密钥→cloud-task 落库）。

### T8 冒烟脚本 + 集成回归

| 文件 | 内容 |
|---|---|
| `cloud-base/scripts/smoke/smoke_task_center.sh`（新） | 设计 §8 冒烟矩阵逐条：网关登录 → 演示端点提交 → 进度递增断言 → 取消两态 → failPercent 任务重试耗尽+失败终态+admin 站内信到达 → `/task/inner/**` 网关 403 → xxl-job admin（18081）任务 13 下一分钟轮次调度日志无错 |

**验收**：冒烟全过；`cd cloud-e2e && npm run e2e` 全量回归 **只认 `grep -c '[FAIL]'`=0**（exit 0 假绿陷阱；**回归跑之前提=F2b 既有夹具同步已落地**——新增顶级菜单必漂移侧边栏序/菜单闭合集/角色绑定数，未同步必红且归因污染）；`npm run e2e:taskcenter` 绿；通过后按主控惯例 kill 全部本轮起的进程交还。

### T9 文档同步

| 文件 | 变更 |
|---|---|
| `CLAUDE.md` | ① 模块拓扑加 cloud-task:9204 行（任务中心语义）与 cloud-task-api 行 ② 端口段补 9204/19204 ③ 「关键约定」新增「任务中心」条目（一段：提交/执行/告警/清扫语义 + 契约与设计路径 + 与 xxl-job/MQ 边界一句话）④ xxl-job 约定段补组 5/任务 13 ⑤ 网关段提 /task/** 与 /task/inner 屏蔽 ⑥ 编码规范「错误码分段」行补 6xxx task 段（现文止于 3xxx system/4xxx bpmn，另有 AI 5xxx 口径） |
| `docs/handoff/`（按需） | 若有挂起项按 SOP 登记欠账 |

**验收**：六处与契约一致；无决策代号裸引用。

## 2. 前端章

### F1 API 封装 + 任务列表/详情页

| 文件 | 内容 |
|---|---|
| `cloud-web/src/api/task.ts`（新） | 五函数沿 `api/notify.ts` 形态：`pageTask(query)` / `getTaskDetail(id)` / `cancelTask(id)` / `retryTask(id)` / `submitDemoTask(payload)`（URL `/system/task-demo/submit`，注明该端点在 cloud-system）；注释标 perms 与错误码 6001-6006 |
| `cloud-web/src/types/api.ts` | 加 TaskVo / TaskDetailVo / TaskPageQuery / DemoSubmitPayload 类型（taskId 为 string，契约 §3/§4 逐字段；注释标契约节号） |
| `cloud-web/src/views/task/async/index.vue`（新） | 列表页沿 notify 管理页结构：工具栏（**提交演示任务**按钮 perms task:demo:submit + 弹窗两参数表单 total/failPercent + 状态/类型筛选下拉）→ el-table（列：taskId/taskType+bizKey/status el-tag 色板（0 排队 info/1 执行中 warning/2 成功 success/3 失败 danger/4 已取消 info）/progress 列（执行中行 el-progress + 文本，终态行纯文本）/retryCount/submitBy/submitSource/createTime）→ 操作列（详情/取消（仅 0/1 行，perms task:asyncTask:cancel；执行中行确认文案含「将在任务下一检查点生效」）/重试（仅 3/4 行，perms task:asyncTask:retry））→ el-pagination（total 先 Number()）。**自动轮询**：rows 存在 status∈{0,1} 行时 setInterval 2s 调 load()，onUnmounted/onBeforeUnmount 清理，无活动行不轮询。**取消/重试禁用重复点击**（行级 loading） |
| `cloud-web/src/views/task/async/components/TaskDetailDialog.vue`（新） | el-descriptions 全字段回显（TaskDetailVo）+ params/result JSON `JSON.parse` 失败兜底原文 `<pre>` 展示 + 底部取消/重试按钮（显隐同列表口径）+ 打开时单次拉取（进度刷新由父页轮询触发的重开/手动刷新——弹窗内 2s 轮询且关闭即清，同 index 口径） |
| `cloud-web/src/router/viewRegistry.ts` | 注册 `'/task/async'` → 新页面 |

**验收**：`cd cloud-web && npm run build` 绿（连续两次）；dev 起后路由可达、空态不报错；Element Plus 按需引入铁律；6004/6005/6006 toast 透出后端 msg。

### F2 e2e 场景（route-mock，零后端依赖）

| 文件 | 内容 |
|---|---|
| `cloud-e2e/run-task-center-e2e.mjs`（新） | 沿 `run-notify-e2e.mjs`/`run-inner-secret-e2e.mjs` 先例（route mock 分层 + harness）：S1 登录+菜单「任务中心/异步任务」→ 列表渲染（五状态 mock 行+进度列+表头）；S2 提交演示任务（弹窗默认参数）→ mock 200 → toast → 排队行出现；S3 进度轮询：mock 分次返回 progress 30→70→100 且状态 1→2 → 断言行内进度与状态 tag 流转；S4 取消：排队行→已取消；执行中行→确认文案含「检查点」→ 状态仍执行中；S5 重试：失败行→toast→状态回排队 errorMsg 空；成功行无重试按钮（显隐断言）；S6 详情弹窗字段回显（params JSON pre 展示）；S-VERIFY console/pageerror 卫生 |
| `cloud-e2e/package.json` | scripts 加 `"e2e:taskcenter": "node run-task-center-e2e.mjs"`（不进 `e2e` 聚合，沿 aichat/notify 先例） |

**验收**：`cd cloud-e2e && npm run e2e:taskcenter` 全绿（仅前端 dev 5173）；`npm run e2e` 聚合回归不因新脚本引入而破。

### F2b 既有 e2e 夹具同步（全量回归前置，主控规格审查补）

新增顶级菜单「任务中心」+页面「异步任务」（菜单种子 40/401/402/403/404，admin 绑定 5 行）会漂移既有夹具——通知中心轮与密钥轮换轮同族债，**不同步则全量回归必红**：

| 文件 | 漂移点 |
|---|---|
| `run-nav-e2e.mjs` | 侧边栏全量字符串 19→20 项（N1/N6 两处，新增「异步任务」按实际侧边渲染序插入）；顶级目录断言若枚举目录名则补「任务中心」（新顶级目录，**不是**系统管理子级——系统管理 C 子级九页断言不变） |
| `run-dict-e2e.mjs` / `run-bpmn-e2e.mjs` / `run-menu-e2e.mjs` | 侧边栏全量字符串 19→20 项（各一处） |
| `run-menu-e2e.mjs` | SEED_MENU_IDS 加 `'40','401','402','403','404'`；种子段注释补一行（40 段来源契约 §10） |
| `run-role-e2e.mjs` | R5 admin 绑定闭合集 68→73；SEED_MENU_IDS 同步加 5 项 |

**验收**：起全栈后 `npm run e2e` 全量 `[FAIL]`=0（与 T8 验收合流）。

## 3. 移交备忘（下一轮规划前必读）

1. **第二轮锚点——kb 大文件异步化（跨语言）**：cloud-base-ai 两段式上传改造 + TaskHandlerResolver 远程分支（type→HTTP 端点注册表）+ cloud-base-ai 执行端点协议；设计 §9 演进项清单为准。
2. 父子任务/批次并行分片、SSE 进度推送、任务优先级、同类型并行度上限、告警重试与告警提交人、自动重试退避——均演进项（设计 §9）。
3. 多实例部署前必须完成：本地运行表/容量统计改按 executor_id 分域（设计 §9 第 6 条）。
4. xxl-job 任务 13 的僵尸清扫在本轮单实例下与启动恢复重叠——多实例时它是唯一跨实例兜底，勿删。

## 4. 给 backend-agent 的任务清单（完整任务书）

**任务**：实现通用任务中心后端全部（T1-T9）。三份文档已定稿：设计 `docs/superpowers/specs/2026-11-10-task-center-design.md`（架构五链路/DDL 与索引映射/执行引擎语义/SPI）、契约 `docs/superpowers/contracts/2026-11-10-task-center-api.md`（端点逐字段/错误码/种子段）、本计划 §1（T1-T9 逐任务验收）。**开工前通读**；冲突时以契约为准，仍歧义回报主控问架构。

**执行序**：T1 种子（pymysql 落库+前置断言）→ T2 api 模块 → T3 骨架+网关+Nacos → T4 读写面 → T5 引擎+种子场景 → T6 通知链路 → T7 演示消费方 → T8 冒烟+全量回归（只认 `[FAIL]`=0）→ T9 CLAUDE.md。关键约束：**开工前置——上一轮 inner 密钥轮换工作区未提交改动须先由主控收口，收到主控开工指令前不得动 cloud-base 工作树**；三胞胎结构逐字对齐（启动类/DemoController/application.yml 仅名称端口不同）；mapper XML 认领语句显式 LIMIT 是认领取数语义（非分页），守护测试如有 LIMIT 检查按白名单豁免记档；async_task 无 deleted 列（流水式，XML 头注记档）；引擎状态推进全部单语句条件写**零 @Transactional**；轮询线程是 SmartLifecycle 自管线程（@Scheduled 注解禁用，守护规则）；xxl-job 类落 job/ 包、handler 类落 handler/ 包（守护规则）；inner 密钥真值不进任何文件/日志/回报（Nacos 双租户发键，冒烟经环境变量）；NotifyClient/TaskSubmitClient 必带 fallbackFactory（守护测试）。红线：不做 git 提交；不改网关既有路由；契约文档不自改；不动 fallbackFactory/超时既有配置。

## 5. 给 frontend-agent 的任务清单（完整任务书）

**任务**：实现任务中心前端（F1/F2）。对齐物：`docs/superpowers/contracts/2026-11-10-task-center-api.md`（端点/字段/枚举/错误码/菜单种子全在）；结构先例 `cloud-web/src/views/system/notify/index.vue` + `cloud-web/src/api/notify.ts`（最新管理页样板：列表/分页/toast/按钮权限写法）；e2e 先例 `cloud-e2e/run-inner-secret-e2e.mjs`（route-mock 分层、harness 用法、S-VERIFY 卫生段）。本计划 §2（F1/F2 文件清单与验收）。

**执行序**：F1 types → api → 列表页 → 详情弹窗 → viewRegistry，build 连续两次绿 → F2 e2e runner + package.json script，`npm run e2e:taskcenter` 全绿 → **F2b 既有夹具同步**（本计划 §2 F2b 表；菜单种子落库后按实际侧边渲染序改四处字符串与两处 SEED_MENU_IDS/闭合集，改动属机械同步不改场景语义）。关键约束：组件按需引入铁律（禁全量注册/全量样式 import）；taskId 一律 string（Long→String 口径）；状态字面量用常量（STATUS_QUEUED=0 等，禁魔法数）；**2s 轮询必须有条件启停与清理**（存在活动行才轮询，卸载必清 interval）；进度列 el-progress 仅执行中行渲染；取消执行中任务的确认弹窗必须含「将在任务下一检查点生效」文案；6004/6005/6006 toast 透出后端 msg；演示提交端点在 `/system/task-demo/submit`（不是 /task 前缀，注释说明）。红线：不做 git 提交；不 import 后端/cloud-base 内部代码；不改既有页面；e2e 不进聚合 script。
