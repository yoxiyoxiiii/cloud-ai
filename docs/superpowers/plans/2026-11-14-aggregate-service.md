# 聚合服务 cloud-aggregate 实施计划（2026-11-14，全栈轨道）

> 输入：设计 `docs/superpowers/specs/2026-11-14-aggregate-service-design.md`、契约 `docs/superpowers/contracts/2026-11-14-aggregate-workbench-api.md`（冲突时以契约为准，仍歧义回报主控问架构）。
> 轨道：全栈——后端章（T1-T9）与前端章（F1-F3）**独立可并行**；前端只需契约定稿即可开工。集成验收（§4）在两侧完成后执行。
> 开工前必读：根 pom 现有 modules、cloud-task 模板（裁剪蓝本）、cloud-bpmn-api / cloud-system-api 现有结构、`docs/multi-agent-dev-sop.md` 文档与注释可读性标准。

---

## 1. 依赖序总览

- 后端：T1（骨架）→ T2（api 契约增量）→ T3/T4/T5 可并行（转发/编排/端点）→ T6/T7/T8/T9 收口。
- 前端：F1 → F2 → F3 串行，与后端全程并行（契约即输入）。
- 集成：冒烟 + e2e 回归在两侧完成后统一执行。

## 2. 后端章（给 backend-agent 的任务清单）

| # | 任务 | 关键文件 | 验收要点 |
|---|---|---|---|
| T1 | 服务骨架 | `cloud-base/pom.xml`（modules 注册）、`cloud-base/cloud-aggregate/pom.xml`、`AggregateApplication.java`、`application.yml`、`controller/DemoController.java` | 依赖清单=设计 §5.5（无 mybatis/mysql/redis/xxljob/inner/MQ）；端口 9205、服务名 cloud-aggregate、nacos 双 optional import、feign circuitbreaker.enabled=true + connect 1000/read 3000 + disable-time-limiter；`/aggregate/demo/ping` 直连通 |
| T2 | api 契约增量 | `cloud-bpmn-api`：`client/BpmnWorkbenchClient.java`（contextId=bpmnWorkbenchClient，GET /task/todo + GET /approval/page）、`fallback/BpmnWorkbenchClientFallbackFactory.java`、`domain/TaskVo.java`、`domain/ApprovalVo.java`；`cloud-system-api`：`client/NotifyUserClient.java`（contextId=notifyUserClient，GET /notify/summary）、`fallback/NotifyUserClientFallbackFactory.java`、`domain/NotifySummaryVo.java`；两模块 `AutoConfiguration.imports` 注册 fallbackFactory bean | 镜像字段与契约 §1.3 逐字一致（TaskVo 9 字段 / ApprovalVo 15 字段 / NotifySummaryVo 2 字段）；镜像类无注解、javadoc 注明「外部面出参镜像，随契约 2026-11-14 §3 同步」；client 必带 fallbackFactory；降级形态 `R.fail("cloud-bpmn 服务不可用")` / `R.fail("cloud-system 服务不可用")` |
| T3 | 用户头转发 | `security/AggregateUserContext.java`（ThreadLocal Map，set/get/clear）、`security/UserHeaderForwardInterceptor.java`、`config/AggregateFeignConfig.java`（拦截器 bean + 段并行线程池） | 头名全部引用 `SecurityConstants` 常量（五头）；上下文空时不注入任何头；线程池 core=max 取 `AggregateProperties.pool-size`（默认 8）、SynchronousQueue + CallerRuns；**全工程不引 cloud-common-inner-starter**（grep 验证 pom 无该依赖） |
| T4 | 编排服务 | `service/WorkbenchService.java`、`config/AggregateProperties.java` | 编排四步拆分（§5.4）：身份校验→预判→并行三段→装配；方法体 ≤50 行、单一职责；段准入预判（todo 段查 bpmn:task:list、approval 段查 bpmn:approval:list、notify 段恒发起）；每段独立 try/catch + `log.error` 记根因；固定取数 pageNum=1&pageSize=5 |
| T5 | 端点与 VO | `controller/WorkbenchController.java`、`vo/WorkbenchVo.java`、`vo/TodoSectionVo.java`、`vo/ApprovalSectionVo.java`、`vo/NotifySectionVo.java`、`vo/SectionStatusEnum.java` | Controller 两行式返回 + javadoc + 登录即可（无 @PreAuthorize）；缺 X-User-Id → `BusinessException(401)`；段对象结构 status/msg/data，status≠200 时 data 恒 null；枚举名 Enum 后缀 |
| T6 | 网关 | `cloud-gateway/src/main/resources/application.yml`（lb 路由）、`filter/AuthGlobalFilter.java`（白名单 +/aggregate/demo） | 路由置于 task 路由之后、AI 路由之前；StripPrefix=1；`/aggregate/workbench` 不进白名单；重启网关后无凭证 ping 200、无凭证 workbench 401 |
| T7 | Nacos 配置面 | 新建 data_id `cloud-aggregate.yaml`（`''` 与 `public` 两租户**完全相同内容**：`cloud.aggregate.pool-size: 8` + 注释）；跑 `backup_nacos_config.py` 出新快照 | 两租户内容逐字一致（备份脚本体检行报一致）；`cloud-aggregate/src/main/resources/application-local.yml` 自动生成（gitignored）；聚合服务加 `--spring.profiles.active=local` 可本地兜底启动（可选验证） |
| T8 | 测试与冒烟 | `cloud-aggregate/src/test/java/.../AggregateArchitectureGuardTest.java`、`WorkbenchServiceTest.java`、`cloud-base/scripts/smoke/smoke_aggregate.sh` | 守护项=设计 §9 表（含镜像字段清单断言）；单测覆盖转译表五行 + 预判跳过 + 身份缺失 401；冒烟两场景：admin 三段全 200（断言 createTime 格式与 total/id 字符串化）、构造无 bpmn perms 用户两段 7001 + notify 段 200 |
| T9 | 记档收口 | `CLAUDE.md`（模块拓扑 :9205 / 启动命令 / 错误码 7xxx / 网关白名单行 / 备份 data_id 5→6 / 多胞胎表述更新）、契约与设计文档无改动则不动 | CLAUDE.md 六处更新与设计 §11.7 清单一致 |

后端整体验收：`$MVN -f cloud-base/pom.xml clean install` 全绿（含新守护测试）；冒烟脚本两场景全过。

## 3. 前端章（给 frontend-agent 的任务清单）

| # | 任务 | 关键文件 | 验收要点 |
|---|---|---|---|
| F1 | api 与类型 | `cloud-web/src/api/aggregate.ts`、`cloud-web/src/types/api.ts`（增量 WorkbenchVo/三段类型） | `getWorkbench(): Promise<WorkbenchVo>` → GET /aggregate/workbench；段数据类型复用既有 `TaskVo / ApprovalVo / NotifySummaryVo` 定义（字段逐字一致，不新造重复类型）；段 status/msg/data 结构注释引契约 §1.2 |
| F2 | dashboard 切换 | `cloud-web/src/views/dashboard/index.vue` | 单请求 `getWorkbench()`；卡片显隐由段 status 驱动（200 渲染 / 7001 整卡隐藏 / 7002 空态 + msg）；status≠200 不解引用 data；删页内 hasPerm 判断与双请求编排；视觉不变：卡标题/加载态/空态文案（「暂无待办任务」「暂无审批记录」）/「查看全部」跳转 / 状态 tag 颜色映射全保留；`defineOptions({ name: 'Dashboard' })` 保留（keep-alive 依赖） |
| F3 | e2e | 新增 `cloud-e2e/run-aggregate-e2e.mjs`（route mock 专线，不进聚合回归脚本） | 三态场景：全成功（两卡 + 摘要渲染）/ bpmn 段 7001（两卡隐藏、通知摘要正常）/ bpmn 段 7002（空态 + msg 展示）；`grep -c '[FAIL]'` = 0（退出码不可信，只认 FAIL 计数）；`npm run build` 绿 |

前端整体验收：build 绿 + 专线 FAIL=0；不改 `utils/sse.ts`、不动顶栏通知轮询、不动其余页面。

## 4. 集成验收（两侧完成后）

1. 起服：cloud-aggregate :9205 + 网关重启（路由生效）+ cloud-bpmn / cloud-system（应零改动、无需重启）。
2. 冒烟：`smoke_aggregate.sh` 两场景全过（经网关）。
3. e2e 回归：`npm run e2e` 全量聚合 `[FAIL]`=0（BP12 工作台真链路 + 侧边栏 20 项形态不变）+ `npm run e2e:aggregate` 专线全绿。
4. 双审：架构视角（契约逐字合规：两行式返回、段转译表、镜像冻结）+ 实现视角（守护测试、日志纪律、ThreadLocal 清理无泄漏）。
5. 收口：CLAUDE.md 记档核对（T9 清单）+ 本计划「移交备忘」回填实测结论。

## 5. 移交备忘（本轮开工前预置，收口时回填）

1. 设计 §11.6 三项验证结论（2026-11-14 收口实测）：① Feign 嵌套泛型与日期往返——冒烟实测通过（`R<PageResult<TaskVo>>` 嵌套泛型解码正确，total/id Long→String 字符串化、createTime `yyyy-MM-dd HH:mm:ss` 格式断言过，与下游直连逐字一致）；② 熔断期 7002——停 cloud-bpmn 实测：整体 HTTP 200 + code 200，todo/approval 两段 7002（msg「cloud-bpmn 服务不可用」降级文案）、notify 段 200 不受影响（段间隔离实证）；bpmn 复起注册后首次调用即三段恢复 200，降级非粘性（单次失败未达熔断 OPEN 阈值；契约记档的「半开恢复前降级持续属预期」形态未触发）；③ 并行池退化——静态结论成立：SynchronousQueue 不堆积 + CallerRuns 池满退化串行由设计论证与单测覆盖（8 线程 vs 3 段，正常态永不触发退化路径）。
2. 其余 3 处跨服务组合页（请假详情弹窗、MCP/KB 权限弹窗）迁移另轮；顶栏轮询合并机会项另轮评估。
3. AI 域跨语言聚合接入须先立契约形态（无 `-api` jar 先例），未立前不接入。
4. 聚合层将来若需调下游 `/inner` 面：须引 cloud-common-inner-starter 并在该轮明确「用户头转发拦截器 + inner 密钥拦截器」并存口径（本轮物理不并存，见设计 §4.3）。
