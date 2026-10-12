# 数据权限列处理注解化实施计划（纯后端轨道：api 组件 + leave/bpmn 双试点同轮切换）

- 日期：2026-10-12
- 输入：设计 `docs/superpowers/specs/2026-10-12-dataperm-column-advisor-design.md`（D51-D58，红线见其 §9）、契约 `docs/superpowers/contracts/2026-10-12-dataperm-column-advisor-api.md`（轻量修订式，wire 零变更 + 3042）
- 轨道：纯后端（无前端章——机制对前端透明，前端零改动）
- 环境：mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；服务启动 java -jar（9201-9203 + 网关 18080）；开发期服务 agent 自管——联调+冒烟通过即 kill 交还

## B 章 后端任务

### B1 api 模块组件（cloud-system-api，TDD：每任务测试与实现同落）

#### B1.1 pom 依赖增量

文件：`cloud-base/cloud-api/cloud-system-api/pom.xml`

- 加 `org.springframework:spring-webmvc`（**provided**——ResponseBodyAdvice/@RestControllerAdvice/OncePerRequestFilter 编译所需，沿 translate-starter 先例）
- 加 `jakarta.servlet:jakarta.servlet-api`（**provided**——filter 签名编译；provided 参与 test classpath，单测 MockHttpServletRequest 脚手架同源可用）

验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-system-api -am` 编译通过；宿主（system/bpmn）依赖树无 provided 传染。

#### B1.2 白名单注解 + ThreadLocal 上下文

文件（新增）：
- `cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/DataPermVo.java`（类级、RUNTIME、无参数纯标记，javadoc 注明 D56 注解义务）
- `cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/DataPermColumnContext.java`（record ColumnDecision(String resource, ColumnScope columnScope) + ThreadLocal HOLDER + set/get/clear，javadoc 注明 D52「set 即接线声明，空 scope 也 set」）

测试（新增）：`cloud-base/cloud-api/cloud-system-api/src/test/java/com/cloudai/system/api/dataperm/DataPermColumnContextTest.java`——set/get 往返、clear 后 get null、null scope 的 set 不抛（防御）；@AfterEach clear 防跨用例污染。

验收：模块单测全绿。

#### B1.3 上下文清理 filter（双层清理，串号护栏）

文件（新增）：`cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/DataPermColumnContextFilter.java`——extends OncePerRequestFilter，doFilterInternal：入口 `clear()` → try `filterChain.doFilter` → finally `clear()`（设计 §3.4 逐字形态）。

测试（新增）：`.../dataperm/DataPermColumnContextFilterTest.java`——用 MockHttpServletRequest/Response/MockFilterChain：
1. 红态①（入口清）：doFilter 前 set 残留 → 自定义 FilterChain（doFilter 内断言 get()==null）
2. 红态②（finally 清）：FilterChain 内 set → doFilter 返回后 get()==null
3. 异常路径：chain.doFilter 抛 RuntimeException → 传播且 finally 已清（get()==null）

验收：三用例全绿（双层清理缺一不可——设计红线 7）。

#### B1.4 DataPermColumnAdvice（统一列应用 + fail-closed，核心任务）

文件（新增）：`cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/DataPermColumnAdvice.java`

实现要点（设计 §3.3 逐字遵守）：
- `@RestControllerAdvice` + `implements ResponseBodyAdvice<Object>`；常量 `ERR_COLUMN_CONTEXT_MISSING = 3042`（文案「数据权限列处理异常，响应已拒绝」）
- supports：泛型链收集类型（沿 TranslateAdvisor.findAnnotated 的 ParameterizedType 递归形态）→ `scanNeedCache`（Class→boolean：白名单类，或其 com.cloudai 字段类型闭包递归可达白名单类，visited 防循环类型）→ 任一 true 才介入；按 Method 缓存（ConcurrentHashMap）；解析异常 catch log.error return false（沿翻译）
- beforeBodyWrite：`data = body instanceof R<?> r ? r.getData() : body` → applyTree → Outcome 缺失/任何 Throwable → **log.error（URI/方法/VO 类）+ return R.fail(3042)**（fail-closed 总纲，**无任何原样返回的吞异常分支**——设计红线 7）
- applyTree：null/depth>MAX_DEPTH(5)/IdentitySet 防环剪枝；PageResult→rows 展开；Collection/数组逐元素；`@DataPermVo` 类→consume（上下文在→`DataPermColumnApplier.apply`；空→MISSING 短路）；其余 POJO→`drillNeedCache` 命中（字段含 com.cloudai 类型，含 Collection 泛型实参）逐字段 get 下钻
- consume 后不下钻白名单类字段（列 VO 字段是叶子；其翻译字段归 TranslateAdvisor）

测试（新增）：`.../dataperm/DataPermColumnAdviceTest.java`——测试包建脚手架 VO：`@DataPermVo TestColumnVo{title,reason,...}`、`TestDetailWrapperVo{TestColumnVo inner}`、`@DataPermVo SelfRefVo{SelfRefVo next}`、普通 `PlainVo`（无注解）。用例矩阵（设计 §7.1 十条逐条落）：
1. R<PageResult<TestColumnVo>> + ctx(hidden title/masked reason) → 两字段改写
2. R<TestDetailWrapperVo> → 下钻 inner 应用（嵌套红态核心）
3. 白名单在树、上下文空 → body 为 R.fail code=3042 且 data=null
4. set 空 scope（ColumnScope.of(Set.of(),Set.of())）→ 原样全可见
5. R<PlainVo> 无注解 → 原样零处理（上下文空也不触发 3042）
6. SelfRefVo 自引用 → 不死循环、正常返回
7. supports 红绿：返回 R<PageResult<TestColumnVo>>/R<TestDetailWrapperVo> 的 MethodParameter true；R<PlainVo>/R<Void> false
8. 树遍历只改列字段：TestColumnVo 加非列字段断言原值
9. PageResult.rows=[] 且上下文空 → 原样（无实例不触发 fail-closed）
10. 脚手架：MockHttpServletRequest + MethodParameter（真 controller 测试接口类或反射构造，沿翻译单测形态）
每用例 @AfterEach `DataPermColumnContext.clear()`。

验收：十用例全绿；代码评审确认无吞异常原样返回分支。

#### B1.5 自动装配

文件：
- 新增 `cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/config/DataPermColumnAdvisorAutoConfiguration.java`——`@AutoConfiguration` + `@ConditionalOnWebApplication(type = SERVLET)` + 两个 @ConditionalOnMissingBean @Bean（advice、filter）；**不设功能开关**（D57，javadoc 注明理由）
- 修改 `cloud-base/cloud-api/cloud-system-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`——additive 加一行 `com.cloudai.system.api.config.DataPermColumnAdvisorAutoConfiguration`

验收：imports 文件两行；装配类无条件污染既有 SystemApiAutoConfiguration（独立类，D51）。

#### B1.6 assertDeclared 注解断言扩展（漂移防护单点）

文件（修改）：`cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/DataPermColumns.java`——assertDeclared 方法首部加注解必标断言（未标 @DataPermVo → IllegalStateException，消息含 VO 类名与「D56」锚，设计 §3.6 逐字形态）；既有两断言零变更。

测试（修改）：`.../dataperm/DataPermColumnsTest.java`——增红态：未标注解的 VO 类 → IllegalStateException；绿态回归：既有用例的测试 VO 需补 @DataPermVo 标注（用例语义不变）。

验收：红绿两态全绿。

#### B1 收口

`$MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-system-api -am` 全绿；api 模块新增测试 ≥20 例全过。

### B2 system 试点迁移（leave）

文件（修改）：
1. `cloud-base/cloud-system/src/main/java/com/cloudai/system/vo/SysLeaveVo.java`——类头加 `@DataPermVo` + import（与既有 @TranslateVO 并存）
2. `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/LeaveManageService.java`：
   - pageList：evaluate 后加 `DataPermColumnContext.set(DataPermResources.LEAVE, decision.getColumnScope());`（**在空集短路判断之前**——空集分支也 set）；删除 L79 `rows.forEach(row -> DataPermColumnApplier.apply(...))` 行
   - findById：evaluate 行后加 set；删除 L99 applier.apply 行
   - import 清理：删 `DataPermColumnApplier`（若无他处引用），增 `DataPermColumnContext`；类 javadoc 增补一行「列应用迁移至 advice（2026-10-12 D51-D58）：Service 仅声明上下文」
3. `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/LeaveManageServiceTest.java`——列级用例改断言：原「applied 后 title==null / reason==***」改为断言 `DataPermColumnContext.get()` 非 null 且 columnScope 的 hidden/masked 集合正确（resource="leave"）；每个可能 set 的用例 @AfterEach clear；行级/空集短路/3026/3018 用例零改动

验收：`$MVN -f cloud-base/pom.xml test -pl cloud-system` 全绿；DataPermResourcesTest 全绿（本地 register 路径走扩展断言，SysLeaveVo 已标注解自动通过——B1.6 联动）；代码 diff 确认 applier 手写行零残留（不留双轨）。

### B3 bpmn 试点迁移（bpmn_approval，与 B2 可并行）

文件（修改）：
1. `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/vo/ApprovalVo.java`——类头加 `@DataPermVo` + import
2. `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/service/ApprovalQueryService.java`：
   - pageListMy：evalScope 后加 `DataPermColumnContext.set(RESOURCE_APPROVAL, scopeVo.toColumnScope());`（空集短路前）；删除 L104 `ColumnScope columnScope = scopeVo.toColumnScope();` 与 L109 循环内 apply 行
   - findById：evalScope 后加 set；删除 L124 apply 行
   - findDiagram：**零改动**（树无白名单类不 set——设计 §3.7 记档）
   - import 清理与 javadoc 增补同 B2
3. `cloud-base/cloud-bpmn/src/test/java/com/cloudai/bpmn/service/ApprovalQueryServiceTest.java`——set 断言适配同 B2（resource="bpmn_approval"）；diagram 用例增断言「求值后上下文为 null」；fail-closed 求值三态用例零改动
4. `DataPermColumnDeclarationRunner.java` **零改动**（B1.6 断言扩展自动覆盖 ApprovalVo 注解义务）

验收：`$MVN -f cloud-base/pom.xml test -pl cloud-bpmn` 全绿（含 cloud-system-api 依赖更新）；applier 手写行零残留。

### B4 全量构建 + 真链路冒烟（含 3042 红态实证）

1. 全量：`$MVN -f cloud-base/pom.xml clean install` 全绿（18 模块）
2. 起服务：sso 9201 → system 9202 → bpmn 9203 → 网关 18080（java -jar，最后网关）
3. 冒烟正态（wire 零变更实证）：
   - 配列规则（system:dataPerm:save，admin token）：leave 资源给 admin 角色 reason=脱敏 + title=隐藏；bpmn_approval 同款 title=脱敏
   - `curl http://localhost:18080/system/leave/page`（ASCII header）→ 断言 title null、reason `***`（与迁移前逐字节一致）
   - `curl http://localhost:18080/system/leave/{id}` 详情 → 嵌套 leave.title null（树下钻实证）
   - `curl http://localhost:18080/bpmn/approval/page` 与 `/{id}` → title `***`（跨服务双端实证）
   - `curl http://localhost:18080/bpmn/approval/{id}/diagram` → 正常返回（零上下文路径不触发）
4. **3042 红态实证**（fail-closed 真链路，验后必须还原）：
   - 注释 `LeaveManageService.pageList` 的 set 行 → `mvn install -pl cloud-system` → 重启 system → curl leave/page → 断言 code=3042 + data null + system 日志 log.error 含 uri/method
   - 还原 set 行 → 重装重启 → 复绿（正态 3 重验一次）
5. 冒烟数据清理（规则行删除/还原）；kill 全部服务交还（开发期服务自管口径）

验收：正态 wire 与迁移前一致 + 红态 3042 触发与恢复全链路通过 + 服务 kill 交还。

## E 章 e2e 回归（主控 ③ 阶段执行，本计划不派发实现）

- 命令：`cd cloud-e2e && npm run e2e:dataperm && npm run e2e:dataperm-component && npm run e2e:dataperm-predicate && npm run e2e:dataperm-operators && npm run e2e:bpmn`
- 判定口径：**FAIL 计数是唯一可靠门**（R9 记档：harness exit 0 是假绿、`| tail` 管道吞码）——每脚本 grep -c '\[FAIL\]' 为 0 才算过；预期 8+9+11+12+16 全 PASS
- 既有断言零维护即通过 = wire 零变更红线（设计红线 1）的最终证明；不新增 e2e 脚本（3042 红态由 B4 冒烟覆盖）

## 守护测试注意（backend-agent 必读）

- `cloud-system/src/test/java/com/cloudai/system/ArchitectureGuardTest.java` **零改动零触碰**：新组件落 cloud-system-api（不在守护扫描范围）；LeaveManageService/ApprovalQueryService 改动是行替换（不内联 R.ok、@PathVariable/方法名/分层不变）——任何守护变红即实现偏差，回报主控
- api 模块新代码规范：advice/filter 无状态无 mapper；类 javadoc 注明设计 D 号锚（项目惯例）；不用三方拷贝工具（树遍历反射 get/set 自实现，沿 TranslateAdvisor）

## 验证命令汇总

```bash
MVN=D:/software/apache-maven-3.8.4/bin/mvn
$MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-system-api -am   # B1
$MVN -f cloud-base/pom.xml test -pl cloud-system                              # B2
$MVN -f cloud-base/pom.xml test -pl cloud-bpmn                                # B3
$MVN -f cloud-base/pom.xml clean install                                      # B4 全量
# 冒烟经网关（服务已起）
curl http://localhost:18080/system/leave/page -H "Authorization: Bearer <token>"
```

## 移交后续阶段的备忘（下一阶段规划前必读）

1-7 条见设计文档 §11（双 advisor 遍历成本、com.cloudai 剪枝约定、**导出出口必须自行接列处理（VO 在 Service 返回时是原始值——最重要一条）**、异步形态预警、3042 告警联动、多资源同响应、错误码账本 3043 接续）。补充实现层两条：

8. **api 模块测试脚手架 VO（TestColumnVo 等）留在 test 包**：未来 advice 行为演进的回归基线，勿删
9. **接入模板 v3 已生效**（设计 §5.3）：新资源列接入 =「标注解 + assertDeclared + set」三步，applier 手写路径关闭——code review 对新读路径检查「求值后无 set」即 3042 隐患（会显性炸，属预期防御）

## 给 backend-agent 的任务清单（直粘）

> 按 `docs/superpowers/plans/2026-10-12-dataperm-column-advisor.md` B 章执行（B1 → B2∥B3 → B4 顺序，B2/B3 可并行），设计 `docs/superpowers/specs/2026-10-12-dataperm-column-advisor-design.md`（D51-D58，§3 组件示意逐字采用——fail-closed 总纲「任何异常一律 R.fail(3042)、无原样返回分支」是红线）、契约 `docs/superpowers/contracts/2026-10-12-dataperm-column-advisor-api.md`（wire 零变更 + 3042）。红线：applier（DataPermColumnApplier）签名行为零变更；行级全链/留痕/DDL/配置台 9 端点文件级零 diff；ArchitectureGuardTest 零触碰；4 调用点 applier 手写行全删不留双轨；双层清理（filter 入口+finally）缺一不可；mvn 用绝对路径 D:/software/apache-maven-3.8.4/bin/mvn。收口 = 18 模块 clean install 全绿 + B4 冒烟（含 3042 红态实证与还原）+ kill 服务交还；e2e 回归由主控 ③ 阶段派发（FAIL 计数口径），勿自行跑全量。完成后回报：文件清单、测试计数、冒烟正态/红态输出摘要。
