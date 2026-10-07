# 内置数据保护 API 契约（cloud-system：角色/菜单/字典/admin 用户防删防改防停）

- 日期：2026-10-07
- 状态：**保护域现行版（v1.1）**——v1 配合内置保护需求（设计 `docs/superpowers/specs/2026-10-07-translate-remote-builtin-protection-design.md`，计划 `docs/superpowers/plans/2026-10-07-translate-remote-builtin-protection.md`）；**v1.1 = §7 UI 强化修订**（2026-10-07 同日第二批：五域 VO additive `builtin` 字段 + 徽标/按钮禁用语义，设计 `docs/superpowers/specs/2026-10-07-translate-rollout-builtin-ui-design.md`，计划 `docs/superpowers/plans/2026-10-07-translate-rollout-builtin-ui.md`）——v1 全部条款零回改，§7 为 additive 声明 + 对 §5.4/§5.5 的声明性关闭
- **与既有契约的关系（additive 错误码扩张 + 声明性取代）**：本文档对 pilot（`2026-10-05-pilot-auth-user-api.md` 用户/角色/菜单域）、dict（`2026-10-07-dict-api.md`）、translation（`2026-10-07-translation-api.md`）三契约的部分条款做**声明性取代/增补**——既有端点、入参、出参、成功语义**零变化**，仅其 delete/update 类端点的**错误码集合扩张**（新增可能错误码 3013-3017）。各域冲突处以本文档为准（仅限保护语义）；通用约定沿用 pilot §1
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对既有契约，v1 共 5 处 + v1.1 补行 1 处）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | 错误码段 | **保护域占用 3013-3017**（§4）；3013+ 的旧预留声明（dict-api §5 末行、translation-api §4 第 2 条）自本文档起改写为"3018 起预留给 bpmn 域（阶段 4）"（原文不回改，本行为准） | 声明性取代——接续 dict-api §0.1 的重排惯例 |
| 2 | pilot 角色/菜单域 | 8 个端点的错误码集合扩张：PUT/DELETE /system/role、PUT /system/role/menu、PUT/DELETE /system/menu 新增可能返回 3013/3014（§2 矩阵）——**仅内置行触发**，非内置行为零变化 | additive（新错误码入既有端点） |
| 3 | pilot 用户域 | DELETE /system/user/{id} 新增 3017；PUT /system/user 新增 3017（仅停用分支）；PUT /system/role（用户分配角色端点 PUT /system/user/role）新增 3017（§2 矩阵与 §5 延伸声明） | additive |
| 4 | dict 域 | PUT/DELETE /system/dict/type、PUT/DELETE /system/dict/data 新增可能 3015/3016 | additive |
| 5 | translation-api §5 宽松语义 | §5 第 4 条"种子可被管理操作破坏"**关闭**——user_status 已受内置保护（本文档 §2）；其余宽松语义条目不变 | 声明性取代（缩小） |
| 6 | （v1.1 补行） | **五域 VO additive `builtin: boolean` + UI 徽标/按钮禁用语义**（§7）；**声明性关闭 §5.4/§5.5**；内置范围扩张声明（common_status 种子，随 translation-api §8.3） | additive + 声明性取代——详见 §7；保护矩阵/错误码/校验实现零变化 |

## 1. 域语义（保护域特有）

- **内置判定**：行级 `is_builtin=1`（DDL 新列，`TINYINT NOT NULL DEFAULT 0`）——种子数据置 1（admin 角色 id=1、23 行种子菜单、user_status 字典类型 id=1 及其项 id=1/2、admin 用户 id=1），用户新建数据恒 0。**该列无任何 API 读/写出口**（VO 不出、请求不可传）：前端无法感知内置标记，只能从"操作被拒 + 错误码"得知——UI 强化（徽标/按钮禁用/VO additive 字段）另轮全栈
- **保护粒度 = 一刀切（用户拍板）**：内置行的**删除与修改一律拒绝**（修改含停用：status 变更属修改），**用户域例外**——admin 用户仅禁删 + 禁停用，**nickname 可改、重置密码放行**（合法运维）
- **绑定操作整体拒绝**：admin 角色的 assignMenus（分配权限）、admin 用户的 assignRoles（分配角色）整体拒绝——清空/摘除绑定等同删除（无权限锁死）
- **校验顺序**：保护检查先于其余业务校验（如字典类型删除的 3011 项检查）——内置行操作一上手即被拒，错误原因准确
- **放宽预告**：未来放开某内置字段（如菜单改名）为 additive 变更（拒绝变允许，对前端无破坏）——收紧难、放宽易，本版取最简
- **放宽/标记维护通路**：is_builtin 只能经 SQL 变更（无 API 写入口，结构性防篡改）；后续新增内置种子（菜单/字典/角色）SQL 须带 is_builtin=1

## 2. 保护矩阵（唯一权威表）

| 域 | 内置范围 | DELETE | PUT（update） | 停用（status→1） | 绑定操作 | 放行项 |
|---|---|---|---|---|---|---|
| 角色 | id=1（admin） | **3013 禁** | **3013 全禁**（name/roleKey/status 一律拒） | 含于全禁 | assignMenus **3013 整体拒** | —（种子绑新菜单走 SQL） |
| 菜单 | 23 行种子（id 见 §3 DDL） | **3014 禁** | **3014 全禁**（name/icon/sort/path/perms/type/parentId/status 一律拒） | 含于全禁 | — | — |
| 字典类型 | id=1（user_status） | **3015 禁**（先于 3011） | **3015 全禁**（dictName/dictKey/status 一律拒） | 含于全禁 | — | — |
| 字典项 | id=1/2（正常/停用） | **3016 禁** | **3016 全禁**（label/value/sort/status 一律拒） | 含于全禁 | — | — |
| 用户 | id=1（admin） | **3017 禁** | **仅禁停用**：请求 status=1 → **3017**；status=0/null → 放行 | **3017 禁** | assignRoles **3017 整体拒**（§5 延伸声明） | nickname 修改放行；resetPassword 放行 |
| 非内置行 | — | 全部照旧 | 全部照旧 | 照旧 | 照旧 | **既有全部语义零变化** |

- 语义要点：**"全禁"= 不论请求改什么字段（哪怕提交与库中相同的值），内置行 update 一律 3013-3016 拒绝**——不做字段级豁免（设计 D6）；用户域"仅禁停用"= 按**请求值**判定（status=1 拒，status=0/未传放行），不比对库中旧值

## 3. DDL 声明（5 表 is_builtin + 种子 UPDATE）

```sql
-- 5 表同款（sys_role/sys_menu/sys_dict_type/sys_dict_data/sys_user）；列位置 AFTER status
ALTER TABLE sys_role ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0
    COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建' AFTER status;
-- sys_user 的 COMMENT：'内置标记：1=系统内置（禁删禁停用，昵称可改），0=用户创建'

UPDATE sys_role      SET is_builtin = 1 WHERE id = 1;
UPDATE sys_menu      SET is_builtin = 1 WHERE id IN (10,11,12,13,14,20,21,211,111,112,113,114,115,121,122,123,124,131,132,133,141,142,143);
UPDATE sys_dict_type SET is_builtin = 1 WHERE id = 1;
UPDATE sys_dict_data SET is_builtin = 1 WHERE id IN (1,2);
UPDATE sys_user      SET is_builtin = 1 WHERE id = 1;
```

- 增量脚本 `scripts/sql/2026-10-07-builtin-protection.sql` + 基线 `cloud_system.sql` 同步（列定义 + 种子 INSERT 显式 is_builtin=1）；UPDATE 幂等、ALTER 前核对列不存在
- **VO/TypeScript 零增补**：本轮不出 builtin 字段（无前端消费方）；索引零增量（is_builtin 不进任何查询条件）；事务零新增（校验为读+抛）

## 4. 错误码汇总（**3xxx 段分配的现行权威**）

| code | 含义 | 出现端点 |
|---|---|---|
| 3013 | 内置角色禁止删除 / 内置角色禁止修改 / 内置角色禁止修改权限 | PUT/DELETE /system/role；PUT /system/role/menu |
| 3014 | 内置菜单禁止删除 / 内置菜单禁止修改 | PUT/DELETE /system/menu |
| 3015 | 内置字典类型禁止删除 / 内置字典类型禁止修改 | PUT/DELETE /system/dict/type |
| 3016 | 内置字典项禁止删除 / 内置字典项禁止修改 | PUT/DELETE /system/dict/data |
| 3017 | 内置用户禁止删除 / 内置用户禁止停用 / 内置用户禁止修改角色 | DELETE /system/user/{id}；PUT /system/user；PUT /system/user/role |

**占位声明（接续 dict-api §5 / translation-api §4）**：3xxx 现状 = 3001-3012（用户/角色/菜单/字典域）+ 3013-3017（保护域，本文档）；**3018 起预留给 bpmn 域（阶段 4）**。

## 5. 宽松语义与延伸声明

1. **"全禁"不区分字段**：内置行 update 即使提交原值也被拒——简化规则面（设计 D6），前端编辑弹窗对内置行全量提交必拒
2. **assignRoles 保护为拍板精神延伸（供确认）**：admin 用户分配角色（PUT /system/user/role）的整体拒绝未在用户拍板字面内，系与已拍板"admin 角色 assignMenus 整体拒绝"的对称漏洞补全（取消 admin 用户角色绑定 = 无权限锁死）——按一致性纳入 3017，如不认可可剔除（独立校验点，剔除不动其余）
3. **保护不自动恢复**：若历史环境已有内置种子被删（墓碑）或被改，本轮不提供自动修复——增量 SQL 的 UPDATE 只对存活行生效；治理另案
4. **错误 toast 即全部前端反馈**：3013-3017 经既有拦截器 toast msg（零前端改动）；删除按钮仍可见可点，点了即拒——体验强化（按钮禁用/徽标）另轮
5. **is_builtin 对前端不可见**：契约无此字段；前端不得通过任何途径推断内置状态做 UI 分支（现状用错误码被动得知即正确形态）

## 6. TypeScript 类型字典 / 前端消费映射

- **零增补、零改动**：本轮无新端点、无新字段、无新页面——前端唯一可感知变化 = 内置行写操作由成功变 toast 报错（拦截器既有路径）
- 后续 UI 强化轮（另案全栈）：VO additive `builtin: boolean` + 徽标/按钮禁用 + 与 4 页翻译铺开可同轮

## 给 backend-agent / e2e 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-07-translate-remote-builtin-protection.md`（后端章 B1-B7 / e2e 章 E1-E3，纯后端轨道）。要点：

- **backend**：B1 DDL 增量 + 基线同步 + 执行回查；B2 五实体 is_builtin + BuiltinEnum + 五 mapper allColumns 补列（INSERT/UPDATE 不动 = 结构性防篡改）；B3 保护校验 12 处 + 错误码常量 + 单测；B4-B5 remote 子模块 + inner 端点（见 inner 契约）；B6 全量构建；B7【卡点】重启 9202 + curl 验收（保护矩阵 12 条 + inner 直连 + 网关 403 实证）
- **e2e**：E1 四脚本内置保护断言（toast + 行仍在）；E2 逐脚本种子操作点复核 + 六脚本全量回归
- 红线：契约定稿后两端不得单方改；既有端点 additive-only（仅错误码集合扩张）；3013-3017 归保护域、3018+ 归 bpmn；零新增 @Transactional、零索引增量、零前端改动

## 7. v1.1 UI 强化修订（builtin VO 字段 + 徽标/禁用语义，2026-10-07 第二批）

> 设计 `2026-10-07-translate-rollout-builtin-ui-design.md`（D2-D4/D6）｜计划 `2026-10-07-translate-rollout-builtin-ui.md`｜**保护矩阵 §2、错误码 §4、12 处校验实现零变化**——本节只做「前端可感知」的 additive 声明与两条宽松语义的声明性关闭。

### 7.0 变更点清单（v1.1 相对 v1，共 5 处）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | 五域 VO | SysRoleVo / MenuTreeNode / SysDictTypeVo / SysDictDataVo / SysUserVo 各 additive `builtin: boolean`（§7.1） | additive |
| 2 | 前端 UI | 徽标（el-tag info「内置」）+ 写按钮禁用（§7.2 矩阵 = §2 镜像） | 前端实现细节 |
| 3 | §5.4 | **声明性关闭**：错误 toast 不再是全部前端反馈——徽标+禁用为主反馈，3013-3017 降为最终防线（标准 UI 下不再可触发，错误码与后端校验原样保留） | 声明性取代 |
| 4 | §5.5 | **声明性关闭**：is_builtin 经 `builtin` 只读字段对前端可见；**写出口仍零**（SaveRequest 无此字段、实体写列不含 is_builtin，结构性防篡改维持；请求传入 builtin 属 DTO 超集被忽略） | 声明性取代（缩小） |
| 5 | 内置范围 | **扩张声明**：common_status 字典类型与其 2 项（translation-api §8.3 种子，is_builtin=1）自动落入 §2 字典域保护（3015/3016）——判定本体是 is_builtin 列，§2 矩阵 id 列为 v1 描述不回改 | 范围扩张——校验逻辑零改动 |

### 7.1 builtin 字段（五 VO 同构）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| builtin | boolean | 是 | is_builtin=1 → true；0/null → false（null-safe：无标记行与用户创建行同为 false） | `true` |

- **生效端点**：`GET /system/role/page`、`GET /system/role/list`、`GET /system/menu/tree`、`GET /system/dict/type/page`、`GET /system/dict/data/page`、`GET /system/user/page`、`GET /system/user/{id}`（与 translation-api §8.2 同批；/role/list、user/{id} 当前无 UI 消费方，流入无害）
- **只读**：无任何写端点接受 builtin（超集传入不落库）；is_builtin 变更仍只经 SQL（§1 维持）
- 响应示例（role 分页种子行，menu/dict/user 同构）：

```json
{ "id": "1", "name": "管理员", "roleKey": "admin", "status": 0, "builtin": true, "...": "审计与译文字段" }
```

### 7.2 UI 禁用矩阵（§2 保护矩阵的逐行镜像，前端唯一权威）

| 域 | 徽标位置 | 禁用按钮（:disabled="row.builtin"） | 保持可用 | 弹窗内表达 |
|---|---|---|---|---|
| 角色 | name 列 | 编辑 / 分配权限 / 删除 | — | — |
| 菜单 | name 列 | 编辑 / 删除 | — | — |
| 字典类型 | dictName 列 | 编辑 / 删除 | **字典项**（打开弹框管理项） | — |
| 字典项 | label 列（弹框内） | 编辑 / 删除 | **新增**（内置类型可追加项，§7.3） | — |
| 用户 | account 列 | 删除 / 分配角色 | **编辑**（弹窗开、昵称可改）/ **重置密码** | 编辑弹窗内**「停用」单选项禁用**（「正常」不禁）；不整体锁弹窗 |

- 徽标统一 `el-tag type="info" size="small"` 文本「内置」，内联名称类单元格（不新增列）；tag 颜色与状态列无关
- 隐藏按钮 / 保留可点靠 toast 均不采用（设计 D3）；AssignMenuDialog、AssignRoleDialog 内部零改（builtin 流入不消费）

### 7.3 宽松语义（v1.1 新增 1 条，其余沿用 §5）

1. **内置字典类型可追加项**：向 user_status / common_status 新增字典项（POST）不在保护面 = 放行，UI 同步放行（§7.2）；追加项 is_builtin=0 可正常编辑删除。误删内置项的后果 = 译文降级 null + 消费端点空数组（translation-api §1 优雅降级覆盖）。若未来要「内置类型整体冻结」属矩阵收紧，另案明示

### 7.4 TypeScript 类型字典（types/api.ts additive）

| 类型 | 增补 |
|---|---|
| SysRoleVo / MenuTreeNode / SysDictTypeVo / SysDictDataVo / SysUserVo | 各 + `builtin: boolean`（与 translation-api §8.4 译文字段同批增补） |

## 给 backend-agent / frontend-agent / e2e 的任务清单（v1.1 增补）

完整清单见 `docs/superpowers/plans/2026-10-07-translate-rollout-builtin-ui.md`（后端章 B3 / 前端章 F2-F5 / e2e 章 E2）。要点：

- **backend**：五 VO +Boolean builtin、四 Convert + MenuTreeBuilder 各 +1 行 setter 传递（null-safe）；**保护校验/错误码/mapper 零改动**
- **frontend**：五处徽标 + 禁用绑定（§7.2 矩阵）+ UserFormDialog 停用项禁用
- **e2e**：四脚本保护断言迁移为「UI disabled + page.request 直连 body 3013-3017」双层（N5 先例）；3015 先于 3011、3013 原值亦拒两语义断言转 API 层保留
- 红线：保护矩阵/错误码/权限标识/事务/索引零变化；builtin 只读无写出口；既有端点 additive-only
