# 列级数据权限前端动态列实施计划（2026-10-22，全栈 A 级）

- 设计：`docs/superpowers/specs/2026-10-22-dataperm-user-column-visibility-design.md`（决策 D59-D66）
- 契约：`docs/superpowers/contracts/2026-10-22-dataperm-column-visibility-api.md`（增量修订挂 2026-10-10 体系，前后端唯一对齐物）
- **顺序约束：本轮实现排在 ProTable 轮收口提交之后**（工作区 ProTable 成果未提交——不构成阻塞，但开工前确认主控已完成该轮提交，避免两轮 diff 纠缠）
- 章节结构：A 后端章 / B 前端章**独立可并行**（对齐物=契约，两章互不等待）；C e2e 章（依赖 A+B 联通）；D 联通回归章
- 纪律：错误码零新增；权限标识零新增；mapper XML `<if>` 标签体换行；DDL 每列带 COMMENT；前端按需引入 + TS strict 禁 any/裸 as；agent 零 git 操作（主控统一提交）

## A 后端章（cloud-base）

### A1 DDL 增量脚本与基线同步

**文件**：新建 `cloud-base/scripts/sql/2026-10-22-dataperm-user-columns.sql`；改 `cloud-base/scripts/sql/cloud_system.sql`（sys_user 建表语句同步加两列 + 种子段同步 admin 示例值）。

**要点**：内容照契约 §5 逐字（mobile VARCHAR(20) NULL / email VARCHAR(64) NULL，COMMENT 齐全，AFTER nickname/mobile 定位）；**中文 SQL 落库用 pymysql 执行**（Git Bash curl/客户端 GBK 坑——CLAUDE.md 惯例，`uv.exe run --project cloud-base-ai python` + charset utf8mb4）；基线与增量同一份定义（沿 2026-10-10 先例）；零索引（设计 §8 记档，不写任何 KEY/uk）。

**验收**：pymysql 执行增量脚本无错；`SHOW COLUMNS` 见两列且 COMMENT 正确；admin 行 mobile/email 有示例值；基线文件与增量定义逐字一致。

### A2 实体/DTO/VO 增量与 XML 三处

**文件**：改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysUser.java`（+mobile/+email，注释说明可空与列规则语义）、`dto/UserSaveRequest.java`（+mobile/+email，javadoc 注明哨兵语义引契约 §4.3）、`vo/SysUserVo.java`（+mobile/+email String；**类级标注 @DataPermVo**）、`convert/SysUserConvert.java`（toVo 补两 setter）、`resources/mapper/SysUserMapper.xml`（四处：pageList select 加 u.mobile,u.email / findById select 加两列 / insert 动态加两 `<if>` / update 动态加两 `<if>`——标签体换行规范）。

**要点**：@DataPermVo 标注**必须与 A3 注册行同任务落地**（assertDeclared 启动断言：注册列所在 VO 未标注解 = 启动失败——分开提交会炸启动，实现时注意装配顺序在重启验证前都已完成）；mobile/email 的 update `<if>` 与既有 nickname 同款 null 跳过形态（哨兵归 null 在 Service 层做，XML 不感知 `***`）。

**验收**：`$MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 构建绿（A3 完成前启动会红属预期——标注解与注册行互相断言）；XML 无 `${}`、`<if>` 换行、deleted=0 条件不动。

### A3 资源注册 columnOnly 形态与求值器分支

**文件**：改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/dataperm/DataPermResources.java`（ResourceDef record 增 columnOnly 组件；新增 `registerColumnOnly(resource, voClass, columns)`——共用 validateRegistration + assertDeclaredColumns + **filterColumns 强制空清单断言**；static 块注册 `USER = "user"` 行：`registerColumnOnly(USER, SysUserVo.class, List.of("mobile", "email"))`；增 `isColumnOnly(resource)` 查询方法）、`service/dataperm/DataPermEvaluator.java`（doEvaluate 开头 columnOnly 分支：行段 dataScope=all()+rowFilter=rowAll()+hitRules=[]、列段照常 resolveColumns、narrative 增「该资源仅列级权限生效，行范围不适用」）。

**要点**：既有 `register` 方法签名与 leave/bpmn_approval 两注册行**零变化**（红线）；columnOnly 分支是早返回还是行段短路由实现定，但**列规则收集的 roleIds/userId 取数不能因分支跳过**（resolveColumns 依赖）；narrative 句式与既有中文句式一致（「列决策：」前插一句）。

**验收**：单测——columnOnly 资源配行规则（DEPT 档）求值 dataScope=all 且 hitRules 空；列规则（角色脱敏+用户隐藏）正常并集；leave 资源求值结果与改前逐字一致（零 diff 回归——既有 DataPermEvaluatorTest 全绿不改编译即过）。

### A4 my-scope 结构化与 resources columnOnly

**文件**：改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/vo/MyScopeVo.java`（+`columns`，元素类型复用 `DataPermExplainVo.ColumnDecisionVo`——import 内嵌类）、`vo/DataPermResourceVo.java`（+columnOnly boolean）、`convert/DataPermConvert.java`（toMyScopeVo 增 columns 转换：columnScope hidden/masked → columnKey 字典序稳定输出、空动作 `[]`；resolveScopeLabel 首位增 columnOnly 分支输出「不适用（该资源仅列级权限）」）、`service/DataPermManageService.java`（listResources 转换处补 columnOnly）。

**要点**：scopeLabel 既有五态判定序**不动**（新分支在最前，仅 columnOnly 资源进入）；columnSummary 保留原样（leave 页零影响红线）。

**验收**：单测——user 资源 my-scope 转换（columns 元素/顺序/空 `[]`、scopeLabel 新取值）；leave 资源转换输出与改前逐字一致；resources 出 admin 视角三资源含 user 且 columnOnly 标记正确。

### A5 user 读路径接线 + 写路径哨兵与校验

**文件**：改 `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/SysUserManageService.java`（pageList/findById 两方法：`evaluate(DataPermResources.USER, …)` + `DataPermColumnContext.set(USER, decision.getColumnScope())`——pageList 在 evaluate 后 set 再查库，findById 同款 businessKey=id；save/update 增格式校验 + update 哨兵归 null——先 `if (MASK.equals(req.getMobile())) req.setMobile(null)` 同款 email，再非空校验 hutool `Validator.isMobile/isEmail` 无效抛 1002 文案照契约 §4.2）。

**要点**：求值器注入（@RequiredArgsConstructor 加 final 字段即得）；pageList 的 evaluate 留痕每次分页一条属预期（设计 D61）；哨兵处理**必须在校验前**（`***` 不进格式校验）；import `ColumnScope.MASK` 常量（禁魔法串）；save 路径不设哨兵（新增无回填场景，`***` 作为新值被格式校验拦）。**空串归一（主控规格审查补）**：mobile/email 提交值为空串/纯空白时 trim 后归 null——契约 §4.1「null/空=不更新」要求空永不写库（mapper `<if test="!=null">` 只挡 null 挡不住 `''`），且脱敏 placeholder「留空保持不变」语义依赖它；归一放在哨兵检测同一段（save/update 两路径都归一）。

**验收**：单测——set 上下文断言两处（resource+scope 值，@AfterEach clear）；update `***` 哨兵（mobile=`***` → mapper 收 null → 库值不变；混合场景 email=`***`+nickname 新值同时生效）；非法格式 1002 两文案；空值放行；内置保护/查重等既有用例全绿。

### A6 MCP 入口上下文供给

**文件**：改 `cloud-base/cloud-common/cloud-common-mcp-starter/src/main/java/com/cloudai/common/mcp/auth/McpAuthFilter.java`（loadPrincipal 成功后构建 `LoginUser`（userId/account/permissions）+ authorities 塞 SecurityContextHolder；chain 包 try/finally clearContext；McpPrincipal attribute 通道不动）；必要时 `cloud-common-mcp-starter/pom.xml`（spring-security-core 依赖——JwtUtil 所在 security-starter 传递链应已含，**实现时验证**，缺则 provided 补一行）。

**要点**：设计 D62 时序论证已备（HeaderAuthFilter 无 header 早退不清 context，嵌套 finally 双清无害）；只挂 MCP 端点（既有 filter registration 限定 /mcp），REST 面零影响；三关任一失败路径不构建 context（未认证无身份可建）。

**验收**：单测（McpAuthFilterTest 增量）——三关全过后 `SecurityUtils.currentUser()` 字段等于 principal、未认证 context 空、chain 后已清；既有 MCP 单测全绿。

### A7 后端测试与冒烟收口

**文件**：改各对应 `src/test` 用例（DataPermEvaluatorTest/DataPermResourcesTest/DataPermConvertTest/SysUserManageServiceTest/McpAuthFilterTest 增量，见设计 §7.1 矩阵）。

**要点**：**先起服务做真链路冒烟**（设计 §7.2 全链：起 system+网关 → 配列规则 → curl 验证 mobile=`***`/email=null/account 原值 → my-scope columns 结构化 → `***` 哨兵 PUT/GET 复验 → 清规则复验）+ MCP 冒烟（`cloud-base-ai/scripts/smoke/smoke_mcp_system.py` 扩展：配 mobile=脱敏 → MCP 查询断言 `***` → 清规则复验原值——**实现时验证清单**：①SecurityContext 单测 ②真连接脱敏断言 ③@McpRequirePerm 拒绝路径回归）；验后还原一切临改。

**验收**：`$MVN -f cloud-base/pom.xml clean install` 全模块绿；冒烟清单逐项过并留输出证据；临时规则/数据清理干净。

## B 前端章（cloud-web）

### B1 类型与常量增量

**文件**：改 `cloud-web/src/types/api.ts`（`MyScopeVo` +`columns: ColumnDecision[]`；`DataPermResourceVo` +`columnOnly: boolean`；`SysUserVo` +`mobile: string | null` / `email: string | null`；`UserPayload` +`mobile?: string` / `email?: string`；+`DATAPERM_MASK = '***'`——**落 types/api.ts 紧邻既有 `ACTION_HIDDEN`/`ACTION_MASKED`（同族常量一处，主控规格审查定），不进 constants 目录**）。

**要点**：ColumnDecision 形态若 explain 既有类型可复用则复用（命名不重复定义）；JSDoc 注契约节号（§1.1/§2/§3.2/§4）。

**验收**：`cd cloud-web && npm run build` 两次全绿。

### B2 usePermColumns composable

**文件**：新建 `cloud-web/src/composables/usePermColumns.ts`。

**要点**：API——`usePermColumns(resource: string)` 返回 `{ load, isHidden, isMasked }`；load 拉 getMyScope → columns 按 ACTION_HIDDEN/ACTION_MASKED 分组进两个 `Set<string>`；**catch 置空两 Set 不抛**（降级全列，值层后端防线）；JSDoc 写明拉取时机约定（与列表加载并行）与降级语义（设计 D65）。

**验收**：build 绿；单元层面以 e2e 覆盖为主（工程无组件单测惯例，不新开测试栈）。

### B3 用户页消费：动态列 + 表单两字段

**文件**：改 `cloud-web/src/views/system/user/index.vue`（mobile/email 两列 `v-if="!isHidden('mobile')"` / `v-if="!isHidden('email')"`，值展示 `?? '-'`（null 与 `***` 均原样展示——null 显示 `-`、`***` 原样）；loadUserPage 内并行 `void permColumns.load()`；composable 引入与初始化）、`views/system/user/components/UserFormDialog.vue`（mobile/email 两个可选输入框；**预填检测**：`props.user.mobile === DATAPERM_MASK` → 置空 + placeholder「已脱敏，留空保持不变」；提交 payload 携带两字段（undefined 不更新语义与后端 null 不更新对齐））。

**要点**：**ProTable 零引入**（主控自决：user 页不顺迁，保持裸 el-table 形态）；列位置建议 mobile/email 在状态列前（昵称后）；表单校验前端不重复格式校验（后端权威 1002 拦截器 toast——避免双端正则漂移）；不做 ProTable 改动、不动 viewRegistry/路由。

**验收**：build 两次绿；dev 手测——无规则账号全列原值；配隐藏规则账号刷新后列消失；配脱敏规则账号值 `***` 且打开编辑弹窗 mobile 空 + placeholder 提示。

### B4 配置台 columnOnly 禁用与文案

**文件**：改 `cloud-web/src/views/system/dataPerm/components/RuleConfigDialog.vue`（computed `isColumnOnly`（当前资源 columnOnly）；行档位 radio 组 `:disabled="isColumnOnly"` + 条件段整块隐藏或禁用 + el-alert/text 注明「该资源仅列级权限生效，行范围配置不参与求值」；columnOnly 资源 form.rowScope 默认/回显时固定 SELF）。

**要点**：既有 leave/bpmn_approval 资源形态**零变化**（isColumnOnly=false 时一切照旧）；资源切换时行档区状态联动（watch form.resource）；文案照契约 §2。

**验收**：build 两次绿；dev 手测——资源切 user 行档灰 + 文案出现 + 保存成功（predicates 空）；切回 leave 形态完全复原。

## C e2e 章（cloud-e2e）

### C1 新脚本 run-dataperm-usercolumn-e2e.mjs

**文件**：新建 `cloud-e2e/run-dataperm-usercolumn-e2e.mjs` + `cloud-e2e/package.json` 增 script `e2e:dataperm-usercolumn`（不进 `e2e` 聚合，沿 dataperm 家族独立脚本惯例）。

**要点**：`e2edpu` 前缀 + 时间戳造数；前置探测（resources 含 user+columnOnly / my-scope columns / user 分页三探针，不过即 SKIP）；场景序（设计 §7.3）：建数（角色挂用户管理菜单闭包含 list+edit、列规则 mobile=脱敏+email=隐藏、两测试用户）→ 脱敏用户视角 DOM 断言（email 列头不存在、mobile 列在且 admin 行 `***`）→ 无规则用户视角全列原值 → admin 改规则（email 隐藏→脱敏）→ 免重登刷新列集变化（即时生效）→ `***` 哨兵 API 直测（PUT 带 mobile=`***` → GET 原值不变）→ my-scope columns API 断言 → 清扫（e2edpu 残留零 + admin 种子完好）+ 环境卫生（DC-VERIFY 同款）；黑盒纪律（禁 import 前端内部代码）；Git Bash 中文 GBK 坑——API 造数经浏览器 fetch 或 ASCII。

**验收**：有头跑全场景 PASS；`grep -c '[FAIL]'` == 0（exit code 不可信——记忆惯例）；结束清理无残留并报告。

### C2 既有回归

**文件**：零改（预期零维护——契约 §8）。

**要点**：依次 `npm run e2e:dataperm` / `e2e:dataperm-component` / `e2e:dataperm-predicate` / `e2e:dataperm-operators`（leave/bpmn_approval 列动作与提示条不破——拍板试点验收主体）+ `e2e:user`（用户页既有流）+ `e2e:dict`（ProTable 页零关证明）。

**验收**：六脚本全绿（同 FAIL 计数判定）；若出现维护点按契约 §8 预期排查（不应出现——出现即回报主控核对契约）。

## D 联通与收口章

### D1 联通 + 全量回归 + 视觉核对

**要点**：前置栈 system(9202)→sso(9201)→bpmn(9203)→gateway(18080)（java -jar 绝对路径，agent 自管）；5173 探活（用户 dev server 在跑则复用绝不 kill）；联通 `curl http://localhost:5173/api/system/demo/ping`；全量 `cd cloud-e2e && npm run e2e`（聚合成员不含本轮新脚本——C1 独立跑）；截图 ≥4 张（用户页全列/隐藏列态/脱敏列态/配置台 columnOnly 文案）→ Read 上传 → analyze_image 核对 → **核对完即删**；验收完 kill 本轮所起服务。

**验收**：全量聚合 grep FAIL==0 + C1 独立绿 + 视觉核对结论 + 起停服务记录齐；汇报文件清单/build/e2e 计数/冒烟证据。

## 移交后续阶段的备忘（下一阶段规划前必读）

1. **`***` 哨兵逐列重审义务**：哨兵安全性论证绑定 mobile/email 格式校验值域——未来扩可配列（尤其自由文本列）必须重审（设计 §11.2），否则用户真值恰为 `***` 会被误忽略
2. **mobile/email 唯一性未约束**：唯一性诉求出现（短信找回等）再评估 uk + 存量清洗
3. **user 页顺迁 ProTable**：独立小轮（ProTable 收口后）；列 v-if 届时搬进插槽，形态不变
4. **usePermColumns 推广 leave 页**（title 隐藏整列消失）是自然续作——本轮 leave 页刻意不动（回归验证既有形态），推广一页一议
5. **MCP 面行级语义**：JWT 主体通道已就绪（A6），未来 user 资源开行级时 MCP 求值自动跟随，无需再动 starter
6. **错误码账本**：system 段现用至 3042，本轮零新增；下轮 3043 接续并在契约再声明
7. **列清单与分页并行非原子**：一层渲染内的极小错配窗口（设计 D66 已论证无害）；严格一致诉求出现时评估响应包络带列元数据方向（本轮按拍板排除）
8. **清空 mobile/email 不可达**：空串归一 null=不更新（契约 §4.1）——管理员无法把手机号/邮箱清回未填态，只能改值；显式清空诉求出现时评估（显式清空标志或空串语义翻案）
