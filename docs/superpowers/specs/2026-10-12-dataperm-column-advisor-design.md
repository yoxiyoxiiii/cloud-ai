# 数据权限列处理注解化（VO 白名单注解 + ResponseBodyAdvice 统一应用）技术方案（纯后端 A 级）

- 日期：2026-10-12
- 状态：**定稿**——架构-agent 出品，配合拍板结论四项（2026-10-12，R10 需求分析回报后）；契约 `docs/superpowers/contracts/2026-10-12-dataperm-column-advisor-api.md`（轻量修订式）、计划 `docs/superpowers/plans/2026-10-12-dataperm-column-advisor.md`
- 拍板结论映射：① 方向 **b** 确认——注解 + ResponseBodyAdvice 统一返回（TranslateAdvisor 同款先例），leave + bpmn 双试点同轮切换不留双轨期；对基座轮 D5「无注解」口径**局部重开**（仅列级应用点，行级过滤/求值链零动）；② **fail-closed 拒绝**——advice 发现白名单 VO 但上下文缺失 → 拒绝请求 + log.error，错误码 **3042**（R9 死信治理轮已占用 3038-3041，本轮自 3042 接续，账本以 git log 38ba9f5 为准）；③ **隐藏列保持字段置 null**——wire 零变更，契约 §2 术语/前端 TS 类型/既有 e2e JSON 断言全部不动，`***` 与 columnSummary 三处冻结点继续冻结；④ **注解仅作 advice 白名单标记**——register/registerRemote 注册表行保持唯一声明真相源，注解 ⊆ 注册表漂移防护经 `DataPermColumns.assertDeclared` 扩展统一断言
- **与既有设计的关系**：本文档是数据权限域第五轮（基座 D1-D14 → 注册表重构 D15-D17 → 组件化 D18-D28 → 谓词 D29-D40 → 操作符 D41-D50）的**列应用形态演进轮**——决策编号 **D51-D58 接续**；行级（RowFilter/mapper XML/求值器/留痕/窄契约 Feign）全部前序决策零变化

## 1. 需求与范围

### 1.1 目标

数据权限列级动作（隐藏/脱敏）的应用点从「Service 读出行后逐行 mutate VO」迁移为「**VO 白名单注解 + 统一返回链路自动应用**」：Service 求值后仅声明上下文，列改写由 ResponseBodyAdvice 在序列化前统一完成——新读路径漏接列处理时由 fail-closed 显性拒绝（而非静默明文返回），消除「忘写 applier 行 = 越权泄露」的机制级隐患。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| api 模块组件 | `@DataPermVo` 白名单注解、`DataPermColumnContext` ThreadLocal 上下文、`DataPermColumnAdvice`（ResponseBodyAdvice）、`DataPermColumnContextFilter`（双层清理）、自动装配类；pom 加 spring-webmvc/jakarta.servlet-api provided |
| 漂移防护 | `DataPermColumns.assertDeclared` 增注解必标断言（本地 register 与消费方 runner 两路单点全覆盖） |
| 试点迁移 | leave（SysLeaveVo 标注解 + LeaveManageService 2 处调用点）+ bpmn（ApprovalVo 标注解 + ApprovalQueryService 2 处调用点）双试点同轮切换，applier 手写行全删 |
| 错误码 | system 段占用 **3042**（数据权限列上下文缺失，响应已拒绝） |
| 契约 | 轻量修订式：既有 `2026-10-10-data-permission-api.md` 全部承诺不变 + 增补 3042 语义 |

### 1.3 本轮不落（红线 + 移交）

**零变更红线**：行级 RowFilter 与 mapper XML（谓词/操作符两轮 e2e 红线）；求值器/留痕/sys_data_perm_log（D7 留痕语义与应用点解耦见 D58 论证）；窄契约 Feign（D19-D23）；sys_data_perm_column DDL 与配置台 9 端点；resources 列元信息接口；`***`（ColumnScope.MASK）/columnSummary 三处冻结点（D28）；my-scope；ArchitectureGuardTest 既有规则；wire 形态（隐藏=置 null，拍板③）。移交项见 §11。

## 2. 现状核实（2026-10-12 逐项复核）

1. **列应用现状 = 4 调用点 × 1 行 applier**：LeaveManageService.java:79（列表 forEach）/:99（详情）；ApprovalQueryService.java:104+109（列表循环）/124（详情）；diagram 路径（ApprovalQueryService.java:137-146）无列应用、返回树无列 VO——本轮不动
2. **统一返回先例完整可抄**：`TranslateAdvisor`（cloud-common-translate-starter core/TranslateAdvisor.java:41-79）——@RestControllerAdvice + ResponseBodyAdvice、supports 泛型链解析按 Method 缓存（L54-65）、R.data 分支处理（L72）、PageResult.rows/Collection/数组展开 + MAX_DEPTH=3 + IdentitySet 防环（L94-128）；其 pom 为 **spring-webmvc provided + jakarta.servlet-api test**，自动装配 `@ConditionalOnWebApplication(type = SERVLET)` + @ConditionalOnMissingBean——依赖与装配形态先例齐备
3. **试点 VO 已双注解就绪**：SysLeaveVo 与 ApprovalVo **均已标 @TranslateVO**（翻译 advisor 已在其响应上跑树遍历）——加 @DataPermVo 后双 advisor 并存，翻译写 labelField、列 advice 写列字段，零交集（见 D55 性能记档）
4. **嵌套形态确认**：SysLeaveDetailVo = `{leave: SysLeaveVo}`；ApprovalDetailVo = `{approval: ApprovalVo, steps: List<ApprovalStepVo>}`——列 VO 在 detail 包装类**下一层**，advice 必须下钻非白名单 POJO（与翻译「只下钻 @TranslateVO 实例」不同，翻译嵌套走手动回填逃生门，我们没有逃生门，见 D55）
5. **消费方断言点**：bpmn `DataPermColumnDeclarationRunner.java:42` 调 `DataPermColumns.assertDeclared(ApprovalVo.class, APPROVAL_COLUMNS)`；provider 本地经 `DataPermResources.register` → `assertDeclaredColumns` 委托同一方法（D22 单一实现）——**注解断言加在 assertDeclared 内部即两路全覆盖，runner 零改动**
6. **api 模块现状**：pom 仅 core-starter + openfeign + test；imports 文件单行 SystemApiAutoConfiguration；api 模块自动装配类无条件注解（降级工厂非 web 组件）——advice/filter 需 servlet 条件，**独立自动装配类**（D51）
7. **错误码账本核实**：R9 死信治理（git log 38ba9f5）占用 system 段 3038-3041——本轮 3042 起接续（R10 需求分析中「3038 接续」表述过时，以本节为准）
8. **守护测试零触碰面**：ArchitectureGuardTest 24 条规则全部针对 cloud-system 源码结构与 SQL；advice/filter 落 cloud-system-api（不在守护扫描范围），调用点迁移是行替换不触任何规则（不内联 R.ok、@PathVariable/方法名不变）

## 3. 组件设计（cloud-system-api 增量结构）

```
cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/
├── dataperm/
│   ├── DataPermVo.java                       # 新增：白名单注解（无参数纯标记，D56）
│   ├── DataPermColumnContext.java            # 新增：ThreadLocal 上下文（resource + ColumnScope）
│   ├── DataPermColumnAdvice.java             # 新增：ResponseBodyAdvice 统一列应用（fail-closed）
│   ├── DataPermColumnContextFilter.java      # 新增：OncePerRequestFilter 双层清理（D54）
│   └── DataPermColumns.java                  # 修改：assertDeclared 增注解必标断言（D56）
└── config/
    └── DataPermColumnAdvisorAutoConfiguration.java  # 新增：SERVLET 条件装配（D51）
```

### 3.1 @DataPermVo（白名单标记，无参数）

```java
/** 类级注解：标记该 VO 是数据权限列载体——advice 树遍历命中即消费（上下文缺失去 3042）。
 *  无参数（拍板④）：资源标识唯一真相源是 provider 注册表 register/registerRemote 行，
 *  注解不做第二声明点；「标注解」义务由 DataPermColumns.assertDeclared 统一断言（D56）。 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataPermVo {
}
```

标注面（本轮仅两处）：`SysLeaveVo`、`ApprovalVo`（列表行与详情嵌套共用主体）。detail 包装类（SysLeaveDetailVo/ApprovalDetailVo）与 ApprovalStepVo **不标**——树遍历下钻可达（D55）。

### 3.2 DataPermColumnContext（ThreadLocal 上下文，set 即接线声明）

```java
/** 列处理上下文（D52）：Service 求值后 set——语义是「本请求列处理已接线」而非数据本身：
 *  空 ColumnScope（无列动作）也必须 set；advice 判定「树中白名单实例存在而上下文空」= 漏接
 *  → fail-closed 3042。生命周期归 DataPermColumnContextFilter（双层清理，D54）。 */
public final class DataPermColumnContext {

    /** 决策快照：resource 供 3042 日志与未来多资源演进排查 */
    public record ColumnDecision(String resource, ColumnScope columnScope) { }

    private static final ThreadLocal<ColumnDecision> HOLDER = new ThreadLocal<>();

    public static void set(String resource, ColumnScope columnScope) {
        HOLDER.set(new ColumnDecision(resource, columnScope));
    }

    public static ColumnDecision get() { return HOLDER.get(); }

    public static void clear() { HOLDER.remove(); }
}
```

### 3.3 DataPermColumnAdvice（统一列应用 + fail-closed）

```java
@RestControllerAdvice
public class DataPermColumnAdvice implements ResponseBodyAdvice<Object> {

    /** 树深度上限：R→detail 包装→列 VO 为 3 层，留裕量取 5（防失控遍历，沿翻译先例） */
    static final int MAX_DEPTH = 5;

    private final Map<Method, Boolean> supportsCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> scanNeedCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> drillNeedCache = new ConcurrentHashMap<>();

    /** supports（沿 TranslateAdvisor L54-65 模式）：泛型链收集类型 → 任一类型可达白名单类才介入；
     *  R<Void>/springdoc 等零成本跳过 */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) { /* 实现 */ }

    /** fail-closed 总纲（与翻译降级总纲方向相反，D53）：树中白名单实例而上下文空、或处理链任何异常
     *  → 一律改写 body 为 R.fail(3042)（不返回数据），log.error 记 VO 类/方法/URI */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        try {
            Object data = body instanceof R<?> r ? r.getData() : body;
            if (applyTree(data, 1, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                return body;                                   // Outcome.OK
            }
        } catch (Throwable t) {
            log.error("数据权限列应用异常（fail-closed 拒绝响应）: uri={}", request.getURI(), t);
            return R.fail(ERR_COLUMN_CONTEXT_MISSING, MSG_COLUMN_CONTEXT_MISSING);
        }
        log.error("数据权限列上下文缺失：白名单 VO 出现但 Service 未接线（fail-closed 拒绝）: uri={}, method={}",
                request.getURI(), returnType.getMethod());
        return R.fail(ERR_COLUMN_CONTEXT_MISSING, MSG_COLUMN_CONTEXT_MISSING);
    }

    /** 树遍历：PageResult.rows/Collection/数组展开；白名单实例 → 消费；非白名单 POJO → 下钻
     *  com.cloudai 字段（D55）；返回 false = 发现上下文缺失（短路停止遍历） */
    private boolean applyTree(Object node, int depth, Set<Object> visited) { /* 实现 */ }

    /** 白名单实例消费：上下文在 → DataPermColumnApplier.apply(vo, columnScope)（复用三层防御）；空 → false */
    private boolean consume(Object vo) { /* 实现 */ }
}
```

树遍历规则（与翻译的差异对照见 D55）：

| 节点形态 | 处置 |
|---|---|
| null / depth > MAX_DEPTH / 已访问（IdentitySet 防环） | 剪枝 |
| `PageResult<?>` | 展开 rows，depth+1 |
| Collection / 数组 | 逐元素，depth+1 |
| 类标 `@DataPermVo` | consume（应用或发现缺失）；**不再下钻其字段**（列 VO 字段是叶子，双层注解 VO 由翻译负责自己的字段） |
| 其余 POJO | `needsDrill(cls)` 为 true 时逐 `com.cloudai.*` 类型字段 get 后下钻，depth+1；JDK 类型/枚举/String 不下钻 |

`needsDrill` 缓存口径：字段类型（含 Collection 泛型实参）中存在 `com.cloudai.` 开头的非注解、非枚举类型 → true。`supportsCache` 沿翻译按 Method 缓存；`scanNeedCache`（泛型链类型是否可达白名单类，递归含字段闭包、visited 防循环类型）。

### 3.4 DataPermColumnContextFilter（双层清理，D54）

```java
/** 上下文生命周期拥有者（D54）：入口防御清（防上一请求任意路径残留=串号护栏）+ 出口必清
 *  （finally 覆盖正常/异常全路径）。advice 在 chain 内消费、filter 在序列化完成后收尾——
 *  上下文存活窗口精确覆盖「Service set → 响应序列化完成」。 */
public class DataPermColumnContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        DataPermColumnContext.clear();
        try {
            filterChain.doFilter(request, response);
        } finally {
            DataPermColumnContext.clear();
        }
    }
}
```

串号场景推演（双层为何必要）：Service set 后、controller 返回前抛异常 → GlobalExceptionHandler 返回 R.fail（handler 方法返回类型非白名单 → advice supports=false 不介入）→ 若无 finally 清，ThreadLocal 残留到线程池下一请求。入口清再兜一层：任何未知残留形态（如 error page 直写、未来非 MVC 出口）在本请求 Service set 之前归零——**双保险缺一不可**（只留 finally：finally 自身异常前丢；只留入口：本请求内残留窗口到序列化后）。

### 3.5 自动装配（独立类，D51）

```java
/** 列 advisor 装配（D51）：SERVLET 条件——非 web 宿主引 api jar 零装配；
 *  @ConditionalOnMissingBean 用户可覆盖。不设功能开关（D57：关闭=fail-open 越权面）。 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DataPermColumnAdvisorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataPermColumnAdvice dataPermColumnAdvice() {
        return new DataPermColumnAdvice();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataPermColumnContextFilter dataPermColumnContextFilter() {
        return new DataPermColumnContextFilter();
    }
}
```

imports 文件 additive 加一行 `com.cloudai.system.api.config.DataPermColumnAdvisorAutoConfiguration`。pom 增量：`spring-webmvc`（provided，ResponseBodyAdvice/@RestControllerAdvice/OncePerRequestFilter 编译所需，宿主 web 服务自带）+ `jakarta.servlet-api`（provided，filter 签名；provided 参与 test classpath，advice 单测的 MockHttpServletRequest 脚手架同源可用——沿 translate-starter 先例）。

### 3.6 assertDeclared 扩展（D56 漂移防护单点）

```java
public static void assertDeclared(Class<?> voClass, List<String> columns) {
    // 新增第一断言（D56）：列载体 VO 必须标注 @DataPermVo——否则 advice 白名单不可达，
    // 列规则永不应用且不报错（静默），启动期显性拦截
    if (!voClass.isAnnotationPresent(DataPermVo.class)) {
        throw new IllegalStateException(String.format(
                "数据权限列载体 VO %s 未标注 @DataPermVo（advice 白名单不可达，D56）",
                voClass.getSimpleName()));
    }
    // 既有两断言（D17/D22）：真实实例字段 + String 类型——零变更
}
```

两路自动全覆盖：provider 本地 `DataPermResources.register` → `assertDeclaredColumns` 委托本方法（SysLeaveVo 未标注解 = system 启动失败）；消费方 `DataPermColumnDeclarationRunner.java:42`（ApprovalVo 未标注解 = bpmn 启动失败）——**runner 与注册行零改动**。反向漂移（标了注解但注册表无此资源）：Service 不接线 → 上下文永不 set → 树中白名单实例触发 3042 显性炸（运行期护栏，见 D52）。

### 3.7 调用点迁移（精确改写，试点双资源同轮）

**LeaveManageService（cloud-system）**：

```java
// pageList（L68-81）：evaluate 后即 set（无条件——空集短路/空 scope 均照 set，set 语义=接线声明）；
// L79 的 rows.forEach(applier) 整行删除
DataPermDecision decision = dataPermEvaluator.evaluate(...);
DataPermColumnContext.set(DataPermResources.LEAVE, decision.getColumnScope());   // 增
RowFilter filter = decision.getRowFilter();
if (filter.isEmptyScope()) { return PageResult.of(0, List.of()); }               // 不变
... mapper + fillListNames ...
return PageResult.of(page.getTotal(), rows);                                      // rows 不再 mutate

// findById（L87-103）：evaluate 后 set；L99 的 applier.apply 整行删除；
// fillDetailLabels 保留（译文回填与列应用无关，advice 在其后序列化时处理列）
```

**ApprovalQueryService（cloud-bpmn）**：

```java
// pageListMy（L96-113）：evalScope 后 set；L104 ColumnScope 局部变量删；L109 apply 删
DataPermScopeVo scopeVo = evalScope(account, DataPermOperation.LIST, null);
DataPermColumnContext.set(RESOURCE_APPROVAL, scopeVo.toColumnScope());           // 增
RowFilter filter = scopeVo.toRowFilter();
...（循环只做 toEnrichedVo + rows.add，不再 apply）

// findById（L119-130）：evalScope 后 set；L124 apply 删；diagram（findDiagram）零改动——
// 其返回树（ApprovalDiagramVo/ApprovalStepVo）无白名单类，不 set 不触发 advice（安全自洽）
```

四个 `DataPermColumnApplier` import 随调用删除同步清理（LeaveManageService/ApprovalQueryService 各一）；applier 类本身保留（advice 的消费实现就是它，三层防御不变）。

## 4. 设计决策（D51-D58，接续 D1-D50）

### D51 组件落位：cloud-system-api dataperm 包（不建独立 starter）

- 消费集合完全相同：使用方恰是「引 cloud-system-api 的数据权限消费方」（system 本地 + bpmn Feign），独立 starter = 多一模块多一份版本管理，消费方一个不多
- 沿 projection 先例（api 模块承载框架组件，2026-10-09 记档）第二次扩展到 web 组件形态；provided 依赖不传染宿主
- **独立自动装配类**（不塞 SystemApiAutoConfiguration）：该类现存降级工厂 bean 无条件装配，混入需 servlet 条件的 advice 会让降级工厂一并被条件化——行为面外溢；独立类 `DataPermColumnAdvisorAutoConfiguration` + imports additive 一行，边界干净
- sso 若未来引 system-api 且为 web 服务：SERVLET 条件满足自动装配 advice——supports 判定零白名单类零介入，幂等无害（记档）

### D52 上下文语义：set 即接线声明（空 scope 也 set）+ fail-closed 判定「树中白名单实例存在而上下文空」

- set 的语义是「本请求的列处理已由 Service 接线」，不是「存在列动作」——若只在有动作时 set，无动作用户的响应同样含白名单实例，advice 无法区分「无动作」与「漏接」，fail-closed 无从谈起
- 漏接的唯一来源 = 新读路径开发者忘了 set（试点内 4 调用点全接线；未来接入模板更新为「标注解 + assertDeclared + 读路径 set」三步，见 §5.3）——fail-closed 把它从静默明文（越权泄露）变成显性 3042 拒绝 + log.error，方向与 D21「数据权限降级不得 fail-open」一致
- 空 ColumnScope（无列动作用户）走正常路径：advice 消费时 applier 短路零处理（DataPermColumnApplier.apply 的 isEmpty 分支），全可见

### D53 拒绝形态：advice 改写 body 为 R.fail(3042)（不抛异常走 GlobalExceptionHandler）

- 抛 BusinessException：ResponseBodyAdvice 阶段抛出的异常进入 DispatcherServlet 异常解析链，行为依赖 Spring 内部处理路径（该阶段响应处理语义与 controller 执行期不同，版本敏感）；改写 body 零依赖、自包含
- 改写形态与项目约定同构：HTTP 恒 200 + body.code 分流（@PreAuthorize 拒绝同款 HTTP 200 + body 403）——3042 走前端拦截器通用 toast 链，无需前端特判
- **与翻译降级总纲的方向性差异明示**：TranslateAdvisor「全程 try/catch 原样返回（翻译绝不拖垮业务响应）」对翻译合法（译文是数据等价物，fail-open 无权限语义）；列 advice 反向——**任何异常（含树遍历反射理论不可达异常）一律 R.fail(3042)**，因为「原样返回」对列处理就是明文泄露。advice 内部唯一合法的「跳过」是 applier 三层防御的脏列 warn（D16 既有，防御的是脏数据不是上下文）
- 副作用记档：3042 改写后 data 丢弃——这正是拒绝语义（不给数据）；留痕行（Service 求值时已落）仍真实记录该用户的列决策，与「响应被拒」无审计漂移（D58）

### D54 上下文生命周期：filter 拥有（入口清 + finally 清），advice 不清

- advice 消费后立即清 vs filter finally 清：选后者——序列化发生在 advice 返回之后（converter 写 body），filter 的 finally 在 `filterChain.doFilter` 返回时执行，恰在序列化完成后；若 advice 清理则窗口语义分裂（谁消费谁清理 vs 谁拥有谁清理）
- 入口防御清是串号护栏的兜底层：Tomcat 线程池复用 + 任何「set 后未走到 finally」的历史残留形态（error page 直写、未来非 MVC 出口）在下一请求 Service set 前归零——**双层清理是本轮安全底线**（拍板②配套）
- OncePerRequestFilter（非 HandlerInterceptor）：不依赖 spring-webmvc 的 InterceptorRegistry 注册，filter bean 即被 Boot 自动注册；api 模块仅需 provided servlet-api

### D55 树遍历策略：非白名单 POJO 全下钻（com.cloudai 剪枝）——与翻译「只下钻标注类」的差异理由

- 翻译只下钻 @TranslateVO 实例、嵌套 detail 走手动回填逃生门（其 javadoc 候选①挂账）；列处理**没有逃生门**——手动 applier 全删（本轮目标），嵌套 SysLeaveDetailVo.leave/ApprovalDetailVo.approval 必须被树遍历到达，否则详情页列规则失效
- 下钻剪枝口径 `com.cloudai.` 包前缀：数据 VO 均为本工程 POJO，JDK/枚举/String 剪枝防失控；MAX_DEPTH=5（R→detail→列 VO=3 层留裕量）+ IdentitySet 防环（沿翻译先例）
- 白名单实例不再下钻其字段：列 VO 的字段是叶子；SysLeaveVo/ApprovalVo 的翻译字段由 TranslateAdvisor 自己负责（双 advisor 并存、各写各的字段：翻译写 labelField、列 advice 写列字段——title/reason 不是 labelField，零交集，顺序无关）
- 性能记档：与翻译同量级（每响应一次树遍历 + FIELD_CACHE 反射 set O(1)）；试点两 VO 双 advisor 各跑一次遍历——读路径大头仍是求值 4-5 次索引查询（D10 记档延续），非热点

### D56 注解无参数 + 漂移防护单点（assertDeclared 扩展）

- 注解带 resource 值 = 第二声明点（注册表行 vs 注解值漂移面）——拍板④明确注解仅白名单标记，**注解无参数**
- 漂移防护矩阵（三层收口）：
  1. 「注册了列资源但 VO 未标注解」→ assertDeclared 首断言启动失败（本地 register / 消费方 runner 两路单点，§3.6）
  2. 「标了注解但注册表无此资源」→ Service 不接线 → 运行期 3042 显性炸（fail-closed 天然反向护栏）
  3. 「声明列 ⊆ VO 字段 + String」→ 既有 D17/D22 断言零变更
- 接入模板 v3（§5.3）同步：「标注解」并入 assertDeclared 义务，开发者无感知单步

### D57 不设功能开关（无 enabled 配置项）

- 翻译/projection 均有 enabled 开关，本轮不设：开关关闭 = advice 失效 = 上下文永不消费 = fail-open 明文返回——**关闭本身就是越权面**，与 D53 fail-closed 总纲矛盾；紧急止损路径应是回滚版本而非线上开关
- 装配条件的唯一「关」是宿主非 SERVLET（技术性，非功能开关）；记档：若未来出现强制关闭诉求（如某环境调试），按回滚处理并复盘接线缺陷

### D58 留痕无漂移论证（D7 语义保持）

- 留痕（sys_data_perm_log insert）仍在 Service 求值时（evaluate/evaluateFor 恒留痕，本轮零改动）；advice 只消费 ColumnScope，无留痕动作
- 分离后的审计一致性：唯一交叉场景 = 留痕记录 `column_summary: reason:脱敏` 而该响应被 3042 拒绝——审计行真实反映「该用户的列决策是什么」，响应被拒是服务端缺陷显性化（已 log.error 定位到方法/URI），**不存在「留痕说脱敏了但响应发了明文」的漂移形态**（那是 fail-open 才有的病，D53 已堵）
- my-scope/explain/配置台与 advice 零交互（它们不返回列载体 VO 或不含列动作展示需求），零波及

## 5. 数据流（双端接线）

### 5.1 provider 侧（cloud-system，leave）

```
前端 → GET /system/leave/page（网关剥前缀）
  → DataPermColumnContextFilter.doFilter：clear() → chain
  → LeaveController.page → LeaveManageService.pageList：
      decision = evaluator.evaluate("leave", "list", null)              // 留痕 1 条（不变）
      DataPermColumnContext.set("leave", decision.getColumnScope())     // 接线声明（空 scope 也 set）
      rowFilter 空集短路 / mapper pageList / fillListNames              // 行级+译文（不变）
      return rows                                                        // 不再 mutate
  → DataPermColumnAdvice.beforeBodyWrite：
      R.data = PageResult → 树遍历 → SysLeaveVo（@DataPermVo）命中
      → 上下文在 → DataPermColumnApplier.apply(vo, columnScope)         // title→null / reason→***
      → 上下文空（漏接）→ R.fail(3042) + log.error                      // fail-closed
  → Jackson 序列化（列值已是 null/**，wire 与旧版逐字节一致）
  → Filter finally：clear()
```

详情（R<SysLeaveDetailVo>）：树下钻 detail 包装 → leave 命中 → 同款应用。3026 拒绝路径：Service set 后抛 3026 → GlobalExceptionHandler 返回 R.fail（handler 返回类型非白名单 → advice 不介入）→ filter finally 清上下文——链路自洽。

### 5.2 消费方侧（cloud-bpmn，bpmn_approval）

```
前端 → GET /bpmn/approval/page
  → Filter：clear → chain
  → ApprovalController.page → ApprovalQueryService.pageListMy：
      scopeVo = evalScope(account, "bpmn_approval", "list", null)        // Feign 求值（不变）
      !r.isSuccess() → fail-closed 拒绝（D21，不变）
      DataPermColumnContext.set("bpmn_approval", scopeVo.toColumnScope()) // 本地 ThreadLocal 接线
      rowFilter 空集短路 / mapper / toEnrichedVo 循环（不再 apply）
  → Advice（api jar 自动装配，bpmn 是 servlet web）：ApprovalVo 命中 → apply
  → Filter finally：clear
```

diagram（findDiagram）：零改动——树（ApprovalDiagramVo）无白名单类，不 set 不触发（§3.7）。

### 5.3 新资源接入模板 v3（取代组件轮 §5.4 步骤②的列应用部分）

1. provider：`DataPermResources` static 块 `register/registerRemote` 一行（不变）；
2. consumer：列载体 VO 标 `@DataPermVo` + 启动期 `DataPermColumns.assertDeclared(XxxVo.class, 同列清单)`（**注解义务已并入断言，漏标启动失败**）；
3. consumer 读路径：求值后 `DataPermColumnContext.set(resource, columnScope)`（空 scope 也 set）——**applier 手写行不再需要**；
4. consumer mapper：RowFilter 参数 + XML 显式块（不变）。

## 6. 错误处理

| 层 | 语义 | 失败形态 |
|---|---|---|
| advice fail-closed（D52/D53） | 白名单 VO 在树中出现而上下文空（Service 漏接线） | R.fail(3042) 改写 body（HTTP 200）+ log.error（URI/方法/VO 类）——数据不返回 |
| advice 处理异常（D53） | 树遍历/反射理论不可达异常 | 同 3042（不吞、不原样返回——fail-open 即泄露） |
| applier 三层防御（D16 既有） | DB 脏列：字段 miss / masked 非 String | warn 去重跳过不炸，其余列照常——唯一合法跳过分支 |
| 上下文残留（D54） | 任意路径 ThreadLocal 未清 | filter 入口 clear 兜底——下一请求 Service set 前归零，无串号窗口 |
| 3042 文案 | 面向用户 | 「数据权限列处理异常，响应已拒绝」（服务端缺陷显性化；排查靠 log.error） |

## 7. 测试策略

### 7.1 api 模块单测（真组件真 ThreadLocal，不 mock 静态）

- **DataPermColumnAdviceTest**（核心矩阵 ≥10 例，MockHttpServletRequest/ServletServerHttpRequest 脚手架沿翻译单测先例）：
  1. 列表形态：R<PageResult<SysLeaveVo>> + ctx(leave, hidden title + masked reason) → title null / reason `***`
  2. 嵌套详情：R<SysLeaveDetailVo> → 下钻 leave 应用（双资源同款 ApprovalDetailVo 一例）
  3. **fail-closed 红态**：白名单 VO 在树中、上下文空 → body 改写 R.fail(3042) 且 data 丢弃
  4. 空 scope 正常态：set 了但无动作 → 原样返回（全可见）
  5. 白名单外树：无 @DataPermVo VO 响应 → 原样零处理零炸
  6. 防环：自引用 VO 不死循环
  7. supports：白名单可达方法 true / 普通 VO false / R<Void> false / springdoc 形态 false
  8. 非白名单字段零污染：树遍历只改列字段，译文 label 字段不受影响（与翻译并存红线）
  9. 空集合：PageResult.rows=[] 白名单不触发 fail-closed（无实例）
  10. body 非 R 形态（防御）：原样处理 data 分支
- **DataPermColumnContextFilterTest**：①请求前残留 → chain 内 get 为 null（入口清）；②chain 内 set → doFilter 返回后 get null（finally 清）——串号护栏双红态
- **DataPermColumnsTest 增量**：未标 @DataPermVo → IllegalStateException（红态）；已标注解 + 列合法 → 通过（绿态回归）
- **DataPermColumnContextTest**：set/get/clear 直测（含 null scope 防御）

### 7.2 服务侧单测适配（行为语义迁移：断言 set 而非断言 mutate）

- **LeaveManageServiceTest**：既有列级用例（「应用列动作」类断言 title/reason 改写值）改为断言 `DataPermColumnContext.get()` 值正确（resource + hidden/masked 集合），@AfterEach clear（防跨用例污染）；行级/空集短路/3026 用例零改动
- **ApprovalQueryServiceTest**：同款三读路径适配（pageListMy/findById set 断言、diagram 零上下文断言）；fail-closed 求值三态用例零改动
- **DataPermColumnApplierTest**：零改动（applier 签名与行为零变更，advice 是它的新调用方）

### 7.3 冒烟（真链路，B4）

起 system+bpmn+网关：配列规则（reason 脱敏/title 隐藏）→ `curl /system/leave/page` 与 `/bpmn/approval/page` 验证 title null/reason `***`（wire 与旧版一致）→ 详情嵌套同验 → **3042 红态**：临时注释 LeaveManageService 一处 set 行重启 → 对应接口 3042 + log.error → 恢复重启复绿（fail-closed 真链路实证，验后必须还原）。

### 7.4 e2e 回归（主控 ③ 阶段全量，FAIL 计数口径——R9 记档 exit 0 假绿）

机制动在返回链路，既有断言（title null/reason `***` 经真实 HTTP）直接复验 advice 正确性：e2e:dataperm（8）+ e2e:dataperm-component（9）+ e2e:dataperm-predicate（11）+ e2e:dataperm-operators（12）+ e2e:bpmn（16）全绿；不新增 e2e 脚本（3042 红态需故意漏接线，单测+冒烟覆盖）。

## 8. 索引审查（规范强制章节）

本轮零 DDL、零新查询：求值/留痕/mapper 查询路径与索引命中零变化（advice 是序列化层组件，不触存储）；无增量 ALTER。

## 9. 红线核对清单（实现与验收共同遵守）

1. wire 零变更：隐藏列仍为「字段置 null」、脱敏仍为 `***`、columnSummary/getSummary 三处冻结点不动——既有 e2e JSON 断言零维护即通过
2. 行级全链零触碰：RowFilter/mapper XML/求值器/留痕/窄契约 Feign/配置台 9 端点/resources 接口/my-scope——文件级零 diff
3. sys_data_perm_column DDL 零变更；错误码仅新增 3042（账本：3038-3041 已被 R9 占用）
4. ArchitectureGuardTest 既有 24 规则零改动、零触发
5. applier（DataPermColumnApplier）行为与签名零变更——它是 advice 的消费实现
6. 4 个调用点的 applier 手写行全删（不留双轨）；diagram 路径零改动
7. 双层清理缺一不可（filter 入口 + finally）；advice 无任何吞异常原样返回分支
8. 契约轻量修订式：既有 data-permission-api 承诺逐条不变

## 10. 错误码账本

system 段本轮占用 **3042**（数据权限列上下文缺失，响应已拒绝）；下轮 3043 接续。bpmn 段仍至 4018。

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **双 advisor 遍历成本**：试点两 VO 上翻译+列 advice 各一次树遍历——量级同翻译实证可承受；若未来树遍历成本显性化（大分页深嵌套），评估「单 advisor 管道化合并」方向（一次性遍历双消费），当前不做记档
2. **needsDrill 的 com.cloudai 剪枝是项目私有约定**：非本包 POJO 嵌套不被下钻——第三方类型嵌套列载体出现时再泛化（列 VO 当前都在本工程包内）
3. **上下文不覆盖非 HTTP JSON 出口**：未来导出（Excel/PDF）等出口若复用 Service 返回的 VO——序列化层应用使其天然已含列处理（VO 对象在 Service 返回时已被 advice 前身 mutate？**否**——注意迁移后 VO 在 Service 返回时是**原始值**，改写只发生在 HTTP JSON 序列化路径的对象上！导出若在 controller 内直接消费 Service 返回的 VO（不经 advice 的另一序列化器），**必须自行接 applier 或复用 advice 消费逻辑**——导出功能立项时必读本条（基座 §1.3 导出移交项的补充约束）
4. **异步/StreamingResponseBody 形态**：advice 语义基于同步 MVC 序列化；引入异步 controller 时 ThreadLocal 传递需重新设计（filter 清理时序随之变）——当前项目无此形态，记档预警
5. **3042 用户文案与告警联动**：3042 出现 = 服务端缺陷，生产应联动告警（R9 移交的告警通道同源诉求）；本轮仅 log.error
6. **多资源同响应**：单请求树中同时出现两个资源的列 VO（如聚合接口）——上下文单值覆盖语义（后 set 覆盖前 set）不满足；当前无此形态，出现时上下文升级为 resource→scope Map，记档
7. **错误码账本**：system 段现用至 3042；下轮 3043 接续并在契约再声明
