# 数据权限组件化实施计划（后端 ∥ 前端，E 章 e2e 收口）

- 日期：2026-10-10；设计 `docs/superpowers/specs/2026-10-10-dataperm-component-design.md`（D18-D28）、契约 `docs/superpowers/contracts/2026-10-10-dataperm-component-api.md`
- 轨道：全栈 A 级。**B 章（后端）与 F 章（前端）独立可并行**；E 章（e2e）依赖 B+F 完成后收口
- 任务时序：B1（api 模块，纯增量）→ B2（system 切换）∥ B3（bpmn 接入）→ B4（后端联调冒烟）；F1 任意时点开工（仅依赖既有 my-scope 端点与页面，联调验收依赖 B2 的 registerRemote）

## B 章：后端

### B1 cloud-system-api 组件搬家与新增（纯增量，先行）

**做什么**：创建 `cloud-api/cloud-system-api` 的 dataperm 契约面——四类搬家（行为零变更）+ client/fallback/三契约模型 + 消费方断言辅助 + AutoConfiguration 注册。

**文件清单**：
- 新增 `cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/DataScope.java`——自 system `service/dataperm` 搬家，包名与 javadoc 更新（指向本轮设计 D18/D19），实现零变更
- 新增 `.../api/dataperm/ColumnScope.java`——搬家 + D28：getSummary 的 `SysDataPermColumn.ActionEnum.label()` 改内联常量 `LABEL_HIDDEN="隐藏"/LABEL_MASKED="脱敏"`，输出格式逐字不变（javadoc 记档三处标签冻结点=契约 §6.8）
- 新增 `.../api/dataperm/DataPermOperation.java`（LIST/DETAIL/DENY 常量搬家）
- 新增 `.../api/dataperm/DataPermColumnApplier.java`（反射列应用搬家，D16 机制与防御零变更）
- 新增 `.../api/dataperm/DataPermColumns.java`——`public static void assertDeclared(Class<?> voClass, List<String> columns)`：真实实例字段 + String 类型两断言，violation → IllegalStateException（实现自 DataPermResources.assertDeclaredColumns 提取，语义单一实现两处消费——D22）
- 新增 `.../api/client/DataPermClient.java`（§2.3：contextId=dataPermClient，path=/inner/data-perm，必带 fallbackFactory——守护测试检查项）
- 新增 `.../api/fallback/DataPermClientFallbackFactory.java`（设计 §3.4：两端点 R.fail(1002,「数据权限服务不可用，请稍后重试」) + log.error 含 account/resource）
- 新增 `.../api/domain/DataPermEvaluateRequest.java` / `DataPermDenyRequest.java` / `DataPermScopeVo.java`（§5.1 四字段 + toDataScope()/toColumnScope()；注解口径统一 **`@Data + @NoArgsConstructor + @AllArgsConstructor`**——Feign Jackson 反序列化需无参构造，B3 消费方全参构造两用；沿 LoginUserDTO 简单 POJO 先例）
- 修改 `.../api/SystemApiAutoConfiguration.java`——additive 增 `@Bean DataPermClientFallbackFactory`
- 测试：新增 `src/test/java/com/cloudai/system/api/fallback/DataPermClientFallbackFactoryTest.java`（沿 SystemUserClientFallbackFactoryTest 范式）、`.../api/domain/DataPermScopeVoTest.java`（转换 + rowAll 短路 + 空集合边界）、`.../api/dataperm/DataPermColumnsTest.java`（红绿两态：不存在列/非 String 字段→IllegalStateException）；**搬迁** `DataPermColumnApplierTest` 自 cloud-system 至 api 模块（包路径随迁，用例集零改动——真类不 mock，搬家行为等价证明）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-system-api -am` 全绿；api 模块零 spring-data/mybatis 依赖（仅 core-starter+openfeign 沿现状）；cloud-system 暂未切换仍用旧类双绿并存（B2 收口）。

### B2 system 侧：类型切换 + inner 端点 + 注册表 registerRemote + 种子

**做什么**：system 改引 api 包类（删除本地旧类）、Evaluator 账号显式化、/inner/data-perm 两端点、注册表远程资源、admin 种子。

**文件清单**：
- 修改 `cloud-base/cloud-system/pom.xml`——显式声明 `cloud-system-api` 依赖（现状仅经 translate-starter 传递；本轮 system 直接 import api 类须显式，版本走父管委免）
- 修改 `.../system/service/dataperm/DataPermEvaluator.java`——① import 切换（DataScope/ColumnScope/DataPermOperation 改 `com.cloudai.system.api.dataperm`）；② 新增 `public DataPermDecision evaluateFor(String account, String resource, String operation, String businessKey)`：account 空白 → BusinessException(1002)；`userMapper.findByAccount` 解 userId（不存在/停用 → userId=null 收敛，方向安全 D20）→ doEvaluate → persistLog；③ `logDeny` 签名扩为 `(String account, String resource, String businessKey)`（assertResource→校验先行，account 显式化）
- 修改 `.../system/service/LeaveManageService.java`——logDeny 调用点同步传 `SecurityUtils.currentAccount()`（行为等价）+ import 切换（Applier/Operation/Resources 引用点）
- 修改 `.../system/service/dataperm/DataPermResources.java`——① 新增 `public static final String BPMN_APPROVAL = "bpmn_approval"` 与 `registerRemote(String resource, List<String> columns)`（重复/空白校验同 register；ResourceDef.voClass 允许 null，**不调类断言**——D17 断言仅本地 register 生效，javadoc 记档）；② static 块增 `registerRemote(BPMN_APPROVAL, List.of("title"))`；③ `assertDeclaredColumns` 委托 api 包 `DataPermColumns.assertDeclared`（单一实现）
- 修改 `.../system/service/dataperm/DataPermDecision.java`、`DataPermRegistryConsistencyChecker.java`、`SysLeaveMapper.java` 等引用点——import 切换（全局 grep `service.dataperm.DataScope|ColumnScope|DataPermOperation|DataPermColumnApplier` 清零旧引用）
- 删除 `.../system/service/dataperm/{DataScope,ColumnScope,DataPermOperation,DataPermColumnApplier}.java`（四类已搬 api；Evaluator/Decision/Resources/Checker 留守）
- 新增 `.../system/controller/feign/InnerDataPermController.java`——`@RequestMapping("/inner/data-perm")`：`@PostMapping("/evaluate")` 入参 `@RequestBody DataPermEvaluateRequest` → 两行式返回 `R.ok(toScopeVo(evaluator.evaluateFor(...)))`；`@PostMapping("/deny")` → `evaluator.logDeny(...)` → `R.ok()`（薄壳，无 @PreAuthorize 沿 inner 惯例；Decision→ScopeVo 四字段收敛私有方法 ≤30 行）
- 种子：新增 `scripts/sql/2026-10-10-dataperm-component.sql`（admin 角色 `bpmn_approval/ALL` 行 INSERT，uk 无冲突）+ 基线 `scripts/sql/cloud_system.sql` 同步（沿数据权限轮双落位先例）
- 测试：`DataPermEvaluatorTest` 增量（evaluateFor 留痕/account 空 1002/account 无效收敛空 SELF；logDeny 三参等价）；`DataPermResourcesTest` 增量（registerRemote 生效 isRegistered/getConfigurableColumns=["title"]；D17 类断言不触远程资源行）；新增 `InnerDataPermControllerTest`（mock evaluator 薄壳）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿（既有单测除 logDeny 签名处外零修改）；java 单文件源码查库验证种子落库；leave 冒烟（page/detail/my-scope）行为零变化。

### B3 bpmn 侧：Feign 接入 + 行级/列级/IDOR 收口

**做什么**：审批读路径三端点接数据权限（设计 §5 数据流），fail-closed 全路径。

**文件清单**：
- 修改 `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/BpmnApplication.java`——`@EnableFeignClients(clients = {SystemUserClient.class, DataPermClient.class})`
- 新增 `.../bpmn/config/DataPermColumnDeclarationRunner.java`——ApplicationRunner：`DataPermColumns.assertDeclared(ApprovalVo.class, List.of("title"))`（启动 fail-fast，D22 消费方断言；try 外不包——断言失败即启动失败）
- 修改 `.../bpmn/service/ApprovalQueryService.java`——① 注入 `DataPermClient`；② `pageListMy(query, account)`：`R<DataPermScopeVo> r = client.evaluate(new DataPermEvaluateRequest(account, RESOURCE_APPROVAL, DataPermOperation.LIST, null))`（资源标识用**本地常量 `RESOURCE_APPROVAL = "bpmn_approval"`**——注册表在 system 侧，bpmn 不引 DataPermResources；`DataPermOperation` 引 api）→ `!r.isSuccess()` throw `BusinessException(r.getMsg())`（fail-closed）→ `toDataScope().isEmptyScope()` 空页短路 → mapper 传 scope → 行集 `toEnrichedVo` 后 `DataPermColumnApplier.apply(vo, r.getData().toColumnScope())`；③ `findById(id, account)`（签名增 account，Controller 显式传）：requireApproval(4010) → evaluate(DETAIL, businessKey=id) → `!scope.allows(approval.getApplyUser())` → `client.deny(...)`（降级 catch log.error 不阻断）→ `throw BusinessException(4018, "无权访问该审批单")`；可见 → applier 应用 title；④ `findDiagram(id, account)` 同款判定（4018/deny 同路径）；私有方法 `evalScope(account, operation, businessKey)` 收敛三处求值+fail-closed 胶水（≤30 行，多端点共用）
- 修改 `.../bpmn/controller/ApprovalController.java`——detail/diagram 调用点增传 `SecurityUtils.currentAccount()`（page 已传不变；javadoc 更新指向新契约）
- 修改 `.../bpmn/mapper/BpmnApprovalMapper.java` + `resources/mapper/BpmnApprovalMapper.xml`——`pageList(Page<BpmnApproval> page, @Param("scope") DataScope scope)`（applyUser 参数退役）：`WHERE deleted = 0 <if test="scope.all == false"> AND apply_user IN <foreach .../> </if> ORDER BY id DESC`（`<if>` 标签体换行、#{} only，镜像 SysLeaveMapper.xml 范式；XML 头注更新索引命中说明）
- 测试：`ApprovalQueryServiceTest` 重构增量——mock DataPermClient：pageList 三态（正常白名单/空集短路零查库/R.fail→BusinessException fail-closed）；findById（可见应用列级/4018+deny 调用验证/4010 先行）；findDiagram（4018 同款）；title 列级经**真 applier + 真 ScopeVo.toColumnScope()**（evaluator 链路 mock client 即止，D14 教训不 mock 到底）

**验收标准**：`MVN -f cloud-base/pom.xml clean install -pl cloud-bpmn -am` 全绿；`DataPermColumnDeclarationRunner` 红态自证（临时改 List.of("titleX") 启动失败→复原）；grep 全仓 `pageListMy|findById|findDiagram` 调用点签名一致。

### B4 后端联调冒烟与索引抽查

**做什么**：起 Nacos/Redis/MySQL 既有容器 + sso/system/bpmn/gateway 四服务，按契约 §8 验收口径冒烟。

**步骤与验收**：
1. 建库种子执行（增量脚本）；四服务起服（java -jar，端口 9201/9202/9203/18080）；system 日志确认一致性检查零越界 warn（bpmn_approval 已注册）
2. 三账号矩阵 curl（经网关 18080，ASCII）：admin 审批页=全部（种子生效）；无规则 e2e 用户=仅自己（向后兼容复证）；配部门档主管角色 → 部门成员单据可见
3. 列级：主管角色配 bpmn_approval title=脱敏（既有配置台 API）→ 列表/详情 title=`***`；直绑隐藏 → null
4. 越权：普通用户 curl 他人审批 detail/diagram → 4018；system 库 sys_data_perm_log 见 deny 行（resource=bpmn_approval）
5. my-scope：`GET /system/data-perm/my-scope?resource=bpmn_approval` 三账号标签正确
6. **fail-closed**：taskkill system（9202）→ bpmn 审批 page/detail → 1002「数据权限服务不可用」，无数据返回；重启恢复
7. EXPLAIN 抽查：`pageList` 的 `apply_user IN (...)` 命中 `idx_apply_user`（range scan），与原 `=` 等值形态对比记档
8. 留痕页核对：数据权限页留痕 tab resource=bpmn_approval 有 list/detail/deny 三类行，要素齐

## F 章：前端（cloud-web）

### F1 审批页数据范围提示条 + title 列级展示

**做什么**：审批列表页复用 leave 页 my-scope 范式加提示条；title 空值/`***` 自然展示（后端已处理，前端零转换）。

**文件清单**：
- 修改 `cloud-web/src/views/bpmn/approval/index.vue`——顶部 el-alert 提示条（v-if=myScopeLabel，`当前数据范围：${myScopeLabel}${columnSummary ? （...） : ''}`，**整段复用** `views/system/leave/index.vue` L136-145/L223-228 范式）；title 列模板维持 `{{ rowOf(row).title }}`（null 渲染空、`***` 原样——契约 §3.1）；script 头 javadoc 更新（数据源语义变更：恒按申请人→数据权限求值，契约 2026-10-10-dataperm-component-api §3.1）
- 修改 `cloud-web/src/api/dataPerm.ts`——零新增函数（getMyScope 既有复用，resource 传 `'bpmn_approval'`）；仅 JSDoc 补资源取值域说明
- 修改 `cloud-web/src/types/api.ts`——增 `DATAPERM_RESOURCE_APPROVAL = 'bpmn_approval'` 常量（与 leave 页资源常量同款声明点，禁散落字面量）
- 详情弹窗 `ApprovalDetailDialog.vue` 预检：title 展示处对 null 容忍（空渲染）——若现有模板对 title 有非空假设（如标题栏拼接）补空态；`***` 原样

**验收标准**：三账号登录审批页提示条标签与实际行集一致（全部/指定范围 N 人/仅自己）；title 脱敏 `***` 与隐藏空均正确展示；详情/图数据越权 4018 拦截器 toast（复用 3026 同款处理链，零新代码）；Playwright MCP UI 验证截图（登录→审批页→提示条与列展示）；`npm run build` 通过、既有 cloud-web 测试零回归。

## E 章：e2e（cloud-e2e，依赖 B+F 收口）

### E1 新脚本 run-dataperm-component-e2e.mjs + 既有回归

**做什么**：新增三账号矩阵黑盒 e2e；跑既有 e2e:dataperm / e2e:bpmn 验零回归。

**文件清单**：
- 新增 `cloud-e2e/run-dataperm-component-e2e.mjs`（沿 run-dataperm-e2e.mjs 骨架：lib 登录/建号/清理复用；e2e 前缀账号，种子 admin 只碰配置台）+ `package.json` 增 `"e2e:dataperm-component": "node run-dataperm-component-e2e.mjs"`
- 用例序：建部门/三账号（主管挂部门）→ 用户 A/B 各发起请假（产生 approval）→ 审批页行集三态（A=仅自己 B 的单不可见 / 主管=部门成员 / admin=全部）→ 配主管角色 title=脱敏 → 列表/详情 `***` → B 深链 A 审批 id（query.approval）→ 4018 弹窗不吐数据 → my-scope 提示条三账号断言 → system 留痕页（resource=bpmn_approval）list/detail/deny 三类行断言 → 清理
- 既有维护点核对（契约 §7.3）：run-bpmn-e2e.mjs 若有 admin「仅自己」审批断言 → 改全量预期；run-dataperm-e2e.mjs 规则分页全表总数断言 → +1（resource 筛选用例不动）

**验收标准**：`npm run e2e:dataperm-component` 有头全绿；`npm run e2e:dataperm` + `npm run e2e:bpmn` 零回归（维护点修改外零 diff）。

## 移交后续阶段的备忘（下一阶段规划前必读）

1. 规则缓存与失效（D18 采纳口径：本轮远程实时，缓存整体移交——失效是经典事故源）
2. IN 大集合与 Feign 载荷（数百账号 KB 级可承受；三路候选不变，量级出现 EXPLAIN 后定）
3. 留痕治理（跨服务后写入频次含 detail+diagram 双留痕——采样/异步/xxl-job 清理沿原账本）
4. 远程资源声明自动化（D22 双行声明的漂移防护已闭环；消费方启动期推送方向落选记档，资源数增长再评估）
5. 中性降级码（本轮 1002+文案沿消费方定制码先例；与 SystemUserClientFallbackFactory 同款移交）
6. diagram 独立留痕粒度（同弹窗两条 detail 留痕——单请求内决策复用会引入缓存语义，本轮不做）
7. 错误码账本：bpmn 段现用至 **4018**（下轮 4019 接续）；system 段仍至 3034
8. 列脱敏多样化 / 非 String 可配列 / 严格模式：沿原数据权限账本续期

---

## 给 backend-agent 的任务清单（直粘）

按序执行 B1→B2∥B3→B4，规范依据：设计 `docs/superpowers/specs/2026-10-10-dataperm-component-design.md`（D18-D28）+ 契约 `docs/superpowers/contracts/2026-10-10-dataperm-component-api.md`（§2 inner 端点逐字段、§3 bpmn 语义变更、4018）。

1. **B1** cloud-system-api 增 dataperm 面：dataperm 包五类（DataScope/ColumnScope——label 内联 D28、DataPermOperation/DataPermColumnApplier 搬家零行为变更 + DataPermColumns.assertDeclared 断言辅助）+ client/DataPermClient（contextId=dataPermClient、path=/inner/data-perm、必带 fallbackFactory）+ fallback（两端点 R.fail(1002)）+ domain 三模型（三者统一 **@Data + @NoArgsConstructor + @AllArgsConstructor**——Feign 反序列化无参构造 + 消费方全参构造两用；ScopeVo 含 toDataScope/toColumnScope）+ SystemApiAutoConfiguration 增 fallback bean + 四组测试（fallback/ScopeVo/Columns 红绿/Applier 搬迁）。纯增量不动 cloud-system。
2. **B2** system 切换：pom 显式依赖 cloud-system-api；import 全量切换后删除四个旧类；Evaluator 增 evaluateFor(account,...)（findByAccount 解 userId，无效收敛不报错）+ logDeny 三参签名（LeaveManageService 调用点同步）；DataPermResources 增 BPMN_APPROVAL 常量 + registerRemote（voClass null 不走类断言）+ static 块一行 + assertDeclaredColumns 委托 api；新增 controller/feign/InnerDataPermController（evaluate/deny 薄壳）；种子 SQL 增量+基线双落位；单测增量。
3. **B3** bpmn 接入：@EnableFeignClients 加 DataPermClient；config/DataPermColumnDeclarationRunner（assertDeclared(ApprovalVo.class, ["title"])）；ApprovalQueryService 三读路径接求值（私有 evalScope 收敛 fail-closed 胶水；pageListMy 空集短路；findById/findDiagram 签名增 account、4018+deny 补痕、applier 应用 title）；ApprovalController 两调用点传 account；BpmnApprovalMapper.pageList 签名换 DataScope + XML 显式 if/foreach（镜像 SysLeaveMapper，标签体换行）；单测 mock client 覆盖三态+4018+列级真 applier。
4. **B4** 联调冒烟按计划 B4 八步（含停 system 验 fail-closed 与 EXPLAIN 抽查记档）。
5. 红线：leave 行为零变化（D26 本地调用保持）；配置台/DDL/网关/sso 零改动；D15-D17 机制零破坏；fail-closed 无任何放行分支；mvn 全量构建全绿。

## 给 frontend-agent 的任务清单（直粘）

执行 F1（可与后端并行，联调验收等 B2 registerRemote 落库后），规范依据：契约 `docs/superpowers/contracts/2026-10-10-dataperm-component-api.md` §3/§4 + `/frontend-page` 技能。

1. `cloud-web/src/views/bpmn/approval/index.vue`：顶部 my-scope 提示条——整段复用 `views/system/leave/index.vue` L136-145/L223-228 范式（el-alert + `当前数据范围：${myScopeLabel}${columnSummary ? （...） : ''}`），resource 传 `bpmn_approval`；title 列维持原样渲染（null 空/`***` 原样——后端已处理零转换）；script 头 javadoc 更新数据源语义（契约 §3.1）。
2. `cloud-web/src/types/api.ts` 增 `DATAPERM_RESOURCE_APPROVAL = 'bpmn_approval'` 常量（声明点之外禁散落字面量）；`api/dataPerm.ts` 零新函数（getMyScope 复用），JSDoc 补资源取值域。
3. `ApprovalDetailDialog.vue` 预检 title 空态容忍（null 渲染空）；4018 走拦截器统一 toast（与 3026 同链零新代码），弹窗关闭即可。
4. 验收：Playwright MCP 三账号 UI 验证（提示条标签/行集/title `***` 与空/4018 toast）+ 截图；`npm run build` 与既有测试零回归；按 /frontend-page 测试规范留证。
