# 字段翻译 API 契约（cloud-system：字典消费端点 + 用户 VO 译文字段 additive）

- 日期：2026-10-07
- 状态：**翻译域现行版（v1.1）**——v1 配合字段统一翻译需求（设计 `docs/superpowers/specs/2026-10-07-field-translation-design.md`，计划 `docs/superpowers/plans/2026-10-07-field-translation.md`）；**v1.1 = §8 铺开修订**（2026-10-07 同日第二批：四 VO 译文字段 + common_status 种子，设计 `docs/superpowers/specs/2026-10-07-translate-rollout-builtin-ui-design.md`，计划 `docs/superpowers/plans/2026-10-07-translate-rollout-builtin-ui.md`）——v1 全部条款零回改，§8 为 additive 声明
- **与既有契约的关系（additive-only）**：本文档包含 ① 新端点（字典消费）② 对 pilot（`2026-10-05-pilot-auth-user-api.md`）用户域的 **additive 字段变更声明**。pilot 全部既有端点与字段**零触碰零变更**；两文冲突以本文档为准（仅限翻译域）。通用约定（R 结构 / HTTP 恒 200 + body.code / Long→String / 时间格式 / 错误码分段 / 前端处理策略）沿用 pilot §1。dict 契约（`2026-10-07-dict-api.md`）8 个管理端点零触碰
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对既有契约，v1 共 3 处 + v1.1 补行 1 处）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | pilot §3.1/§6.1 SysUserVo | **additive 三字段**：statusLabel / createByName / updateByName（§3）——**既有全部字段语义与取值零变化**（原字段永不因翻译被覆盖/丢弃，见 §1 红线） | additive——旧前端按原字段消费不受任何影响；新字段可选消费 |
| 2 | 字典域 | 新增消费端点 `GET /system/dict/data/type/{dictKey}`（§2）——兑现 dict 契约移交备忘 1 预留；dict 8 管理端点与其错误码（3008-3012）零触碰 | additive 新端点 |
| 3 | 种子数据 | sys_dict_type/sys_dict_data 各 +内置种子（user_status：正常/停用）——字典管理页可见可操作（防删并入 3013+ 保护 backlog，本轮不设防） | 数据扩张——对 sys_menu/perms 零影响（无新权限节点） |
| 4 | （v1.1 补行） | **四 VO 翻译铺开 + common_status 内置种子**（§8）：SysRoleVo / MenuTreeNode / SysDictTypeVo / SysDictDataVo additive 译文字段，status 译文经新种子 common_status；user 域零触碰 | additive——详见 §8；设计 D1/D5 |

## 1. 域语义（翻译域特有）

- **翻译红线（设计规则 0，用户明示）**：翻译**绝不丢弃/覆盖数据库原始值**——userId、status、account 等原字段必须**原样返回** VO（前端用它们做编辑弹窗回填再提交、行内逻辑判断、tag 颜色映射、筛选等业务处理）；译文只落**配对新增字段**（status→statusLabel、createBy→createByName、updateBy→updateByName），**原字段与译文字段并存**
- **翻译机制（后端内部实现，前端无感知）**：VO 类标 @TranslateVO、原字段标 @DictTrans/@UserTrans，响应级统一批量翻译回填——机制不进前端契约，前端只认 §3 字段
- **译文语义**：statusLabel = 字典 user_status 中 value=String(status) 的 label（消费口径：类型启用 ∧ 项启用）；createByName/updateByName = account 对应的 nickname
- **译文可为 null（降级语义，不是错误）**：字典项缺失/停用、用户已删、缓存/回源异常、原文案未配置——凡翻译未命中，译文字段为 null，原字段照常返回。**前端必须走降级链**（§3 表末）而不是假设译文恒有
- **译文时效（宽松语义）**：字典/用户管理写操作即时失效主通道 + TTL 30 分钟兜底——极端路径（DEL 失败/事务竞态）下译文最长陈旧 30 分钟（§5.2）
- **手翻优先**：译文字段若被 Service 手动赋值（个别复杂场景），后端翻译器跳过不覆盖——前端无需感知

## 2. 字典消费端点（/system/dict/data，cloud-system）

### 2.1 按字典键取启用项 `GET /system/dict/data/type/{dictKey}`

- 权限：**无 @PreAuthorize——登录即可**（user-nav 同款口径；表单下拉是登录用户的基础能力，不挂管理权限 system:dict:list）
- 入参（path）：`dictKey` string（字典类型键，如 user_status；显式命名 `@PathVariable("dictKey")`）
- 行为：按**消费口径**返回该类型的启用项——类型 status=0 且 deleted=0 ∧ 项 status=0 且 deleted=0，`ORDER BY sort ASC, id ASC`；数据经 Redis 缓存（键 trans:dict:{dictKey}，回源 DB）
- **dictKey 不存在 / 类型停用 / 无启用项：一律 `200 + 空数组`，不设业务错误码**（表单容错优先——下拉空是可接受 UI 态；与翻译降级静默语义一致）
- 返回 `R<List<DictItemVo>>`——空态 data 为 `[]`（非 null）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| value | string | 是 | 存库值（表单提交用原值，见 §1 红线同源语义） | `"0"` |
| label | string | 是 | 展示标签 | `"正常"` |
| sort | number | 是 | 排序号（恒有值，DDL NOT NULL DEFAULT 0） | `1` |

```json
{ "code": 200, "msg": "操作成功", "data": [
  { "value": "0", "label": "正常", "sort": 1 },
  { "value": "1", "label": "停用", "sort": 2 } ] }
```

- 错误码：401（未认证，网关）/ 403（不会出现——无 @PreAuthorize）
- 前端消费：本轮**暂无页面**（契约先行，随后续表单下拉接入；e2e 以页内 fetch 验证链路）

## 3. SysUserVo additive 字段（pilot §6.1 的增量声明，原文不回改）

### 3.1 字段表（原字段不变 + 译文字段新增，逐字段对照）

| 字段 | 类型 | 必返 | 性质 | 说明 | 示例 |
|---|---|---|---|---|---|
| id | string | 是 | **既有（不变）** | 用户 ID | `"1"` |
| account | string | 是 | **既有（不变）** | 登录账号（编辑不可改，原样返回） | `"admin"` |
| nickname | string | 是 | **既有（不变）** | 昵称 | `"管理员"` |
| status | number | 是 | **既有（不变）** | 0 正常 1 停用——**行内逻辑判断/tag 颜色映射/筛选仍用此字段** | `0` |
| createBy | string \| null | 是 | **既有（不变）** | 创建人账号 | `"admin"` |
| createTime | string \| null | 是 | **既有（不变）** | `yyyy-MM-dd HH:mm:ss` | `"2026-10-05 21:30:00"` |
| updateBy | string \| null | 是 | **既有（不变）** | 更新人账号 | `"admin"` |
| updateTime | string \| null | 是 | **既有（不变）** | 同上格式 | `"2026-10-06 10:00:00"` |
| **statusLabel** | string \| null | 是 | **新增（译文）** | status 的字典含义（user_status 消费口径 label）；null=未命中/降级（§1） | `"正常"` |
| **createByName** | string \| null | 是 | **新增（译文）** | createBy(account) 对应 nickname；null 同上 | `"管理员"` |
| **updateByName** | string \| null | 是 | **新增（译文）** | updateBy(account) 对应 nickname；null 同上 | `"管理员"` |

- **既有字段语义与取值零变化**（additive 声明）：status/createBy/updateBy 等原字段不因翻译被覆盖、改型或置空——编辑弹窗回填再提交、行内判断、颜色映射等既有用法全部不受影响
- **前端消费降级链（必须实现，不许假设译文恒有）**：状态列文本 `statusLabel ?? 本地 STATUS_MAP[status].label ?? status`；创建人列 `createByName ?? createBy ?? '-'`；更新人列 `updateByName ?? updateBy ?? '-'`；**tag 颜色仍按原值 status 映射**（译文不含颜色语义）
- 生效端点：`GET /system/user/page`（pilot §3.1）——pilot 其余用户端点（save/update 等）出参不含 VO，无涉

### 3.2 响应示例（用户分页，种子 admin）

```json
{ "code": 200, "msg": "操作成功", "data": { "total": "1", "rows": [
  { "id": "1", "account": "admin", "nickname": "管理员", "status": 0,
    "createBy": null, "createTime": "2026-10-05 21:30:00",
    "updateBy": null, "updateTime": null,
    "statusLabel": "正常", "createByName": null, "updateByName": null } ] } }
```

（原字段 + 译文字段并存——§1 红线的响应形状。**种子 admin 的审计列为 NULL**（`cloud_system.sql` 建库 INSERT 未设审计值）——createByName/updateByName 对 null 原值走 null 降级属正确行为，勿误读为翻译故障；运行期新建/编辑的用户行 createBy/updateBy 有值，译文正常回填。）

## 4. 错误码账本声明（**3xxx 段现行权威的延续**）

- **本轮错误码零新增**：消费端点采空数组语义（§2.1），翻译降级不设业务码（§1）——无任何新 code 入账
- 3xxx 现状（dict 契约 §5 延续）：**3001-3012 已占用（字典域至 3012）；3013 起预留给「内置角色/菜单/字典保护」backlog**——该 backlog 现新增「内置字典 user_status 防删防改」一项（设计 D7），仍从 3013 接续，本轮不动
- 翻译相关失败（Redis 不可用/回源异常）**不产生对前端的错误码**——一律降级译文为 null（§1），后端 log 记录

## 5. 宽松语义清单（如实记录，前端以降级链兜底，不因契约掩盖）

1. **译文可为 null 的多因一果**：字典缺项/停用、用户已删、缓存异常、TTL 窗口内陈旧——前端不区分原因，统一走降级链（§3.1）
2. **译文陈旧窗口**：字典/用户管理写操作后译文即时失效为主通道；DEL 失败或事务竞态路径下最长 30 分钟陈旧（TTL 兜底）
3. **dictKey 静默空**：消费端点未知 dictKey 返回空数组不报错——拼写错误靠下拉为空暴露，不炸表单
4. **种子可被管理操作破坏**：user_status 内置字典当前无防删防改（3013+ backlog）——删/停后果=译文 null + 消费端点空数组（优雅降级不崩溃）
5. **译文非强一致快照**：同一响应内原字段与译文字段来自不同时点的字典数据（缓存窗口），极小概率不匹配（如 status=1 而 statusLabel="正常"）——前端以原字段为业务依据，译文仅展示用（§1 红线的自然推论）

## 6. TypeScript 类型字典（types/api.ts 增补）

| 类型 | 定义 |
|---|---|
| `SysUserVo`（pilot 既有） | additive 三字段：`statusLabel: string \| null; createByName: string \| null; updateByName: string \| null`（interface 直接扩字段，既有字段零改动） |
| `DictItemVo`（新增） | `{ value: string; label: string; sort: number }` |
| `getDictItems`（api/dict.ts 新增函数） | `(dictKey: string): Promise<DictItemVo[]>`——`GET /system/dict/data/type/${dictKey}`（URL 前缀 /system 走 /api 代理） |

## 7. 前端消费映射

| 端点/字段 | 消费方 | 备注 |
|---|---|---|
| statusLabel/createByName/updateByName | 用户管理页三列（状态 tag 文本 / 创建人 / 更新人） | 降级链必做（§3.1）；tagType 仍按原 status 本地映射 |
| GET /dict/data/type/{dictKey} | 本轮无页面（契约先行）；api/dict.ts 先落 `getDictItems` | 后续表单下拉统一取数入口 |
| 既有页面/端点 | 零改动 | role/menu/dict 三页本轮不翻（推迟记档） |

## 给 backend-agent / frontend-agent 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-07-field-translation.md`（后端章 B1-B7 / 前端章 F1-F3 / e2e 章 E1-E3）。要点：

- **backend**：B1 种子 SQL（user_status 两表 3 行 + 执行落库）；B2 translate-starter 全套（注解/Advisor/缓存/Provider 接口 + 单测，**守护用例：翻译前后原字段一致**）；B3 system 依赖 + Provider×2 + mapper 只读查询×2（EXPLAIN 审查）；B4 失效挂钩三个 ManageService；B5 SysUserVo 三字段 + 消费端点；B6 全量构建；B7【卡点】重启 9202 + curl 验收
- **frontend**：F1 types/api.ts additive；F2 用户页三列降级链消费；F3 build + 联调
- **e2e**：E1 run-e2e.mjs +S13 翻译断言；E2 run-dict-e2e.mjs +D5 消费端点场景；E3 六脚本全量回归
- 红线：契约定稿后两端不得单方改；既有端点/字段 additive-only（原字段零变化）；错误码零新增、3013+ 预留不动；Element Plus 按需；黑盒纪律

## 8. v1.1 铺开修订（四 VO 译文字段 + common_status 种子，2026-10-07 第二批）

> 设计 `2026-10-07-translate-rollout-builtin-ui-design.md`（D1/D5）｜计划 `2026-10-07-translate-rollout-builtin-ui.md`｜v1 全部条款与 §3 用户域字段**零回改零变化**；本节为对 role/menu/dict 三域既有端点的 **additive 出参声明**（同 §3 对 pilot 的声明方式）。共同语义（红线/降级链/null 多因一果/手翻优先/宽松语义）**全部沿用 §1/§3.1/§5，本节不重复**，仅列差异。

### 8.0 变更点清单（v1.1 相对 v1，共 4 处）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | SysRoleVo / SysDictTypeVo / SysDictDataVo / MenuTreeNode（dto） | **各 additive 三字段**：statusLabel / createByName / updateByName（§8.1）——既有字段零变化；status 的译文键为 **common_status**（非 user_status） | additive |
| 2 | 生效端点 | `GET /system/role/page`、`GET /system/role/list`、`GET /system/menu/tree`、`GET /system/dict/type/page`、`GET /system/dict/data/page`（§8.2）；/menu/tree 嵌套子节点译文恒 null（机制边界，见 §8.2 要点） | additive——MenuTreeNode 字段表同时为 menu-management v2 §3 的 additive 声明（原文不回改，冲突以本节为准） |
| 3 | 种子数据 | sys_dict_type/sys_dict_data 各 +内置种子 common_status（通用状态：正常"0"/停用"1"）（§8.3）——is_builtin=1，自动受 3015/3016 保护（保护契约 §7 范围扩张声明） | 数据扩张——sys_menu/perms 零影响 |
| 4 | 前端消费 | role/menu 页三列 + dict 类型表/项弹框状态列接入降级链（§8.4） | 前端实现细节 |

### 8.1 四 VO additive 字段（逐 VO 同构，语义同 §3.1）

| VO | statusLabel | createByName / updateByName | 说明 |
|---|---|---|---|
| SysRoleVo | 字典 common_status 中 value=String(status) 的 label | createBy / updateBy（account）对应 nickname | 必返、可 null（降级链 §3.1 同款） |
| MenuTreeNode | 同上 | 同上（种子菜单审计列为 null → 译文 null，属正确降级） | 同上 |
| SysDictTypeVo | 同上 | 同上（种子 createBy='system' 非真实账号 → 译文 null，降级显示 'system'） | 同上 |
| SysDictDataVo | 同上 | 同上 | 同上 |

- **SysUserVo 零触碰**：statusLabel 仍为 user_status 口径（§3 原文为准）——用户域不随本节变化
- 原字段（status/createBy/updateBy）语义与取值零变化（§1 红线对本节同样生效）；tag 颜色/编辑回填/行内判断仍用原字段
- 响应示例（role 分页种子行形态，menu/dict 同构）：

```json
{ "id": "1", "name": "管理员", "roleKey": "admin", "status": 0,
  "createBy": null, "createTime": "2026-10-05 21:30:00", "updateBy": null, "updateTime": null,
  "statusLabel": "正常", "createByName": null, "updateByName": null }
```

### 8.2 生效端点与消费方

| 端点 | 出参载体 | 既有消费方对新字段的消费 |
|---|---|---|
| GET /system/role/page | PageResult<SysRoleVo> | 角色管理页三列（§8.4） |
| GET /system/role/list | List<SysRoleVo> | 分配角色弹窗候选——**流入不消费**（JSON 多字段运行时忽略） |
| GET /system/menu/tree | List<MenuTreeNode> | 菜单管理页三列；AssignMenuDialog 流入不消费 |
| GET /system/dict/type/page | PageResult<SysDictTypeVo> | 字典管理页类型表状态列 |
| GET /system/dict/data/page | PageResult<SysDictDataVo> | DictDataDialog 弹框状态列 |

- **`/menu/tree` 嵌套子节点译文恒 null（机制边界）**：翻译收集只展开容器（PageResult.rows/Collection/数组），到 `@TranslateVO` 实例只收集**自身字段**、不遍历对象字段；且响应层级 R→List→根节点已达深度上限 3——**仅根节点（顶级菜单）回填译文**，嵌套子节点 statusLabel/createByName/updateByName 恒 null（字段键仍在 JSON 中，值为 null）。前端降级链兜底（common_status 项文案与本地 STATUS_MAP 一致，UI 零可见差异）；原字段（status/createBy/updateBy）不受影响照常返回。嵌套 VO 下钻增强记移交候选（另轮，见计划移交备忘 8）

### 8.3 common_status 内置种子声明

- sys_dict_type：`通用状态 / common_status / status=0 / is_builtin=1`；sys_dict_data：`正常 / "0" / sort=1`、`停用 / "1" / sort=2`（均 is_builtin=1，create_by='system' 种子惯例）
- **id 非契约内容**：新建环境基线为 2/3/4；存量环境经增量脚本自动分配（AUTO_INCREMENT 已被历史数据消耗，显式 id 会冲突）——两种形态均合法，**内置保护按 is_builtin 判定与 id 无关**（保护契约 §1/§7）
- 消费口径同 §2.1：`GET /system/dict/data/type/common_status` → 2 项（label 与 user_status 项文案一致为设计现状，两类型独立演化）
- user_status 种子与管理端语义零变化；字典管理页将可见两个内置类型（user_status / common_status）

### 8.4 前端消费映射（additive 增行）

| 消费方 | 接入 | 备注 |
|---|---|---|
| 角色管理页 | 状态 tag 文本 `statusLabel ?? STATUS_MAP[status].label ?? status`；创建人/更新人列 `*Name ?? 原字段 ?? '-'` | tagType 仍按原 status（§1 红线） |
| 菜单管理页（树表） | 同上三处 | 同上 |
| 字典类型表 / DictDataDialog | 仅状态列 statusLabel 降级链 | 审计列不展示（Round E 取舍维持；译文字段后端照给，UI 暂不消费） |
| TS 类型（types/api.ts） | SysRoleVo / MenuTreeNode / SysDictTypeVo / SysDictDataVo 各 + `statusLabel: string \| null; createByName: string \| null; updateByName: string \| null` | builtin 字段见保护契约 §7 |

- 错误码：**v1.1 仍零新增**（§4 账本延续：3013-3017 已占，3018 起预留给 bpmn）；译文失败一律降级 null（§1）

## 给 backend-agent / frontend-agent / e2e 的任务清单（v1.1 增补）

完整清单见 `docs/superpowers/plans/2026-10-07-translate-rollout-builtin-ui.md`（后端章 B2 / 前端章 F1-F5 / e2e 章 E2）。要点：

- **backend**：B1 common_status 种子（增量不写显式 id + 基线显式 id + 回查）；B2 四 VO 注解与译文字段（SysUserVo 零触碰）；B3 五域 builtin 传递见保护契约 §7（同轮同任务）
- **frontend**：F1 TS additive；F2-F5 四页 + DictDataDialog + UserFormDialog 翻译列/降级链/徽标/禁用
- **e2e**：E2 四脚本断言迁移（翻译每页 UI+fetch 各一条）
- 红线：既有端点 additive-only；user_status 与 admin 零触碰；错误码/权限标识零新增；降级链必做
