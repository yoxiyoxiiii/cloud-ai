# 列级数据权限驱动前端动态列显示技术方案（全栈 A 级：my-scope 结构化 + user 资源注册 + 用户页动态列）

- 日期：2026-10-22
- 状态：**定稿**——架构-agent 出品，配合拍板结论四项 + 主控自决四项（2026-10-22，阶段一《需求分析》回报后）；契约 `docs/superpowers/contracts/2026-10-22-dataperm-column-visibility-api.md`（增量修订挂 2026-10-10-data-permission-api 体系）、计划 `docs/superpowers/plans/2026-10-22-dataperm-user-column-visibility.md`
- **修订（2026-10-22 实施回流）**：D62 两处机制性误判致 MCP 入口列脱敏不生效（明文泄露），翻案定案见 §12（D67-D68 接续）——契约零改动，实现兑现既有承诺
- 拍板结论映射：① 接口形态 = **my-scope 增补结构化字段**（MyScopeVo 加机器可读列动作清单，wire additive，leave 页既有消费零影响）；② user 可配列 = **补 mobile/email 两列**（ALTER sys_user + VO/DTO/表单/校验/示例种子；**注册可配列仅 mobile、email**——nickname 编辑回填写库污染、account 删除/分配文案消费，均不注册）；③ 行级耦合 = **列级-only**（user 资源行档位与谓词不参与求值；契约写明 + my-scope 行范围报「不适用」+ 配置台文案注明）；④ 脱敏列语义 = **保留显示 `***`**（隐藏列整列消失、脱敏列列在值掩码，与 leave 页现状一致）
- 主控自决映射：前端消费 = **composable（usePermColumns）+ 页面列 v-if，ProTable 本体零改**；拉取时机 = **列表每次加载伴随重拉，失败降级显示全列**（值层防线在后端 null/`***`，不泄露明文）；user 页**本轮不顺迁 ProTable**（后续独立小轮）；e2e = **user 动态列独立脚本挂 dataperm 家族（e2edpu 前缀）+ 既有四 dataperm 脚本全量回归 + e2e:user / e2e:dict 回归**
- **与既有设计的关系**：数据权限域第六轮（基座 D1-D14 → 注册表重构 D15-D17 → 组件化 D18-D28 → 谓词 D29-D40 → 操作符 D41-D50 → 列处理注解化 D51-D58）的**消费端补全轮**——后端列动作引擎（求值+advice）前五轮已完整，本轮补「前端据结构化清单动态渲染列」的最后一环并注册第二个本地资源；决策编号 **D59-D66 接续**

## 1. 需求与范围

### 1.1 目标

用户管理页的列集合按当前登录人的数据权限列规则动态渲染：配置了「隐藏」的列整列消失（数据层该列恒 null），配置了「脱敏」的列保留显示、值为 `***`——同一用户列表，张三见 mobile+email 两列，李四只见其一或全无。前端驱动源 = my-scope 增补的**机器可读列动作清单**。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| DDL | `sys_user` 增 `mobile`/`email` 两可空列 + admin 示例值种子；增量脚本 + 基线 `cloud_system.sql` 同步 |
| 资源注册 | `user` 资源（**列级-only**：可配列 mobile/email，无行级）；`DataPermResources` 增 `registerColumnOnly` 注册形态 |
| 求值器 | columnOnly 资源分支：行规则与谓词不参与收敛（dataScope 恒 all）、narrative 注明「该资源仅列级生效」 |
| my-scope | `MyScopeVo` 增 `columns`（`[{columnKey, action}]`，术语与既有 ColumnRuleVo/ColumnDecisionVo 对齐）；columnOnly 资源 scopeLabel 新取值「不适用（该资源仅列级权限）」 |
| resources | `DataPermResourceVo` 增 `columnOnly` boolean（配置台禁用行档区与文案的数据源） |
| user 读路径 | `SysUserManageService.pageList/findById` 求值 + 接线列上下文（@DataPermVo 白名单生效）；mapper XML 零行过滤改动 |
| 写路径防污染 | update 对 `***` 字面量按「不修改」处理（哨兵语义）+ save/update 格式校验；前端表单预填检测脱敏置空 |
| MCP 上下文 | `McpAuthFilter` 三关全过后构建 SecurityContext（JWT 主体即规则主体）——MCP 面列动作与 REST 面一致 |
| 前端 | `usePermColumns` composable + user 页 mobile/email 列 v-if + UserFormDialog 两输入框（预填脱敏置空）+ RuleConfigDialog columnOnly 禁用与文案 |
| e2e | 新脚本 `run-dataperm-usercolumn-e2e.mjs`（e2edpu 前缀）+ 既有回归 |

### 1.3 本轮不落（红线 + 移交）

**零变更红线**：行级全链（RowFilter/mapper XML 行过滤/留痕表结构/窄契约 Feign）——user 页 mapper **不增任何行过滤条件**；`***`（MASK）与 columnSummary 三处冻结点；`leave` 资源注册行与 leave 页（回归验证不破即可）；ProTable 组件本体；my-scope 既有 `scopeLabel`/`columnSummary` 字段语义（仅 additive）；错误码零新增（见 §10）。移交项见 §11。

## 2. 现状核实（2026-10-22 逐项复核，阶段一实证）

1. **sys_user 无 mobile/email/age**：实体仅 id/account/nickname/deptId/password/status/isBuiltin + 审计列——需求原文「username、age」在本系统对应 account、nickname，且均被拍板排除出可配列；**敏感列须本轮新建**（拍板②）
2. **可配列 D17 硬约束**：注册列必须是 VO 真实实例字段且 String 类型（`DataPermColumns.assertDeclared` 启动断言）——mobile/email 新建为 String，天然满足；status(Integer)/deptId(Long)/时间列不可注册（机制现状，本轮不扩）
3. **SysUserVo 返回面三入口**：`GET /system/user/page`（pageList）、`GET /system/user/{id}`（findById→convert）、**MCP 工具 `system_query_users`（SystemMcpTools 委托同一 pageList）**——标 @DataPermVo 后 advice 对三者全部生效；三入口恰共用两个 Service 方法，接线两处全覆盖
4. **MCP 链路现状无 SecurityContext**：`McpAuthFilter`（HIGHEST_PRECEDENCE）三关全过后只把 `McpPrincipal`（userId/account/perms）放 request attribute；`McpPermissionAspect`/`McpPermGuard` 从 attribute 读权限——**`SecurityUtils.currentUser()` 在 MCP 路径返回 null**（直连 9202 无网关注入的 X-User-* header，HeaderAuthFilter 构建匿名）。若不补，pageList 求值得 account=null → 列规则查不到 → 空 scope 全可见：**mobile/email 明文出 MCP 面**（列权限语义被绕过）
5. **HeaderAuthFilter 与 McpAuthFilter 嵌套时序**：McpAuthFilter 先跑（HIGHEST），若它构建 SecurityContext，内层 HeaderAuthFilter 无 X-User header 时早退**不清 context**（仅自身 finally 清，时序在响应回程、controller 已执行完）——MCP 构建的 context 生命周期覆盖 controller 执行，方案可行（D62）
6. **写回污染实证**：UserFormDialog 编辑回填 `props.user.mobile` 并在提交 payload 携带（现 nickname 同款）；后端 `update()` 现为「请求值直 set + mapper 动态 `<if>` null 跳过」——`***` 非 null 会穿透写库
7. **求值器方法面**：`evaluate`（真实+恒留痕）/`evaluateFor`（账号显式+留痕）/`evaluateForSelf`（my-scope，不留痕）/`explain`（模拟不留痕）——user 读路径用 `evaluate`（真实决策可观察原则）；`doEvaluate` 行/列收敛两段独立（resolveRowFilter / resolveColumns），columnOnly 分支可干净插入
8. **谓词校验链天然拦截**：谓词 field 必须 ∈ 资源 filterColumns（3035）——user 资源 filterColumns 为空清单，**任何谓词配置自动 3035 拒绝，零新校验码**
9. **resources/my-scope 转换单点**：`DataPermConvert.toMyScopeVo`（scopeLabel+columnSummary）与 `DataPermManageService.listResources`——两处 additive 即完成 wire 增量
10. **配置台数据源驱动**：RuleConfigDialog 资源下拉/列配置/条件段全部由 resources 接口驱动——`columnOnly` 下发后前端仅需禁用+文案，结构零改
11. **错误码账本**：system 段现用至 3042（2026-10-12 列处理注解化轮占用）；本轮复用 1002/3034/3035，**零新增**
12. **composable 目录惯例在**：`cloud-web/src/composables/`（useStickToBottom.ts 先例）；constants 已有 ACTION_HIDDEN=0 / MASKED=1（契约 §7 前端常量）

## 3. 数据设计

### 3.1 sys_user 增量（`cloud-base/scripts/sql/2026-10-22-dataperm-user-columns.sql` + 基线 `cloud_system.sql` 同步）

```sql
ALTER TABLE sys_user
    ADD COLUMN mobile VARCHAR(20) DEFAULT NULL COMMENT '手机号（可空=未填；数据权限资源 user 的可配列，可被列规则脱敏为 *** 或隐藏为 null）' AFTER nickname,
    ADD COLUMN email VARCHAR(64) DEFAULT NULL COMMENT '邮箱（可空=未填；数据权限资源 user 的可配列，可被列规则脱敏为 *** 或隐藏为 null）' AFTER mobile;

-- admin 示例值（冒烟/e2e 直观锚点；ASCII 值无 GBK 坑）
UPDATE sys_user SET mobile = '13800000001', email = 'admin@example.com' WHERE id = 1 AND deleted = 0;
```

**索引审查（规范强制）**：零新索引——mobile/email 本轮无任何查询路径（列表无该两列筛选、无查重、无关联），低选择性列建索引违反规范；不做 uk 的取舍：联系信息非登录凭证（account 才是身份键），且 e2e 高频造数同值会撞 uk，唯一性诉求出现时再评估（§11 记档）。

### 3.2 种子影响面

仅上述 UPDATE 一行（示例值）；**sys_menu 零新增**（my-scope 免权限注解、配置台沿用既有 `system:dataPerm:*`、mobile/email 表单字段不涉新权限标识）——既有导航断言零维护。

## 4. 设计决策（D59-D66，接续 D1-D58）

### D59 结构化清单形态：MyScopeVo 增 `columns` 数组，术语对齐既有 ColumnDecisionVo

- 形态：`columns: [{columnKey: string, action: number}]`，**复用 `DataPermExplainVo.ColumnDecisionVo` 内嵌类**（字段名/类型与配置台 ColumnRuleVo、explain 列决策逐字同源）——全域 columnKey/action 一套术语，无第二命名体系
- 双清单（hidden/masked 两组）方案否决理由：wire 层引入新形态术语，前端转 Set 的便利在 composable 内部一行可得，不值当契约分裂
- 取值：无列动作时 `columns=[]`（必返空数组而非 null——机器消费 `length===0` 即全显示，免 null 判断）；`columnSummary` 人读摘要串**保留不动**（leave 页提示条零影响，两口径同源于同一 ColumnScope 对象，无漂移面）
- 兼容性：additive 增量——前端旧类型不消费新字段，wire 逐字节兼容；幂等（GET）；全域无缓存
- 实现：`DataPermConvert.toMyScopeVo` 增列转换（columnScope 的 hidden/masked 集合 → 按 columnKey 字典序稳定输出）

### D60 user 资源列级-only：registry `columnOnly` 标记 + 求值器分支收敛（不是读路径丢弃）

- **registry 形态**：`ResourceDef` 增 `columnOnly` 组件；新增 `registerColumnOnly(resource, voClass, columns)` 注册行（共用 validateRegistration + assertDeclaredColumns 断言 + **强制 filterColumns 为空清单**——行级不可配是注册态约束）；既有 `register` 签名零变化（columnOnly 恒 false）
- **求值器语义**（消费收敛点在 doEvaluate，不是各读路径各自丢弃）：columnOnly 资源 → 行段直接 `dataScope=all()` + `rowFilter=rowAll()` + `hitRules=[]`（行规则不收集——「不参与」的直接体现），列段照常（resolveColumns 需 roleIds/userId，正常求值收敛），narratives 增一句「该资源仅列级权限生效，行范围不适用」
- **为何收敛在求值器而非读路径丢弃 dataScope**：my-scope/explain/留痕/读路径四面自动同语义——若读路径各自丢弃，my-scope 仍会如实报行范围（失真）、留痕记 accounts=N（误导排查）；收敛在求值器后，留痕 scope_summary 恒 `all`、rule_digest 行规则不出现，与「行级不生效」的契约声明自洽
- **读路径红利**：dataScope 恒 all = 无行过滤 = **SysUserMapper XML 零行级改动**（不增 WHERE、不增参数）
- **保存端语义**：rowScope 照收照存（前端对 columnOnly 资源默认 SELF 且行档区禁用——存库值不误导求值，配置台有文案）；**谓词零新校验**——user 资源 filterColumns 空清单，谓词 field 校验链既有 3035 自动拒绝（现状核实 #8）
- **my-scope 联动**：columnOnly 资源 `scopeLabel` 固定输出「不适用（该资源仅列级权限）」（resolveScopeLabel 首位分支）——修正「报仅自己但页面全量」的自查失真；columnSummary/columns 照常

### D61 user 读路径接线：pageList/findById 两处 evaluate + set，mapper 零行过滤

- `pageList`：`evaluate(USER, LIST, null)` → `DataPermColumnContext.set(USER, decision.getColumnScope())`（空 scope 也 set——接线声明语义）→ 原查询；`findById` 同款（DETAIL + businessKey=id）
- **留痕语义**：user/page 每次分页落一条留痕（真实决策恒留痕原则不变）——column_summary 如实记录列结论，行段恒 all（D60），量级与 leave 页同级可接受
- detail 链补齐：`SysUserConvert.toVo` 补 mobile/email 两 setter（原生 setter 逐字段规范）；`SysUserMapper.findById` select 列补两列
- **风险面收口**：SysUserVo 标 @DataPermVo 后，凡返回树含 SysUserVo 的响应（page/detail/MCP 工具）都必须有上下文——三入口恰共用两 Service 方法，接线两处即全覆盖（现状核实 #3）；未来新增返回 SysUserVo 的端点漏接线 = 3042 显性炸（fail-closed 既有护栏），不会静默明文

### D62 MCP 入口上下文：McpAuthFilter 三关全过后构建 SecurityContext（JWT 主体即规则主体）

- **机制**：loadPrincipal 成功后，用 McpPrincipal 同源信息构建 `LoginUser`（userId/account/permissions）+ authorities 塞 `SecurityContextHolder`，chain 外层 try/finally 清理（与 HeaderAuthFilter 同款无状态纪律）；`McpPrincipal` request attribute **保留不动**（@McpRequirePerm 切面照旧从 attribute 读——双通道并存互不依赖）
- **嵌套时序已论证**（现状核实 #5）：McpAuthFilter（HIGHEST_PRECEDENCE）先 set；内层 HeaderAuthFilter 无 X-User header 早退不清 context、其 finally 清理时序在响应回程——controller 执行期 context 存活，方案成立
- **效果**：MCP 路径 pageList 求值拿到的就是 JWT 主体（真实登录人）→ 列规则按该主体求值 → **MCP 面 mobile/email 与 REST 面同脱敏同隐藏**（system_query_users 返回 PageResult<SysUserVo>，advice 统一应用）；留痕 account=JWT 主体，审计一致
- **改动落点**：`cloud-common-mcp-starter` McpAuthFilter 单文件 additive（约 10 行）；pom 需 spring-security-core（starter 现依赖 security-starter 的 JwtUtil——同源已在 classpath，无新依赖；实现时验证）
- **实现时验证步骤**（计划 B6 强制）：① 单测：McpAuthFilter 构建后 `SecurityUtils.currentUser()` 非 null 且字段等于 principal；② 真连接冒烟 `smoke_mcp_system.py` 扩展——配列规则（mobile=脱敏）→ MCP 查询断言 mobile=`***` → 清规则复验原值；③ @McpRequirePerm 拒绝路径回归（切面行为不受 SecurityContext 影响）
- **备选记档**（否决）：读路径双源取主体（Service 层判 McpPrincipal）——业务 Service 硬编码 MCP 特判，依赖方向反了；MCP 路径不生效（currentUser=null 空 scope 全可见）——mobile/email 明文出 MCP 面，列权限语义被绕过，与「列动作全域一致」相悖

### D63 写回污染双保险：后端 `***` 哨兵忽略（机制级）+ 前端预填检测置空（UX）

- **后端（主防线）**：`update()` 对 mobile/email 值等于 `ColumnScope.MASK`（`***`）时按 null 处理（null 走既有动态 `<if>` 不更新——「不修改」语义）；新增路径 save 不适用（新用户无脱敏行可回填，`***` 作为新值被格式校验拦截）
- **安全性论证**：`***` 不在 mobile/email 合法值域（手机号 11 位数字、邮箱必含 @，格式校验兜底）——哨兵无歧义；值域交叠场景（某列合法值可能等于 `***`）不适用本轮两列，未来扩列时逐列重审此论证
- **前端（体验层）**：UserFormDialog 预填时检测行值 `=== MASK` → 置空 + placeholder「已脱敏，留空保持不变」；填新值=修改、留空=不修改——用户不会把 `***` 误当真实值提交
- **取舍**：仅后端（UX 差：脱敏用户打开编辑看到 `***` 以为真值）；仅前端（API 直调可绕过）；双保险兼得，前后端口径一致：**`***` 是脱敏标记，永远不是合法新值**
- nickname/account 等未注册列无此问题（永不被改写），双保险仅覆盖 mobile/email 两字段

### D64 mobile/email 校验口径：Service 层手工校验 + hutool Validator，非空才验，NULL=未填

- 校验位置：`SysUserManageService.save/update` 既有手工校验链尾部追加（项目无 validation-api 注解风格，沿 1002 手工抛惯例）；hutool `Validator.isMobile` / `isEmail`（5.8.32 已在依赖矩阵，中国手机号口径符合本系统场景）
- 语义：null/空串=未填不校验（存量用户与「不想填」都合法）；非空且格式无效 → 1002「手机号格式无效」/「邮箱格式无效」
- 更新链顺序：先 D63 哨兵归 null，再格式校验——`***` 不会进校验（哨兵优先）
- 存量语义：旧行两列 NULL，列表展示前端降级 `-`（与 createTime null 同款处理）；不做数据回填

### D65 前端消费：usePermColumns composable + 列 v-if + 每次加载伴随重拉 + 失败降级全列

- **composable**（`cloud-web/src/composables/usePermColumns.ts`）：入参 resource；暴露 `load()`（拉 my-scope → columns 转 hidden/masked 两个 Set）、`isHidden(key)`、`isMasked(key)`；**load 失败 catch 后置空两 Set（=全显示）不抛**——值层防线在后端（隐藏 null/脱敏 `***`），降级显示全列不泄露明文（fail-open 安全论证）
- **拉取时机**（主控自决）：页面 load 函数内与分页请求并行调 `load()`（`void load()`，不阻塞表格）——与数据权限「每请求实时求值」语义对齐，规则变更后下一次翻页/刷新即同步列集合；不引入 onActivated/stale 窗口讨论
- **列渲染**：`<el-table-column v-if="!isHidden('mobile')">` ——ProTable 透传式本体零改（slot 内页面写列，v-if 天然可用，主控预判成立）；脱敏列**不加 v-if**（保留显示，值 `***` 后端已处理，拍板④）
- **mask 常量共享**：前端 `constants` 已有 ACTION_HIDDEN/ACTION_MASKED；`***` 字面量在前端仅 UserFormDialog 预填检测用——加 `DATAPERM_MASK = '***'` 常量进 constants（禁魔法串），与后端 ColumnScope.MASK 语义对齐但不共享代码（黑盒两端各自常量，契约冻结点同款纪律）

### D66 脱敏列显示语义与两层共存矩阵（拍板④落定）

| 列规则 | 数据层（wire，冻结不动） | 列层（前端 v-if） | 用户所见 |
|---|---|---|---|
| 无动作 | 原值 | 列在 | 原值 |
| 隐藏（action=0） | 字段 null | **列消失** | 整列不可见 |
| 脱敏（action=1） | 值 `***`（null 值保持 null） | 列在（**不 v-if**） | 列头在、值掩码 |
| 清单拉取失败（降级） | 后端照常应用 | 全列显示 | 隐藏列显示空值、脱敏列显示 `***`（无明文泄露） |

错配窗口（规则刚变更、列清单尚未重拉）：值层已按新规则返回、列层用旧清单渲染——多显示列=值已 null/`***`（无害），少显示列=数据暂时看不见（体验问题非安全问题，下次加载自愈）。

## 5. 数据流（user 页全链 + MCP 链）

### 5.1 REST 链（用户管理页）

```
前端用户页 → GET /system/user/page（@PreAuthorize system:user:list）
  → SysUserManageService.pageList：
      decision = evaluator.evaluate("user", "list", null)      // columnOnly：行段 all 短路 + 列规则正常收敛；留痕 1 条
      DataPermColumnContext.set("user", decision.getColumnScope())
      userMapper.pageList(page)                                 // 零行过滤（dataScope 恒 all）
  → DataPermColumnAdvice：PageResult.rows → SysUserVo（@DataPermVo）命中
      → mobile 脱敏=*** / email 隐藏=null（wire 冻结形态）
并行：页面 load 函数 void usePermColumns('user').load()
      → GET /system/data-perm/my-scope?resource=user            // 不留痕
      → MyScopeVo{scopeLabel:"不适用（该资源仅列级权限）", columns:[{email,0},{mobile,1}]}
      → email 列 v-if 消失、mobile 列保留
```

### 5.2 MCP 链（system_query_users）

```
AI 侧 fastmcp → 直连 9202 /mcp（X-MCP-Client-Secret + Bearer JWT）
  → McpAuthFilter：三关全过 → McpPrincipal attribute + 【新增】SecurityContext(LoginUser=JWT 主体)
  → @McpRequirePerm("system:user:list") 切面（attribute 通道照旧）
  → SystemMcpTools.queryUsers → userManageService.pageList     // 同一方法，上下文接线自动生效
      → evaluate 以 JWT 主体求值 → 列规则同 REST 面收敛
  → advice 应用 → MCP 响应 mobile/email 与 REST 面同脱敏同隐藏
  → filter finally：SecurityContext 清理（HeaderAuthFilter finally 双清无害）
```

## 6. 错误处理

| 层 | 语义 | 失败形态 |
|---|---|---|
| my-scope 结构化字段 | additive，老前端不消费 | 无新错误路径；非法 resource 沿用 3034 |
| 求值 columnOnly 分支 | 行规则不参与 | 无规则/坏配置均短路 all，不炸读路径 |
| 读路径漏接线（未来新端点） | 返回树含 SysUserVo 无上下文 | 3042 fail-closed 既有护栏（R【2026-10-12】），本轮零改动 |
| update `***` 哨兵 | 脱敏标记≠合法新值 | 归 null 不更新（D63）；save 路径 `***` 被格式校验 1002 拦 |
| 格式校验 | 非空才验 | 1002「手机号格式无效」/「邮箱格式无效」（文案进契约） |
| MCP 认证失败 | 既有三关 | HTTP 401 R JSON 不变；SecurityContext 仅在三关全过后构建（未认证无身份可建） |
| 前端列清单拉取失败 | 降级全列 | catch 置空 Set 不抛（值层后端防线，无明文泄露） |
| 留痕 | user/page 真实决策 | 恒落一条；insert 异常 catch 不抛（既有 D7 纪律） |

## 7. 测试策略

### 7.1 后端单测

- **DataPermEvaluatorTest 增量**：columnOnly 资源行段 all 短路（配了行规则也不参与）、列规则正常收敛（角色脱敏+用户隐藏并集宽松者胜）、narrative 含「仅列级生效」句
- **DataPermResourcesTest 增量**：registerColumnOnly 断言链（列 ⊆ VO String、filterColumns 强制空、重复注册失败）
- **SysUserManageServiceTest 增量**：pageList/findById set 上下文断言（resource+scope 值）；update `***` 哨兵（mobile=`***` → 库值不变）、`***`+合法新值混合（email=`***` 同时 nickname 正常更新）；格式校验（非法手机号/邮箱 1002、空值放行）；save 携带 `***` 被格式校验拦
- **McpAuthFilterTest 增量**：三关全过后 SecurityUtils.currentUser() 等于 principal（userId/account/perms）；未认证路径 context 空；chain 后清理
- **DataPermConvertTest 增量**：toMyScopeVo columns 转换（hidden 在前 masked 在后字典序稳定、空动作 `[]`）；columnOnly scopeLabel「不适用」分支

### 7.2 冒烟（真链路）

起 system+网关：pymysql 种子落库 → 配列规则（mobile=脱敏 / email=隐藏）→ `curl /system/user/page` 验证 mobile=`***`、email=null、account/nickname 原值 → my-scope?resource=user 验证 columns 结构化输出与 scopeLabel「不适用」→ `***` 哨兵：PUT /system/user 携带 mobile=`***` → GET 复验原值 → MCP 冒烟（smoke_mcp_system.py 扩展，D62 验证步骤）→ 清规则复验全可见。

### 7.3 e2e（新脚本 + 回归）

- 新脚本 `run-dataperm-usercolumn-e2e.mjs`（e2edpu 前缀，dataperm 家族惯例）：前置探测（resources 含 user 且 columnOnly=true / my-scope 结构化 / user 分页）→ 建数（角色挂用户管理菜单闭包 + 列规则 mobile=脱敏+email=隐藏 + 测试用户）→ 场景：脱敏用户视角 email 列头不存在（DOM 断言）+ mobile 列在且 admin 行 `***`；无规则用户视角全列原值；admin 改规则（email 隐藏→脱敏）→ 测试用户免重登刷新列集变化（即时生效）；`***` 哨兵 API 直测（PUT 带 `***` → GET 原值不变）；my-scope columns API 断言 → 清扫（e2edpu 残留零 + admin 种子规则完好）
- 回归：e2e:dataperm / e2e:dataperm-component / e2e:dataperm-predicate / e2e:dataperm-operators（leave 资源列动作不破——拍板试点验收主体）+ e2e:user（用户页既有流 + 新字段表单）+ e2e:dict（ProTable 试点页）；判定只认 `grep -c '[FAIL]'` == 0

## 8. 索引审查（规范强制章节）

本轮唯一 DDL 为 sys_user 加两可空列（§3.1）——无新查询路径（列表无该两列筛选/查重/关联/排序），零新索引；既有查询路径（pageList 全量分页、findById 主键）索引命中零变化，无增量 ALTER。

## 9. 红线核对清单（实现与验收共同遵守）

1. wire 冻结点不动：隐藏=null、脱敏=`***`、columnSummary 格式——既有 e2e JSON 断言零维护通过
2. my-scope/resources 增量全部 additive：旧前端（leave 页）零改动可运行
3. `register` 既有签名与 leave/bpmn_approval 注册行零变化；`registerColumnOnly` 是新增重载路径
4. SysUserMapper XML 零行过滤条件（columnOnly 语义由求值器承担，不进 SQL）
5. McpPrincipal attribute 通道与 @McpRequirePerm 切面零改动（SecurityContext 是并行新增通道）
6. ProTable.vue 文件零 diff；user 页不引入 ProTable
7. 错误码零新增（复用 1002/3034/3035）
8. 前端按需引入红线（组件内 el-* 模板自动解析）；TS strict 禁 any/裸 as
9. sys_menu 零新增——导航断言零维护
10. 实现顺序：**ProTable 轮收口提交之后**（工作区未提交成果不构成本轮起点）

## 10. 错误码账本

本轮**零新增**（复用：1002 格式校验文案两条新句、3034 资源校验既有、3035 谓词字段校验既有）；system 段现值仍至 **3042**，下轮 3043 接续。bpmn 段仍至 4018。

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **mobile/email 唯一性未约束**：联系信息非身份键 + e2e 造数冲突风险；出现唯一性诉求（如短信找回密码）时再评估 uk + 存量清洗
2. **`***` 哨兵逐列重审义务**：D63 的「哨兵不在合法值域」论证绑定 mobile/email 两列的格式校验——未来扩可配列（尤其自由文本列）时必须重做该论证，否则哨兵歧义（用户真值恰为 `***` 会被误忽略）
3. **user 页顺迁 ProTable**：独立小轮（ProTable 轮收口后），届时列 v-if 写进 ProTable 默认插槽，形态不变
4. **列清单拉取与分页请求并行非原子**：极小窗口列层滞后值层一层渲染（D66 矩阵已分析，无害）；若未来要求严格一致，评估响应包络带列元数据方向（本轮按拍板排除）
5. **MCP 面行级语义**：columnOnly 资源 MCP 同样无行过滤（语义一致）；未来 user 资源若开行级，MCP 求值自动跟随（JWT 主体通道已就绪），无需再动 starter
6. **composable 推广**：usePermColumns 目前消费面 = user 页；leave 页动态列（title 隐藏整列消失）是自然续作——本轮 leave 页不动（回归验证既有形态），推广时一页一议
7. **错误码账本**：system 段现用至 3042；下轮 3043 接续并在契约再声明

## 12. D62 修订（2026-10-22 实施回流）：MCP 通道两处机制性误判与修复定案（D67-D68）

> 本节是 D62 的翻案与替代定案。D62 的**目标**（MCP 面列动作与 REST 面同主体同规则同改写）不变且正确，但其**机制论证**有两处误判，导致实现不生效。契约 §3.2「三入口同改写」承诺本就存在，本轮零契约改动，只让实现兑现承诺。

### 12.1 回流事实（缺陷现状）

- 现象：列规则（mobile=脱敏）生效期间，REST `GET /system/user/page` 与 `GET /system/user/{id}` 正确返回 `***`；同一时刻 MCP 工具 `system_query_users` 返回 admin 行 `mobile=13800000001` 明文——安全缺陷。
- 伴随铁证：system 日志出现 `数据权限决策留痕失败…account=null`（留痕表 `sys_data_perm_log.account` NOT NULL，insert 违约被既有 catch 吞）——即工具执行线程上 `SecurityUtils.currentUser()` 返回 null，求值空 scope 全可见。
- D62 验证步骤②（smoke 断言 mobile=`***`）实测 FAIL——回归探针已就位，本轮修复后必须转绿。

### 12.2 两处根因（源码级核实，2026-10-22 回流时复核）

**根因一：SecurityContext 活不到工具执行（D62 时序论证漏了安全链内置的置换层）**

运行时过滤器时序（注册序）：`McpAuthFilter`（HIGHEST_PRECEDENCE = Integer.MIN_VALUE）→ `springSecurityFilterChain`（DelegatingFilterProxy，注册序 -100）→ 链首 `SecurityContextHolderFilter`。装机 spring-security-web 6.5.11 的 `SecurityContextHolderFilter.doFilter` 源码：`setDeferredContext(securityContextRepository.loadDeferredContext(request))`——对 holder **整体置换**（丢弃先前 set 的一切，含 McpAuthFilter 放入的 authentication）。MCP 请求无 HTTP 会话、仓库无值 → deferred 解析为空 context → 工具体读到匿名。原 D62 只论证了「内层 HeaderAuthFilter 无 X-User header 早退不清 context」——该论断本身成立，但 `SecurityContextHolderFilter` 在 HeaderAuthFilter **之前**就把 context 换掉了；过滤器孤立单测（裸 capturing chain）链上没有这层置换，故全绿（假绿教训见 §12.6）。

**根因二：advice 机制不覆盖 MCP 出参（D62「advice 统一应用」不成立）**

MCP 工具出参路径（装机 spring-ai 1.1.8 源码）：`McpToolUtils` 的 sync 工具规格 callHandler 内部调 `toolCallback.call(...)`——`@Tool` 的 resultConverter（McpToolResultConverter）在**工具调用内部**已把返回对象序列化成 String，包进 JSON-RPC `TextContent`。且 `/mcp` 端点是 MVC `RouterFunction`（`WebMvcStreamableServerTransportProvider`），响应由 `ServerResponseResultHandler` 直写——`ResponseBodyAdvice`（DataPermColumnAdvice 的机制）只挂在 @ResponseBody/ResponseEntity 方法边界，RouterFunction 路径不经过。两层独立原因，任何一层都足以让 advice 失效；「converter 层不可行（mcp-starter→system-api 依赖方向反）」的原判断维持。

**修复可行性前提已核实——工具体在 servlet 请求线程执行**：① 配置默认 `spring.ai.mcp.server.type=SYNC`（autoconfigure `matchIfMissing=true`）→ sync 工具规格 callHandler 是同步内联函数（无 scheduler hop）；② `McpStreamableServerSession.responseStream` 无 subscribeOn/publishOn，`ServerResponse.sse` 消费者在请求线程 `.block()`；③ 经验佐证：@McpRequirePerm 切面在工具体上读 `RequestContextHolder`（ThreadLocal）取 McpPrincipal，2026-10-17 轮真连接冒烟正向路径通过。→ ThreadLocal 通道（SecurityContextHolder / DataPermColumnContext / RequestContextHolder）在工具体上全部可用，修复可基于既有 ThreadLocal 机制。

### 12.3 D67（翻案 D62 机制）：McpAuthFilter 注册序后移到安全链之后，上下文通道打通

- **定案**：`CloudMcpAutoConfiguration.mcpAuthFilterRegistration` 的 order 从 `Ordered.HIGHEST_PRECEDENCE` 改为 **-90**（依据：`springSecurityFilterChain` 注册序 = `SecurityProperties.DEFAULT_FILTER_ORDER`(-100)，本过滤器必须排其后；(-100, 0) 区间任取，取 -90 便于阅读，注释注明锚点）。
- **机制**：链首 `SecurityContextHolderFilter` 先跑（deferred=空、缓存失效）→ 安全链出站后 `McpAuthFilter` 执行 `getContext().setAuthentication(...)`——getContext() 先求值 deferred 得空 context 并缓存，setAuthentication 写入该缓存对象 → 从此处到工具体（同线程）无任何再置换点 → 工具体读到已认证 context。
- **过滤器代码零改**：三关认证 + `McpPrincipal` attribute + 构建 SecurityContext + finally 清理全部原样保留——原验证步骤①的过滤器层单测（三态断言）语义不变。
- **安全性等价论证**：401 拒绝路径仍在任何 MCP 处理之前生效（安全链对 /mcp 是 permitAll 纯透传，无拦截语义；三关顺序与 deny 响应逐字不变；GET/POST/DELETE 全覆盖不变）。直连伪造 X-User-* 场景反而更稳：HeaderAuthFilter（链内，时序在前）构建的伪 context 会被 McpAuthFilter 的**已验证**主体整体覆盖（setAuthentication 替换）。
- **留痕顺带修复**：工具体拿到真实主体 → `evaluate` 留痕 account=JWT 主体（D62 原预期兑现），「Column 'account' cannot be null」日志噪音消失。
- **否决备选记档**：
  - *挪进 security 链内*（`addFilterAfter` HeaderAuthFilter）：starter 须定制宿主 `SecurityFilterChain`，与「任意服务可引、宿主零改动」的组件形态冲突，宿主自建链时静默失效；
  - *切面建上下文*（@McpRequirePerm 切面从 attribute 构建 SecurityContext 包住 proceed）：权限切面单一职责被破坏；未标注解的工具（其他宿主合法形态）静默丢身份→fail-open；McpAuthFilter 既有 set 代码变死代码；
  - *业务层双源取主体*（Service/工具从 McpPrincipal attribute 读）：D62 已否决的同款（业务硬编码 MCP 特判），维持否决。

### 12.4 D68：MCP 出参列动作应用点 = 工具体直调 applier（fail-closed 对齐）

- **定案**：`SystemMcpTools.queryUsers` 在 `pageList` 返回后读 `DataPermColumnContext.get()`（pageList 按 D61 无条件 set，ThreadLocal 存活窗口覆盖工具执行全程），对 `page.getRows()` 逐行 `DataPermColumnApplier.apply(vo, decision.columnScope())`。`system_query_dict_items` 出参 `DictItemEntry` 非 @DataPermVo 白名单类，零改动。
- **fail-closed 分支**：decision==null（理论不可达——pageList 异常时工具早已报错；防御性保留对齐 advice 总纲）→ log.error + `BusinessException(3042, "数据权限列处理异常，响应已拒绝")`——REST 面 advice 转 `R.fail(3042)`，MCP 面经框架 isError 文本拒绝，语义同为「拒绝返回数据」，无原样返回分支。3042 与文案是 advice 冻结口径（system 段既有错误码），跨包引用不便则在工具类内以字面量 + 同源注释落地。
- **守护补强**：cloud-system `ArchitectureGuardTest` 增规则——@Tool 方法返回类型树可达 @DataPermVo 白名单类的，方法体必须调用列应用（复用既有文本检查模式，与 @McpRequirePerm 标注规则并排）。理由：MCP 面无 advice 兜底，未来新工具漏应用=明文泄露，构建期拦截。
- **否决备选记档**：
  - *converter 层应用*（cloud-system 侧自定义 resultConverter）：converter 经反射无参实例化、面向任意 Object 无类型信息，树遍历须从 advice 抽取重构——动 REST 关键路径，违「REST 面零影响」红线；mcp-starter 侧做则依赖方向反（common→system-api），原判断维持；
  - *Service 层急改写*（pageList 返回前应用）：破坏 D51-D55「Service 只声明上下文、advice 统一应用」分层——REST 面双重应用虽幂等但职责错位，属翻既有架构案，不做；
  - *MCP 通用 advice 等价物*（框架级拦截所有工具出参）：spring-ai 无该扩展点（resultConverter 是唯一出参钩子且已否决），等价自研框架层，超本轮范围。

### 12.5 零回归论证

- **REST 面零影响**：McpAuthFilter URL pattern 仅 `/mcp/*`（REST 路径不进该过滤器）；SystemMcpTools 不在任何 REST 调用链上；advice/applier/求值器/Service 零改动。验证=REST 冒烟（配规则窗口内两入口仍 `***`）+ e2e 全量回归零红。
- **MCP 既有能力零回归**：三关认证（HTTP 401 + R JSON）不动；McpPrincipal attribute 通道与 @McpRequirePerm 切面（CGLIB 代理路径、字面量切点约束）不动；McpToolResultConverter 的 Long→String/日期口径不动（列应用发生在序列化前的 VO 树上，converter 输入已是改写后对象）。
- **契约零改动**：§3.2 三入口承诺本就存在；错误码复用 3042 既有值，零新增、零分段接续（下轮仍 3043 起）。

### 12.6 测试策略升级（收口「孤立单测假绿」缺口）

- **教训**：过滤器孤立单测（A6 裸 capturing chain）无法覆盖「链上存在置换层」——过滤器层语义正确 ≠ 运行时生效。修复验证必须含集成级。
- **starter 侧新增集成级测试**：用**真实** `SecurityContextHolderFilter`（`new SecurityContextHolderFilter(new RequestAttributeSecurityContextRepository())`）前置 + `McpAuthFilter` 后置的双层链，断言最内层 `SecurityUtils.currentUser()` 为 JWT 主体（userId/account/perms）、链返回后 holder 已清——精确复刻生产过滤器拓扑。另加反证用例：旧序（McpAuthFilter 先 + SecurityContextHolderFilter 后）最内层 currentUser()=null——把根因一固化成回归探针，防未来改回 order。
- **cloud-system 侧**：SystemMcpToolsTest 增两用例（上下文 set → rows 应 mask/hidden；上下文 null → 抛 3042）；ArchitectureGuardTest 增 D68 守护规则（验收含「删掉 applyColumnActions 调用守护测试转红」的反证）。
- **真链路验收**（D62 原验证步骤②兑现）：`smoke_mcp_system.py` 现存 FAIL 断言全转绿（规则窗口 mobile=`***`、清规则复原原值、无权限拒绝、三关 401）；system 日志不再出现 account=null 留痕失败；e2e 回归（dataperm 四脚本 + e2e:user + e2e:dict）`grep -c '[FAIL]'` == 0。

### 12.7 移交备忘接续（并入 §11 清单）

8. **MCP 工具出参列动作是逐工具义务**：MCP 面无 advice 兜底——未来新增返回 @DataPermVo 白名单树的 MCP 工具，必须在工具体应用列动作（守护测试构建期强制，§12.4）。
9. **MCP server 形态锁定 SYNC**：`spring.ai.mcp.server.type=ASYNC` 下工具执行 hop 到 boundedElastic 线程池，ThreadLocal 通道（SecurityContextHolder/DataPermColumnContext）整体失效——D67/D68 基于 SYNC 定型；若未来切 ASYNC 形态须重估两决策。
