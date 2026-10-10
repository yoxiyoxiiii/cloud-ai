# DataPermResources 注册表抽象升级实施计划（纯后端重构轮）

- 日期：2026-10-10
- 状态：待执行（backend-agent 单端轨道）
- 对齐基线：设计 `docs/superpowers/specs/2026-10-10-dataperm-registry-refactor-design.md`（D15-D17 必读）+ 原设计 `2026-10-10-data-permission-design.md`（D1-D14 语义冻结不动）+ 既有契约 `2026-10-10-data-permission-api.md`（**零变更红线**——本轮不出新契约，行为以此契约为验收基线）+ `/backend-spec` 技能 + CLAUDE.md 编码规范
- 范围：cloud-system 单模块内聚重构；契约/DDL/错误码/前端/网关/sso 全零变更；**每任务收口时全仓编译绿**（任务间保持可编译时序）

## 任务总览

| # | 任务 | 产物 | 核心验收 |
|---|---|---|---|
| B1 | 注册表 Class 化 + 断言 | DataPermResources 重写 + DataPermResourcesTest | 五 API 行为等价；断言红绿两态 |
| B2 | 反射列应用工具 | DataPermColumnApplier + 单测 | 真 VO 非 mock 六场景 |
| B3 | 试点切换 | LeaveManageService 改造 + 常量退役 | 既有 LeaveManageServiceTest **零修改**全绿 |
| B4 | DB 一致性检查器 | 两 mapper 查询 + checker + 单测 | 越界清单四态断言 |
| B5 | 全量收口 | 全绿证明 + 启动冒烟 + e2e 回归 | 461 零回归；warn 红态实证 |

---

## B1 注册表 Class 化与启动断言（D15 + D17 前半）

**Files**:
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/dataperm/DataPermResources.java`（重写内部实现，公共面冻结）
- Add: `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/dataperm/DataPermResourcesTest.java`

**实现要点**（完整示意设计 §4.1）：

1. 内部结构改 `LinkedHashMap<String, ResourceDef>` + `record ResourceDef(Class<?> voClass, List<String> columns)`；static 块 `register(LEAVE, SysLeaveVo.class, List.of("title", "reason"))`
2. `register` 私有静态：空白 resource / 空列清单 / 重复注册 → IllegalStateException（消息含 resource 与 VO 类名）；随后调断言
3. 断言体**包可见** `static void assertDeclaredColumns(String resource, Class<?> voClass, List<String> columns)`：逐列①`getDeclaredFields()`（排除 static 修饰符字段，serialVersionUID 不干扰）中存在同名实例字段②`field.getType() == String.class`——violation 抛 IllegalStateException（消息含 resource/列名/VO 类名/原因，两条原因分别可辨）
4. 五个公共 API（isRegistered/assertResource/assertColumn/getConfigurableColumns/listRegisteredResources）签名与行为**逐条不变**，内部读 REGISTRY；`ERR_INVALID_RESOURCE` 常量保留
5. **时序约束**：`LEAVE_COLUMN_TITLE/LEAVE_COLUMN_REASON` 两常量本任务**暂保留**（javadoc 注明「B3 退役」）——唯一消费方 LeaveManageService L124-131 尚未切换，删早了编译破
6. 类 javadoc 重写：D15 形态说明 + 新资源接入一行模板（设计 §5）+ 列字面量在唯一声明点的口径

**DataPermResourcesTest**（红绿两态，直调包可见断言，不触 REGISTRY 无需 reset）：

- 绿：类加载后 `isRegistered("leave")` 真 / `getConfigurableColumns("leave")` = [title, reason] / `assertColumn("leave","reason")` 通过 / `listRegisteredResources()` 含 leave；`isRegistered(null)`/`isRegistered("ghost")` 假
- 红：`assertDeclaredColumns("leave", SysLeaveVo.class, List.of("remark"))` → IllegalStateException（字段不存在）
- 红：`assertDeclaredColumns("leave", SysLeaveVo.class, List.of("createTime"))` → IllegalStateException（非 String）

**验证**：
```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-system -am -Dtest=DataPermResourcesTest
```
红绿两态全绿后，`mvn -f cloud-base/pom.xml test -pl cloud-system -am`（既有 DataPermEvaluatorTest/DataPermManageServiceTest/LeaveManageServiceTest 用 `"leave"` 字面量，静态注册保持即零回归）。

---

## B2 反射列应用工具（D16）

**Files**:
- Add: `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/dataperm/DataPermColumnApplier.java`
- Add: `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/dataperm/DataPermColumnApplierTest.java`

**实现要点**（完整示意设计 §4.2）：

1. final 工具类（私有构造），唯一公共方法 `public static void apply(Object vo, ColumnScope columnScope)`
2. `vo/columnScope 为 null 或 isEmpty()` 短路返回
3. `FIELD_CACHE`：`ConcurrentHashMap<Class<?>, Map<String, Field>>` 惰性缓存——首次触达类 `getDeclaredFields()` 一次展开（排除 static 字段）逐个 `setAccessible(true)`；VO 无继承层级，getDeclaredFields 完备
4. hidden 逐列 `field.set(vo, null)`；masked 逐列 `field.set(vo, columnScope.mask((String) field.get(vo)))`——**mask 复用 ColumnScope.mask（null→null）**
5. 双防御（读路径永不炸，D7 口径）：字段 miss → `log.warn`（`MISSING_WARNED` 集合按 `class#field` 去重防大分页刷屏）跳过；masked 列字段类型非 String → 同款 warn 跳过；`IllegalAccessException` catch → `log.error` 跳过（理论不可达的终态兜底）
6. javadoc：D16 决策引用 + 三层纵深防御说明（注册断言→3034→本防御）+ 性能量级记档（缓存后 O(1)，≤200 行 × ≤几列可忽略）

**DataPermColumnApplierTest**（**真 SysLeaveVo 真 ColumnScope，禁 mock**——D14 教训：反射映射类缺陷 mock 不可见）：

1. hidden：`ColumnScope.of(Set.of("title"), Set.of())` → title null，reason 原值
2. masked：`of(Set.of(), Set.of("reason"))` → reason `***`，title 原值
3. masked 且原值 null → 保持 null（不被替换为 `***`）
4. empty scope（`of(Set.of(), Set.of())`）→ 全字段零修改
5. 脏列防御：`of(Set.of("ghost"), Set.of())` → 不抛异常，其余动作列（同 scope 内再放 "title"）正常应用
6. 非 String 脏列防御：masked 含 "createTime"（LocalDateTime 字段）→ 不抛异常跳过
7. hidden+masked 并存：`of(Set.of("title"), Set.of("reason"))` 两动作同时生效

**验证**：`mvn ... test -pl cloud-system -am -Dtest=DataPermColumnApplierTest` 全绿。

---

## B3 试点切换与常量退役（设计 §4.4）

**Files**:
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/LeaveManageService.java`

**实现要点**：

1. 列表路径（现 L72）与详情路径（现 L92）改调 `DataPermColumnApplier.apply(row/vo, decision.getColumnScope())`
2. `applyColumnScope` 私有方法（现 L120-134）整体删除；import 增 DataPermColumnApplier、删不再引用的（ColumnScope 若无他用一并清）
3. 回 B1：删除 `DataPermResources.LEAVE_COLUMN_TITLE/LEAVE_COLUMN_REASON` 两常量（此刻消费方已清零）——`DataPermResources.LEAVE` 保留（L63/L86/L88 求值调用引用）
4. javadoc 类注释补一句：列应用经通用工具（D16），行为与手写版逐字段等价

**验收（本轮最关键回归证明）**：`LeaveManageServiceTest` **一行不改**全绿——列级用例（`findById_appliesColumnScopeOnVisibleRow` 等）原以真 VO + 真 ColumnScope 构造、仅 mock evaluator，切换后自动穿透**真反射工具**；若有任何用例需修改才通过 = 行为漂移，**停下来回报主控**，不得自行改测试凑绿。

**验证**：`mvn ... test -pl cloud-system -am`（全模块单测含 B1/B2 新增）。

---

## B4 DB 一致性检查器（D17 后半）

**Files**:
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/DataPermRuleMapper.java`（+`List<String> listDistinctResources()`）
- Modify: `cloud-base/cloud-system/src/main/resources/mapper/DataPermRuleMapper.xml`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/DataPermColumnMapper.java`（+`List<SysDataPermColumn> listDistinctResourceColumns()`）
- Modify: `cloud-base/cloud-system/src/main/resources/mapper/DataPermColumnMapper.xml`
- Add: `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/dataperm/DataPermRegistryConsistencyChecker.java`
- Add: `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/dataperm/DataPermRegistryConsistencyCheckerTest.java`

**实现要点**（完整示意设计 §4.3）：

1. SQL 两句（物理删表**无 deleted 条件**——D9 口径；只读查询）：
   - `SELECT DISTINCT resource FROM sys_data_perm_rule`
   - `SELECT DISTINCT resource, column_key FROM sys_data_perm_column`（resultType 实体，仅两列有值）
2. Checker：`@Component @RequiredArgsConstructor implements ApplicationRunner`，注入两 mapper；`run` 整体 try-catch（失败 log.error「不影响启动」）
3. `List<String> collectViolations()` **包可见**（可测）：rule 表 resource 不在 `isRegistered` → 条目「资源 xxx 未注册」；column 表 (resource, column_key) 不在 `getConfigurableColumns(resource)` → 条目「资源 xxx 列 yyy 不在可配清单（疑似孤儿规则）」；越界键完整拼进条目文本
4. `run` 内 violations 非空时逐条 `log.warn` + 末尾一条汇总（条数 + 「请人工核对处置，检查不阻断启动」）；空清单零输出
5. 索引零新增记档（设计 §8：启动一次性全扫配置态小表）

**DataPermRegistryConsistencyCheckerTest**（Mockito mock 两 mapper，直调 collectViolations）：

1. 两表均空 → 空清单
2. rule=[leave]、column=[(leave,title)] → 空清单
3. rule=[leave, ghost] → 清单含「ghost 未注册」条目
4. column=[(leave, status)] → 清单含「列 status 不在可配清单」条目
5. 混合越界 → 两条目并存且顺序稳定（rule 路先 column 路后）

**验证**：`mvn ... test -pl cloud-system -am -Dtest=DataPermRegistryConsistencyCheckerTest` 全绿。

---

## B5 全量收口与三层实证

1. **全量构建**：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install`——全绿（既有 461 + 本轮新增 ≈15 例，零回归）
2. **启动冒烟（绿态）**：起 9202（`java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar`，Nacos/Redis/MySQL 前置在跑）——启动完成无注册表断言异常、无一致性 warn；`curl http://localhost:18080/system/demo/ping`（网关在跑则经网关，否则直打 9202 等价路径）确认服务健康
3. **warn 红态实证**：java 单文件源码 + mysql-connector-j 插两条脏数据（`sys_data_perm_rule` 一条 resource='ghost'；`sys_data_perm_column` 一条 (leave,'ghost_col',action=0) 的完整列）→ 重启 9202 → 启动日志出现两条 warn（资源未注册 + 列不在清单）**且服务正常启动**（不阻断实证）→ 删两条脏数据复原并回查删净
4. **e2e 回归（行为漂移防线）**：四端口全起（18080 网关最后）→ `cd cloud-e2e && npm run e2e:dataperm`——部门/数据权限三 tab/角色联动/leave 页脱敏全场景通过（列级行为经真实 HTTP 等价实证）；跑完按 dev-server-agent-managed 口径收口服务
5. 交付物自查：三文档一致（本轮设计 D15-D17 ↔ 本计划 ↔ 既有契约零变更声明）；§9 红线五条逐条勾验

---

## 移交备忘（随 commit 归档）

1. bpmn 接入时 register 的 voClass 可见性问题（设计 §10.1——与求值器抽 api 模块同轮演进）
2. 非 String 可配列随脱敏策略多样化演进（D17 String 断言约束记档）
3. 资源中文名 label（resources 端点）属契约变更另轮
4. 一致性检查仅启动时执行；周期对账/规则页孤儿标识为配置台增强另轮

## 给 backend-agent 的任务清单（可直接粘发）

按 B1→B5 顺序执行，**每任务收口编译绿**；对齐基线 = 本计划 + 设计 `2026-10-10-dataperm-registry-refactor-design.md`（D15/D16/D17 必读）+ `/backend-spec` 技能 + CLAUDE.md 编码规范。要点重申：

- B1：注册表重写公共面冻结（五 API 签名行为不变）；断言体包可见可测；LEAVE_COLUMN_* 两常量**暂留**（B3 才删，删早编译破）
- B2：工具单测**真 VO 禁 mock**（D14 教训）；脏列/非 String 双防御不炸读路径
- B3：LeaveManageServiceTest **零修改全绿是验收条件**——任何用例需改即回报主控，禁改测试凑绿
- B4：两句 SQL 无 deleted 条件（D9 物理删表）；collectViolations 包可见返回清单（可测不依赖日志捕获）
- B5：clean install 全绿零回归 + 起服绿态无 warn + 脏数据红态 warn 不阻断实证（脏数据插完必删净回查）+ `npm run e2e:dataperm` 回归 + 服务收口
- 红线：契约零变更（发现行为与契约不符立即停手回报）；DDL 零触碰；错误码零新增（至 3034）；不碰网关/sso/前端；mapper XML `<if>` 标签体换行、#{} only（本轮两句 SQL 无动态条件，纯静态）
