# 数据权限求值 AOP 化设计（@DataPermEvaluate 切面）

- 日期：2026-11-16
- 状态：定稿（重构轮，主控出品；翻案 `2026-10-10-data-permission-design.md` D5 的「无注解无 AOP」约束，其余 D 系列结论不变）
- 关联：`2026-10-10-data-permission-design.md`（原始设计）、`2026-10-10-data-permission-api.md`（契约）、谓词轮 `2026-10-11-dataperm-predicate-*`、注解化轮 `2026-10-12-dataperm-annotation-*`

## 1. 问题与目标

数据权限接入面的 Service 读路径（用户分页/详情、请假分页/详情、审批分页/详情/图数据共 6 处）存在同构样板：取当前登录人 → 求值（本地 evaluator 或远程 Feign fail-closed 胶水）→ `DataPermColumnContext.set(...)` → 方法体内取 RowFilter 做行集判定。每新增一个受控读路径都要手抄一遍，且两种后端（cloud-system 本地 / cloud-bpmn 远程）胶水各自维护。

目标：求值与列上下文接线收敛到一个注解 + 一个切面，方法体只剩业务；`@DataPermVo` 列改写链路（advice）原样保留。

## 2. 组件清单

| 组件 | 位置 | 职责 |
|---|---|---|
| `@DataPermEvaluate` | cloud-system-api `dataperm/` | 方法级注解：`resource` / `operation` / `businessKey`（缺省空=列表语义） |
| `DataPermEvaluateAspect` | 同上 | @Around 切面：解析 businessKey → 经 provider 求值 → set 列上下文 + set 求值holder → proceed → finally 恢复外层求值 |
| `DataPermEvaluation` | 同上 | 求值结果 record（resource/columnScope/rowFilter）+ ThreadLocal holder；`currentRowFilter()` 未求值即抛 IllegalStateException |
| `DataPermEvaluationProvider` | 同上 | 求值后端 SPI（单方法 `evaluate(resource, operation, businessKey)`），宿主各自实现 |
| `DataPermEvaluateAspectAutoConfiguration` | 同上 `config/` | @ConditionalOnWebApplication(SERVLET) + @ConditionalOnMissingBean 注册切面；注册进 AutoConfiguration.imports |
| `LocalDataPermEvaluationProvider` | cloud-system `service/dataperm/` | 包本机 DataPermEvaluator（规则库在 system——provider 不自我 Feign） |
| `RemoteDataPermEvaluationProvider` | cloud-bpmn `service/dataperm/` | evalScope Feign fail-closed 胶水原样迁入；当前登录人由 SecurityUtils 解析 |

api 模块新增 `spring-boot-starter-aop` 依赖（compile，沿 cloud-common-mcp-starter 同款声明）——消费方（sso/task/aggregate 等）传递获得，无标注方法即零代理零行为变化。

## 3. 关键设计

### 3.1 切点与 businessKey：字面量 + `#pN` 位置引用

切点必须字面量形式（`@annotation(com.cloudai....DataPermEvaluate)`），注解经反射读取——绑定式 `@annotation(x)` 需 `-parameters` 编译参数（本工程未启用），会静默不命中（与 MCP 权限切面同坑，见 CLAUDE.md「MCP 工具面」）。`businessKey` 只支持 `#pN` 位置引用（正则 `#p\d+`，`String.valueOf(args[n])`），不用 SpEL/形参名引用——后者同样依赖 `-parameters`，写错静默得 null。越界/非法表达式启动后首次调用即抛，fail-fast。

### 3.2 求值时点与语义变化（接受的差异）

切面在**方法入口**求值，先于 3018/4010 存在性检查：

- 留痕表会为不存在的 id 也留一行求值记录（原本不存在的单不产生留痕）——可观察性反而更完整；
- 基础设施故障（Feign 不可用等）对不存在的 id 报「权限服务不可用」而非 3018/4010——fail-closed 方向，不泄漏存在性差异。

不存在仍然报 3018/4010（方法体内判断不变），拒绝语义与 IDOR 收口（3026/4018 + deny 补痕）全部原样。

### 3.3 holder 语义

- `DataPermEvaluation.currentRowFilter()`：未求值（漏标注/自调用绕过代理/误用）抛 IllegalStateException——自调用是显式不支持的形态（生产 6 处调用点全部来自 controller/MCP 工具，无自调用）；
- 切面 finally 恢复**前一层**求值结果——嵌套标注方法（外层 list 内层 detail）各自正确；
- **列上下文生命周期不变**：切面 set（空 scope 也 set），永不 clear——清理仍归 `DataPermColumnContextFilter` 请求级双清（advice 的 3042 fail-closed 判据依赖「求值过即有上下文」）。

### 3.4 后端 SPI 与无开关

provider 未注册 → 调用即 IllegalStateException（fail-closed，不静默放行）；不设功能开关（沿 inner-starter 无开关先例）——关闭某资源的权限=不配规则（求值得全量档），而非关切面。

### 3.5 pageListMy 签名收敛

`pageListMy(PageQuery query, String account)` 去掉 account 参数——求值账号由 provider 内部经 SecurityUtils 解析；findById/findDiagram 保留 account 参数（deny 补痕仍需要）。

## 4. 测试策略

无 Spring 上下文，`AspectJProxyFactory` 织入**真实切面** + 真实 provider（包 mock 的 evaluator/Feign client）——与生产代理路径同构：

- api 模块 `DataPermEvaluateAspectTest`（9 用例）：接线/holder 生命周期/嵌套恢复/无 provider fail-closed 不 proceed/非法与越界 businessKey/求值异常不接线；
- 三个宿主测试类的读路径用例改走 `proxiedService()` 辅助；既有求值桩（`when(evaluator.evaluate(...))` / `when(client.evaluate(...))`）经 provider 委派**原样命中**；
- bpmn 桩的 `DataPermEvaluateRequest` 精确匹配 account 改 null（单测无登录上下文，SecurityUtils 返回 null）；
- 4010/3018 用例的 `verifyNoInteractions(求值器)` 断言按 §3.2 时点变化改为「留痕不发生（logDeny/deny 未调）」；
- diagram 用例原「零上下文锚」反转为「有上下文但 advice no-op」（返回树无白名单实例，改写零发生）。

## 5. 移交

- 无新增错误码/权限标识/表/接口——契约面零变化（内部重构）；
- 若未来出现自调用形态的受控读路径（Service 内部 this 调用标注方法），需改为经代理调用或显式编程式兜底——当前不存在此形态。
