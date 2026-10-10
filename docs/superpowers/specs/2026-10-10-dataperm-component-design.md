# 数据权限组件化（跨服务求值）技术方案（全栈 A 级：契约模型抽 cloud-system-api + 窄契约远程求值 + bpmn_approval 首个跨服务试点）

- 日期：2026-10-10
- 状态：**定稿**——架构-agent 出品，配合拍板结论（阶段一《需求分析》八问 + 用户拍板三项 + 主控采纳倾向，2026-10-10）；契约 `docs/superpowers/contracts/2026-10-10-dataperm-component-api.md`、计划 `docs/superpowers/plans/2026-10-10-dataperm-component.md`
- 拍板结论映射：① 求值形态 **A 窄契约远程求值**（每读路径一次 evaluate 内网 Feign，实时零失效窗口）；② 降级语义 **fail-closed**（Feign 失败/熔断打开期 bpmn 列表/详情拒绝，不降级为无过滤/空集冒充正常）；③ 试点范围 **全做**（行级 pageList 显式化 + 详情 IDOR 4018 + diagram 同族判定 + title 列级 + 前端提示条 + e2e 三账号矩阵）。主控采纳：落位 cloud-system-api、身份显式传 account、注册表双层、deny 远程化、leave 本地调用保持、my-scope 零新契约
- **与既有设计的关系**：本文档是 `2026-10-10-data-permission-design.md`（D1-D14）与 `2026-10-10-dataperm-registry-refactor-design.md`（D15-D17）的**组件化演进轮**——D1-D17 全部有效，决策编号 **D18-D28 接续**；原 D6 记档的触发条件（第二服务接入）本轮兑现

## 1. 需求与范围

### 1.1 目标

数据权限实现逻辑封装为组件供其他服务直接使用：契约模型与消费侧辅助搬家至 cloud-system-api（引 jar 即得），cloud-system 提供服务间求值端点（/inner），并以 **bpmn_approval 为首个跨服务接入试点**打通全链——影子过滤最后一处显式化、详情/diagram IDOR 收口、title 列级、前端提示条。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| 组件搬家 | DataScope/ColumnScope/DataPermOperation/DataPermColumnApplier 自 cloud-system `service/dataperm/` 抽至 cloud-system-api `api/dataperm/`（行为零变更） |
| 服务间契约 | DataPermClient（evaluate + deny 两方法）+ fallbackFactory + 三契约模型（EvaluateRequest/DenyRequest/ScopeVo）|
| system 侧 | InnerDataPermController（/inner/data-perm 两端点）、Evaluator 账号显式入口、DataPermResources.registerRemote、admin 种子 |
| bpmn 试点 | approval pageList 行级求值、detail/diagram IDOR 收口（4018）、title 列级、deny 远程补痕 |
| 前端 | 审批列表页 my-scope 提示条 + title 空/`***` 自然展示 |
| e2e | 新脚本三账号矩阵（既有 e2e:dataperm / e2e:bpmn 零回归红线） |

### 1.3 本轮不落（红线 + 移交）

**零变更红线**：数据权限配置台 9 端点与前端三 tab（含 resources 端点形状——registerRemote 资源自然出现在下拉，机制零改）；DDL 零新增（bpmn_approval 表已有 `idx_apply_user(apply_user, deleted)`）；网关/sso 零改动（/system/inner/** 已双处屏蔽）；leave 试点行为零变化（本地调用保持，仅类型 import 切换）；规则缓存与失效（D6 字面「本地缓存」继续留移交，见 D18 采纳口径）。移交项见 §11。

## 2. 现状核实（2026-10-10 逐项复核）

1. **消费侧基建已齐**：cloud-bpmn 已依赖 cloud-system-api（pom 87 行）+ resilience4j，yml 已开 circuitbreaker（connect 1s/read 5s、disable-time-limiter），`@EnableFeignClients(clients = {SystemUserClient.class})`（BpmnApplication.java:11）——bpmn 接 DataPermClient 仅加一个 import 与注入
2. **api 模块先例**：client/fallback/domain + SystemApiAutoConfiguration 注册降级 bean；projection 包先例已记档「api 模块承载框架组件」——本轮 dataperm 包沿此先例
3. **网关已屏蔽**：/system/inner/** 在 gateway application.yml:22-26（路由级 403）+ AuthGlobalFilter.java:45（白名单前缀）双处——新 inner 端点零网关改动
4. **bpmn 影子过滤与 IDOR 现状**：`BpmnApprovalMapper.xml:33` 硬编码 `WHERE apply_user = #{applyUser}`（ApprovalController.page 显式传 currentAccount）；`ApprovalQueryService.findById`（L79-86）与 `findDiagram`（L90-97）**均无归属校验**（比收口前的 leave 更宽的 IDOR 面）；cancel 有本人校验（4012，不动）
5. **身份先例**：既有 /inner 契约全部**显式传 account**（`getUserByAccount(@PathVariable account)`，InnerUserController.java:29），无 Feign header 透传拦截器；资源端 security 链 anyRequest().permitAll + 网关屏蔽 = 网格内信任模型
6. **搬家成本核查**：DataScope（47 行不可变 POJO）/DataPermOperation（常量）/DataPermColumnApplier（115 行静态工具，仅依赖 ColumnScope+反射）零框架依赖；**ColumnScope.getSummary 引 SysDataPermColumn.ActionEnum（实体）——搬家伴生解耦见 D28**
7. **错误码账本**：system 段已用至 3034；bpmn 段已用至 4017（4018 起接续，本轮占用 4018）
8. **列级候选**：ApprovalVo 的 String 列中 `title`（标题快照）唯一自然候选（L36）；businessKey/detailPath 属导航字段，approver 是译文依赖列，均不适合
9. **cloud-system 对 system-api 的依赖现状**：仅经 translate-starter 传递（pom 无显式声明）——本轮 system 代码直接 import api 类，**须显式声明依赖**（计划 B2）
10. **前端范式就绪**：leave 页 my-scope 提示条（index.vue L136-145/L223-228）可整段复用到审批页；title 后端置 null/`***` 前端原样展示零改造

## 3. 组件设计（落位与形态）

### 3.1 cloud-system-api 增量结构（唯一新代码面）

```
cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/
├── client/DataPermClient.java                  # @FeignClient(cloud-system, contextId=dataPermClient, path=/inner/data-perm)
├── fallback/DataPermClientFallbackFactory.java # fail-closed 降级（D21）
├── domain/DataPermEvaluateRequest.java         # {account, resource, operation, businessKey?}
├── domain/DataPermDenyRequest.java             # {account, resource, businessKey}
├── domain/DataPermScopeVo.java                 # 窄契约出参四字段 + toDataScope()/toColumnScope()（D19）
└── dataperm/                                    # 消费方框架组件包（沿 projection 先例记档）
    ├── DataScope.java                           # 自 system service/dataperm 搬家，行为零变更
    ├── ColumnScope.java                         # 搬家 + D28 label 解耦
    ├── DataPermOperation.java                   # 搬家（LIST/DETAIL/DENY 常量）
    ├── DataPermColumnApplier.java               # 搬家（反射列应用，D16 机制不变）
    └── DataPermColumns.java                     # 消费方列声明断言辅助（D22 第三层）
```

SystemApiAutoConfiguration additive 增 `DataPermClientFallbackFactory` @Bean（circuitbreaker 关时 bean 空闲无害，沿既有注释口径）。**不建独立 dataperm-api/starter 模块**：提供方是 system，沿「跨服务调用走提供方 -api 模块」规范。

### 3.2 窄契约出参 DataPermScopeVo（D19）

```java
/** 服务间求值窄契约出参（D19）：仅承载消费方读路径所需四要素；
 *  toDataScope()/toColumnScope() 在 api 模块内完成契约模型→终态对象转换（消费方零胶水）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPermScopeVo implements Serializable {
    private boolean rowAll;                 // true=行过滤豁免
    private List<String> accounts = new ArrayList<>();      // rowAll=false 时白名单（空=空集短路）
    private List<String> hiddenColumns = new ArrayList<>(); // 列隐藏（VO 字段置 null）
    private List<String> maskedColumns = new ArrayList<>(); // 列脱敏（整值 ***）
    public DataScope toDataScope() { /* rowAll ? DataScope.all() : DataScope.of(Set.copyOf(accounts)) */ }
    public ColumnScope toColumnScope() { /* ColumnScope.of(Set.copyOf(hidden), Set.copyOf(masked)) */ }
}
```

**Decision/HitRule/narratives 不出 system**（D19）：命中规则明细、中文解释句是求值内部产物，消费方读路径不需要；explain/my-scope 是 system 自有端点已覆盖。契约面最小 = 演进最稳。

### 3.3 DataPermClient（两端点）

```java
@FeignClient(name = "cloud-system", contextId = "dataPermClient", path = "/inner/data-perm",
        fallbackFactory = DataPermClientFallbackFactory.class)
public interface DataPermClient {
    /** 真实决策求值（provider 恒留痕 D7）：account 显式传入（D20） */
    @PostMapping("/evaluate")
    R<DataPermScopeVo> evaluate(@RequestBody DataPermEvaluateRequest request);
    /** deny 安全审计补痕（D13/D23）：详情被行级拒绝后消费方调用 */
    @PostMapping("/deny")
    R<Void> deny(@RequestBody DataPermDenyRequest request);
}
```

POST 语义依据：evaluate 有留痕副作用（每次真实决策落 sys_data_perm_log 一条），非幂等资源读——POST 正确；沿「入参必须是对象（DTO）」规范用 @RequestBody。

### 3.4 fallbackFactory（fail-closed，D21）

```java
public DataPermClient create(Throwable cause) {
    return new DataPermClient() {
        public R<DataPermScopeVo> evaluate(DataPermEvaluateRequest req) {
            log.error("数据权限求值降级（fail-closed，拒绝访问）: account={}, resource={}", req.getAccount(), req.getResource(), cause);
            return R.fail(1002, "数据权限服务不可用，请稍后重试");
        }
        public R<Void> deny(DataPermDenyRequest req) {
            log.error("数据权限 deny 补痕降级（审计丢失风险记日志）: {}", req, cause);
            return R.fail(1002, "数据权限服务不可用");
        }
    };
}
```

消费方统一处置（沿 CLAUDE.md Feign 规范「降级 R 走 code!=SUCCESS 分支转译域码」）：

```java
R<DataPermScopeVo> r = dataPermClient.evaluate(request);
if (!r.isSuccess()) {
    throw new BusinessException(r.getMsg());   // 1002 + 拦截器 toast——fail-closed：不返数据
}
DataScope scope = r.getData().toDataScope();
```

## 4. 设计决策（D18-D28，接续 D1-D17）

### D18 组件落位 cloud-system-api + D6「本地缓存」采纳口径窄化

D6 原文：「第二个服务接入时，将 DataScope/ColumnScope/契约模型抽 cloud-system-api 模块 + **求值走 Feign + 本地缓存（版本失效）**」。本轮兑现前半，**缓存部分按 D10 精神继续移交**（用户拍板①）：失效缓存是数据权限最经典事故源，窄契约远程求值天然零失效窗口（每请求实时求值，规则/部门/挂载变更即时生效语义与 leave 试点完全一致）；本地求值除规则外还需角色绑定/部门树/用户挂载（全在 system 库）——宽契约等于复制半个 RBAC 读模型 + 失效广播，复杂度与事故面双高。缓存诉求（量大后）沿原 §11.4 移交账本续期。**与 D6 字面的出入以此口径明示记档**。

### D19 契约面最小化：四字段 ScopeVo，Decision 不出 system

见 §3.2。窄契约的直接收益：api 模块契约模型全部 Jackson 友好（@Data 简单 POJO，沿 LoginUserDTO 先例），**不需要把不可变的 DataScope/ColumnScope 暴露给 Feign 序列化**（二者无私有构造外的 Jackson 反序列化路径）；DataPermDecision/HitRule/narratives 留 system 内部，未来 explain 形态演进不牵动跨服务契约。

### D20 身份显式传 account（三重一致 + 信任模型记档）

evaluate/deny 入参显式携带 account，消费方从自己 SecurityContext 取（bpmn 读路径本就持有 `SecurityUtils.currentAccount()`）。依据：① /inner 先例（getUserByAccount 显式传账号）；② D5 显式哲学（调用点看得见「以谁的身份求值」——签名即文档）；③ D8「权限数据不进 header」同类论证（隐式 header 透传是隐式契约，且无先例拦截器）。provider 侧 account→SysUser 解析复用 explain 同款路径（findByAccount→userId）；account 无效时不报错、按其规则空集收敛（doEvaluate(account, null) → 无规则默认 SELF={account}，方向安全不越权）。**信任模型记档**：inner 端点网关屏蔽 + 网格内信任，与 getUserByAccount（暴露密码散列，敏感度更高）同信任级——「调用方可代表任意账号求值」的风险由网格边界承担，同既有 inner 口径。

### D21 降级语义 fail-closed（与翻译 fail-open 的差异理由）

翻译远程降级 fail-open（昵称降级为账号）合法，因为降级值是**数据等价物**、无权限语义；数据权限降级若 fail-open（无过滤放行）= **越权泄露**，方向性错误不可接受（用户拍板②）。fail-closed 落地形态选**拒绝报错**而非静默空集：空集会伪装成「没有数据」误导用户与排查（my-scope 提示条也会渲染错误范围），报错经拦截器 toast「数据权限服务不可用」是诚实信号；熔断打开期（半开恢复前）持续拒绝属预期（CLAUDE.md 既有口径）。deny 补痕降级仅 log.error（审计行丢失记日志，不阻断 3026/4018 已定的拒绝语义）。

### D22 注册表双层：registerRemote 字符串注册 + 消费方列断言下沉

- **provider 侧（真相源，不变）**：DataPermResources 保持 static final + LinkedHashMap（D15 形态零变化）；static 块 additive 增一行 `registerRemote(BPMN_APPROVAL, List.of("title"))`——registerRemote(resource, columns) 纯字符串形态（无 voClass，跨服务资源类不可见，§10.1 记档兑现）；ResourceDef.voClass 对远程资源为 null，**D17 类断言仅对本地 register 生效**（记档：远程资源的「声明列 ⊆ VO 字段」断言下沉消费方）。3034 校验链/配置台下拉/my-scope/DB 一致性检查对远程资源全部自然工作（消费的只是字符串清单）
- **consumer 侧（第三层防线）**：api 包 `DataPermColumns.assertDeclared(Class<?> voClass, List<String> columns)`——消费方启动期一行声明 `DataPermColumns.assertDeclared(ApprovalVo.class, List.of("title"))`（bpmn 落 ApplicationRunner 或 @PostConstruct 组件），violation → IllegalStateException 启动失败。实现直接复用 D17 断言语义（真实实例字段 + String 类型），代码从 DataPermResources.assertDeclaredColumns 提为 api 包公共静态（DataPermResources 内部改调它——单一实现两处消费）
- **双层漂移防护记档**：远程资源的列清单在 provider（registerRemote 行）与 consumer（assertDeclared 行）各出现一次——两行各有防线（provider 侧 3034+一致性检查拦配置错；consumer 侧启动断言拦 VO 漂移），两行互相漂移由 consumer 断言兜底（provider 清单 ⊋ consumer 声明时，多余列配了规则但 applier 跳过 warn——三层纵深第三层已防）；运行期注册（消费方推送）方向因启动顺序与状态同步问题落选记档

### D23 deny 远程化 + Evaluator 账号显式化（additive 重构）

- Evaluator 增 `evaluateFor(String account, String resource, String operation, String businessKey)`：account 显式入参（findByAccount 解 userId）→ doEvaluate → persistLog——inner evaluate 端点薄壳；既有 `evaluate(resource, operation, businessKey)`（SecurityUtils 取上下文）保留，leave 路径零改动
- `logDeny(String resource, String businessKey)` 签名扩为 `logDeny(String account, String resource, String businessKey)`：账号显式化（D5 一致性），leave 调用点同步传 `SecurityUtils.currentAccount()`（行为等价）；inner deny 端点薄壳直调
- **留痕集中语义**：远程求值/deny 的留痕全部落 system 库 sys_data_perm_log（与 leave 同表同页）——跨服务决策史单点可查，D7 语义不变；bpmn 侧不落任何留痕

### D24 详情/diagram IDOR 收口：4018（bpmn 段接续）

- `GET /bpmn/approval/{id}` 详情与 `GET /bpmn/approval/{id}/diagram` 图数据同为行级读路径，**一并判定**（拍板③）：读出行 → evaluate(DETAIL, businessKey=id) → `scope.allows(applyUser)` 不通过 → deny 远程补痕 → **4018「无权访问该审批单」**（D13 同语义：明确拒绝不伪装 404，错误码归消费方域——4010 仍留真不存在）
- 每次 evaluate 均留痕（detail 与 diagram 各调一次 = 详情弹窗开图时两条 detail 留痕，各自是独立真实决策，记档接受）；diagram 复用 OP_DETAIL 语义（同一单据同一读操作面）

### D25 列级试点：bpmn_approval/title 单列

资源 `bpmn_approval` 可配列 = `["title"]`（标题快照，String，唯一自然候选）。列表与详情共用同一列规则（一致治理 D11 延续）；应用点 = toEnrichedVo 之后 `DataPermColumnApplier.apply(vo, columnScope)`（消费方引 jar 即得，零手写）。远程列路径打通 = 组件列能力跨服务验证（title null/`***` 经 Feign 决策 + 本地反射应用）。

### D26 leave 读路径保持本地调用（防提供方自我 Feign）

cloud-system 是求值器宿主——leave 试点继续本地注入 DataPermEvaluator（零网络、零 Feign 自环）；仅类型 import 随搬家切换（DataScope/ColumnScope/Applier/Operation 改引 `com.cloudai.system.api.dataperm`）。同 projection「提供方不自我消费」先例（enabled 默认关防自我消费）。**sys_leave 行为零变化 = 既有 e2e:dataperm 零回归红线的机制保障**。

### D27 admin 种子：bpmn_approval/ALL（沿 leave 先例）

`INSERT INTO sys_data_perm_rule (resource, subject_type, subject_id, row_scope, ...) VALUES ('bpmn_approval', 0, 1, 4, ...)`——admin 角色预配全部档（D3「admin 靠种子规则，代码无特例」跨资源一致）。**行为变更记档**：admin 审批列表由「仅自己发起」→「全部」（与 leave 轮 admin 语义变更同款）；无规则普通用户行为不变（默认 SELF=仅自己，与现状恒按申请人逐字等价——向后兼容）。增量脚本 + 基线 cloud_system.sql 同步；无菜单/perms 种子（复用 bpmn:approval:list 既有）。

### D28 ColumnScope label 解耦（搬家伴生）

ColumnScope.getSummary 现引 `SysDataPermColumn.ActionEnum.label()`（system 实体）——搬家后将「隐藏/脱敏」标签内联为 ColumnScope 私有常量（`LABEL_HIDDEN/LABEL_MASKED`），输出格式逐字不变（既有单测/e2e 断言零回归）；标签字面量三处记档（实体枚举 label、ColumnScope 常量、前端 ACTION 常量文案）——语义冻结点为契约 §6.8 columnSummary 格式，任一处变更为契约变更。

## 5. 数据流（bpmn_approval 全链）

### 5.1 列表

```
前端审批页 → GET /bpmn/approval/page
  → ApprovalController.page（@PreAuthorize bpmn:approval:list 不变；account = SecurityUtils.currentAccount() 显式传）
  → ApprovalQueryService.pageListMy(query, account)：
      r = dataPermClient.evaluate({account, "bpmn_approval", "list", null})   // 1 次 Feign，provider 留痕 1 条
      !r.isSuccess() → throw BusinessException(msg)                          // fail-closed（D21）
      scope = r.getData().toDataScope()；scope.isEmptyScope() → 空页短路（不查库）
      page = approvalMapper.pageList(page, scope)                             // XML 显式 if/foreach（D5 同款）
      rows.forEach(row → DataPermColumnApplier.apply(row, columnScope))      // title 隐藏/脱敏
  → R<PageResult<ApprovalVo>>
```

### 5.2 详情（IDOR 收口）与 diagram

```
GET /bpmn/approval/{id}
  → findById(id)：approval = requireApproval(id)（4010 真不存在）
      r = evaluate({account, "bpmn_approval", "detail", String.valueOf(id)})  // 留痕 1 条
      !scope.allows(approval.getApplyUser())
          → dataPermClient.deny({account, "bpmn_approval", id})               // 补 deny 留痕（降级仅 log.error）
          → throw BusinessException(4018, "无权访问该审批单")
      可见 → DataPermColumnApplier.apply(approvalVo, columnScope) → 详情拼装（时间线译文不变）
GET /bpmn/approval/{id}/diagram：requireApproval + 同款行级判定（4018/deny 同路径）→ 图数据
```

### 5.3 provider 侧（system）

```
POST /inner/data-perm/evaluate（网关屏蔽，网格内信任）
  → InnerDataPermController.evaluate(@RequestBody DataPermEvaluateRequest)
  → evaluator.evaluateFor(account, resource, operation, businessKey)          // account 显式（D20/D23）
      → doEvaluate（4-5 次索引小查询，D10 实时）→ persistLog（sys_data_perm_log，D7）
  → R<DataPermScopeVo>（Decision→ScopeVo 四字段收敛）
POST /inner/data-perm/deny → evaluator.logDeny(account, resource, businessKey)
```

### 5.4 新资源跨服务接入模板 v2（四步，取代 §7.3/重构轮 §5 的单服务版）

1. provider：`DataPermResources` static 块 `registerRemote("xxx", List.of(可配列))` 一行；
2. consumer：启动期 `DataPermColumns.assertDeclared(XxxVo.class, 同列清单)` 一行（D22）；
3. consumer 读路径：注入 DataPermClient，list/detail 各一次 evaluate（空集短路 + allows 判定 + deny 补痕 + applier 列应用）；
4. consumer mapper：增 DataScope 参数、XML 显式 `<if>/<foreach>` 块。

接入成本：注册 2 行 + 每读路径 3-5 行显式代码 + XML 一块——「其他服务直接使用」兑现。

## 6. 接口面概览（逐端点定稿见契约）

| 域 | 端点 | 说明 |
|---|---|---|
| inner | POST /inner/data-perm/evaluate、POST /inner/data-perm/deny | 服务间 Feign 专用（网关屏蔽已就位），无 @PreAuthorize 沿 inner 惯例 |
| bpmn | GET /bpmn/approval/page 语义变更 | 恒按申请人 → 数据权限求值（无规则=SELF 行为不变）；title 可 null/***
| bpmn | GET /bpmn/approval/{id}、/{id}/diagram 语义变更 | 行级判定 + 4018 + deny 补痕 |
| system | 既有 9 端点 + my-scope 零新契约 | my-scope?resource=bpmn_approval 直接可用（registerRemote 后注册表认得） |

## 7. 错误处理

| 层 | 语义 | 失败形态 |
|---|---|---|
| Feign 降级/熔断 | system 不可用 | evaluate/deny 返回 R.fail(1002) → 消费方 BusinessException → 拒绝访问（fail-closed，D21）；deny 降级仅 log.error |
| inner 入参 | account/operation 空白 → 1002；resource 空白或未注册 → 3034（assertResource 一站式，isRegistered(空白)=false 天然覆盖，无特判分支） | provider BusinessException——inner 信任域轻校验 |
| 行级拒绝 | 详情/diagram 归属不通过 | 4018 + deny 远程补痕（D24） |
| 列应用 | DB 脏列 | applier 三层防御第三层不变（warn 跳过不炸读路径，D16/D22） |
| 留痕失败 | provider 侧 insert 异常 | catch log.error 不抛（D7 口径，跨服务同款） |

## 8. 测试策略

- **cloud-system-api 单测（新模块测试面）**：DataPermClientFallbackFactoryTest（两方法均 R.fail 且日志含 account/resource）；DataPermScopeVoTest（toDataScope/toColumnScope 转换 + rowAll 短路 + 空集合边界）；DataPermColumnApplierTest（自 system 搬家迁移，用例集零改动——真类不 mock）；DataPermColumnsTest（assertDeclared 红绿两态：不存在列/非 String 字段 → IllegalStateException）
- **cloud-system 单测**：DataPermEvaluatorTest 增量（evaluateFor 账号显式路径留痕、logDeny 三参签名等价）；DataPermResourcesTest 增量（registerRemote 注册后 isRegistered/getConfigurableColumns 生效、D17 类断言不触远程资源、voClass null 记档）；InnerDataPermController 测试经 evaluator mock（薄壳）；既有 461+ 单测零回归（类型 import 切换后全绿 = 搬家行为等价证明）
- **cloud-bpmn 单测**：ApprovalQueryServiceTest 重构增量——pageList（mock client：正常/空集短路/fail-closed 三态）、findById（可见/4018+deny 调用/4010 先行）、findDiagram（4018 同款）、title 列级经真 applier；getSummary 格式快照测试防 D28 漂移
- **联调冒烟**：起 system+bpmn+网关——三账号矩阵（admin=全部/主管=部门档/无规则=仅自己）审批列表行集差异 + title 列规则 `***` + 详情越权 4018 + system 留痕页见 bpmn_approval 决策/deny 行 + my-scope?resource=bpmn_approval；**拔 system 验 fail-closed**（bpmn 列表 toast「数据权限服务不可用」不返数据）
- **既有回归**：`mvn clean install` 全绿；`npm run e2e:dataperm`（leave 零回归）+ `npm run e2e:bpmn`（审批页既有断言——admin 行为变更 L 检查点：若 bpmn e2e 有 admin 视角全量断言需按 D27 维护）+ 新 `e2e:dataperm-component`

## 9. 索引审查（规范强制章节）

本轮零 DDL、零新索引。查询路径核对：`bpmn_approval.pageList` 的 `apply_user IN (...)` 命中既有 `idx_apply_user(apply_user, deleted)`（range scan，单值 SELF 档等值同命中——与原 `=` 语义等价）；sys_data_perm_rule/column 求值查询仍命中 uk 最左前缀（远程求值不新增查询形态，仅多一次 Feign hop）；sys_data_perm_log 插入无新路径。EXPLAIN 抽查（pageList IN 形态）进计划 B4。

## 10. 红线核对清单（实现与验收共同遵守）

1. leave 试点行为零变化（D26：本地调用保持 + 仅 import 切换）——e2e:dataperm 零回归
2. 配置台 9 端点与前端三 tab 零改动（resources 下拉自然多出 bpmn_approval 属预期数据变化，非代码变更）
3. DDL 零新增；网关/sso 零改动
4. D15-D17 机制零破坏：DataPermResources 保持 static final；D17 断言对本地 register 行为逐字不变（实现提取为共享静态方法，语义单一实现）
5. 既有单测（除搬家迁移与签名显式化两处）零修改；全量构建全绿
6. fail-closed 全路径生效：无任何分支在求值失败时放行数据

## 11. 已知取舍与移交备忘

1. **规则缓存与失效**：D6 字面「本地缓存」继续移交（D18 采纳口径）；量级出现后按「规则变更主动失效/版本号」另轮设计
2. **IN 大集合**：DataScope accounts 经 Feign JSON 传输（数百账号 KB 级可承受）；分批 IN/临时表 JOIN/部门直滤列三路候选不变（原 §11.2）
3. **留痕治理**：跨服务后 sys_data_perm_log 写入频次 = bpmn 读路径频次（detail+diagram 双留痕记档于 D24）——采样/异步/xxl-job 清理沿原 §11.3 移交
4. **远程资源声明自动化**：D22 双行声明（provider registerRemote + consumer assertDeclared）的漂移防护已闭环；「消费方启动期推送注册」方向因启动顺序问题落选——资源数增长后再评估
5. **中性降级码**：fallback 沿用消费方定制码先例（本轮 1002+文案）；中性降级码演进沿 SystemUserClientFallbackFactory 同款移交记档
6. **非 String 可配列 / 列脱敏多样化**：D17/D28 约束延续，需求出现随脱敏策略轮演进
7. **错误码账本**：bpmn 段本轮占用 4018（现用至 4018，下轮 4019 接续）；system 段仍至 3034
8. **diagram 独立留痕粒度**：detail 与 diagram 各自 evaluate 各自留痕（同一弹窗两条）——若视为噪音，可在消费侧做单请求内决策复用（会引入请求内缓存语义），本轮不做记档
