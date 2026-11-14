# 聚合服务 cloud-aggregate 技术方案（2026-11-14）

> 需求：对外提供聚合接口，把多个后端微服务的数据在一次请求内汇总，避免前端同时调用多个接口再做拼装。
> 本轮拍板结论（用户已确认）：独立新服务形态 / 首场景工作台 / 复用下游外部用户面+透传用户头 / 段式失败状态 / AI 域排除首轮。
> 阅读前提：本文写给第一次接触本项目的人。「段」= 聚合响应里一个下游数据源的分区；「外部用户面」= 走网关、带登录权限校验的业务接口（与 `/inner/**` 服务间面相对）。

---

## 1. 结论与范围

- 新增第五个 Java 服务 **cloud-aggregate，端口 9205**：纯读聚合服务，无数据库、无 Redis、无 xxl-job、无 MQ、无 `/inner` 面。对外仅一个业务端点 `GET /aggregate/workbench`（经网关），返回工作台三段数据：我的待办 top5（cloud-bpmn）+ 我的审批 top5（cloud-bpmn）+ 未读通知摘要（cloud-system）。
- 下游调用**复用既有外部用户面**（`/bpmn/task/todo`、`/bpmn/approval/page`、`/system/notify/summary`），cloud-bpmn / cloud-system **零改动**。聚合层把网关注入的 `X-User-*` 身份头转发给下游，下游的 `@PreAuthorize` 权限校验、数据权限求值、字段翻译全部照常生效——权限语义与前端直调完全一致。
- 失败语义为**段式状态**：聚合响应整体恒 HTTP 200 + `code=200`（聚合动作本身成功），每个段自带 `status/msg/data`，段级失败不影响其它段。
- 前端工作台页（dashboard）切换为单请求消费，卡片视觉与交互不变。
- 本轮明确不做：写路径聚合、AI 域（Python 双服务）聚合、其余 3 处跨服务组合页迁移、通用配置化聚合引擎、聚合层 `/inner` 提供面。见 §11 移交备忘。

---

## 2. 背景与问题（现状事实）

需求分析阶段（同日本文档的前置回报）已核实三类事实，此处只留结论：

1. **前端确有多服务拼装**：工作台页 `cloud-web/src/views/dashboard/index.vue` 现在并行发 2 个 bpmn 请求；请假详情弹窗一次弹窗发 4 个请求跨 system+bpmn 两服务；MCP/知识库权限弹窗跨 Java+Python 两技术栈发 3 个请求。工作台是天然的全貌汇总位（待办+审批在 bpmn，通知摘要与任务失败告警在 system），但当前只展示了 bpmn 两卡。
2. **inner 面没有页面级查询端点**：五个 `-api` 契约 jar 共 9 个方法全是动作与窄投影（提交任务/发通知/发起审批等），待办、审批分页、通知摘要等查询只存在于外部用户面。
3. **跨服务状态回读已有投影机制**：审批状态在业务方库有只读副本（审批投影表），业务列表页读侧 JOIN 即可——聚合服务不重复解决这类问题，专注「多服务多卡同屏汇总」。

---

## 3. 架构总览（文字版）

```
浏览器(cloud-web)
   │  GET /aggregate/workbench   （Authorization: Bearer <JWT>）
   ▼
cloud-gateway :18080
   │  验签 JWT + 查 Redis 在线会话 → 剥离伪造头 → 注入真实 X-User-* 五头
   │  路由 /aggregate/** → lb://cloud-aggregate（StripPrefix=1）
   ▼
cloud-aggregate :9205（新增，WebMVC）
   │  WorkbenchController（登录即可，无 perms）
   │  WorkbenchService：段准入预判 → 三段并行编排 → 段装配
   │  Feign 出站拦截器：把 X-User-* 五头从 ThreadLocal 上下文写入请求头
   ├─（并行）lb://cloud-bpmn  GET /task/todo?pageNum=1&pageSize=5        → @PreAuthorize bpmn:task:list
   ├─（并行）lb://cloud-bpmn  GET /approval/page?pageNum=1&pageSize=5   → @PreAuthorize bpmn:approval:list
   └─（并行）lb://cloud-system GET /notify/summary                      → 登录即可
   ▼
R<WorkbenchVo>（HTTP 200，三段各自 status/msg/data）
```

要点：聚合层是普通 WebMVC 服务（引 `cloud-common-security-starter`，从 `X-User-*` 构建认证上下文）；Feign 走 Nacos 注册发现直连下游服务端口（不经网关），下游安全链不受影响。

---

## 4. 关键选型与理由

### 4.1 形态：独立服务（已拍板）

备选的「网关内组合端点」与「挂在 cloud-system 内」在需求分析阶段已排除：前者 WebFlux 与阻塞式 Feign 不匹配且破坏网关职责纯粹性；后者把跨域依赖永久引入 RBAC 服务。独立服务沿四胞胎模板裁剪，增量成本已验证为低。

### 4.2 下游调用面：复用外部用户面 + 透传用户头（已拍板）

两个备选的对比结论：

- **A. 复用外部用户面**（采纳）：聚合层转发 `X-User-*`，下游 `@PreAuthorize` / 数据权限 / 字段翻译照常生效，**提供方零改动**；快照透传语义（权限变更需重新登录生效）与前端直调完全一致，无回归。
- B. 下游新增 `/inner` 查询端点：契约独立可控，但三个提供方都要加端点、三份契约增量，且「我的待办」这类用户过滤逻辑要在 inner 端点重写一遍（口径漂移风险）。

代价与对策：A 需要新写一个用户头转发 Feign 拦截器（§5.3）；外部面 VO 的响应形状被聚合层被动接受——对策是镜像字段冻结进契约（§4.4）。

### 4.3 与 inner 体系的关系：不引 jar，物理不并存

`cloud-common-inner-starter` 的 Feign 出站拦截器是**全局**的——引 jar 即对全部 Feign 请求注入 `X-Inner-Secret` 头。聚合层调用的是下游外部面（不是 `/inner/**` 路径），下游入站过滤器只拦截 `/inner/**`，带了多余密钥头虽不会报错，但语义上是「把服务间密钥泄露给非 inner 通道」，且聚合层自身没有 `/inner` 提供面、也不需要读活跃密钥集。

**定稿：聚合层不引 `cloud-common-inner-starter`**。出站只有用户头转发拦截器，两个拦截器无并存/互斥问题。将来若聚合层需要调用下游 `/inner` 面（如批量状态查询），再引 jar 并在该轮明确两拦截器并存口径——本轮不预埋。

### 4.4 Feign 声明归位：提供方 api 模块 + 出参镜像模型

沿「Feign 声明统一归位提供方 `-api` 模块」的既有口径（2026-10-09 契约聚合轮定型）：api 模块不能依赖提供方实现 jar，所以在外部面 client 的返回模型用**镜像类**——字段与下游 VO 逐字相同的纯数据类，落提供方 api 模块 `domain/` 子包，注释注明镜像来源。镜像类不带任何注解（翻译注解、数据权限列注解都是下游的事，透传场景纯转发 JSON）。

Jackson 兼容性已核实：公共库的日期/Long 统一格式走 `Jackson2ObjectMapperBuilderCustomizer`（Boot 全局 builder，含 Feign 编解码使用的 ObjectMapper），聚合层与下游的 `yyyy-MM-dd HH:mm:ss` 日期串、`Long→String` 序列化完全同构——镜像类零转换透传成立。首轮冒烟仍会实测日期字段往返（§9）。

### 4.5 并行编排：固定小线程池 + 显式上下文传递

三段互相独立，串行会叠加延迟（审批段含数据权限求值，三次求值串行可到数百毫秒）。两案对比：

- **A. 主线程串行**：实现最简，延迟 = 三段之和；比前端现状（并行两次）更慢，与聚合动机相悖。
- **B. 并行 + 显式 ThreadLocal 上下文**（采纳）：固定线程池并行三段；用户头在编排前一次性读入不可变 Map，主线程与每个段任务开头都放进聚合层自有的 `ThreadLocal` 上下文，任务结束清理——不依赖 Spring 的请求上下文跨线程行为（其默认不可继承，显式传递是唯一可控路径）。

线程池口径：core=max=8（3 段 × 并发请求余量），队列不堆积（`SynchronousQueue`），拒绝策略 `CallerRuns`（池满时退化为调用线程串行执行，正确性无损只损时延）。段级超时靠 Feign client 配置兜底（§7），编排层不做额外超时包装。

### 4.6 段准入预判：省掉注定 403 的下游调用

聚合层收到的 `X-User-Perms` 与下游 `HeaderAuthFilter` 构建 authorities 的是**同一份登录快照**（网关同源注入），所以聚合层预判「该段权限是否在快照中」与下游 `@PreAuthorize` 判定等价。无权限的段**不发下游调用**，直接装配段失败态（7001），为无 bpmn 权限的用户省掉两次注定 403 的内网请求（这是前端现状「无权限卡不发请求」优化的后端等价迁移）。

安全性论证（两个方向）：预判误放行 → 下游 403 → 转译 7001，安全无损（下游是真把关人）；预判误拒 → 只损可用性不损安全，且段与权限的对应关系冻结在契约里（§contract 5），不会漂移。

---

## 5. 封装设计

### 5.1 模块清单（新增）

```
cloud-base/
├── cloud-aggregate/                         # 新服务 :9205
│   ├── pom.xml                              # 依赖见 §5.5，无 mybatis/mysql/redis/xxljob/inner/MQ
│   └── src/main/java/com/cloudai/aggregate/
│       ├── AggregateApplication.java        # @EnableFeignClients(clients={BpmnWorkbenchClient, NotifyUserClient})
│       ├── controller/
│       │   ├── WorkbenchController.java     # GET /workbench，两行式返回，javadoc
│       │   └── DemoController.java          # GET /demo/ping（连通性，网关白名单放行）
│       ├── service/
│       │   └── WorkbenchService.java        # 编排：身份校验→预判→并行三段→装配；@Slf4j
│       ├── vo/
│       │   ├── WorkbenchVo.java             # { todo, approval, notify } 三段
│       │   ├── TodoSectionVo.java           # { status, msg, data: PageResult<TaskVo> }
│       │   ├── ApprovalSectionVo.java       # { status, msg, data: PageResult<ApprovalVo> }
│       │   ├── NotifySectionVo.java         # { status, msg, data: NotifySummaryVo }
│       │   └── SectionStatusEnum.java       # 段状态枚举（Enum 后缀规则）：OK(200)/NO_PERM(7001)/DOWNSTREAM_ERROR(7002)
│       ├── security/
│       │   ├── AggregateUserContext.java    # ThreadLocal<Map<String,String>>：段任务内取用户头
│       │   └── UserHeaderForwardInterceptor.java  # Feign 出站：上下文五头 → 请求头
│       └── config/
│           ├── AggregateProperties.java     # @ConfigurationProperties("cloud.aggregate")：pool-size（默认 8）
│           └── AggregateFeignConfig.java    # 线程池 bean + 拦截器注册
└── cloud-api/
    ├── cloud-bpmn-api/                      # 增量：外部面复用 client + 镜像
    │   └── .../api/
    │       ├── client/BpmnWorkbenchClient.java    # contextId=bpmnWorkbenchClient, fallbackFactory 必带
    │       │                                      #   @GetMapping("/task/todo")、@GetMapping("/approval/page")
    │       ├── fallback/BpmnWorkbenchClientFallbackFactory.java  # 降级 R.fail("cloud-bpmn 服务不可用")
    │       └── domain/TaskVo.java / ApprovalVo.java              # 镜像类（字段逐字同下游 vo，无注解）
    └── cloud-system-api/
        └── .../api/
            ├── client/NotifyUserClient.java       # contextId=notifyUserClient（与既有 inner 发送 NotifyClient 区分）
            │                                       #   @GetMapping("/notify/summary")
            ├── fallback/NotifyUserClientFallbackFactory.java
            └── domain/NotifySummaryVo.java        # 镜像类（unreadCount/latestId 两字段）
```

镜像字段清单（与下游 VO 逐字核对过，2026-11-14 实读）：

- `TaskVo` 镜像（源：cloud-bpmn `vo/TaskVo.java`，9 字段）：taskId、approvalId、businessType、businessTypeName、title、detailPath、applyUser、applyUserName、createTime——全 String。
- `ApprovalVo` 镜像（源：cloud-bpmn `vo/ApprovalVo.java`，15 字段）：id（Long，序列化后 String）、businessType、businessTypeName、businessKey、title、status（"0"-"3" 字符串）、statusLabel、applyUser、applyUserName、approver、approverName、detailPath、processInstanceId、createTime（LocalDateTime）、updateTime（LocalDateTime，可为 null）。
- `NotifySummaryVo` 镜像（源：cloud-system `vo/NotifySummaryVo.java`，2 字段）：unreadCount、latestId——全 String。

### 5.2 聚合层自身端点与段装配

- `GET /workbench` 无入参：三段的取数参数固定 `pageNum=1&pageSize=5`（契约冻结，前端工作台现状即 top5；段参数扩展留给未来轮 additive）。
- Controller 两行式返回；身份缺失（无 `X-User-Id` 头，即绕过网关直连服务端口的场景）→ `BusinessException(401)`，沿「服务层 HTTP 200 + body.code」惯例。
- 段装配：每段独立 try/catch——catch 内 `log.error` 记根因（规范第 4 条）后装配 `DOWNSTREAM_ERROR` 段；任何一段异常不外抛、不影响其它段。
- 段 data 直接用 api 模块镜像类型（它们是契约 VO，不是 DB 实体，符合 VO 出参规范；聚合层零字段转换）。

### 5.3 用户头转发拦截器（示意，≤30 行）

```java
/** Feign 出站：把当前聚合请求携带的用户身份五头写入下游请求头。
 *  头值来源是网关注入的真实身份（网关已剥离外部伪造头）；非聚合请求线程（上下文为空）不注入，
 *  下游按匿名处理、由其自身安全链裁决。 */
@Component
@RequiredArgsConstructor
public class UserHeaderForwardInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        Map<String, String> headers = AggregateUserContext.get();
        if (headers == null) {
            return;
        }
        headers.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                template.header(k, v);
            }
        });
    }
}
```

五个头名直接引用 `SecurityConstants` 常量（`X-User-Id / X-User-Account / X-User-Perms / X-User-RoleIds / X-User-DeptIds`），不散落字符串。空值头跳过（网关对空角色/空部门注入空串——转发语义与下游解析为空列表一致，可注入也可跳过，取跳过更干净）。

### 5.4 编排流程（WorkbenchService 单一职责拆分）

方法体按 ≤50 行拆分为四个私有步骤方法：

1. `requireUserHeaders()`：读请求头构建不可变五头 Map；缺 `X-User-Id` → `BusinessException(401)`。
2. `buildSections(headers)`：主线程 `AggregateUserContext.set` + 提交三个段任务（`CompletableFuture` 各自带 set/clear）+ join。
3. 每段一个装配方法（`buildTodoSection` / `buildApprovalSection` / `buildNotifySection`）：先段准入预判（bpmn 两段查 perms，notify 段恒发起），再 Feign 调用，按 §7 转译表装配段。
4. `assembleWorkbench(...)`：三段装进 `WorkbenchVo`。

### 5.5 pom 依赖清单（裁剪自 cloud-task 模板）

保留：`cloud-common-core-starter`、`spring-boot-starter-web`、nacos discovery/config、`springdoc`、`cloud-common-security-starter`、`spring-cloud-starter-openfeign`、`spring-cloud-starter-loadbalancer`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`cloud-bpmn-api`、`cloud-system-api`、resilience4j circuitbreaker。
移除：`cloud-common-mybatis-starter`、mysql driver、`cloud-common-redis-starter`、`cloud-common-xxljob-starter`、`cloud-common-inner-starter`、rocketmq 相关、`cloud-task-api`。

---

## 6. 数据流（时序文字）

1. 浏览器 → 网关：`GET /aggregate/workbench` + Bearer JWT。
2. 网关：白名单不命中（`/aggregate/**` 不在白名单）→ 验签 → 查 `sso:online:{jti}` → 注入五头 → 路由到 cloud-aggregate（StripPrefix 后命中 `/workbench`）。
3. cloud-aggregate：构建五头 Map → 段预判（无 `bpmn:task:list` 则 todo 段直接 7001，其余同理）→ 三段并行 Feign（各自带五头）。
4. 下游：`HeaderAuthFilter` 从五头构建认证上下文 → `@PreAuthorize` 校验 → 数据权限求值（审批段行级+列级）→ 字段翻译 → 返回 `R<PageResult<...>>`（HTTP 200）。
5. cloud-aggregate：按转译表装配三段 → `R<WorkbenchVo>`（HTTP 200 + code 200）。
6. 前端：单请求拿全量 → 按 `段.status` 渲染卡片 / 空态 / 隐藏。

---

## 7. 错误处理

### 7.1 段状态转译表（契约定稿依据）

| 下游实际表现 | 段 status | 段 msg | 说明 |
|---|---|---|---|
| 下游返回 code=200 | 200 | 操作成功 | 正常段 |
| 下游返回 code=403（`@PreAuthorize` 拒绝，服务层惯例 HTTP 200 + body 403） | 7001 | 无操作权限 | msg 沿下游 `ErrorCode.FORBIDDEN` 文案 |
| 聚合层段准入预判不通过（未发下游） | 7001 | 无操作权限 | 与下游拒绝同文案，前端无需区分来源 |
| 下游返回 code=1002（fallbackFactory 降级，如「cloud-bpmn 服务不可用」） | 7002 | 取下游 msg | 熔断打开期持续 7002，属预期 |
| 下游返回其它非 200 code（含 500/域错误码） | 7002 | 取下游 msg | 下游业务/系统失败一并归段失败 |
| FeignException（降级链未接住的异常） | 7002 | 下游调用异常 | catch 兜底，log.error 记根因 |

### 7.2 整体错误码（7xxx 段记档）

- 整体维度：HTTP 恒 200；`code=200` 聚合成功（**部分段失败也算聚合成功**，段内自述）；匿名缺身份 → `code=401`；聚合层自身异常 → `code=500`（GlobalExceptionHandler 兜底）。
- **新错误码段 7xxx 归 cloud-aggregate**，本轮分配：7001 段无权限 / 7002 段下游失败，后续聚合域错误码从 7003 接续。CLAUDE.md 错误码分段行随本轮记档更新。
- 段状态值复用同一个语义空间（200/7001/7002），前端判断口径统一为 `section.status === 200`。

### 7.3 超时与熔断预算

- Feign：connect 1s / read 3s（下游三端点均为快查询；审批段含数据权限求值实测在百毫秒级，3s 留 10 倍余量）。`disable-time-limiter: true`（resilience4j TimeLimiter 默认 1s 会截断 read，四胞胎同款处置）。
- 端到端预算：三段并行，P99 ≤ max(段) + 自身开销 ≈ 3.2s，远低于前端 axios 全局 15s 超时——聚合改造不触碰前端超时配置。
- 熔断：resilience4j 默认滑动窗口；某下游持续不可用 → 该段持续 7002，其余段正常——单点故障不放大为整屏失败。

---

## 8. 网关与 Nacos 配置面

### 8.1 网关（cloud-gateway/application.yml，两处增量）

1. lb 路由：`id: cloud-aggregate` / `uri: lb://cloud-aggregate` / `Path=/aggregate/**` / `StripPrefix=1`，置于既有四个业务服务路由之后、AI 路由之前。
2. 鉴权白名单（`AuthGlobalFilter.WHITELIST`）追加 `/aggregate/demo`——连通性冒烟需要无凭证可达；`/aggregate/workbench` 不进白名单（默认走 JWT 鉴权，自动继承五头注入）。
3. 不新增屏蔽段：聚合层无 `/inner` 面、无 MCP 面。

### 8.2 Nacos（双租户纪律）

- 新建 `cloud-aggregate.yaml`（最小壳）：`cloud.aggregate.pool-size: 8` + 注释预留。**向 `''` 与 `public` 两租户发完全相同的干净内容**（3.x 双写合并坑：两租户不同真值会合成重复嵌套键导致 YAML 解析失败）。
- 聚合层 `application.yml` 仍按四胞胎惯例接线 `optional:nacos:cloud-aggregate.yaml` + `optional:nacos:cloud-common.yaml`（共享配置当前只有 JWT 密钥与 xxl-job 公共项，聚合层两者都不消费，接线仅为惯例统一，optional 容缺无副作用）。
- 备份脚本 `backup_nacos_config.py` 为**全量导出**（无硬编码 data_id 清单），新 data_id 自动纳入；`application-local.yml` 也是按库中实际配置自动生成——均零脚本改动。CLAUDE.md 中「当前 5 个 data_id」的描述同步更新为 6 个。
- 聚合层无密钥面：`.env`、Redis、JWT 密钥均不需要；Nacos 不可达时本地 `application.yml` 兜底即可完整启动（无配置面依赖）。

---

## 9. 测试策略

| 层 | 手段 | 验收点 |
|---|---|---|
| 守护测试 | cloud-aggregate 内新增 `AggregateArchitectureGuardTest`（沿 cloud-system 守护测试模式，取适用子集） | 禁内联 `R.ok`、`@PathVariable` 显式命名（本轮无 path variable，防回归）、禁 Map 入参/出参、Controller javadoc、段状态枚举名 Enum 后缀；api 模块镜像字段清单断言（字段名集合硬编码对比，防镜像漂移） |
| 单测 | WorkbenchService 段转译表逐行（mock Feign client 返回各形态 R） | 200→200 / 403→7001 / 1002→7002 / 其它→7002 / FeignException→7002；预判跳过不发起调用；身份缺失 401 |
| 真链路冒烟 | `cloud-base/scripts/smoke/smoke_aggregate.sh`（沿 smoke_task_center.sh 惯例） | 登录取 token → 网关 `GET /aggregate/workbench` → 断言三段 status=200 与字段形状（含 `createTime` 格式、`total`/`id` 字符串化——验证 §4.4 的 Feign 编解码同构）；再以无 bpmn 权限用户断言 bpmn 两段 7001、notify 段 200 |
| e2e 回归 | 既有聚合七脚本 + BP12 | dashboard 数据源切换后 `run-bpmn-e2e.mjs` BP12（两卡渲染/行数/双跳转）真链路原断言不红；侧边栏 20 项形态不变（本轮无新菜单） |
| e2e 专线 | 新增 `cloud-e2e/run-aggregate-e2e.mjs`（route mock 分层，不进聚合脚本——沿 taskcenter/aichat 专线先例） | mock 三态：三段全成功（两卡+铃铛摘要渲染）/ bpmn 段 7001（两卡隐藏、通知卡正常）/ bpmn 段 7002（空态展示） |

---

## 10. DDL 说明

本服务**无数据库**：无表、无索引设计义务；无迁移脚本。持久化职责全部留在下游各服务。

---

## 11. 移交备忘（下一轮规划前必读）

1. **AI 域聚合预留**：cloud-base-rag / cloud-base-agent 已注册 Nacos 且 Python 端已有同构 R 响应体，Java Feign 理论可达；跨语言 `-api` jar 机制缺失，接入时须另立契约形态（本轮明确排除）。
2. **镜像漂移风险**：api 模块镜像类与下游 VO 靠人工+守护测试字段清单同步；下游 VO 字段变更时契约文档是唯一对齐物——提供方改端点出参必须连带检查本契约 §3。
3. **顶栏轮询合并是机会项**：前端通知 store 每 30s 轮询 `/system/notify/summary` 与工作台段有重叠数据源，未来可评估把铃铛轮询也切到聚合端点（水位段）——本轮明确不动。
4. **写入路径永不进聚合层**：跨服务写已有事务消息与 Feign 同步链两套口径，聚合服务保持纯读；将来任何写编排需求不落入本服务。
5. **其余 3 处跨服务组合**（请假详情弹窗、MCP 权限弹窗、KB 权限弹窗）机制验证后按需另轮迁移，本轮不动。
6. **实现时验证清单**（机制已核实、联调须实测）：① Feign 解码 `PageResult<镜像>` 嵌套泛型与 `LocalDateTime` 往返（冒烟断言覆盖）；② 熔断打开期段持续 7002 的行为观察；③ 并行池在压测下的 CallerRuns 退化路径（可选）。
7. **CLAUDE.md 记档清单**（随实现轮落地）：模块拓扑加 cloud-aggregate :9205、启动命令加一行、错误码分段补 7xxx、端口行补 9205、网关白名单行补 /aggregate/demo、备份 data_id 数 5→6、「四个业务服务多胞胎」表述更新为「四业务服务 + 纯读聚合服务」。
