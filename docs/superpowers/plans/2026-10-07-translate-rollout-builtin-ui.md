# 翻译铺开 + 内置 UI 强化 + e2e 两债清偿 实施计划（全栈：后端章 ∥ 前端章 + e2e 章）

- 日期：2026-10-07
- 需求：① 四 VO 翻译铺开（role/menu/dictType/dictData 对齐用户页样板）+ common_status 内置种子；② 五域 VO additive `builtin` + 徽标/按钮禁用；③ run-nav-e2e 竞态守卫（副本漂移根除）；④ harness logoutViaUi 时序加固（双守卫）
- 设计：`docs/superpowers/specs/2026-10-07-translate-rollout-builtin-ui-design.md`（D1-D9；重点：D1 种子 id 非契约/增量不写显式 id、D3 禁用=矩阵镜像、D4 断言双层迁移、D7 双守卫）
- 契约：`2026-10-07-translation-api.md` **§8（v1.1 铺开节）** + `2026-10-07-builtin-protection-api.md` **§7（v1.1 UI 强化节，关闭 §5.4/§5.5）**——字段表以契约为准，本计划不重复
- 轨道：**全栈 A 级**——后端章 B1→B5 ∥ 前端章 F1→F6 **两章独立可并行**；e2e 章 E1（基建债修）可先行并行，E2/E3 在 B5+F6 后

```
主控：① 派发 backend-agent（B1→B5）∥ frontend-agent（F1→F6）∥（可先行）e2e E1 债修
      ② B1 落库 + B4 全绿 → B5【卡点】用户重启 9202 + curl 验收通过
      ③ F6 双 build 绿 + dev 联调（5173 agent 自管）
      ④ e2e：E2 四脚本断言迁移 → E3 全量回归 + scaffold/user ×5 重复跑
      ⑤ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

- 总红线：保护矩阵/错误码（3013-3017）/权限标识/事务/索引零变化；MapperXmlBindingTest 计数 **46 不变**（零 mapper 语句变更）；既有端点 additive-only；**user_status 与 admin 种子零触碰**；SysUserVo 仅允许 +builtin 一处改动

## 后端章（cloud-base/，backend-agent，B1→B5 串行）

> 规范来源：CLAUDE.md 编码规范 + `/backend-spec` 技能（**索引：本轮零增量——builtin/statusLabel 均不进查询条件；事务：零新增**）。ArchitectureGuardTest 全执法（新字段 javadoc/两行式自动受检）；common 模块零触碰（translate-starter 机制复用，不改本体）。

### B1 common_status 内置种子（增量 SQL + 基线同步 + 执行落库）

- 文件：
  - `cloud-base/scripts/sql/2026-10-07-common-status-seed.sql`（新建，增量——**不写显式 id**，设计 D1：存量库 AUTO_INCREMENT 已被多轮 e2e 消耗，显式 id 会主键冲突；与 user_status 先例（空库显式 id）的关键差异）
  - `cloud-base/scripts/sql/cloud_system.sql`（改，基线同步：user_status 种子块之后追加 common_status 块，**显式 id=2（类型）/3,4（项）**——全新建库干净可控；头注释注明「与增量脚本语义等价，存量环境 id 自动分配，**id 非契约内容**（保护按 is_builtin 判定）」）
- 增量脚本内容（照录）：

```sql
USE cloud_system;
-- 幂等：INSERT 不幂等——执行前核对种子未落（应 0 行）：
--   SELECT id FROM sys_dict_type WHERE dict_key = 'common_status' AND deleted = 0;
-- 不满足时停下排查，不盲跑。基线已同步本 3 行，新环境直接跑基线无需本增量。
-- 红线：不动 user_status 与其他任何数据；不进 sys_menu（无新权限节点）。

INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
VALUES ('通用状态', 'common_status', 0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '正常', '0', 1, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'common_status' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '停用', '1', 2, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'common_status' AND t.deleted = 0;
```

- 执行通路：java 单文件源码 + mysql-connector-j（B1 惯例；临时文件置 scripts 外用后即删）；中文 VALUES 经 Java UTF-8 连接无 GBK 陷阱
- 验收（同通路回查）：dict_key='common_status' 类型恰 1 行（is_builtin=1，status=0）；其项恰 2 行（正常"0" sort=1 / 停用"1" sort=2，均 is_builtin=1）；**user_status 原样**（1 类型 2 项）；sys_role/sys_menu/sys_user 行数与 is_builtin 分布不变；**本任务不起停任何服务**

### B2 四 VO 翻译注解 + 译文字段（SysUserVo 零触碰）

- 文件（改 4 个）：
  - `vo/SysRoleVo.java` / `dto/MenuTreeNode.java` / `vo/SysDictTypeVo.java` / `vo/SysDictDataVo.java`：各标 `@TranslateVO`，`status` 标 `@DictTrans(dictKey = "common_status", labelField = "statusLabel")`，`createBy`/`updateBy` 各标 `@UserTrans(labelField = "createByName"/"updateByName")`；各 +3 个 String 字段（statusLabel/createByName/updateByName，javadoc 引契约 translation-api §8.1，注明 statusLabel 键为 common_status）
- **SysUserVo 本任务零触碰**（user_status 口径维持契约 §3 原文）
- 实现时验证（设计 D5）：MenuTreeNode 在 dto 包——翻译 Advisor 按注解扫描应与包名无关；若 `/menu/tree` 译文未回填（Advisor 有包过滤），最小调整并回报所选通路，**不改 translate-starter 机制本体**
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 编译绿；ArchitectureGuardTest 绿；译文实际形态留 B5 curl 实测

### B3 五域 builtin 字段传递

- 文件（改 10 个）：
  - `vo/SysRoleVo.java` / `dto/MenuTreeNode.java` / `vo/SysDictTypeVo.java` / `vo/SysDictDataVo.java` / `vo/SysUserVo.java`（**SysUserVo 本轮唯一改动**）：各 +`private Boolean builtin;`（javadoc 引保护契约 §7.1；Boolean 包装型 + 字段名 builtin → Jackson 出 `"builtin"`，不用 isBuiltin——getIsBuiltin 会序列化成 "isBuiltin"）
  - `convert/SysRoleConvert.java` / `SysUserConvert.java` / `SysDictTypeConvert.java` / `SysDictDataConvert.java`：各 +1 行 `vo.setBuiltin(Integer.valueOf(1).equals(entity.getIsBuiltin()));`（null-safe：isBuiltin null → false）
  - `util/MenuTreeBuilder.java`：`toNode` +1 行同款传递
- 审查确认（零改动项）：实体接参端点（role/menu 的 POST/PUT 以 SysRole/SysMenu 实体接收）body 含 `isBuiltin` 会被 Jackson 绑定进实体，但 **mapper INSERT/UPDATE 列枚举不含 is_builtin（上轮 B2 红线维持）→ 不落库**，结构性防篡改不因出参放开而破坏；SaveRequest 系 DTO 无 builtin 字段
- 单测：`test/util/MenuTreeBuilderTest.java` 增补 builtin 传递断言（builtin=1 实体 → 节点 true；0/null → false；既有孤儿/排序断言零改动）；四个 Convert 无既有测试面，不为 1 行 setter 开新测试面（覆盖靠 B5 curl + e2e）
- 验收：cloud-system 单测全绿（既有用例零改动应全绿）；grep 核对五 VO / 四 Convert / Builder 各恰一行传递

### B4 全量构建 + 守护核对

- 命令：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install`
- 核对：MapperXmlBindingTest 计数 46 注释算式不变（本轮零 mapper 语句变更）；ArchitectureGuardTest 全绿；全部单测绿；surefire 3.2.5 生效
- 验收：BUILD SUCCESS 全绿

### B5【卡点】重启 9202 + curl 验收（请用户执行重启）

- 前置：B1 已落库、B4 全绿；用户重启 cloud-system（9202）——网关/sso 不动
- curl 清单（token 经网关登录获取；请求体全 ASCII——**PUT 校验类请求避免中文入参**，中文仅出现在响应断言）：
  1. **翻译形态**：`GET /system/role/page` → admin 行 `builtin:true`、`statusLabel:"正常"`（common_status 口径）、createByName/updateByName null（种子审计 null 属正确降级）；`GET /system/menu/tree` → 根节点「系统管理」`builtin:true` + statusLabel 正常（**dto 包 Advisor 生效的实证点**）；`GET /system/dict/type/page` → 种子 2 行（user_status + common_status）均 builtin:true + statusLabel 正常；`GET /system/dict/data/page?typeId={common_status的id}`（id 经回查取）→ 2 项 builtin:true；`GET /system/user/page` → admin 行 builtin:true + statusLabel 正常（user_status 回归，SysUserVo 注解未动）
  2. **消费端点**：`GET /system/dict/data/type/common_status` → 2 项（value "0"/"1"，label 正常/停用）
  3. **保护冒烟（零变化抽验）**：`DELETE /system/role/1` → 3013；`DELETE /system/dict/type/1` → 3015
  4. builtin 写出口零：代码审查覆盖（实体接参不落库 + DTO 无字段，见 B3），不设 curl 条目
- 验收：清单逐条通过并记录；发现问题回报主控（不自行改契约）

## 前端章（cloud-web/，frontend-agent，F1→F6；与后端章并行）

> 规范来源：`/frontend-page` 技能（分层/请求封装铁律/EP 按需/弹窗与列表页模式）。降级链与禁用矩阵以契约为唯一依据（translation-api §8.4 / builtin-protection §7.2）。

### F1 types/api.ts additive

- 文件（改 1 个）：`cloud-web/src/types/api.ts`
- SysRoleVo / MenuTreeNode / SysDictTypeVo / SysDictDataVo 各 +`statusLabel: string | null; createByName: string | null; updateByName: string | null; builtin: boolean`（注释引契约 §8.4）；SysUserVo 仅 +`builtin: boolean`（注释引 §7.4）；既有字段零改动
- 验收：`npm run build` 绿（两弹窗组件 AssignMenuDialog/AssignRoleDialog 消费同类型自动兼容）

### F2 角色管理页

- 文件（改 1 个）：`cloud-web/src/views/system/role/index.vue`
- 状态列降级链（`statusLabel ?? STATUS_MAP[status]?.label ?? status`，tagType 仍按原 status）；创建人/更新人列 `*Name ?? 原字段 ?? '-'`；name 列内联徽标 `el-tag type="info" size="small"` 文本「内置」（`v-if="rowOf(row).builtin"`）；操作列 编辑/分配权限/删除 `:disabled="rowOf(row).builtin"`
- 验收：build 绿；dev 页面 admin 行徽标 + 三按钮置灰 + 状态 tag 文本「正常」

### F3 菜单管理页（树表）

- 文件（改 1 个）：`cloud-web/src/views/system/menu/index.vue`
- 同 F2 三处降级链/译文列；name 列徽标；操作列 编辑/删除 `:disabled="rowOf(row).builtin"`（23 行种子全部置灰，e2e 行正常）
- 验收：build 绿；种子行徽标 + 按钮置灰，e2e 造的行可操作

### F4 字典管理页 + 字典项弹框

- 文件（改 2 个）：
  - `cloud-web/src/views/system/dict/index.vue`：类型表 dictName 列徽标；状态列降级链；编辑/删除 `:disabled`（builtin 行）；**「字典项」按钮保持可用**（契约 §7.2——内置类型可进弹框管理项）
  - `cloud-web/src/views/system/dict/components/DictDataDialog.vue`：项表 label 列徽标；状态列降级链；行内 编辑/删除 `:disabled`（种子项）；**「新增」按钮保持可用**（契约 §7.3 随矩阵放行）
- 审计列不展示（Round E 取舍维持）；DictTypeFormDialog/DictDataFormDialog 零改
- 验收：build 绿；user_status 行徽标 + 编辑/删除置灰 + 字典项可开；弹框内种子 2 项徽标置灰、新增可用

### F5 用户管理页 + 用户编辑弹窗（用户域例外表达）

- 文件（改 2 个）：
  - `cloud-web/src/views/system/user/index.vue`：account 列徽标；操作列 删除/分配角色 `:disabled="rowOf(row).builtin"`，**编辑/重置密码保持可用**（契约 §7.2 放行项）
  - `cloud-web/src/views/system/user/components/UserFormDialog.vue`：编辑模式下「停用」单选项 `:disabled="props.user?.builtin ?? false"`（「正常」项不禁；弹窗不整体锁死——昵称可改是合法运维）；新增模式不受影响（props.user 为 undefined）
- 验收：build 绿；admin 行徽标 + 删除/分配角色置灰 + 编辑/重置密码可点；编辑弹窗开、停用项置灰、正常项可选、昵称可改

### F6 双 build + dev 联调

- 命令：`cd cloud-web && npm run build` ×2（前后各一次，按 F 任务节奏）；dev server 5173 agent 自管启停
- 联调（B5 重启后）：四页 + 两弹框逐页冒烟（徽标/禁用/翻译列/降级链——降级链可临时停 Redis 或改 dictKey 观察 null 降级，可选）
- 验收：build 绿 + 联调冒烟通过（与 B5 curl 形态一致）

## e2e 章（cloud-e2e/，B5+F6 后执行；E1 可先行）

### E1 e2e 基建债修（不依赖 B/F，可与后端/前端章并行）

- 文件（改 4 个）：
  - `lib/harness.mjs`：
    1. +`findRowByCell(page, { path, cellIndex, value, reload = true })`——以 run-role 已修版 findRoleRowByKey 为体（waitForResponse `*/page` → goto → 等响应落定 → `.el-table__row, .el-pagination` 首个可见 → waitTableIdle → 翻页循环按 `cells[cellIndex] === value` 精确比对）
    2. `logoutViaUi` 双守卫改造（设计 D7）：函数内挂一次性 `page.waitForResponse(pathname === '/api/sso/auth/logout')` → `sawLogout` 置位后**循环条件含 `!sawLogout` 永不再点**；每轮点击后以 `page.waitForURL('**/login', { timeout: 4000 }).catch(() => {})` 取代盲等 600ms；state 增 `logoutViaUiStats`，每次调用记录 `{ clicks, posts }`（posts = 调用前后 apiCalls 中 `/api/sso/auth/logout` POST 差分）
  - `run-role-e2e.mjs` / `run-nav-e2e.mjs`：删除本地 `findRoleRowByKey` 副本，改调 `h.findRowByCell(page, { path: ROLE_PATH, cellIndex: 1, value })`——**nav 竞态由此修复**（副本漂移根除；run-dict 的 findTypeRowByKey 自带 respP 等待且作用于 scoped 面板，不迁）
  - `run-scaffold-e2e.mjs`：T-VERIFY +常驻断言「每次 logoutViaUi 调用 posts ≤ 1」（flake 修复转永久回归守卫；nav N-VERIFY 同款一行，可选）
- 验收：`node run-nav-e2e.mjs` 单跑全绿（旧 UI 下场景语义未变，仅找行实现换共享版）；`node run-scaffold-e2e.mjs` 绿且 logoutViaUiStats 全部 posts=1；logoutViaUi 新实现结构审查通过（sawLogout 守卫在 click 之前判定）

### E2 四脚本断言迁移 + 翻译断言（B5+F6 后验证）

- 通用模式（四脚本同构）：
  - **UI 层**：徽标断言（行内 `el-tag` 文本「内置」）+ `await btn.isDisabled()` 禁用断言 + 放行按钮 `isEnabled()` 断言
  - **API 层**：各脚本加局部小助手 `directApi(method, path, data)`——`page.evaluate` 取 localStorage token + `page.request` + Bearer 头（N5 先例：不入 page 网络统计不污染 *-VERIFY；请求体中文经 Node UTF-8 无 GBK 陷阱）
  - **名称列徽标连带**：内联徽标使名称格 innerText 变「{name}内置」——`findMenuRow`（cells[0] 精确比对）与 D5b 名称断言统一先 `stripBadge = (s) => s.replace(/内置$/, '')` 再比对（role/dict 找行锚在 cells[1]、user 用 hasText，不受影响）
  - **expectErrToast 助手删除**（四脚本——3013-3017 在标准 UI 不再可触发；3009/3011/3012 等 toast 断言不受影响）
- `run-e2e.mjs`：
  - S14b 重写：a) admin 行徽标 + 删除/分配角色 disabled + 编辑/重置密码 enabled；b) 直连 `DELETE /api/system/user/1` → body 3017；c) 编辑弹窗开（回显昵称）+「停用」radio 的 input disabled + 取消关闭；d) 直连 `PUT /api/system/user` `{id:'1', nickname:'管理员', status:1}` → 3017；e) 种子终态断言照旧（cells[1]/[2] 比对不受徽标影响——徽标在 account 列）
  - S12b 补一条：页内 fetch `/system/user/page` 断言 admin 行 `builtin===true` 与原字段并存
- `run-role-e2e.mjs`：
  - R1 补：admin 行状态列 tag 文本「正常」（经 statusLabel）+ 页内 fetch `/system/role/page` 断言 admin 行 status=0 与 statusLabel/createByName/builtin 并存（红线黑盒）
  - R5a 重写（分配权限按钮将禁用）：admin 存量绑定改直连 `GET /api/system/role/1/menus` → 断言 23 个种子菜单 id 全量**含父目录 id（10/20 等）**（语义本体保留且更精确）；弹窗分组结构断言（perm-group/perm-menu-row/perm-func/perms 灰字）移至 R5b 测试角色首次开窗处
  - R6b 重写：admin 行徽标 + 编辑/分配权限/删除三按钮 disabled；直连 `DELETE /api/system/role/1` → 3013、`PUT /api/system/role`（id='1' + 全量原值——**「原值亦拒」全禁语义断言保留**）→ 3013、`PUT /api/system/role/menu` `{roleId:'1', menuIds:['10']}` → 3013；行仍在收尾
- `run-menu-e2e.mjs`：
  - `findMenuRow` 比对改 stripBadge 后精确相等（种子行带徽标、e2e 行不带，剥离后统一）
  - M1 补：种子行（系统管理）状态列「正常」+ 页内 fetch `/system/menu/tree` 断言根节点 builtin===true 且 statusLabel 非空「正常」；e2e 行 builtin===false 且 statusLabel 按其层级断言（顶级非空 / 嵌套 null——以该行实际 parentId 为准）；原字段与译文字段键并存（嵌套子节点译文 null 属机制边界非故障，契约 §8.2 要点，勿误读）
  - M5b 重写：种子行（用户管理）徽标 + 编辑/删除 disabled；直连 `PUT /api/system/menu`（id='11' + 全量六写字段原值）→ 3014、`DELETE /api/system/menu/11` → 3014；行仍在；CLEANUP 23 行断言照旧
- `run-dict-e2e.mjs`：
  - **种子计数迁移**：D1「恰 1 行」→「恰 2 行」（user_status + common_status，两行均断言徽标与状态列「正常」）；CLEANUP「共 1 条/恰 1 行/仅剩 user_status」→「共 2 条/恰 2 行/残留 dictKey 集合 = {user_status, common_status}」
  - D5 消费断言补：`GET /system/dict/data/type/common_status` → 2 项（正常"0"/停用"1"）
  - D5b 重写：user_status 行徽标 + 编辑/删除 disabled；直连 `DELETE /api/system/dict/type/1` → **3015 而非 3011（次序断言在 API 层保留）**；「字典项」按钮 enabled 断言 → 开弹框 → 种子项（正常）徽标 + 行内编辑/删除 disabled + 弹框「新增」enabled（§7.3 放行）→ 直连 `DELETE /api/system/dict/data/1` → 3016；弹框 2 行/共 2 条断言照旧；名称断言（cellsAfter[0]==='用户状态'）改 stripBadge 后比对
- 验收：四脚本单跑全绿（B5+F6 后）；种子终态全绿（admin 行原样/23 菜单/user_status 2 项/common_status 2 项）

### E3 全量回归 + 重复跑统计验证

- 回归：`cd cloud-e2e && npm run e2e`（有头全量六脚本）全绿；*-VERIFY 各脚本零新增污染（直连请求不入统计）
- logoutViaUi 统计性验证（设计 D7）：`run-scaffold-e2e.mjs` 与 `run-e2e.mjs` 各重复跑 5 次（如 `for i in 1 2 3 4 5; do node run-scaffold-e2e.mjs || break; done`）——全部 PASS 且零 console error（favicon 白名单照旧）/零 ≥400——flake 无法确定性复现，以「双守卫结构 + 常驻断言 + 5×2 统计」共同构成修复证明
- 验收：六脚本绿 + 重复跑零失败 + 结果记录在案

## 移交后续阶段的备忘

1. **dict e2e 种子计数耦合**：D1/CLEANUP 断言已随 common_status 迁移为 2 个内置类型——后续每新增内置字典种子须同步改计数断言（脚本-种子耦合记档，建议下轮考虑把计数改为「≥ 种子清单」宽松断言）
2. **3013-3017 的 e2e 覆盖形态已变**：标准 UI 无触发路径，回归依赖 E2 的直连断言（page.request）；若未来 UI 形态回摆（禁用改可点），toast 断言需复活
3. **logoutViaUiStats 常驻断言只在 scaffold T-VERIFY**（+可选 nav N-VERIFY）——run-e2e/run-role/run-dict 的 logout 调用未断言（脚本无 VERIFY 步骤或低价值，不扩散）
4. **dict 审计译文前端未消费**：SysDictTypeVo/SysDictDataVo 的 createByName/updateByName 后端照给（契约 §8.1）——未来字典页若恢复审计列，直接接降级链即可，零后端改动
5. **MenuTreeNode dto 包 Advisor 验证结果**（B2 实现时验证）：若调整过扫描逻辑，在此记档所选通路
6. **内置类型整体冻结**（禁新增项）若要做：属保护矩阵收紧（12 处校验扩张 + §7.3 语义反转），另案明示
7. **环境**：5173 dev server agent 自管；9201/9202/18080 归用户启停（B5 卡点重启 9202）
8. **嵌套 VO 翻译机制增强**（TranslateAdvisor.collect 增 @TranslateVO 实例字段下钻 + 深度上限调整 + 嵌套树单测）——本轮已按拍板记档不修（translation-api §8.2 机制边界），/menu/tree 嵌套行译文恒 null 由降级链兜底；后续出现树形 VO（部门树等）翻译需求时优先做此增强

## 给 backend-agent / frontend-agent / e2e 的任务清单（可粘发）

- **backend（B1→B5）**：B1 common_status 种子（**增量不写显式 id** + INSERT...SELECT 关联 + 基线显式 id 2/3/4 + java 通路回查）；B2 四 VO @TranslateVO + 三注解三字段（**SysUserVo 零触碰**；dto 包 Advisor 实现时验证）；B3 五 VO +Boolean builtin + 四 Convert + MenuTreeBuilder 各 1 行 null-safe 传递 + MenuTreeBuilderTest 补断言（SysUserVo 仅此一处改动）；B4 全量构建（MapperXmlBindingTest 46 不变）；B5【卡点】重启 9202 + curl 验收（翻译形态 ×5 端点 + 消费端点 + 保护冒烟 3013/3015；请求体全 ASCII）
- **frontend（F1→F6）**：F1 types 五 interface additive；F2/F3 role/menu 页三列降级链 + 徽标 + 禁用；F4 dict 类型表 + DictDataDialog（徽标/降级链/禁用；**字典项与新增按钮保持可用**）；F5 user 页（徽标；删除/分配角色禁，编辑/重置密码留）+ UserFormDialog 停用项禁用；F6 双 build + 联调
- **e2e（E1 可先行 → E2 → E3）**：E1 harness findRowByCell 共享抽取（role/nav 换用）+ logoutViaUi 双守卫 + T-VERIFY 常驻断言；E2 四脚本双层断言迁移（disabled + 直连 3013-3017；R5a 改直连 menuIds；dict 种子计数 2；findMenuRow/D5b stripBadge；expectErrToast 删除）+ 每页翻译断言 UI+fetch 各一条；E3 六脚本全量 + scaffold/user ×5 重复跑
- 红线：契约定稿后两端不得单方改；保护矩阵/错误码/权限/事务/索引零变化；user_status 与 admin 零触碰；既有端点 additive-only；Element Plus 按需；黑盒纪律
