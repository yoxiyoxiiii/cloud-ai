# DataPermResources 资源注册表抽象升级技术方案（纯后端重构轮）

- 日期：2026-10-10
- 状态：**定稿**——架构-agent 出品，配合拍板结论（阶段一《需求分析》六问 + 用户拍板三项，2026-10-10）；计划 `docs/superpowers/plans/2026-10-10-dataperm-registry-refactor.md`
- 拍板结论映射：① 方向 **B Class 注册**——`register(resource, VoClass, 列清单)` 一行登记 + 反射通用列应用工具（白名单内 hidden→null / masked→***）+ 启动断言「声明列 ⊆ VO 真实字段」；② **做**「DB 存量规则 ⊆ 注册表」启动一致性检查（log.warn 不留痕不阻断）；③ A（注解驱动）/C（DB 表驱动）/D（纯现状）落选记档（§3.4）
- **与既有设计的关系**：本轮是 `2026-10-10-data-permission-design.md`（D1-D14）的**增补重构轮**——决策编号 **D15-D17 接续**；该文档 §7.3 接入模板第①步「注册表登记」的形态自本文档起升级为「register() 一行登记」（§5），其余两步与全部 D1-D14 决策**零变化**

## 1. 需求与范围

### 1.1 目标

新资源接入时注册表登记从「常量 + Map.entry 散写」收敛为**一行 Class 注册**；列动作应用从「每资源每列手写 setter 分支」收敛为**一个反射通用工具**；并以两层启动期防护（声明断言 + DB 一致性检查）把「清单与 VO 字段断裂」类缺陷（D14 教训）从运行期隐性漂移提前到启动期显性暴露。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| 注册表 Class 化 | DataPermResources 内部 Map 升级为 `Map<String, ResourceDef(voClass, columns)>`，静态一行注册（D15） |
| 反射列应用工具 | `DataPermColumnApplier.apply(vo, columnScope)` 泛化试点手写 applyColumnScope（D16） |
| 启动断言 | 声明列 ⊆ VO 真实字段且为 String 类型，fail-fast 阻断启动（D17） |
| DB 一致性检查 | 启动比对规则/列规则表存量 ⊆ 注册表，越界 log.warn 不阻断（D17） |
| 试点切换 | LeaveManageService 列应用切工具，手写分支与 LEAVE_COLUMN_* 常量退役 |

### 1.3 本轮不落（红线 + 移交）

**零变更红线**：契约 `2026-10-10-data-permission-api.md` 全文零变更（本轮不出新契约，既有契约仍为行为对齐基线）；sys_data_perm_rule/column DDL 零变更；错误码零新增；前端零改动；网关/sso 零触碰。移交项见 §10。

## 2. 现状核实（2026-10-10 逐项复核）

1. **注册表消费面共五处 API**：`isRegistered / assertResource / assertColumn / getConfigurableColumns / listRegisteredResources`——消费于 DataPermEvaluator（L86/L96/L116 三入口校验）、DataPermManageService（L82 筛选宽松语义、L304/L320 save 校验链、L147-150 resources 端点数据源）、LeaveManageService（L63/L86/L88 资源常量引用）。**签名与行为全部保持不变，消费方零改动**（唯一例外 LeaveManageService 列应用段，见 §5.3）
2. **列应用现状**：LeaveManageService.applyColumnScope（L120-134）逐字段 6 行 × 列数模板代码——本轮真实膨胀点（10 资源 × 3 列 ≈ 千行级）；`LEAVE_COLUMN_TITLE/REASON` 常量唯一消费方即此段，随 §4.4 切换退役；`DataPermResources.LEAVE` 保留（读路径求值调用引用，L63/L86/L88）
3. **ColumnScope 已具备公共消费面**：`of()` 工厂、`getHiddenColumns()/getMaskedColumns()`、`mask()`（null→null，非 null→`***`）——工具零改 ColumnScope
4. **可测性现状**：全仓无任何测试直接引用 DataPermResources；DataPermEvaluatorTest/DataPermManageServiceTest/LeaveManageServiceTest 一律 `"leave"` 字面量——静态注册表保持即全绿；LeaveManageServiceTest 列级用例用**真 SysLeaveVo + 真 ColumnScope**（仅 mock evaluator），切换后自动成为反射工具的真实路径验证（D14 教训的非 mock 防线，一行测试不用改）
5. **量级**：已注册资源 1 个（leave）；现实演进 2-5 个（bpmn 跨服务 +1 在移交、sys_user 第二试点拍板明确不做）

## 3. 设计决策（D15-D17，接续 D1-D14）

### 3.1 D15 注册表 Class 化形态：静态注册表（非 Spring bean），register 一行登记

- **形态**：DataPermResources 保持 `final` 静态工具类；内部 `LinkedHashMap<String, ResourceDef>` + static 块逐资源 `register(...)`；`record ResourceDef(Class<?> voClass, List<String> columns)`。新资源接入 = static 块加一行：
  ```java
  register(LEAVE, SysLeaveVo.class, List.of("title", "reason"));
  ```
- **静态而非 bean 的理由**：①五处消费 API 全静态调用，bean 化意味着 Evaluator/ManageService 注入改造（近十处调用点改动，违背「消费方零改动」目标）；②无依赖注入时序问题——注册与断言在类加载期完成，fail-fast 暴露点最早；③与 dataperm 包既有心智模型（DataPermResources/DataPermOperation 均静态）连续。**需要 mapper 的一致性检查器解法**：检查器独立为 `@Component`（bean）注入 mapper、消费静态注册表——装配解耦，注册表不依赖任何 bean
- **依赖方向**：service/dataperm → vo（注册行引 SysLeaveVo.class）——Service 引 VO 为既有合法方向（分层规范），VO 无反向感知
- **列字面量在唯一声明点**：register 行 `List.of("title", "reason")` 直书字面量（「禁散落字符串字面量」约束的语义=声明点之外禁散落；声明点本身即常量定义的等价物）；`LEAVE_COLUMN_*` 两常量随 §5.3 切换退役。资源标识常量 `LEAVE` 保留（多处引用 + 注册行复用）
- **顺序记档**：listRegisteredResources 顺序由 `Map.of`（无定义）变为注册序（LinkedHashMap，确定序）——契约 §3.5 未约定顺序且试点单元素，行为等价
- **防御**：重复 resource 注册 / 空白 resource / 空列清单 → IllegalStateException（register 内前置校验）

### 3.2 D16 反射列应用工具：DataPermColumnApplier 静态工具 + Field 惰性缓存

- **落位与命名**：`service/dataperm/DataPermColumnApplier`（静态工具，无状态纯函数，沿 DataPermResources 形态）；唯一公共方法：
  ```java
  public static void apply(Object vo, ColumnScope columnScope)
  ```
- **不设批量重载**（论证）：`rows.forEach(r -> apply(r, scope))` 一行等价，批量方法是签名膨胀无行为增益；消费方 forEach 为项目既有习惯（LeaveManageService 现状同款）
- **实现口径**：`columnScope == null || isEmpty()` 短路；hidden 集合逐列 `field.set(vo, null)`；masked 集合逐列 `field.set(vo, columnScope.mask((String) field.get(vo)))`——mask 语义复用 ColumnScope.mask，与手写 `setTitle(null)/setTitle(mask(...))` **逐字段等价**
- **反射策略**：`ConcurrentHashMap<Class<?>, Map<String, Field>>` 惰性缓存（首次触达该类时 getDeclaredFields 一次展开，排除 static 修饰符字段，setAccessible(true)）；VO 均为本工程无继承 POJO（Lombok @Data），getDeclaredFields 覆盖完备。**性能**：缓存后 Field 查找 O(1)，分页 ≤200 行 × ≤几列的 set 开销可忽略——求值本身每请求 4-5 次索引查询才是读路径大头（D10），反射非热点；不做启动预热
- **双防御（读路径永不因列应用炸，D7 口径延伸）**：①字段不存在（DB 脏列指向 VO 无此字段）→ `log.warn` 一次（`missingLogged` 集合按 class#field 去重，防大分页刷屏）后跳过；②masked 列字段类型非 String → 同款 warn 跳过（防脏列指向 LocalDateTime 等字段时 `***` 赋值 IllegalArgumentException）。两防御均为纵深防御第三层——第一层 D15 注册断言拦声明错、第二层 3034 校验链拦配置错，运行期残余只有 DB 脏数据一种来源
- **IllegalAccessException 终态**：setAccessible 已做，理论不可达；仍 catch → log.error 跳过（不炸读路径）

### 3.3 D17 启动断言与 DB 一致性检查：fail-fast + warn 两层分离

- **声明断言（fail-fast，定夺采纳主控倾向）**：register() 内即时断言两条——①每个声明列必须是 voClass 的**真实实例字段**；②字段类型必须 `String.class`（masked 整值替换 `***` 的语义前提，D11）。violation → IllegalStateException（消息含 resource/列名/VO 类名）→ 静态初始化失败 → **服务启动失败**。fail-fast 论证：错误声明是**代码 bug 非运行时数据**，warn 放行只会把暴露点推迟到运行期且形态更隐蔽（3034 拦截正常配置 / 工具 warn 静默跳过 / 规则配了不生效）；启动期异常栈是最短排障路径；与 D7「可观察组件不炸读路径」不冲突——炸在启动期，读路径零波及
- **断言载体（可测试性）**：断言体独立为**包可见**静态方法 `assertDeclaredColumns(String resource, Class<?> voClass, List<String> columns)`（register 内部调用）——单测红态直调该方法（喂不存在列 / 非 String 字段），不触 REGISTRY、无需 reset 钩子。**记档取舍**：重复注册/空白 resource 分支在静态初始化后不可复现（不引入注册表 reset 钩子——生产纯度优先于该低价值分支的可测性），由代码评审覆盖
- **非 String 列约束记档**：hidden 对非 String 列理论可行（置 null 无类型问题），但 masked 不行——MVP 统一「可配列必须 String」简化为单条断言；非 String 可配列需求（如金额类脱敏）出现时随脱敏策略多样化（原 §11.5）一并演进
- **DB 一致性检查（warn 不阻断，用户拍板）**：`@Component DataPermRegistryConsistencyChecker implements ApplicationRunner`（上下文就绪后执行，晚于全部 bean 初始化；run 整体 try-catch，检查自身失败 log.error 不影响启动）。检查两路：
  1. `sys_data_perm_rule` 全表 `DISTINCT resource` ⊆ 注册表——越界=未注册资源（不应存在：写入经 3034；出现即历史脏数据或注册表收缩遗留）
  2. `sys_data_perm_column` 全表 `DISTINCT (resource, column_key)` ⊆ 对应资源可配列清单——越界=**孤儿列规则**（VO 字段改名 / 列清单收缩后的存量，规则永不命中且求值时被工具静默跳过，正是最需要人工排查的形态）
- **越界处置语义**：逐条 `log.warn`（含越界键与「疑似孤儿规则，请人工核对处置」提示）+ 一条汇总；不删数据不阻断启动不留痕（检查器是开发期观测件非审计件）。**载体对比记档**：@PostConstruct（bean 初始化期不宜触 DB）弃；守护测试（编译期可见但测不到运行环境 DB 存量）只能作补充不担主责；ApplicationRunner 就绪后执行为正解
- **可测试性**：`collectViolations()` 包可见返回 `List<String>`（越界描述清单）——单测断言清单内容，不依赖日志捕获；runner 只做 warn 编排

### 3.4 落选方向记档（不回潮条件）

| 方向 | 落选主因 | 回潮条件（均不预见） |
|---|---|---|
| A 注解驱动（@DataPermResource/@DataPermColumn + 扫描） | bpmn VO 在 cloud-bpmn 扫不到必留双轨；字段改名致 DB 孤儿化无编译期拦；「零注解」口径不重述（拍板） | 无 |
| C DB 表驱动目录 | 鸡生蛋（零规则资源不可见/末条规则删除即消失）；目录行无执行代码=可通过校验却永不执行的静默谎言 | 无 |
| D 纯现状仅抽工具 | 列清单字符串与 VO 字段漂移无启动期防线（D14 温床原样保留） | 无 |

## 4. 组件设计（关键示意，每段 ≤30 行）

### 4.1 DataPermResources（重写，公共面冻结）

```java
public final class DataPermResources {
    /** 请假资源（试点）：可配列 title（隐藏示例）/ reason（脱敏示例） */
    public static final String LEAVE = "leave";

    /** 无效的资源或列标识（契约 §8 错误码 3034） */
    public static final int ERR_INVALID_RESOURCE = 3034;

    private static final Map<String, ResourceDef> REGISTRY = new LinkedHashMap<>();

    static {
        register(LEAVE, SysLeaveVo.class, List.of("title", "reason"));
    }

    /** 资源定义：列清单 VO 类（D15）——断言锚点；列 key 语义 = VO 字段名（D11） */
    record ResourceDef(Class<?> voClass, List<String> columns) { }

    private static void register(String resource, Class<?> voClass, List<String> columns) {
        // 空白 resource / 空列清单 / 重复注册 → IllegalStateException；随后 D17 声明断言
    }

    // isRegistered / assertResource / assertColumn / getConfigurableColumns /
    // listRegisteredResources —— 签名与行为逐条不变（内部改读 REGISTRY）
    // assertDeclaredColumns(resource, voClass, columns) —— 包可见，D17 断言体（测试直调）
}
```

（LEAVE_COLUMN_TITLE/LEAVE_COLUMN_REASON 两常量在 §4.4 切换任务中删除——见计划任务时序。）

### 4.2 DataPermColumnApplier（新增）

```java
public final class DataPermColumnApplier {

    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_WARNED = ConcurrentHashMap.newKeySet();

    /** 列动作应用（D16）：hidden→字段置 null；masked→整值替换 ***（ColumnScope.mask 口径） */
    public static void apply(Object vo, ColumnScope columnScope) {
        if (vo == null || columnScope == null || columnScope.isEmpty()) {
            return;
        }
        for (String key : columnScope.getHiddenColumns()) {
            setField(vo, key, null);
        }
        for (String key : columnScope.getMaskedColumns()) {
            applyMask(vo, key, columnScope);
        }
    }
    // setField/applyMask：resolve（缓存/miss warn 去重/类型防御）→ set；IllegalAccessException log.error 跳过
}
```

### 4.3 DataPermRegistryConsistencyChecker（新增）+ mapper 增量

```java
@Component
@RequiredArgsConstructor
public class DataPermRegistryConsistencyChecker implements ApplicationRunner {

    private final DataPermRuleMapper ruleMapper;
    private final DataPermColumnMapper columnMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> violations = collectViolations();
            // violations 逐条 log.warn + 空清单零输出；疑似孤儿规则提示人工核对（D17）
        } catch (Exception e) {
            log.error("数据权限注册表一致性检查执行失败（不影响启动）", e);
        }
    }

    /** 包可见可测：DB 存量 ⊆ 注册表 的越界描述清单 */
    List<String> collectViolations() { /* rule 表 DISTINCT resource ⊆ 注册表；
                                         column 表 DISTINCT (resource, column_key) ⊆ 可配列 */ }
}
```

mapper 增量（两接口各一方法，手写 XML 追加，物理删表无 deleted 条件——D9）：

- `DataPermRuleMapper.listDistinctResources()` → `SELECT DISTINCT resource FROM sys_data_perm_rule`
- `DataPermColumnMapper.listDistinctResourceColumns()` → `SELECT DISTINCT resource, column_key FROM sys_data_perm_column`（resultType 实体，仅两列有值，检查器读 getResource()/getColumnKey()）

### 4.4 LeaveManageService 切换（唯一行为改动点，逐字段等价）

```java
// 列表路径（原 L72）：
rows.forEach(row -> DataPermColumnApplier.apply(row, decision.getColumnScope()));
// 详情路径（原 L92）：
DataPermColumnApplier.apply(vo, decision.getColumnScope());
// applyColumnScope 私有方法（原 L120-134）整体删除；import 增 DataPermColumnApplier
```

## 5. 新资源接入模板（取代原设计 §7.3 第①步形态）

1. `DataPermResources` static 块加一行 `register("xxx", XxxVo.class, List.of(...可配列))`——**声明即受 D17 双断言防护**（列不存在/非 String 启动即炸）；
2. 读路径 Service 显式调 `evaluate`（资源常量随注册定义）+ 空集短路 + 详情 allows 判定 + `DataPermColumnApplier.apply`（**列应用零手写**）；
3. mapper 增 scope 参数、XML 加显式 `<if>` 块（不变）。

接入成本对比：原「常量 2-3 个 + Map.entry + 每列 6 行应用分支」→ 现「注册 1 行 + 应用 1 行」；步骤 ②③的求值与 SQL 显式代码不变（D5 显式哲学不受影响——无注解无拦截器无 SQL 改写，本轮零新增机制违背）。

## 6. 错误处理汇总

| 层 | 语义 | 失败形态 |
|---|---|---|
| D17 声明断言 | 声明列非 VO 字段 / 非 String | IllegalStateException → 静态初始化失败 → **启动失败**（fail-fast，代码 bug 零容忍） |
| 3034 校验链（既有） | 配置态写入非法 resource/columnKey | BusinessException，行为不变 |
| D16 工具防御 | DB 脏列：字段不存在 / masked 指非 String 字段 | log.warn（去重）跳过，**读路径不炸**，其余列照常应用 |
| D17 一致性检查 | DB 存量 ⊄ 注册表（孤儿规则） | 启动 log.warn 逐条 + 汇总，不阻断不留痕 |
| D17 检查器自身 | 查询异常（理论不可达） | log.error 不影响启动 |

## 7. 测试策略

- **DataPermResourcesTest（新增，红绿两态）**：绿——类加载后 `leave` 已注册、可配列 = [title, reason]、isRegistered/assertColumn 行为；红——`assertDeclaredColumns` 喂不存在列（如 "remark"）→ IllegalStateException；红——喂非 String 字段（如 "createTime"）→ IllegalStateException
- **DataPermColumnApplierTest（新增，真类不 mock——D14 教训）**：真 SysLeaveVo + 真 ColumnScope——hidden→null；masked→`***`；null 值 masked 保持 null；empty scope 零修改；脏列 key（非 VO 字段）跳过不炸且其余列正常；masked 脏列指向非 String 字段（"createTime"）跳过不炸；hidden+masked 混合并存
- **LeaveManageServiceTest（既有零修改）**：列级用例（findById_appliesColumnScopeOnVisibleRow 等）经服务层走**真反射工具**（evaluator 仍 mock）——既全绿即行为等价证明；任何用例需要修改即视为行为漂移，回报主控
- **DataPermRegistryConsistencyCheckerTest（新增，mock mapper）**：DB 空表 → 空清单；全在注册表 → 空清单；rule 表越界 resource → 清单含「资源未注册」条目；column 表越界 (leave,"status") → 清单含「列不在可配清单」条目
- **全量回归**：`mvn clean install` 全绿（既有 461 单测零回归 + 本轮新增）；`cd cloud-e2e && npm run e2e:dataperm` 回归（列脱敏/隐藏经真实 HTTP 的行为漂移防线）
- **启动冒烟（B5）**：9202 起服观察（a）断言通过无异常（b）干净库无 warn；红态验证——java 单文件源码插一条脏列规则（leave + 不存在列）重启 → 启动日志出现对应 warn（不阻断）→ 清理脏数据复原

## 8. 索引审查（规范强制章节）

本轮零 DDL。新增两查询均为**启动一次性全扫**配置态小表（行数 = 主体 × 资源，内部系统百行级），无高频 WHERE 路径——**不建新索引**（记档）；`DISTINCT resource` 理论可借 uk_resource_subject 最左前缀，MySQL 不保证 loose index scan，启动全扫量级可接受。求值/配置路径查询与索引命中零变化（§12 总表沿用原设计）。

## 9. 红线核对清单（实现与验收共同遵守）

1. 契约 `2026-10-10-data-permission-api.md` 零变更——§3.5 出参 `{resource, columns[]}` 形状、§8 错误码、§6.8 scopeLabel、§3.3 校验链顺序全冻结
2. 五处消费 API（isRegistered/assertResource/assertColumn/getConfigurableColumns/listRegisteredResources）签名与行为等价——Evaluator/ManageService 零改动
3. 试点列级行为逐字段等价：hidden→null / masked→`***` / null 保持 null / 空动作零处理
4. sys_data_perm_rule/column DDL 与 D9 物理删语义零触碰；错误码零新增（账本仍至 3034）
5. 既有 461 单测零回归；e2e:dataperm 回归通过

## 10. 已知取舍与移交备忘

1. **bpmn 跨服务接入时**：register 的 voClass 参数要求列清单 VO 类对 cloud-system 可见——bpmn 场景其 VO 在 cloud-bpmn，届时列清单类应经 cloud-bpmn-api 契约模型承载，或与 D6 触发条件（求值器抽 cloud-system-api + Feign）**同轮演进**（注册表随组件一起搬家，机制不变）——本轮机制不阻碍、类可见性是模块依赖问题，记档
2. **非 String 可配列**：D17 String 断言统一约束；需求出现（金额/日期类脱敏）时随脱敏策略多样化（原 §11.5）一并设计
3. **资源中文名展示**：resources 端点加 label 字段属契约变更——需求出现另轮（可搭 C 方向残值「目录元数据」的便车，但目录真相源仍在代码）
4. **一致性检查频次**：仅启动时执行；若需周期对账（规则页展示孤儿标识）属配置台增强，需求出现另轮
5. **错误码账本**：零新增，仍至 3034；下轮从 3035 接续
