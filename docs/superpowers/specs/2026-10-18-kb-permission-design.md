# 知识库权限分配技术方案（2026-10-18）

> 需求：知识库管理功能之上增加权限分配——角色、部门（含子树）、直接用户三途径授权，任一命中即对该库有只读可见权。
> 本方案基于 2026-10-18 用户拍板结论（三维度独立并集 / 网关注入扩展 / 管理权限豁免 + 授权放开 / 部门固定含子树 / 单级只读 / 复用 ai:kb:update / 本轮不出「我的库」端点）。
> 配套契约：`docs/superpowers/contracts/2026-10-18-kb-permission-api.md`（前后端唯一对齐物）；实施计划：`docs/superpowers/plans/2026-10-18-kb-permission.md`。
> 读者假设：第一次接触本项目。背景术语（X-User-* 注入链、require_perms、ai:kb:* 四权限）见 CLAUDE.md「关键约定」与知识库既有契约 `2026-10-14-ai-kb-api.md`。

## 1. 背景与目标

知识库管理面（创建/上传/列表/编辑/删除/文件分页/重嵌入共 7 端点）已在线，鉴权是**操作级**的：持 `ai:kb:list` 者可见全部知识库，没有任何「谁能看见哪个库」的实例级控制。本轮补上实例级授权：

1. 授权主体三类：角色、部门（含子部门）、直接指定用户；三维度独立、并集语义（任一命中即有权）。
2. 被授权动作 = **只读可见**（列表行可见 + 文件可浏览）；上传/编辑/删除/重嵌入仍由功能权限独占裁决（双闸门，见 D6）。
3. 默认基线 = 管理员兜底 + 授权放开：持知识库管理动作权限者可见全部库；其余持 `ai:kb:list` 者仅见自己创建的 + 被授权的库。

**明确不做**（拍板排除）：聊天选库与检索过滤（检索轮消费，「我的可用知识库」端点本轮缓出）；多级授权（查看者/管理者分级）；跨服务主体同步框架；主体存在性校验（见 D10）。

## 2. 总体架构（文字版）

```
【登录快照链——Java 侧】
sys_user(sys_user_role→sys_role 启用角色) ──┐
sys_dept（dept_id 沿 parent_id 向上走祖先链） ├→ cloud-system /inner/user/{account}
                                            │   LoginUserDTO 新增 roleIds + deptIds
                                            └→ cloud-sso 登录/refresh → OnlineSession（Redis，新增两字段）
                                                 ↓
cloud-gateway AuthGlobalFilter：解析会话 → 剥离伪造头 → 注入
  X-User-Id / X-User-Account / X-User-Perms（既有）
  X-User-RoleIds / X-User-DeptIds（本轮新增，逗号分隔）
                                                 ↓
【授权配置与执行——Python 侧 cloud-base-ai】
配置流：前端知识库页「权限」弹窗 → PUT /ai/rag/kb/{id}/perms（ai:kb:update）
        → service 全删全插 ai_kb_grant（角色/部门/用户三类主体行）
执行流：GET /ai/rag/kb/list、GET /ai/rag/kb/{id}/files（ai:kb:list）
        → 解析身份头（user_id/role_ids/dept_ids/perms）
        → 豁免判定（持任一管理权限 → 全量可见）
        → 否则 create_by=本账号 OR kb_id ∈ 授权命中集
```

资源数据在 cloud_ai 库、主体主数据在 cloud_system 库——授权表落 cloud_ai 库（D4），主体 id 为弱引用；名称展示由前端用 system 域既有端点组装（D9）。

## 3. 关键设计决策

### D1 授权模型：三维度独立并集（拍板项，落定为表模型）

一个知识库的有效可见主体 = 全部角色授权 ∪ 全部部门授权 ∪ 全部用户授权。不做「角色×部门」交叉主体，不做授权分级（单级=只读可见）。与既有语义同构：数据权限框架的「多规则并集宽松者胜」（`2026-10-10-data-permission-design.md`）。

### D2 身份头形态：部门注入「祖先链」而非单值（对拍板细节的一处修正，重点审查项）

拍板原文是「X-User-DeptId 单值可空」。设计时发现**单值无法支撑「部门授权固定含子树」**：部门授权落在部门 D 上语义是「D 及其全部子孙部门」，用户（部门 X）被覆盖当且仅当 D 是 X 的祖先或 X 自身——判定需要部门树，而 AI 侧没有部门主数据（本轮已否决同步方案）。

修正为：**X-User-DeptIds = 用户自身部门 + 全部祖先部门 id，逗号分隔**（root→self 顺序，顺序无匹配语义）。此时「部门授权含子树」的匹配在 AI 侧退化为一次 `IN` 比较：授权部门 id ∈ 用户部门链即命中，零树计算。链在登录时由 cloud-system 求值（沿 sys_dept.parent_id 内存向上走，先例：部门树全量加载内存递归），随会话快照。

采纳口径：通道模式（登录快照 → 网关剥离伪造头注入 → AI 消费 → 快照时效=换部门/角色需重登或 refresh）与拍板完全一致，仅部门头的值语义从「单值」修正为「链值」。头名定为 `X-User-DeptIds` / `X-User-RoleIds`（复数形式与值形态一致）。

### D3 管理豁免标识：ai:kb:add / ai:kb:update / ai:kb:delete 任一

拍板留了「取哪个标识」给设计定。取**三个管理动作权限的任一**，理由：

- 双闸门（D6）下管理动作按 kb_id 直达、不做实例校验——能编辑/删除/上传的人若在列表里看不见目标库，操作面与可见面割裂（按 id 调 API 能改、页面上看不见），违背最小惊讶；
- 不取 `ai:kb:list` 单独豁免（否则授权无意义——人人全见）；
- 列表/文件端点本身要求 `ai:kb:list`，故「三管理标识任一」在端点调用面上等价于「四标识任一」，实现取三标识集合即可。

AI 侧常量 `MGMT_PERMS = {"ai:kb:add", "ai:kb:update", "ai:kb:delete"}`，与 X-User-Perms 求交集非空即豁免。

### D4 授权表落 cloud_ai 库：ai_kb_grant（纯关系表，物理删除）

```sql
CREATE TABLE ai_kb_grant (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '授权ID（主键）',
    kb_id       BIGINT      NOT NULL COMMENT '知识库ID（ai_knowledge_base.id，弱引用不建外键）',
    subject_type TINYINT    NOT NULL COMMENT '主体类型：0=角色 1=部门 2=用户',
    subject_id  BIGINT      NOT NULL COMMENT '主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）',
    create_by   VARCHAR(30) NULL COMMENT '授权操作人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（DB 时钟 SQL NOW() 口径，应用层不传时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_subject (kb_id, subject_type, subject_id),
    KEY idx_subject (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='AI 知识库授权（纯关系表：物理删除，随保存全删全插、随知识库删除级联清理）';
```

- **索引取舍**：`uk_kb_subject` 兼查重与配置回显（按 kb_id 前缀取全量授权行）；`idx_subject` 命中可见性求值主路径「按主体反查 kb_id」（subject_type+subject_id 等值 → kb_id 列表）。`ai_knowledge_base.create_by` **不**加索引：库表量级十百级，列表主路径是主键 id 倒序扫 + IN 过滤，为此列建索引收益为零。
- **形态沿 sys_user_role / sys_role_menu 纯关系表先例**：物理 DELETE、无 deleted 列、无 update 审计列（行只有插入与删除两个生命周期）；`create_time` 用 DDL `DEFAULT CURRENT_TIMESTAMP` 取 DB 时钟——符合 2026-10-18 全仓「审计时间 SQL NOW()」口径（Python 侧 ORM 插入**不设时间字段**，仅 create_by 显式给值）。
- **生命周期**：保存授权=按 kb_id 全删全插（同事务，D7）；删除知识库=级联物理删授权行（挂在既有 delete_kb 同一事务，与三表软删并列第四步——授权行是配置数据非业务数据，软删库后无恢复通道，物理清理无追溯损失）。
- subject_type 取值 0/1/2 与数据权限框架的主体枚举（角色 0/用户 1）**数值语义不同、不共享枚举**——两域各自定义，Python 实体内嵌 `SubjectTypeEnum`（禁魔法数）。

### D5 可见性执行面：两个读端点，SQL 层并集过滤

执行点本轮只有两处（检索/聊天消费是未来轮，模型已预留）：

1. **列表 `GET /rag/kb/list`**：豁免（D3）→ 行为与现状完全一致（无过滤）。非豁免 → 查询条件追加 `(create_by = :account OR id IN (:visible_ids))`，其中 visible_ids 由授权命中查询得出：

```sql
SELECT kb_id FROM ai_kb_grant
WHERE (subject_type = 2 AND subject_id = :user_id)
   OR (subject_type = 0 AND subject_id IN :role_ids)     -- 空列表时本分支不拼接
   OR (subject_type = 1 AND subject_id IN :dept_ids)     -- 空列表时本分支不拼接
```

   visible_ids 为空集时退化为仅 `create_by = :account` 条件（沿数据权限「XML 永不收到空集合、规避 IN () 非法 SQL」短路先例）。name 模糊过滤、分页钳制、id 倒序均不动。
2. **文件分页 `GET /rag/kb/{kb_id}/files`**：先校验库存在（5004，既有），再校验可见性：豁免 或 `kb.create_by == account` 或 `kb_id ∈ visible_ids`，否则新错误码 **5011「无权访问该知识库」**（不与 5004 混用——5011 表示「存在但无权」，便于被授权者排查与前端提示；存在性泄露面可接受：库 id 本身非敏感，且列表端点对无权者不回显）。

**这是对既有契约 §4/§7 的行为变更**（非豁免用户从「全见」变为「自己创建+被授权」），契约以修订段记档，消费方仅前端知识库页同轮收敛（先例：2026-10-15 列表管理轮 §4 重写同款 breaking 处理）。

### D6 双闸门：管理动作不做实例授权校验（拍板项记档）

上传（ai:kb:add）/编辑/重嵌入（ai:kb:update）/删除（ai:kb:delete）维持纯功能权限裁决，**不追加**「目标库是否对操作者可见」的检查。理由：管理权限持有者本就豁免全量可见（D3），两闸门在实际权限组合下自洽；免掉四处端点的重复校验逻辑。衍生边界（记档勿当 bug 报）：持 add 而无任何管理豁免组合不存在（add 本身在豁免集合内）——即「能管理必能见」，双闸门无割裂路径。

### D7 授权端点与保存语义：查询 + 全量覆盖

- `GET /rag/kb/{kb_id}/perms`（ai:kb:update）：回显三类主体 id 数组。读授权清单也是管理配置动作（探测他人库的授权面不应开放给 list-only 用户），故与保存同权限。
- `PUT /rag/kb/{kb_id}/perms`（ai:kb:update）：全量覆盖（按 kb_id 物理删旧行 + 批插新行，同一事务）——沿数据权限列规则「save 全删全插、无独立 update 路径」先例。空三数组=清空授权（库回落到「仅创建者+豁免者」可见）。并发保存后写者胜（整集原子替换）。校验：元素为 ≥1 整数、每数组 ≤100（重复元素服务端静默去重）；越界 1001。

### D8 会话兼容与快照时效（Java 侧改动面）

- **OnlineSession 加字段非破坏**：Redis 会话是纯 JSON（无 @class），sso 与网关两侧 ObjectMapper 均 `FAIL_ON_UNKNOWN_PROPERTIES=false`。旧会话缺新字段 → 反序列化得 null → 网关注入空串（与 account 为 null 时注入 "" 同款处理）→ AI 侧解析为空列表=无部门无角色。**既有在线会话无需清理**，重登或 refresh 后取新值（refresh 链路回查 system 重取 LoginUserDTO，自动携带新字段——零额外改动）。
- **快照时效**：换部门/换角色后需重登或 refresh 才反映到授权判定，与权限快照同语义（已接受先例，CLAUDE.md「鉴权数据流」）。
- **角色 id 口径**：与 permissions 聚合同源——仅**启用**角色（sys_role.status=0 且 deleted=0），停用角色的授权自然失配（自愈，见 D10）。
- **部门链求值**：sys_user.dept_id 为 NULL → 空链；否则沿 parent_id 向上收集至根（parent_id=0），全部走未删除部门行。部门删除有「存在子部门或在职用户阻止」（既有 3030 约束），链上不会出现软删节点；防御性断链即止。

### D9 前端数据源与两处 system 端点权限放宽

授权弹窗三块选择器的数据源**全部复用 system 域既有端点，Java 侧零新端点**：

| 选择块 | 数据源 | 现有权限 | 问题与处置 |
|---|---|---|---|
| 角色多选 | `GET /system/data-perm/subject-options?type=0`（返回启用角色，label=角色名） | `system:dataPerm:list` | KB 管理员可能无此权限 → **放宽** |
| 部门树勾选 | `GET /system/dept/tree`（全量树） | `hasAnyAuthority(system:dept:list, system:user:add, system:user:edit)` | 同上 → **放宽** |
| 用户多选 | `GET /system/data-perm/subject-options?type=1`（启用用户，label=`昵称(账号)`） | `system:dataPerm:list` | 同上 → **放宽** |

放宽方式：两个端点的权限表达式各追加 `'ai:kb:update'`（dept/tree 的 hasAnyAuthority 列表 +1；subject-options 的 @PreAuthorize +1）。先例：dept/tree 本就是「部门管理与用户表单共同数据源」的多权限端点（2026-10-10 数据权限设计）。这是对既有契约 `2026-10-10-data-permission-api.md` §2.1/§3.6 权限行的 additive 修订，随本轮契约一并记档（见契约 §6）。

**id 类型换轨**：system 域出参 id 是字符串（Java Long→String），AI 域入参是 JSON number——前端在弹窗内做 `Number(id)` 转换后提交（自增 id 值域内安全，非雪花）。回显时 granted id 不在选择器选项中（主体已停用/删除）→ 显示「id:N（已失效）」占位 tag，不阻断保存。契约 §1 有专段提醒。

### D10 主体弱引用与自愈：不做存在性校验（记档的取舍）

AI 侧无主体主数据，保存授权**不校验** role/dept/user id 是否存在（前端选择器只提供真实主体，脏数据仅来自手工 API 调用）。自愈机制：删除/停用的角色不再进任何用户的快照 roleIds、删除的用户无法登录、删除的部门不在任何用户的部门链上——失效授权行永不命中，无清理任务负担。代价：授权表可能积累永不命中的死行（量级=手工脏数据，可忽略）；回显时前端以「已失效」占位呈现。若未来要求严格校验，需 AI→system 存在性查询通道（移交备忘）。

## 4. 数据流

**配置流**（低频管理动作）：知识库页行内「权限」按钮（v-perms ai:kb:update）→ 弹窗并行拉 subject-options×2 + dept/tree + GET perms 回显 → 勾选 → PUT perms（三数组 number）→ service 事务内 [delete grants by kb_id → insert 新行（create_by=contextvar 账号，create_time 不设由 DB DEFAULT 生成）] → 前端 toast + 关闭。

**读流**（每次列表/文件请求）：网关注入五头 → require_perms 校验 ai:kb:list 通过后，Identity 解析（user_id/dept_ids/role_ids/perms 存 contextvar）→ service 判豁免 → 非豁免跑授权命中查询（一条 SQL 三分支 OR，命中 idx_subject）→ 列表条件并入 / 文件端点判定 5011。

## 5. 错误处理

| 场景 | 码 | 说明 |
|---|---|---|
| 无 ai:kb:update 调授权两端点 | 403 | 既有语义（require_perms） |
| perms 端点 kb_id 不存在 | 5004 | 活记录视角，与 kb 契约共用 |
| 文件分页对不可见库 | **5011（新）** | 「无权访问该知识库」；先 5004 后 5011（存在性校验在前） |
| 入参非法（kb_id 非整数、数组元素 <1、数组超 100） | 1001 | pydantic + service 关系校验 |
| 授权命中查询失败 | 500 | 全局兜底（读路径不吞异常） |

AI 段错误码占用至 5010（MCP），本轮启用 **5011**，下一可用 5012。

## 6. 测试策略

- **Java 单测**：SysUserLinkageService（roleIds/deptIds 聚合，含无角色/无部门/停用角色分支）；TokenService（OnlineSession 新字段序列化 + 旧格式 JSON 反序列化兼容用例——缺字段的存量会话样例）；AuthGlobalFilterTest（新头注入/伪造头剥离/旧会话空值注入三用例）；dept 链求值（深链/断链/NULL）。
- **Python 单测**：授权命中查询（三分支组合/空集短路）；list 过滤（豁免/创建者/被授权/三者皆非）；files 5011；save 全删全插（含空数组清空）；delete_kb 级联清理授权行；test_architecture_guard 全绿（实体列 comment/分层依赖/事务边界红线）。
- **双路实证**（验证记录回填契约 §7）：8010 直连手工头（ perms + RoleIds + DeptIds 组合矩阵）+ 网关全链（admin 豁免、普通 list-only 用户 own+granted、5011 分支）。
- **e2e**：cloud-e2e 新增 kb 授权场景脚本（route mock 层，沿 run-ai-kb-e2e.mjs 形态）；既有 kb e2e 用 admin（豁免者）行为不变——跑一遍回归确认断言不破。

## 7. 移交后续阶段的备忘

1. **检索轮消费点**：检索/聊天选库的可见库判定直接复用本方案的授权命中查询（Identity + ai_kb_grant）；「我的可用知识库」只读端点本轮缓出（拍板），届时按本契约 additive 扩展。
2. **会话兼容**：OnlineSession 新字段对存量会话向下兼容已设计（空值语义），但若未来再扩身份头，同款处理（加字段不删字段、网关注入空串兜底）。
3. **多级授权扩展点**：ai_kb_grant 预留演进路径——加 perm_level 列即升级为分级授权（本轮拍板单级）；出参若从 `*_ids: number[]` 改为对象数组属 breaking，届时契约须走修订段。
4. **主体严格校验**：若要求保存时校验主体存在/启用，需 AI→system 存在性查询通道（本轮 D10 自愈口径够用）。
5. **subject-options 全量用户**：用户量增长后该端点（无分页）会变大——影响的是数据权限域既有端点，非本轮引入；届时分页化属 data-perm 契约演进。
6. **Java 侧身份快照注入目前仅 AI 消费**：bpmn 等其它服务若需要部门/角色感知，头已就位，HeaderAuthFilter 按 X-User-Perms 构建 authorities 的机制无需变动。
