# AI 知识库授权 API 契约（cloud-base-ai + 网关身份头扩展，2026-10-18 首立）

> 前后端唯一对齐物。本轮覆盖：授权查询/保存两端点（§2/§3）、既有列表与文件端点的可见性行为修订（§4/§5）、
> 网关新身份头规格（§6，Java 侧改动面的消费方对齐物）、对数据权限契约两端点的权限放宽修订（§7）。
> 母契约：`2026-10-14-ai-kb-api.md`（知识库 7 端点与通用口径——R 结构、AI 域出参 snake_case、
> id/total 为 JSON number、分页参数名 page/size——本文沿用不重复）。
> 设计依据：`docs/superpowers/specs/2026-10-18-kb-permission-design.md`。
>
> **2026-10-18 行为变更声明（breaking，消费方=前端知识库页同轮收敛）**：§4 列表与 §5 文件分页对
> **非管理豁免用户**从「全量可见」改为「自己创建 + 被授权」。豁免判定与授权模型见 §1。

## 1. 授权模型与通用约定

- **授权主体三类，独立并集**：角色 / 部门（**固定含子部门树**）/ 直接用户——任一命中即对该库有
  **只读可见**权（列表行可见 + 文件可浏览）。上传/编辑/删除/重嵌入仍由功能权限（ai:kb:add/update/delete）
  独占裁决，**不做实例授权校验**（双闸门）。
- **默认可见性基线**：持 `ai:kb:add` / `ai:kb:update` / `ai:kb:delete` **任一**者（管理豁免）可见全部库；
  其余持 `ai:kb:list` 者仅见 `create_by = 本账号` 的库 + 被授权的库。豁免判定消费 X-User-Perms，AI 侧实现。
- **本轮权限标识零新增**：授权两端点复用 `ai:kb:update`（2026-10-18 拍板，沿重新向量化轮「修改类动作
  复用编辑权限」先例——零新种子零重登）。
- **错误码**：403 无操作权限 / 1001 参数校验 / 5004 知识库不存在（活记录视角，母契约共用）/
  **5011 无权访问该知识库（本轮新立，AI 段接续 5009/5010 之后）** / 500 系统异常。AI 段下一可用 5012。
- **主体 id 类型换轨（前端对接重点）**：授权两端点属 AI 域——`role_ids` / `dept_ids` / `user_ids` 均为
  **JSON number 数组**；而选择器数据源（system 域 subject-options / dept/tree）出参 id 为**字符串**
  （Java Long→String）。前端提交前逐个 `Number(id)` 转换（自增 id 值域内安全，非雪花）。
- **主体弱引用**：保存不校验主体存在/启用（AI 侧无主数据）；失效主体授权永不命中（自愈语义），
  回显时前端以「id:N（已失效）」占位呈现。

## 2. GET /rag/kb/{kb_id}/perms 查询授权（perms `ai:kb:update`）

- 路径参数：kb_id int 必填（非整数 → 1001）。外部路径 `GET /ai/rag/kb/{kb_id}/perms`。
- 入参：无 body。
- 语义：回显该库当前全部授权行，按主体类型分三组。目标库不存在（软删不可见）→ 5004。
  **读授权清单与保存同权限**（管理配置动作，不开放给 list-only 用户探测授权面）。
- 返回 `R<KbPermsVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| role_ids | number[] | 被授权角色 id 集合（sys_role.id）；无则 `[]` |
| dept_ids | number[] | 被授权部门 id 集合（sys_dept.id；**每条含子部门树**语义在服务端求值侧生效，本字段只存被勾选节点）；无则 `[]` |
| user_ids | number[] | 被授权用户 id 集合（sys_user.id）；无则 `[]` |

- 示例：`{"code": 200, "msg": "操作成功", "data": {"role_ids": [2, 5], "dept_ids": [3], "user_ids": [952]}}`
- 分支：200 / 403 无 `ai:kb:update`（持 `ai:kb:list` 亦拒——权限按动作分立）/ 1001 kb_id 非整数 / 5004 目标不存在。

## 3. PUT /rag/kb/{kb_id}/perms 保存授权（perms `ai:kb:update`）

- 路径参数：kb_id int 必填（非整数 → 1001）。外部路径 `PUT /ai/rag/kb/{kb_id}/perms`。
- 入参 `KbPermsSaveReq`（body JSON）：

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| role_ids | number[] | 否（缺省 `[]`） | 每元素整数 ≥1；数组 ≤100 | 全量覆盖角色授权 |
| dept_ids | number[] | 否（缺省 `[]`） | 同上 | 全量覆盖部门授权（含子树语义由服务端求值） |
| user_ids | number[] | 否（缺省 `[]`） | 同上 | 全量覆盖用户授权 |

- 重复元素**服务端静默去重**（同 id 多次出现只落一行）；跨数组互不影响（主体类型不同不冲突）。
- 语义：**全量覆盖**（按 kb_id 物理删全部旧授权行 → 批插新行，同一事务）。三数组全空/不传 = 清空授权
  （库回落「仅创建者 + 豁免者」可见）。并发保存后写者胜（整集原子替换）。
- 返回 `R<null>`：`data` 恒 null。
- 分支：200 保存成功 / 403 无 `ai:kb:update` / 1001（kb_id 非整数、元素非整数或 <1、任一数组超 100 项）/
  5004 目标库不存在（活记录视角）。
- 行为要点（前端对接须知）：保存成功即对**新登录/既有会话**立即生效（授权求值实时读表，**不经会话快照**——
  快照的只有用户自己的部门/角色归属）；被移除授权的在线用户下一次列表请求即不可见。
- 审计：新授权行 create_by = X-User-Account；create_time 由 DB 时钟生成（SQL NOW() 全仓口径，应用层不传时间）。

## 4. 母契约 §4 修订：GET /rag/kb/list 可见性过滤（perms `ai:kb:list`）

- 入参/出参/分页语义**零变化**（page/size/name、id 倒序、KbVo 四字段），仅过滤行为修订：
  - 管理豁免用户（X-User-Perms 含 `ai:kb:add`/`ai:kb:update`/`ai:kb:delete` 任一）：全量分页，行为同旧版；
  - 其余用户：仅返回 `create_by = 本账号` 或被授权（角色命中 ∪ 部门含子树命中 ∪ 直授用户命中）的库。
- **本地直连差异提示**：绕网关直连 :8010 调试时，手工头须多带 `X-User-Id` / `X-User-RoleIds` / `X-User-DeptIds`
  （格式见 §6），否则非豁免用户只会看到自己创建的库（身份头缺失=空集，不算错误）。

## 5. 母契约 §7 修订：GET /rag/kb/{kb_id}/files 可见性校验（perms `ai:kb:list`）

- 入参/出参零变化。校验顺序变为三步：① 库不存在 → **5004**（活记录视角，不变）；② 管理豁免 或
  `create_by = 本账号` 或授权命中 → 200 正常分页；③ 否则 → **5011** `{"code":5011,"msg":"无权访问该知识库"}`。
- 5011 语义边界（记档）：表示「库存在但当前用户无权访问」；与 5004（不存在/软删）区分，便于被授权者
  排查与前端提示。管理动作端点（上传/编辑/删除/重嵌入）**不产生 5011**——双闸门下不做实例校验。

## 6. 网关身份头扩展（Java 侧改动对齐物，AI 侧消费规格）

AuthGlobalFilter 注入头从三个扩为五个（剥离外部伪造同名头的范围同步扩大）：

| 头 | 格式 | 值来源（登录快照） | 空值语义 |
|---|---|---|---|
| X-User-Id（既有） | 十进制数字串 | JWT subject | — |
| X-User-Account（既有） | 字符串 | JWT account 声明 | 注入空串 |
| X-User-Perms（既有） | 逗号分隔权限标识 | OnlineSession.permissions | 注入空串 |
| **X-User-RoleIds（新）** | 逗号分隔角色 id，如 `"2,5"` | OnlineSession.roleIds（仅启用角色的 id，登录时聚合） | 无角色/旧会话 → 注入空串 → AI 侧空列表 |
| **X-User-DeptIds（新）** | 逗号分隔部门 id，**= 用户自身部门 + 全部祖先**（root→self 顺序，顺序无匹配语义），如 `"1,3,12"` | OnlineSession.deptIds（登录时沿 sys_dept.parent_id 向上求链；未挂部门 → 空串） | 同上 |

- **设计说明**：拍板原文的「X-User-DeptId 单值」修正为**祖先链**——部门授权固定含子树，单值部门头无法
  在 AI 侧（无部门树）判定祖先关系；链值使「含子树」匹配退化为一次 IN 比较（设计文档 D2）。
- **快照时效**：换部门/换角色后需重登或 refresh 生效（与权限快照同语义，既有先例）。
- **兼容性**：OnlineSession 加字段对存量 Redis 会话向下兼容（缺字段=空值→空串注入）；refresh 链路回查
  system 自动携带新值。存量会话不清理，AI 侧空值=无部门无角色（授权不命中，无错误）。
- AI 侧解析容错：逗号分段非整数内容跳过（记 warning，不报错）；整头缺失/空串 = 空列表。

## 7. 对既有契约的 additive 修订（权限放宽，随本轮落地）

| 契约 | 端点 | 修订 |
|---|---|---|
| `2026-10-10-data-permission-api.md` §2.1 | `GET /system/dept/tree` | 权限从 `hasAnyAuthority('system:dept:list','system:user:add','system:user:edit')` 追加 `'ai:kb:update'`（知识库授权弹窗的部门树数据源；沿该端点多权限共同数据源先例） |
| `2026-10-10-data-permission-api.md` §3.6 | `GET /system/data-perm/subject-options` | @PreAuthorize 从 `system:dataPerm:list` 改为 `hasAnyAuthority('system:dataPerm:list','ai:kb:update')`（知识库授权弹窗的角色/用户选项数据源） |

两处均为**放宽**（新增可访问权限标识，不收紧），既有消费方零影响；data-perm 契约文档的权限行由本轮
实施时同步更新（修订声明指向本文档）。

## 8. 种子

零新增（授权两端点复用 184 `ai:kb:update`，2026-10-18 拍板）。落库脚本仅 `cloud_ai` 库新表
`ai_kb_grant`（DDL 见设计文档 D4，基线 cloud_ai.sql 同步 + 增量脚本，pymysql 落库沿中文入库规则）。

## 9. 验证记录（2026-10-18 双路实证回填）

环境：MySQL/Nacos/Redis 常驻；sso 9201 → system 9202 → gateway 18080 + AI 基座 8010 由本轮拉起。
验证脚本 `cloud-base-ai/scripts/p4_*.py`（stage_a 网关+inner / direct 直连矩阵 / gateway+gateway2 网关全链 /
cleanup 收尾清理），测试数据全部 `e2e_kbperm_*` 前缀，验后已清理（见末条）。

- **§2/§3 授权两端点（网关全链，admin 登录 token，ts=1792295481）**：
  - 初始空 `GET /ai/rag/kb/15/perms` → `{"role_ids":[],"dept_ids":[],"user_ids":[]}`；
    `PUT {"role_ids":[2],"dept_ids":[3],"user_ids":[1]}` → 200 data=null，回显三组精确一致；
  - DB 回查（pymysql）：`ai_kb_grant WHERE kb_id=15` 恰 3 行（subject_type 0/1/2 各一）、
    `create_by='admin'`、`create_time` DB 时钟非空；
  - 1001 分支四条全中：kb_id=abc（路径非整数）→ 1001；`{"role_ids":[0]}`（元素<1）→ 1001；
    `{"dept_ids":["3"]}`（数字串）→ 1001；`user_ids` 101 元素 → 1001；
  - 5004：GET/PUT `kb_id=999999` → 5004「知识库不存在」（校验前置，不落任何行）；
    部分 body（仅空 role_ids）合法 = 清空该组（全量覆盖语义）。
- **§4 三态 + §5 5011（8010 直连，手工五头矩阵；三库 = own 库 create_by=e2e_kbperm_user /
  admin 库 create_by=admin / other 库 create_by=someone）**：
  - 管理豁免：perms 分别取 `ai:kb:add`/`ai:kb:update`/`ai:kb:delete`（仅其一）→ 三库 total=3 全量可见；
  - list-only + `X-User-RoleIds=9`（角色授权在 admin 库）→ total=2（own + admin 库）；
    list-only + `X-User-DeptIds="1,3,12"`（部门授权在祖先节点 1）→ 命中 admin 库；
    list-only + 直授用户（`X-User-Id=952`）→ total=2（own + other 库）；
  - 空身份头（仅 Perms/Account）→ 仅 own（空集语义不报错）；
  - files：own 库 200 / 角色命中库 200 / 未授权库 → **5011「无权访问该知识库」** /
    管理豁免（`ai:kb:delete`）对他人库 200 / kb=999999 恒 5004（先于可见性判定）；
  - 权限分立：仅 `ai:kb:list` GET/PUT perms 均 403；无 Perms 头 403；
  - 容错：`X-User-RoleIds="8,x,9"`/`X-User-DeptIds="zz,1"` 非整数分段跳过不报错（9/1 照常参与匹配）。
- **§6/网关链全量（18080，list-only 测试用户 e2e_kbperm_user：角色 223 仅菜单 182、挂部门 106，
  父部门 105、脱离链部门 107）**：
  - inner 聚合（B2）：`GET /inner/user/e2e_kbperm_user` → `roleIds=["223"]`、
    `deptIds=["1","105","106"]`（祖先链 root→self）、perms=`["ai:kb:list"]`；admin 同构
    （`roleIds=["1"]`、`deptIds=["1"]`）；
  - 授权前：list 仅 own（total=1）；
  - **网关伪造头剥离（B1）**：授权 admin 库部门 999 后，请求自带伪造
    `X-User-DeptIds: 999`/`X-User-RoleIds: 888` → 仍仅 own（伪造值被剥离、真会话值注入）；
  - **角色授权命中**：`PUT {"role_ids":[223]}` → list total=2（own+admin 库）；
  - **部门祖先链命中 + 快照时效双向**：`PUT {"dept_ids":[105]}`（授权在父部门）——
    旧会话（deptIds=[1,107] 不含 105）不命中 total=1；重登（快照 [1,105,106]）命中 total=2 且
    `GET .../files` 200（files 门禁同口径）；再移部门 107 后旧会话仍命中（快照未刷新前保留访问）、
    重登后不命中——「权限变更需重新登录/refresh 生效」语义实证；
  - **B4 旧会话空值兼容**：Redis `sso:online:{jti}` 会话 JSON 删去 roleIds/deptIds 两字段后
    （模拟本轮上线前的存量会话），list 回落仅 own、files → 5011，全程无 500；
  - **id 类型换轨陷阱实证（§1）**：system 域出参 id 为 JSON 字符串（Java Long→String），
    未转换直传 `{"role_ids":["223"]}` → 1001「Input should be a valid integer」——strict 校验
    按设计拒收，前端必须 `Number()` 后提交（正是 §1 规定数字的原因）；
  - 既有端点回归（admin 豁免路径）：创建/编辑/files/列表/删除/删后 5004 行为零变化。
- **§7 两端点权限放宽实测（B3 验收）**：造仅挂菜单 184（`ai:kb:update`，无任何 system:* 权限）
  的角色与用户，经网关 `GET /system/dept/tree` → 200（items=1 根节点）、
  `GET /system/data-perm/subject-options?type=1` → 200（items=3）；对照——仅菜单 182
  （`ai:kb:list`）用户两端点均 body code=403（证明是新增权限标识放行而非端点裸奔）。
  验证脚本 `cloud-base-ai/scripts/p4_b3_check.py`，验后测试用户/角色已删。
- **清理与残留**：测试库 15/17/18、用户 1008、角色 223、部门 105/106/107 全部删除端点软删
  （DB 回查 deleted=1），`ai_kb_grant` 表 0 行（库删除级联物理删授权实证）；测试用户全部 Redis
  会话（6 online + 1 refresh）清除；服务已按开发期自管约定停服交还。
