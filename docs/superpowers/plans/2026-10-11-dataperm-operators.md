# 数据权限 L2 谓词操作符扩展实施计划（后端 ∥ 前端，E 章 e2e 收口）

- 日期：2026-10-11；设计 `docs/superpowers/specs/2026-10-11-dataperm-operators-design.md`（D41-D50）；契约 `docs/superpowers/contracts/2026-10-11-dataperm-operators-api.md`（R7 `2026-10-11-dataperm-predicate-api.md` 增量，R7 文件零改动）
- 承接：R7 计划 `2026-10-11-dataperm-predicate.md` 移交备忘七项——本轮做 ①PREFIX ②GT/LT/NE ③字典翻译 ④索引复审记档 ⑦错误码记账；⑤SQL 膨胀治理 ⑥规则缓存**复审记档不实施**（论证与量化阈值见设计 §11 与本计划移交备忘）
- **B 章（后端）与 F 章（前端）无文件交集、无契约歧义面，可同消息并行派发**（前端仅依赖契约 §8 类型镜像，不依赖后端代码）；E 章依赖 B+F 收口
- 全局红线（设计 §10）：无条件规则与旧操作符规则全链零变化；XML 既有 when 零触碰（增量全为新 when 插入）；守护测试零改动；错误码零新增（system 仍至 3037、bpmn 仍至 4018）；DDL 零变更
- 构建/测试命令（mvn 绝对路径，见 CLAUDE.md）：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install`（18 模块全绿为准）；前端 `cd cloud-web && npm run test` / e2e `cd cloud-e2e && npm run e2e:dataperm-operators`

## B 章：后端

### B1 cloud-system-api 谓词组件（纯增量，先行——B2 依赖本模块）

**文件清单**：

| 文件 | 变更 |
|---|---|
| `cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/dataperm/PredicateOperatorEnum.java` | +`GT(">", "大于")` `LT("<", "小于")` `NE("!=", "不等于")` `PREFIX("^", "前缀")`；`getArity()` 四新枚举=1；`supports()` v2（STRING→EQ/IN/NE/PREFIX，其余→除 PREFIX 全开，设计 §5.1） |
| `.../dataperm/RowPredicate.java` | `testNumber/testDate/testDateTime` switch +GT/LT/NE 三 case（穷尽性编译错强制——严格不等：BigDecimal compareTo >0 / <0 / !=0，时间 isAfter/isBefore/!equals 同构）；`testString` 改 switch 四 case（EQ/IN 原样、NE=规则④求反、PREFIX=`regionMatches(true, 0, raw, 0, raw.length())` 双侧不 stripTrailing）+ default false |
| `.../dataperm/FilterColumn.java` | record 增第 5 组件 `String dictKey`（设计 §5.2）；javadoc 注明 null=无字典 |
| `.../dataperm/FilterColumns.java`（断言工具） | 零改动（编译面自然兼容——title 是实体 String 字段，assertDeclared 现机制覆盖） |
| 测试：`RowPredicateTest`、`PredicateOperatorEnumTest`（cloud-system-api src/test） | 设计 §8.1 用例：GT/LT 边界严格性（GT 边界值不命中）、NE×NULL、NE×STRING ci+pad 求反矩阵、PREFIX（ci 命中/前缀尾空格不命中 P5/值尾空格命中 P4/`a\b` 字面/null→false）、矩阵外防御恒 false；supports 终态 4×9 穷举、九枚举 of/symbol/label/arity |

**验收**：cloud-system-api 模块 `clean install` 全绿；新增单测 ≥20；既有用例零修改（枚举 additive，既有 switch 编译错仅出现在本任务文件内且必须全部消解——不允许 default 吞新枚举绕过）。

### B2 system 侧：注册表 v2 + 翻译注入 + 可观察面

**文件清单**：

| 文件 | 变更 |
|---|---|
| `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/dataperm/DataPermResources.java` | static 块注册行 v2：10 个 FilterColumn 构造全补第 5 参——leave `title`/`title`/STRING/标题/null 新增行、bpmn_approval `title` 同款；`leaveType`→`"system_leave_type"`、`status`→`"bpmn_approval_status"`，其余 8 列 null（契约 §1.4 清单逐行对齐） |
| `.../convert/DataPermConvert.java` | `toPredicateSummary` 新增三参 overload（`+BiFunction<String, List<String>, Map<String, String>> dictResolver`，语义=（dictKey, 值集）→ value→label）；`predicateSummaryPart` 值文本经 `declared.dictKey()` 翻译（译文缺失 `getOrDefault(v, v)` 回退原值；IN 逐值、BETWEEN 双值各自译）；旧两参 overload 保留委托 null resolver（原值）——既有单测零破坏；`conditionalScopeText`（第六态摘要）与 `fillPredicates`（规则页）切三参路径 |
| `.../service/dataperm/DataPermEvaluator.java` | +`TranslationCacheService` 字段（@RequiredArgsConstructor 自动注入，cloud-system 已依赖 translate-starter）；`predicateNarrative` 值文本翻译（resolver=`findDictLabels` 方法引用；构造于调用点，保持方法纯度）；`digestPredicate` +4 case（`>` `<` `!=` `^`，穷尽性编译错强制；值原样不翻译） |
| `.../service/DataPermManageService.java` | 校验链**零结构改动**（矩阵经 `supports()` v2、值数经 `getArity()` 自动生效——确认 3036/3037 文案不变）；config 回显 L574 附近 `toPredicateSummary` 调用切三参（resolver 由本服务注入 TranslationCacheService 构造） |
| 测试：`DataPermEvaluatorTest`（构造器机械补 TranslationCacheService mock）、`DataPermConvertTest`、`DataPermManageServiceTest`、`DataPermResourcesTest` | 设计 §8.2 用例：digest 新符号（`id>100`/`title^e2e`/`leave_type!=2`）、narratives 译文（mock 返回 1→事假）、三参 overload 译文与缺失回退、两参原值（既有用例零触碰即回归证明）、校验链 v3 逐码（STRING×GT→3036、NUMBER×PREFIX→3036、GT 双值→3037、PREFIX 值>50→3037、title EQ/NE/PREFIX 保存回显）、双清单 v2 断言（五列/五列 + dictKey） |

**验收**：cloud-system 模块全绿；新增单测 ≥25；`DataPermConvertTest` 既有用例文件零 diff；无条件规则 digest/summary/narratives 断言逐字不变（红线用例保留通过）。

### B3 leave 试点：mapper XML 条件块增量（+16 分支，既有 20 分支零触碰）

**文件清单**：`cloud-base/cloud-system/src/main/resources/mapper/SysLeaveMapper.xml`（pageList 的 RowFilter 块）。

- 新增 `title` 列块（4 分支：EQ/IN/NE/PREFIX——PREFIX 用三层竖线 REPLACE 链 + `ESCAPE '|'`，设计 §5.3 完整模板逐字采用）；`id`/`leave_type`/`start_date`/`end_date` 四列块各 +GT/LT/NE 三 when（`&gt;`/`&lt;`/`!=`，插在既有 LTE when 之后）
- 注释规范（D47）：每列块头 `<!-- {field}（{type}）：{操作符清单} -->`；when 按枚举声明序；块间空行；`<if>`/`<when>` 标签体多行（守护规则）；仅 `#{}`（PREFIX 的 `'|'`/`'||'` 等是 SQL 字面量非占位符，不涉 `${}`）

**验收**：全模块 clean install 全绿（ArchitectureGuardTest 零改动通过）；分支计数 36（注释锚点可数）；既有 20 分支文本零 diff。

### B4 bpmn 试点：mapper XML 条件块增量（+15 分支，既有 17 分支零触碰）

**文件清单**：`cloud-base/cloud-bpmn/src/main/resources/mapper/BpmnApprovalMapper.xml`。

- `id`/`status`/`create_time` 三列块各 +GT/LT/NE（9）；`business_type` +NE/+PREFIX（2）；新增 `title` 块（4）——bpmn 无表别名，裸列名；其余同 B3
- cloud-bpmn 侧 Java 零改动（判定经 api 模块 `RowPredicate.test()` 与 `toRowFilter()`，B1 升 jar 后自动生效）；如 bpmn 有 ApprovalQueryService 用例则补 title PREFIX/NE 与 id GT 行集用例（设计 §8.3）

**验收**：cloud-bpmn 模块全绿；分支计数 32；既有 17 分支文本零 diff。

### B5 后端联调冒烟与 EXPLAIN 抽查（经网关 18080，ASCII 入参——Git Bash 中文 JSON 是 GBK 会 500）

启动 sso/system/bpmn/gateway（java -jar，CLAUDE.md 端口表），按契约 §9 全量走查：

1. **PREFIX owner 无关**：U 配 `leave/ALL + title PREFIX 'e2e'` → 列表仅 e2e 前缀行（含他人）；`e2e` 外（含本人）不可见；detail 对称（3026+deny）
2. **转义矩阵**：造单 title `e2e%x`/`e2e_x`/`e2e|x` → 配 PREFIX `e2e%` 仅 `e2e%x` 命中；PREFIX `e2e_` 仅 `e2e_x`；PREFIX `e2e|` 仅 `e2e|x`（未转义=全命中即过度授权，必查）
3. **NE/GT/LT**：`leaveType NE 2` 无病假（含本人）；`id GT x` 边界严格（=x 不可见）；`createTime GT` / `businessType NE 'leave'`（bpmn）各一例
4. **字典译文**：predicateSummary=`请假类型等于事假`、my-scope 第六态=`条件范围（请假类型等于事假）`、narratives=`命中条件：请假类型 等于 事假`；**rule_digest 仍 `∧leave_type=1`**；businessType 摘要仍原值
5. **校验链**：STRING×GT→3036、NUMBER×PREFIX→3036、GT 双值→3037、PREFIX 值>50→3037、operator `LIKE`→1002
6. **兼容红线**：清空全部新操作符规则 → 三账号矩阵/my-scope/留痕三态与 R7 终态逐字一致；停 system → bpmn 三端点 fail-closed 拒绝（复证）
7. **EXPLAIN 两项记档**（设计 D48）：`title LIKE 'e2e|%' ESCAPE '|'`（预期全表扫，type=ALL 记档）；`id > ?`（预期 PK range）；结论回填设计文档抽查记录
8. 冒烟通过后 kill 全部服务交还（memory：开发期服务 agent 自管）

**验收**：七组断言全过 + EXPLAIN 两项截图/文本记档回填设计文档；18 模块 clean install 终态全绿。

## F 章：前端（cloud-web）

### F1 类型镜像 + RuleConfigDialog 值输入器 v2（与 B 章零文件交集，可并行）

**文件清单**：

| 文件 | 变更 |
|---|---|
| `cloud-web/src/types/api.ts` | `OPERATOR_GT/OPERATOR_LT/OPERATOR_NE/OPERATOR_PREFIX` 四常量；`PredicateOperator` 联合类型扩九值；`OPERATOR_OPTIONS` +4（大于/小于/不等于/前缀——与契约 §1.1 中文逐字对齐，双处冻结点）；`FilterColumnVo` +`dictKey: string \| null` |
| `cloud-web/src/views/system/dataPerm/components/RuleConfigDialog.vue` | ①`operatorsForType` 矩阵镜像 v2（NUMBER/DATE/DATETIME 八操作符、STRING 四操作符——与后端 supports() 逐格一致）；②值输入器分支：dictKey 非空列 → `el-select`（`getDictItems(dictKey)` 数据源；EQ/NE/GTE/GT/LTE 单选、IN 多选、BETWEEN 双选有序；filterable + allow-create 兜底存量原值回显）；PREFIX（非字典 STRING 列）→ 单行文本输入器，提交时 `trimEnd`；③`rebuildPredicates` 回显兼容新操作符与字典值（allow-create 保证原值不在字典也可回显）；④操作符切换时值清空规则沿既有（类型/形态变化即清） |
| `cloud-web/src/api/dataPerm.ts` / `src/api/dict.ts` | 零新函数（saveRule/getRuleConfig/listResources/getDictItems 复用）；JSDoc 节号更新（契约 §1.1/§2.3/§5.2） |
| 组件单测（如有既有一并跑） | 矩阵镜像与输入器分支的组件测试按 `/frontend-page` 规范补齐 |

**验收**：`npm run test` 全绿；`npm run build` 通过（按需引入无新增全量导入）；配置台手测——title 列下拉出现且操作符仅 4 项、leaveType 列值下拉出现事假/病假/年假、PREFIX 输入框尾空格提交被裁剪、IN 多选回显正确；eslint 零新告警。

## E 章：e2e（cloud-e2e，依赖 B+F 收口）

### E1 新脚本 run-dataperm-operators-e2e.mjs + 既有脚本维护与三回归

**文件清单**：

| 文件 | 变更 |
|---|---|
| `cloud-e2e/run-dataperm-operators-e2e.mjs`（新） | 场景（契约 §9）：①PREFIX owner 无关（他人 e2e 前缀可见/本人非前缀不可见）；②转义矩阵（`e2e%`/`e2e_` 两规则，断言仅字面命中行）；③NE（leaveType NE 2 无病假 + bpmn businessType NE）；④GT/LT 边界严格（id GT x：=x 不可见、x+1 可见）；⑤digest 符号断言（`∧title^`、`∧id>`、`∧leave_type!=`——经留痕查询或 explain）；⑥字典译文三面断言（predicateSummary/scopeLabel 第六态/narratives「事假」+ digest 原值 `∧leave_type=1` + businessType 原值守恒）；⑦校验链四码（3036×2/3037×2/1002）；⑧兼容红线（清空后无条件态逐字回 R7） |
| `cloud-e2e/package.json` | +`"e2e:dataperm-operators": "node run-dataperm-operators-e2e.mjs"` |
| `cloud-e2e/run-dataperm-predicate-e2e.mjs`（维护） | **7 处译文断言**：L364/L378 predicateSummary、L405/L411/L441/L445 scopeLabel（`请假类型等于1`→`请假类型等于事假`）、L469 narratives（`等于 1`→`等于 事假`）；**零维护确认**：L362/369/376/466/480 predicates JSON、digest 断言、L533 审批单号介于、L587/L600 业务类型属于 leave/LEAVE（非字典守恒断言） |
| 回归 | `npm run e2e:dataperm`（8/8）、`e2e:dataperm-component`（9/9）、`e2e:bpmn`（16/16）、维护后 `e2e:dataperm-predicate`（11/11） |

**验收**：新脚本全绿（≥11 断言组）；维护脚本 11/11；三既有回归零 diff；全程有头跑（`npm run e2e` 形态）可复跑。

## 移交后续阶段的备忘（下一阶段规划前必读）

1. **SQL 膨胀治理复审记档（Round 8 拍板不实施）**：静态分支面终态 leave 36 / bpmn 32（D47）——维持显式硬编码 + 列块注释锚点；`${}` 禁令不放宽（include-property 本质 `${}`，守护全禁令是注入面防线）。**量化回潮**：可过滤列 >10/资源、第三资源接入使单 XML 分支面 >60、单用户条件组 >10 或谓词总数 >30（运行时 OR 组膨胀，与静态分支面分治）——任一触发重评「开发期生成器产出显式 XML」方向
2. **规则缓存覆核记档（Round 8 拍板不实施）**：设计 D49 论证全文——实时生效是数据权限现状语义，任何缓存都引入过授权窗口（fail-open 方向）或失效广播复杂度；**量化回潮**：求值 p95 > 50ms / 读路径 QPS 使 system CPU 可见抬升 / sys_data_perm_log 日增 > 50 万行（同窗触发留痕治理：采样/异步/清理）——任一出现按「provider 侧缓存 + 规则/部门/用户/绑定四源变更钩子 + 短 TTL 兜底」另轮设计，**须用户显式接受语义变更**
3. **title 前缀索引回潮**：sys_leave/bpmn_approval 行数过万且 PREFIX 规则活跃 → 补 `idx_title`（可前缀索引）+ EXPLAIN 复查；当前全表扫已记档（D48）
4. **PREFIX digest 符号 `^` 已冻结**（契约 §1.1）；中缀/后缀模糊、REGEXP、IS-NULL 维持不做（D29 排除面仅开右前缀一道口）
5. **字典硬值域校验不做**（下拉软约束 + allow-create 兜底）；若出现「配错值致规则静默永不命中」实际事故再评估（届时需新错误码 + 值域变更治理）
6. **译文 30min TTL 延迟**：字典标签变更后摘要/narratives 最长 30min 滞后（TranslationCacheService 既有缓存，显示层 fail-open 与权限域严格区分）；不可接受时改直查 SysDictDataMapper
7. **错误码账本**：system 段仍用至 **3037**（下轮 3038 接续）；bpmn 段仍至 4018
8. **跨服务 jar 时序**（契约 §6）：system 先行升级期间，未升 jar 的 bpmn 对新操作符规则表现为「不生效」（fail-closed 安全方向）——多环境滚动升级时属预期现象，不是缺陷

## 给 backend-agent 的任务清单（直粘）

> 按 `docs/superpowers/plans/2026-10-11-dataperm-operators.md` B 章执行（B1→B2→B3→B4 顺序，B3/B4 可并行），设计 `docs/superpowers/specs/2026-10-11-dataperm-operators-design.md`（D41-D50，§5 模板逐字采用）、契约 `docs/superpowers/contracts/2026-10-11-dataperm-operators-api.md`（R7 契约不改动，合并视图消费）。红线：XML 既有 when 零触碰、无条件/旧操作符行为零变化、守护测试零改动、错误码零新增、switch 新枚举禁 default 吞。收口 = 18 模块 clean install 全绿 + B5 冒烟七组断言 + EXPLAIN 两项记档回填设计文档；完成后 kill 服务交还。PREFIX 的 REPLACE 链与 ESCAPE 子句必须逐字采用设计 §5.3 模板（顺序敏感：`|` doubling 先行）；test() PREFIX 双侧不 stripTrailing 是探针实证结论（P2-P5），勿与 D34 规则④对齐。

## 给 frontend-agent 的任务清单（直粘）

> 按 `docs/superpowers/plans/2026-10-11-dataperm-operators.md` F 章执行（与后端并行，仅依赖契约 §8 类型镜像），设计 `docs/superpowers/specs/2026-10-11-dataperm-operators-design.md` D45/D41、契约 `docs/superpowers/contracts/2026-10-11-dataperm-operators-api.md`。要点：①OPERATOR_OPTIONS +4 中文与契约 §1.1 逐字对齐（大于/小于/不等于/前缀，双处冻结点）；②operatorsForType 矩阵镜像 v2（NUMBER/DATE/DATETIME 八、STRING 四——与 supports() 逐格一致）；③dictKey 非空列值输入器切 el-select（getDictItems 数据源，filterable+allow-create，IN 多选/BETWEEN 双选/EQ/NE/GTE/GT/LTE 单选）；④PREFIX 文本输入器提交 trimEnd；⑤回显 rebuildPredicates 兼容新操作符与存量原值。组件按需引入铁律不动摇；收口 = npm run test + build 全绿 + 配置台手测五项（计划 F1 验收）。E1 e2e 由主控另行派发，勿动 cloud-e2e。
