# 技术方案：通用任务中心（cloud-task 独立服务，第一轮 Java 侧全链路）

- 日期：2026-11-10
- 关联契约：`docs/superpowers/contracts/2026-11-10-task-center-api.md`（前后端唯一对齐物）；实施计划 `docs/superpowers/plans/2026-11-10-task-center.md`
- 轨道判定：全栈（新服务 cloud-task + cloud-system 两处增量 + 网关 + cloud-web 页面 + e2e）；架构分级 A
- 拍板输入：2026-11-10 用户拍板——独立服务 cloud-task:9204；秒级（1-5s）pickup，DB 表轮询为主通道；第一轮 Java 侧演示性批量任务种子场景；含前端任务列表/详情页；kb 大文件异步化（跨语言）留第二轮

## 1. 结论与边界

任务中心是「有人管的异步执行」基础设施：业务服务把重活打包成任务（类型码+JSON 参数）经 inner Feign 提交，cloud-task 负责任务持久化、秒级 pickup、进程内 handler 执行、进度/结果落库、自动重试、超时兜底、失败站内信告警；前端任务列表页提供查看/取消/重试。

与既有三机制的边界（本设计的最高约束，防职责漂移）：

| 机制 | 归属场景 | 与任务中心的分界 |
|---|---|---|
| xxl-job | CRON 周期例行（对账/清扫/轮换，无业务参数无进度） | 周期触发归 xxl-job；任务中心的清扫/僵尸兜底任务本身也注册在 xxl-job（admin 任务 id 13） |
| RocketMQ 事务消息 | 跨服务事件解耦（fire-and-forget，无任务态） | 任务是有一等公民身份的（状态机/进度/人工干预面），消息不承担此职责；第一轮不引 MQ |
| 同步接口 | 秒级能返回的操作 | 超过秒级的重操作（大文件、批量处理）提交任务中心 |

灰色地带裁定：周期型业务任务（如每日批量导入）由 xxl-job handler 向任务中心提交任务——两者上下游，不竞争。

**跨语言演进预留（第二轮，本轮不实现）**：kb 大文件异步化在 cloud-base-ai（Python）。协议开放性设计：提交协议（§5.2 的 inner HTTP 契约）本身语言无关；执行侧收敛在 `TaskHandlerResolver` 单一入口——第一轮仅本地注册表分支，第二轮在此入口扩展「远程执行器表（type→HTTP 端点）+ 派发分支」，提交方与任务表零改动。

## 2. 架构总览（五条链路）

```
【提交】业务服务(cloud-system 演示端点) --Feign+X-Inner-Secret--> cloud-task /inner/task/submit
          —— 类型校验(6001)/参数校验(6002)/幂等查重(6003) ——> async_task 行(status=0)
【执行】cloud-task TaskPollingLoop(每 1s) —— 容量内 CAS 认领(status 0→1) ——> 执行线程池
          —— TaskHandlerRegistry 按 taskType 路由 ——> handler.execute(ctx)
          —— ctx.reportProgress(节流写库) / ctx.checkCanceled(协作式)
          —— 正常返回=成功(2) / 抛异常=失败(重试或终态3+告警) / TaskCanceledException=取消(4)
【管理】cloud-web 任务列表页 —— 网关 /task/page|{id}|cancel|retry —— TaskManageController(用户态 JWT+perms)
【告警】TaskEngine 终态失败 —— cloud-task --Feign--> cloud-system /inner/notify/send-by-code(模板 task_fail_alert)
          —— NotifySendService 门面 —— admin 角色站内信(软失败：失败仅 log.error)
【清扫】xxl-job admin 任务 id 13(每分钟) —— cloud-task TaskSweepJobHandler
          —— 僵尸 RUNNING 兜底判死 + 终态行保留期物理清理
```

关键决策记录（候选与取舍，结论=拍板+本方案细化）：

| 决策 | 候选 | 结论与理由 |
|---|---|---|
| 宿主 | system 新域 / 独立服务 / 纯 starter | **独立服务 cloud-task:9204**（用户拍板）。任务域独立伸缩与独立库；代价（新部署单元+网关路由+Nacos 配置）按三胞胎模板摊薄 |
| 分发通道 | DB 轮询 / RocketMQ / Redis 队列 | **DB 表轮询**（用户拍板秒级即可）。任务表是唯一真相源，无双写一致性问题；轮询间隔 1s 满足 1-5s pickup；MQ/Redis 推送唤醒记演进项 |
| 执行器模型 | 进程内 bean / 跨服务派发 / 混合 | **第一轮进程内 Spring bean（@TaskHandler 注解路由）**（用户拍板 Java 侧先跑通）。跨服务/跨语言派发经 TaskHandlerResolver 预留 |
| 进度查看 | 轮询 / SSE | **前端 2s 轮询**（拍板派生定稿）。任务是低频状态流，SSE（`cloud-web/src/utils/sse.ts` 帧协议）面向会话流，收益不抵复杂度 |
| 失败告警 | log.error 记档 / 补 inner 通知契约 | **补 inner 通知端点（第一轮做真通路）**。契约 `2026-11-08-notify-api.md` §6 已预留形态（POST /inner/notify/send-by-code），cloud-system 侧按预留实现即可，NotifySendService 双参重载本就为无登录态调用方准备；体量小（一端点一 client），换来告警闭环。告警本身软失败（Feign 失败 log.error 吞掉，不重试） |
| 告警对象 | 提交人 / admin 角色 | **admin 角色**（receiverType=2, roleIds=[1]）。提交人 account→userId 反查链路第一轮不做（演进项） |

## 3. 模块与部署拓扑

新增/改动模块清单：

| 模块 | 动作 | 说明 |
|---|---|---|
| `cloud-base/cloud-task/`（新，:9204） | 新建 | 第四个三胞胎：启动类 `TaskApplication`（`@MapperScan("com.cloudai.task.mapper")` + `@EnableFeignClients(clients = {NotifyClient.class})`）、`DemoController`、application.yml（端口/数据源/Nacos import/Feign 超时三件套，形态抄 cloud-system）。依赖：core/security/mybatis/redis/xxljob/inner 六 starter + cloud-system-api（告警 NotifyClient）+ resilience4j + openfeign 相关（经 system-api 传递，与 cloud-system 同款显式声明）+ validation + springdoc + actuator + mysql |
| `cloud-base/cloud-api/cloud-task-api/`（新） | 新建 | 服务间契约 jar（提交 Feign）：`client/TaskSubmitClient` + `fallback/TaskSubmitClientFallbackFactory` + `domain/TaskSubmitRequest|TaskSubmitResult` + `TaskApiAutoConfiguration`（含 META-INF imports）——结构抄 cloud-system-api；仅依赖 core-starter + validation-api |
| `cloud-base/cloud-api/pom.xml` | 改 | modules 加 cloud-task-api |
| `cloud-base/pom.xml` | 改 | modules 加 cloud-task |
| `cloud-base/cloud-gateway/application.yml` | 改 | 两条路由：`inner-block-task`（Path=/task/inner/** → SetStatus 403，**先于 lb 路由**）+ `cloud-task`（lb://cloud-task, Path=/task/**, StripPrefix=1） |
| `cloud-base/cloud-system`（改，两处增量） | 改 | ① inner 通知端点 `controller/feign/NotifyInnerController`（POST /inner/notify/send-by-code，复用 NotifySendService）+ `cloud-system-api` 增 `NotifyClient`/fallback/自动装配注册；② 演示消费方 `controller/TaskDemoController`（POST /task-demo/submit，perms task:demo:submit，经 TaskSubmitClient Feign 提交）+ pom 引 cloud-task-api + @EnableFeignClients 加 TaskSubmitClient |
| cloud-web（前端） | 改 | 任务列表/详情页 + api/task.ts + 菜单/权限种子（见契约 §10） |
| xxl-job | 增 | 执行器组 id 5（app_name=cloud-task, port 19204）+ 任务 id 13（taskSweepJobHandler, CRON 每分钟） |
| MySQL | 新库 | `cloud_task` 库（同实例 127.0.0.1:3306，root/空密码本地口径，数据源进仓库 application.yml 沿 system 先例） |

Nacos per-service 配置（cloud-task.yaml，双租户 `''` 与 `public` 发相同干净内容）：`spring.data.redis.{host,port}`（inner 活跃密钥集读取需要）、`cloud.common.xxljob.enabled=true` + `xxl.job.executor.{appname=cloud-task, port=19204, ip=192.168.10.22, accessToken=default_token}`（多网卡钉物理网卡，仓规）、`cloud.inner.shared-secret`（与既有三服务同值）。密钥真值不进任何仓库文件。

命名口径（与 bpmn「待办任务」严格区分）：服务 cloud-task；网关前缀 /task；库 cloud_task；表 async_task；菜单顶级「任务中心」+「异步任务」页（bpmn 32 是流程待办，语义不同域不同词）；权限域 `task:*`。

## 4. 数据模型与索引

单表 `async_task`（新库 cloud_task）。流水式表：**不继承 BaseEntity、无 deleted 列**（任务行无软删语义，终态行到期物理清理——沿 mq_tx_log/sys_shared_secret 先例）；审计时间 SQL NOW()。

```sql
CREATE TABLE async_task (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '任务ID（对外字符串化，全局 Jackson Long→String 口径）',
    task_type        VARCHAR(50)   NOT NULL COMMENT '任务类型码（handler 注册键，如 demo-batch）',
    biz_key          VARCHAR(100)  DEFAULT NULL COMMENT '业务幂等键（NULL=不查重；非空时与 task_type 库级唯一，防双击/重试风暴重复提交）',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0排队 1执行中 2成功 3失败(含超时) 4已取消',
    progress         INT           NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    phase            VARCHAR(50)   DEFAULT NULL COMMENT '执行阶段描述（handler 上报，如 批处理中）',
    params           JSON          DEFAULT NULL COMMENT '任务参数（提交方 JSON 对象文本，handler 按类型自解析）',
    result           JSON          DEFAULT NULL COMMENT '成功结果摘要（handler 返回，JSON 文本）',
    error_msg        VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（异常摘要或超时说明；成功/取消为 NULL）',
    cancel_requested TINYINT       NOT NULL DEFAULT 0 COMMENT '协作式取消标志：1=已请求取消（handler 检查点生效后转取消终态）',
    retry_count      INT           NOT NULL DEFAULT 0 COMMENT '已重试次数（自动+手动共计数）',
    max_retries      INT           NOT NULL DEFAULT 3 COMMENT '最大自动重试次数（提交可指定；耗尽后失败为终态）',
    timeout_seconds  INT           NOT NULL DEFAULT 600 COMMENT '执行超时秒数（提交可指定；超时判失败）',
    executor_id      VARCHAR(64)   DEFAULT NULL COMMENT '认领执行器标识 host:port（多实例演进预留，CAS 认领写入口径）',
    submit_by        VARCHAR(50)   DEFAULT NULL COMMENT '提交人账号（业务方从登录态显式传入，审计用）',
    submit_source    VARCHAR(50)   DEFAULT NULL COMMENT '提交来源服务名（如 cloud-system，列表筛选用）',
    start_time       DATETIME      DEFAULT NULL COMMENT '开始执行时间（超时判定基准）',
    finish_time      DATETIME      DEFAULT NULL COMMENT '终态时间',
    create_time      DATETIME      DEFAULT NULL COMMENT '提交时间',
    update_time      DATETIME      DEFAULT NULL COMMENT '状态/进度更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_key),
    KEY idx_status_create (status, create_time),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '异步任务（通用任务中心）';
```

索引设计（依据本表 mapper 查询清单，逐索引注明命中与取舍）：

| 索引 | 命中查询 | 取舍 |
|---|---|---|
| `uk_type_biz (task_type, biz_key)` | 提交幂等查重 `countByTypeBiz`（等值）+ 并发 TOCTOU 的 DuplicateKey 兜底 | biz_key 可 NULL：MySQL 唯一索引多 NULL 共存=「不传键不查重」语义免费获得。语义代价记档：**同 key 任务终态后不能再提交新行**——重跑走重试端点，换批走新 key 或不传（契约 §2 注明）。最左前缀 task_type 同时覆盖「按类型筛选」列表查询 |
| `idx_status_create (status, create_time)` | 轮询认领 `listQueuedIds`（status=0 等值 + create_time 升序取 LIMIT n——pickup 顺序语义）；列表「状态筛选+时间倒序」分页 | 这是秒级 pickup 的扫描主路径，等值+排序一次索引完成。看门狗扫 RUNNING 超时行（status=1 等值后行级过滤 start_time）复用本索引——RUNNING 行数=并发上限（个位数），过滤代价可忽略，不为它单建索引 |
| `idx_create_time (create_time)` | 列表无状态筛选时的默认时间倒序分页 | 无筛选时 idx_status_create 不能直接用（最左列非等值）；小表可 filesort，但轮询与列表共用此表，保持默认排序走索引 |

不建索引：submit_by 单列（低频筛选，走 idx_create_time 回表过滤，量级不支持）；终态清理 delete（finish_time 过滤——每分钟 DELETE 限批量执行，全表扫代价可控，写明即可）。

## 5. 执行引擎设计

### 5.1 状态机与可靠性语义

```
排队(0) --认领CAS--> 执行中(1) --返回--> 成功(2)
  |                    |--异常且 retry_count<max_retries--> 排队(0)（retry_count+1，自动重试）
  |--取消(直接置)--> 已取消(4)
                       |--异常且重试耗尽--> 失败(3，error_msg+告警)
                       |--超时(看门狗)--> 失败(3，error_msg=执行超时)
                       |--checkCanceled 命中--> 已取消(4)
失败(3)/已取消(4) --手动重试端点--> 排队(0)（retry_count+1，清进度/错误/结果）
```

- **投递语义 at-least-once**：认领后进程崩溃/重启，启动恢复把 status=1 的行重置回 0（executor_id 清空）重跑——任务可能执行超过一次，**handler 必须幂等**（演示 handler 天然幂等；此要求写入 handler javadoc 模板与契约 §8）。
- **超时判定双层**：本地看门狗（轮询线程每轮顺带检查本机 RUNNING 行的 start_time+timeout，超时 `Future.cancel(true)` 中断+判失败）为主；xxl-job 任务 id 13 的僵尸清扫为兜底（status=1 且 `start_time + timeout_seconds + 300s 宽限 < NOW()` 判失败——覆盖本地看门狗漏网，如 Future 丢失）。
- **协作式取消**：排队中取消=条件 UPDATE 直接置 4；执行中取消=置 cancel_requested=1，handler 在批次/阶段检查点调 `ctx.checkCanceled()`（查库，低频）命中抛 TaskCanceledException 转 4。不检查取消的坏 handler 由超时兜底（记档）。
- **自动重试**：执行异常且 retry_count < max_retries → 重置回排队（进度清零、retry_count+1、error_msg 留痕在日志）。无退避间隔（重进队列尾部，轮询间隔天然错峰；量大场景的退避策略记演进项）。
- **事务口径**：全部状态推进都是单表单语句条件 UPDATE（`WHERE status=...` 条件写幂等，重投/并发无害）——**整个任务域零 @Transactional**（backend-spec 事务口径：单语句不加）。

### 5.2 轮询认领与线程模型

- `TaskPollingLoop implements SmartLifecycle`：专用单线程 ScheduledExecutorService，**间隔 1s**（`cloud.task.poll-interval-ms` 默认 1000）。启动时先做孤儿恢复（重置 status=1 → 0），再进入调度。
- **认领两步 CAS**（为多实例演进预留形态，单实例下无竞争也按此写）：`listQueuedIds(limit)` 取候选（容量 = `cloud.task.executor.concurrency`(默认 8) − 本机运行数）→ `claimTasks(ids, executorId)`（UPDATE SET status=1, start_time=NOW(), executor_id WHERE id IN (...) AND status=0），affected 即认领数，再回查本批认领行派发执行。
- **执行池**：ThreadPoolExecutor，core=max=`cloud.task.executor.concurrency`（8），keepAlive 60s，有界队列容量=并发数×2，拒绝策略 CallerRunsPolicy（认领侧限流为主，拒绝仅兜底不丢任务）。
- **@Scheduled 禁令辨析（重要，评审必读）**：仓规「@Scheduled 全量清零、定时任务一律 xxl-job」约束的对象是**注解式周期任务**（治理权归 admin 控制台）。pickup 轮询是服务的内建运行机制（同 MQ 消费线程），秒级节奏不在 xxl-job CRON 治理域内，且不能依赖 admin 容器可用性。实现形态=SmartLifecycle 自管线程，**cloud-task 守护测试同样禁 @Scheduled 注解**；治理域的周期任务（清扫/僵尸兜底，分钟级）仍走 xxl-job 任务 id 13。
- **进度写节流**：`ctx.reportProgress(pct, phase)` 仅在距上次写库 ≥500ms 或 pct 达 100 时落库（防千条批量任务打满 UPDATE）。

### 5.3 执行器 SPI（handler 模型）

```java
/** 任务处理器：一个 taskType 一个实现，Spring bean 注册（@TaskHandler 注解含 @Component 元注解） */
public interface TaskHandler {
    String taskType();
    /** 实现 必须 幂等（at-least-once 投递）；取消响应=抛 TaskCanceledException */
    String execute(TaskContext ctx) throws Exception;   // 返回值=结果 JSON 文本（可空）
}
```

`TaskContext`：taskId/params（String JSON）/`paramsAs(Class)`（Jackson 解析）/`reportProgress(int, String)`/`checkCanceled()`。`TaskHandlerRegistry`：`SmartInitializingSingleton` 收集全部 @TaskHandler bean 建 type→handler 表，**重复 type 启动即失败**（fail-fast）；提交时用注册表校验类型存在性（6001），不存在不落库。跨语言派发预留：Registry 实现类即未来 `TaskHandlerResolver` 的本地分支（接口签名现在就抽好，第二轮加远程分支不动调用方）。

### 5.4 清扫任务（xxl-job id 13，CRON `0 * * * * ?`）

`job/TaskSweepJobHandler`（@XxlJob，薄壳，落 job/ 包守仓规）单趟两件事：① 僵尸兜底（§5.1）；② 终态行保留期清理（`cloud.task.retention-days` 默认 30，`DELETE ... WHERE status IN (2,3,4) AND finish_time < ? LIMIT 200` 分批，每分钟自然推进）。

## 6. 可靠性与告警

- 失败告警链路：终态失败（重试耗尽或超时）→ `TaskAlertService` → cloud-system-api `NotifyClient.sendByCode`（模板 `task_fail_alert`，receiverType=2 roleIds=[1]，linkUrl=/task/async）→ NotifySendService → admin 站内信。**软失败**：Feign 降级/异常仅 log.error 吞掉（告警不阻断任务终态推进、不重试——告警重试记演进项）。发送频控：同任务一次（只在「首次进入终态失败」时发，手动重试再失败不再发——按 retry 前置状态判断）。
- inner 通知端点转正：cloud-system 新增 `POST /inner/notify/send-by-code`（按契约 `2026-11-08-notify-api.md` §6 预留形态逐字实现，§6 从「预留」转「已实现」，属契约修订由本架构同步出——见本方案关联契约文档 §11）。
- 审计：submit_by/submit_source 提交方显式传入（inner 面无登录态，沿「操作人显式传参」仓规）；状态推进的 update_by 不设列（引擎侧推进无用户态，终态审计靠 start/finish/error 三列+服务日志）。

## 7. 安全与鉴权

- **提交面（inner）**：`/inner/task/submit` 由 cloud-common-inner-starter 入站过滤器强制 X-Inner-Secret（引 starter 即生效 fail-closed）；网关 `inner-block-task` 屏蔽 `/task/inner/**`（与首个 inner 端点同任务落地，尾斜杠段感知形态沿 `/system/inner/` 先例）。用户身份不经 header 信任（inner 面服务间调用），由提交方显式传 submitterAccount 字段。
- **管理面（用户态）**：`/task/**` 经网关 JWT 链路（AuthGlobalFilter 验签+在线检查+X-User-Perms 注入）→ cloud-task 资源端 HeaderAuthFilter 构建 authorities → @PreAuthorize 三枚 perms（task:asyncTask:list/cancel/retry；演示提交 task:demo:submit 在 cloud-system 端点）。
- 密钥/凭据零进仓库：inner 密钥走 Nacos 双租户，与既有三服务同值同发布纪律。

## 8. 测试策略

| 层 | 内容 |
|---|---|
| 单测（cloud-task） | TaskSubmitService（6001/6002/6003/DuplicateKey 兜底/bizKey 空不查重）；TaskManageService（cancel 两态/6004/6005/6006/条件写幂等）；引擎（认领 CAS 竞争模拟：affected 数小于候选数、自动重试边界 retry_count=max、超时判死、重启恢复、进度节流、取消检查点）；Registry 重复 type fail-fast；DemoBatchHandler（进度上报/取消响应/注入失败） |
| 单测（cloud-system 增量） | NotifyInnerController（按 code 找模板→门面调用、3044 透传）；TaskDemoController（Feign 降级转译：TaskSubmitClient 返回 code!=SUCCESS 走 msg 透出） |
| 守护测试（cloud-task 复制+新增） | 复制 cloud-system 的 ArchitectureGuardTest（DDL 路径指 cloud_task.sql）与 MapperXmlBindingTest；新增规则：① @TaskHandler 类必须落 handler/ 包且注解值非空；② 禁 @Scheduled；③ 禁 new XxlJobSpringExecutor（沿既有）；④ @XxlJob 类落 job/ 包 |
| 真连接冒烟 | `cloud-base/scripts/smoke/smoke_task_center.sh`：网关登录 → system 演示端点提交（验 Feign+inner 密钥链路）→ 轮询任务详情断言进度递增到终态 → 提交 failPercent>0 任务断言重试耗尽+失败终态+admin 站内信到达 → 排队洪泛取消/执行中取消 → 网关 `/task/inner/task/submit` 直访 403 |
| e2e（cloud-e2e） | `run-task-center-e2e.mjs`（route-mock，见契约 §12；script `e2e:taskcenter` 不进聚合） |

## 9. 演进项记档（本轮不做，翻案须回架构）

1. **kb 大文件异步化（第二轮锚点）**：cloud-base-ai 两段式上传（先传 MinIO 拿引用再提交任务）+ TaskHandlerResolver 远程分支（type→HTTP 端点注册表）+ cloud-task 派发协议。
2. 父子任务/批次并行分片（百千条数据的子任务拆分与聚合进度）。
3. MQ/Redis 推送唤醒（pickup 亚秒级时再评估；DB 轮询是真相源不变）。
4. SSE 进度推送；任务优先级；同类型并行度上限（当前仅全局并发上限）。
5. 告警重试与告警提交人（account→userId 反查链路）。
6. 多实例部署强化：认领 CAS 已按多实例形态写（executor_id+条件 UPDATE），但本地看门狗的 Future 表、容量统计是单实例口径，多实例前需把「本机运行数」改为按 executor_id 分域统计。
7. 自动重试退避间隔（当前重进队尾自然错峰）。
