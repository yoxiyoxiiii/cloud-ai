# MCP 服务用户授权 API 契约（2026-10-30 MCP server 权限轮）

> 前后端唯一对齐物。授权模型与运行面影响的设计依据：`docs/superpowers/specs/2026-10-30-mcp-server-permission-design.md`（用户拍板：server 级新增授权、角色+部门+用户三主体、AI 域授权表实时判定、默认拒绝+管理豁免）。
> 运行面装配口径的契约修订见 `2026-10-29-agent-platform-api.md` §3 + §9（由本轮同步修订）。
> 母契约（MCP 管理面九端点与错误码基线）：`2026-10-30-mcp-server-mgmt-api.md`，本契约复用其错误码与 R 结构，不重复定义。

## 0. 端点总览与权限分段

本轮新增两端点，**权限标识零新增零种子**——两端点同挂既有 `ai:mcp:update`（读授权与保存同权限，沿知识库授权 2026-10-18 先例：读授权清单是管理配置动作，不开放给 list-only 用户探测授权面）。

| 方法+路径 | 说明 | perms |
|---|---|---|
| GET /ai/mcp/server/{id}/perms | 查询某 MCP 服务的用户授权（§2） | ai:mcp:update |
| PUT /ai/mcp/server/{id}/perms | 保存授权（三组全量覆盖，§3） | ai:mcp:update |

外部一律走网关 `:18080` 前缀 `/ai`（StripPrefix 后到 cloud-base-ai :8010）；认证继承 AuthGlobalFilter 全链路，本地直连调试须手工带 `X-User-Perms` 头。运行面豁免消费的 `ai:mcp:list` 亦为既有标识（见 §1），无新种子。

## 1. 授权模型与通用约定

- **授权主体三类，独立并集**：角色 / 部门（**固定含子部门树**，求值在服务端——网关注入的 X-User-DeptIds 已聚合自身+全部祖先链，部门命中退化为 IN 比较）/ 直接用户。任一命中即对该 server 获得**装配权**：其名下已发布工具可进入该用户发起的 agent 运行工具面（仍需逐工具过 required_perms 门，见 §4）。
- **默认拒绝 + 管理豁免**：无任何授权行的 server，对运行用户一律不可用（工具静默不注入）；持 `ai:mcp:list` 者豁免——全量 server 免授权可用（「能管即能用」）。**豁免只免 server 授权门**，工具级 required_perms 门对所有人照常生效（拍板：工具级沿用既有）。
- **即时生效，无快照失效**：授权保存后下一次 agent 运行即生效（运行时实时查授权表，非登录快照）——与平台权限标识的「重新登录生效」体系并列，互不干扰。
- **绑定面不滤授权（防误读，定死）**：agent 编辑的 server 选项端点（agent 契约 §2.8）恒返回全部启用未删 server；无权用户可绑定（预绑定），运行期静默过滤——沿「停用行可绑」的「配置宽、运行严」先例。管理面 server 列表/详情、agent 列表/详情回显均不做授权过滤（配置事实）。
- **主体弱引用**：保存不校验主体存在/启用（主数据在 cloud_system 库），失效主体授权永不命中（自愈语义），前端以「已失效」占位呈现。
- **与 Java 侧叠加语义**：内置 SYSTEM server 的工具调用仍由 cloud-system `@McpRequirePerm` 逐工具最终裁决；AI 侧授权门是装配层过滤，双层都过才通。**外部 server 风险记档**（沿 2026-10-30 管理契约 §4 口径）：外部服务不认识平台权限体系，AI 侧授权门同属纯客户端治理层、非服务端强制，知情接受。
- **错误码零新增**：403 无操作权限 / 1001 参数校验 / 5021 MCP 服务不存在（母契约既有码复用）。AI 段 5xxx 下一可用仍为 5030。
- **运行面被拒语义**：静默缩窄——无权 server 的工具不注入模型、不报错不提示（agent 正常运行，工具面变窄），沿工具门既有语义。

## 2. GET /ai/mcp/server/{id}/perms 查询授权（perms ai:mcp:update）

- 路径参数：id int 必填（非整数 → 1001）。外部路径 `GET /ai/mcp/server/{id}/perms`。
- 入参：无 body。
- 语义：回显该 server 当前全部授权行，按主体类型分三组。目标 server 不存在/软删 → 5021。
- 返回 `R<ServerPermsVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| role_ids | number[] | 被授权角色 id 集合（sys_role.id）；无则 `[]` |
| dept_ids | number[] | 被授权部门 id 集合（sys_dept.id，**只含被勾选节点**，子树语义在求值侧生效）；无则 `[]` |
| user_ids | number[] | 被授权用户 id 集合（sys_user.id）；无则 `[]` |

- 主体选择器数据源（前端）：`GET /system/data-perm/subject-options?type=0|1`（type 常量沿数据权限契约：0=角色 SUBJECT_ROLE、1=用户 SUBJECT_USER）与 `GET /system/dept/tree`。**已知依赖边界**：两数据源端点权限现为 `hasAnyAuthority('system:dataPerm:list','ai:kb:update')`（2026-10-18 契约对知识库面板的 additive 放宽）——本轮 Java 侧零改动，意味着**只持 ai:mcp:\*、不持上述任一标识的角色打开授权面板时选项加载 403**（授权面板本身仍可开，仅选择器无选项）。过渡口径：此类角色发放 `system:dataPerm:list` 或 `ai:kb:update` 任一即可；根治（把 `ai:mcp:update` 加入该端点 additive 放宽，一行注解改动、有 2026-10-18 先例）作为待办记入实施计划移交备忘，不在本轮拍板范围内擅动 Java。

## 3. PUT /ai/mcp/server/{id}/perms 保存授权（perms ai:mcp:update）

- 路径参数：id int 必填（非整数 → 1001）。外部路径 `PUT /ai/mcp/server/{id}/perms`。
- 入参 `ServerPermsSaveReq`（body JSON）：

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| role_ids | number[] | 否（默认 []） | 每元素 ≥1 且必须 JSON number（strict：数字串 "3" 拒收 1001）、每数组 ≤100 | 全量覆盖角色授权 |
| dept_ids | number[] | 否（默认 []） | 同上 | 全量覆盖部门授权（只传被勾选节点，子树语义求值侧生效） |
| user_ids | number[] | 否（默认 []） | 同上 | 全量覆盖用户授权 |

- 语义：**三组全量覆盖**（按 server_id 全删全部旧授权行再批插新行，同一事务）；重复元素服务端静默去重；三组全空/不传 = 清空授权（该 server 回到默认拒绝，仅豁免者可用）——合法语义非错误。
- 主体弱引用不校验存在（§1）；并发保存撞 uk 属极端窗口，事务回滚走全局 500（无专用码，沿知识库授权同款口径）。
- **保存后无需缓存失效动作**：运行面 compiled agent 缓存键已含工具面指纹（§4），授权变化自然走新键重组装。
- 内置 SYSTEM 行（is_builtin=1）同样可授权可保存（无特殊限制）。
- 返回 `R<null>`。
- 错误：5021（server 不存在/软删）/ 1001（参数）/ 403（无 ai:mcp:update）/ 500（系统异常）。

## 4. 运行面影响（既有端点行为变化，无新增运行端点）

- **装配口径升级**（对 agent 契约 §3 两个运行端点共同生效，修订已落其 §9）：生效工具面求值链变为——行可见过滤（已发布且归属 server 启用未删）→ **server 授权门（新：管理豁免短路，否则三主体授权命中，未授权 server 的工具静默剔除）** → 工具级 required_perms 门（不变）。被剔除的工具不报错（agent 静默缩窄工具面运行）。
- **compiled agent 缓存键修复记档（行为修复，无端点变化）**：缓存键由 `(agent_id, config_version)` 扩为 `(agent_id, config_version, 工具id序列)`——原键在缓存命中时忽略按用户过滤后的工具面，首个组装用户的工具面会被后续用户复用（既有 required_perms 门的潜伏缺陷，本轮按用户授权使其必现，修复方案见设计文档 §2.2）。
- 管理面零变化：server 列表/详情、工具清单/审核、agent 列表/详情与 options 端点行为均不变（配置事实不滤授权）。

## 5. 种子（`cloud-base/scripts/sql/2026-10-30-mcp-server-permission.sql`；cloud_ai.sql 基线同步）

### 5.1 cloud_ai 库

- `ai_mcp_server_grant` DDL（设计文档 §1.1：uk_server_subject 兼查重与回显、idx_subject 命中运行面按主体反查）。
- 初始授权一行：SYSTEM server（id=1）授 sys_role id=1（内置管理员角色，基线种子恒存在）。取舍：管理员本就持 `ai:mcp:list` 豁免，此行价值是授权状态面板可见、豁免口径收紧时不回退管理员可用性。
- **业务用户上线后需一次性手工授权**（默认拒绝的已接受代价）：管理员在 MCP 管理页对 SYSTEM 及各外部 server 按角色/部门/用户补授权。外部 server 不做种子。
- 前置断言：`SELECT id FROM ai_mcp_server_grant` 应 0 行（表新前无此表）。

### 5.2 cloud_system 库

**零改动**——无菜单种子（两端点挂既有 ai:mcp:update，豁免消费既有 ai:mcp:list）。

## 6. e2e 影响

- 聚合脚本零同步（菜单总数不变、无侧边串变化）。
- 新场景 `run-mcp-perm-e2e.mjs`（route mock，独立入口 `e2e:mcpperm`，不进 `e2e` 聚合——沿 AI 域场景独立入口先例）：授权面板生命周期（打开回显/勾选保存 payload 断言/失效主体占位/5021 分支）。
- 运行面三态（授权命中注入/默认拒绝剔除/管理豁免短路）由后端测试矩阵覆盖（`test_agent_run_service.py`，内存库+伪造身份头）——e2e 为 route mock 无法求值真实装配，且无 UI 面呈现按用户工具面，不做假验证。
