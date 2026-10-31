# MCP Server 用户级权限设计（2026-10-30 MCP server 权限轮）

> 关联契约：`docs/superpowers/contracts/2026-10-30-mcp-server-permission-api.md`（本轮新立，授权两端点）
> 与 `docs/superpowers/contracts/2026-10-29-agent-platform-api.md` §3 修订（运行面装配门，修订记录见其 §9）。
> 实施计划：`docs/superpowers/plans/2026-10-30-mcp-server-permission.md`。

## 0. 背景与拍板结论（结论先行）

MCP 服务（ai_mcp_server 注册表）此前只有启用/停用与软删，**谁能用哪个 server 完全没有用户/角色维度**：agent 绑定 server 后，其名下已发布工具对任何持 `ai:agent:run` 的运行用户都进入模型工具面（仅受工具级 required_perms 门约束，而外部同步工具该值多为空）。本轮按用户拍板补齐 server 级用户授权，四项结论：

| 拍板项 | 结论 |
|---|---|
| 授权粒度 | **server 级新增授权**；工具级沿用既有 required_perms 门不变——两层交集语义：过 server 授权门后，幸存工具仍逐个过 required_perms 门 |
| 授权主体 | **角色 + 部门（固定含子部门树）+ 用户** 三主体独立并集，任一命中即授权——与知识库授权（ai_kb_grant，2026-10-18 轮）完全同构 |
| 授权模型 | **AI 域独立授权表**（资源 id + 主体类型 + 主体 id），运行时实时查库判定，即时生效；授权面板内嵌 MCP 管理页 |
| 默认语义 | **默认拒绝 + 管理豁免**：无授权行的 server 对运行用户不可用；持 `ai:mcp:list` 者豁免（能管即能用，全量 server 免授权） |

三项次级口径（主控向用户声明后按默认执行）：**仅运行面强制**——绑定面 options 端点与管理面回显不做授权过滤，沿「配置宽、运行严」先例；**被拒静默缩窄**——无权 server 的工具不注入模型、不报错不提示（沿工具门既有语义）；**Java 侧不动**——内置 server 的 Java 端 `@McpRequirePerm` 工具级裁决保持原样，与 AI 侧授权门叠加（叠加语义见 §2.4）。

**豁免范围定死**：管理豁免只免「server 授权门」这一层；工具级 required_perms 门对所有人（含豁免者）照常生效。理由：拍板明确「工具级沿用既有」，豁免是本轮新门的配套语义，不外溢改写既有门。

## 1. 授权模型

### 1.1 表结构（真相源：增量脚本 + cloud_ai.sql 基线同步）

新表 `ai_mcp_server_grant`，形态完全对齐知识库授权表 `ai_kb_grant`（2026-10-18 轮）：纯关系表、物理删除、无软删列、无 update 审计列（行只有插入与删除两个生命周期）；保存 = 按 server_id 全删全插（同一事务）。

```sql
USE cloud_ai;

CREATE TABLE ai_mcp_server_grant
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '授权ID（主键）',
    server_id    BIGINT       NOT NULL COMMENT 'MCP服务ID（ai_mcp_server.id，弱引用不建外键；内置 SYSTEM 行同样可授权）',
    subject_type TINYINT      NOT NULL COMMENT '主体类型：0=角色 1=部门 2=用户',
    subject_id   BIGINT       NOT NULL COMMENT '主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）',
    create_by    VARCHAR(30)  NULL COMMENT '授权操作人账号',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（DB 时钟口径，应用层不传时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_subject (server_id, subject_type, subject_id),
    KEY idx_subject (subject_type, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='MCP服务用户授权（纯关系表：物理删除，随保存全删全插；默认拒绝+管理豁免，运行面实时求值）';

-- 初始授权：内置 SYSTEM server 授给内置管理员角色（取舍见 §1.3）
INSERT INTO ai_mcp_server_grant (server_id, subject_type, subject_id) VALUES (1, 0, 1);
```

**索引取舍**（每索引注明命中的查询）：

- `uk_server_subject (server_id, subject_type, subject_id)`——唯一键兼查重：命中「回显某 server 全部授权行」（server_id 前缀扫描，授权面板打开时一次）与并发保存的重复行防御；保存前查重由全删全插语义天然规避，uk 是兜底。
- `idx_subject (subject_type, subject_id)`——命中运行面主路径「按主体反查可用 server id 集」（每次 agent 运行至多一次，三分支 OR + distinct，见 §1.2）。没有它该查询退化为全表扫——虽然行量级小（server 数 × 主体授权数，百位数内），但这是每运行必经路径，与 ai_kb_grant 同款索引。
- 不加 `idx_server_id` 单列索引——uk 的最左前缀即 server_id，单列索引冗余。
- 不给主体加外键——主体主数据在 cloud_system 库（跨库弱引用，沿 ai_kb_grant / ai_agent_mcp_server 先例）。

实体落位 `cloud-base-ai/src/cloud_base_ai/mcp_server/entities.py` 新增 `AiMcpServerGrant`（列定义与注释对齐 AiKbGrant；主体枚举 `SubjectTypeEnum{ROLE=0, DEPT=1, USER=2}` 本域自定义，与数据权限域、kb 域的枚举数值互不共享——沿「两域各自定义」注释先例）。

### 1.2 判定算法（豁免短路 → 三分支并集）

运行面对某用户判定「server 是否可用」的求值顺序：

1. **豁免短路在前**：请求身份的 perms 含 `ai:mcp:list`（管理面最小公共权限，管理角色必持）→ 全量 server 可用，**不查授权表**。语义「能管即能用」，对齐知识库授权的管理豁免（持任一管理权限即全量可见）。
2. **否则查库一次**：按身份三要素（X-User-Id / X-User-RoleIds / X-User-DeptIds，网关登录时注入，部门头已聚合自身+全部祖先链）反查授权表——`subject_type=2 且 subject_id=user_id` ∪ `subject_type=0 且 subject_id IN role_ids` ∪ `subject_type=1 且 subject_id IN dept_ids`，distinct 取 server id 集。空列表分支不拼接（防 IN () 非法 SQL）；user_id 为 None（本地直连无身份头）跳过用户分支——身份缺失=空集语义，非错误，沿 kb 先例。
3. **门与门是交集**：server 授权门幸存的 (工具, server) 对再逐个过工具级 required_perms 门（既有 `visible_tools`，不改动）。

DAO 新函数 `list_authorized_server_ids(session, user_id, role_ids, dept_ids) -> set[int]`（落 mcp_server/dao.py，形态镜像 rag/dao.py 的 `list_visible_kb_ids` 三分支写法）。豁免常量与判定函数 `is_mcp_server_exempt(identity)` 落 `cloud_base_ai/security.py`（与知识库豁免 `MGMT_PERMS / is_mgmt_exemption` 同文件同形态，第二组 frozenset）。

### 1.3 初始授权数据与上线影响（迁移口径）

**默认拒绝意味着上线瞬间收紧**。现状：内置 SYSTEM server 的工具（required_perms 一空一 `system:user:list`）对任何运行用户可用（空门=登录即可）；外部 server 同理。上线后：无授权行的 server 仅豁免者可用。

初始授权定死一条：**SYSTEM server 授给 sys_role id=1（内置管理员角色）**。取舍：

- 角色 id=1 是 cloud_system 基线种子恒存在的行（内置保护角色），跨环境确定；业务角色 id 是环境相关用户数据，不可种子化。
- 管理员本就因持 `ai:mcp:list` 豁免，此行近乎冗余——保留它的价值是授权状态在面板可见（打开 SYSTEM 授权面板非空，避免「全库零授权」的困惑），且豁免口径未来若收紧不回退管理员可用性。
- **业务用户上线后需一次性手工授权**（用户在拍板「默认拒绝」时已接受）：管理员在 MCP 管理页按角色/部门/用户授 SYSTEM 及外部 server。此运营步骤写入契约 §5 与移交备忘。
- 外部 server 不做种子（数量与环境相关，全部走手工授权）。

增量脚本 `cloud-base/scripts/sql/2026-10-30-mcp-server-permission.sql`（幂等口径沿既有：表存在则跳过；种子行重放撞 uk 不视为错误）+ `cloud_ai.sql` 基线同步同表定义与种子。**无菜单种子**（本轮零新权限标识，见 §5）。

## 2. 运行面集成

### 2.1 装配链插入点

改动唯一落点：`cloud-base-ai/src/cloud_base_ai/agent/run_service.py` 的 `_load_visible_tools`（每次运行的工具装配查询）。在该函数内、既有 required_perms 过滤**之前**插入 server 授权门：

```python
async def _filter_by_server_grant(pairs, identity):
    """server 授权门（契约 2026-10-30-mcp-server-permission-api §4）：豁免短路；
    否则按主体反查授权 server id 集，未授权 server 的工具对静默剔除（不报错）。"""
    if identity is not None and is_mcp_server_exempt(identity):
        return pairs
    session = current_session()
    user_id = identity.user_id if identity is not None else None
    role_ids = identity.role_ids if identity is not None else ()
    dept_ids = identity.dept_ids if identity is not None else ()
    authorized = await mcp_server_dao.list_authorized_server_ids(
        session, user_id, role_ids, dept_ids)
    return [pair for pair in pairs if pair[1].id in authorized]
```

调用侧（`_load_visible_tools` 内，两行改动）：`pairs` 经 `_filter_by_server_grant` 过滤后再走既有 `visible_tools`。**管理面回显不受影响**：`list_effective_tools`（DAO）为管理面 tool_names 回显与运行装配共用，授权门放在 run_service 调用侧而非 DAO 内，管理面列表/详情照旧展示配置事实（拍板「仅运行面强制」）。

依赖方向：agent 域 → mcp_server 域 DAO（mcp_server/dao.py 本就 import agent 域的 AiTool 实体，实体是叶子模块，无环——沿 agent 运行链既有跨域 import 方向）。

### 2.2 compiled agent 缓存键修复（本轮必改的既有缺陷）

**缺陷描述**：`agent/runtime.py` 的 compiled agent 进程内缓存现按 `agent_id → (config_version, agent)` 键存取，**缓存命中时忽略传入的 tool_rows**。工具面是按运行用户过滤后的结果——第一个组装缓存的用户的工具面会被同 config_version 的后续所有用户复用。这是既有工具级 required_perms 门的潜伏缺陷（此前工具 required_perms 多为空、门形同虚设，未暴露）；本轮 server 门使工具面按用户差异化成为常态，**不修则按用户授权会被缓存串面直接击穿**（A 用户授权面被 B 用户命中）。

**修复方案**：缓存键加入工具面指纹——`(agent_id, config_version, tuple(工具id升序))`。工具 id 序来自 `list_effective_tools` 的 `order_by AiTool.id.asc()`，天然有序确定。缓存上限维持 32 条 LRU（组装是零网络毫秒级操作，键变多只会增加少量重组装，不涨内存上限）。

**修复带来的简化**：授权变化、工具门过滤结果变化都直接体现为指纹不同 → 新键未命中 → 重组装。**授权保存不再需要任何缓存失效动作**（旧键条目滞留至 LRU 逐出，永不命中错误面——compiled agent 只依赖工具面+配置版本，无其它用户态：JWT/审计走 contextvar 每请求注入）。既有的 `reset_cache()` 调用（工具同步/审核、server 停用启用删除后）保留不动，语义无害。

### 2.3 与 Java 侧、绑定面的叠加语义（防误读，契约同步写明）

- **Java 侧不动**：内置 SYSTEM server 的工具调用仍由 cloud-system 的 `@McpRequirePerm` 逐工具最终裁决（平台权限标识体系）。AI 侧 server 授权门是它之前的装配层过滤——「AI 侧授权门（谁能装配）× Java 侧工具标识门（谁能真调）」叠加，双层都过才算完整通路。
- **外部 server 风险记档沿既有口径**：外部 MCP server 不认识平台权限体系，AI 侧授权门与工具门均为纯客户端过滤（治理层），无平台侧服务端强制——沿 2026-10-30 MCP 管理契约 §4 两层纵深风险记档，知情接受，不引入平台代理。
- **绑定面不滤授权**：agent 编辑弹窗的 server 选项（GET /ai/agent/mcp-server/options）对所有 ai:agent:list 持有者恒返回全部启用未删 server；无权用户绑定的 server 行为「预绑定」，运行期静默过滤——与「停用行可绑」同语义（配置宽、运行严）。

## 3. 管理面设计

授权两端点挂 mcp_server 域既有 `ai:mcp:update` 路由组（mcp_server/router.py 的 mcp_server_update_router，路径前缀 /mcp/server）：

- `GET /ai/mcp/server/{id}/perms`——回显三组主体 id（角色/部门/用户，无授权组空数组）。
- `PUT /ai/mcp/server/{id}/perms`——三组全量覆盖保存（全空/不传=清空授权=回到默认拒绝）。

读授权与保存同权限的口径沿知识库授权先例：读授权清单是管理配置动作，不开放给 list-only 用户探测授权面。校验约束逐项同构 kb：每元素 ≥1 且必须 JSON number（strict，数字串拒收 1001）、每数组 ≤100、重复元素服务端静默去重；目标 server 不存在/软删 → 5021（既有码复用）；主体弱引用不校验存在性（失效授权永不命中，自愈语义）。

service 层新增 `get_server_perms` / `save_server_perms`（manage_service.py，保存 @transactional）；DAO 新增 `list_server_grants`（回显，按 subject_type、subject_id 升序）/ `save_server_grants`（全删全插 + create_by 取 current_account contextvar，镜像 rag/dao.py 的 save_kb_grants）。

前端：MCP 管理页（cloud-web/src/views/mcp/manage/）新增「授权」行操作按钮（v-perms ai:mcp:update）+ `ServerPermsDialog.vue` 授权弹窗——知识库授权弹窗 KbPermsDialog.vue 全套同构移植（三块选择器分段平铺：角色/用户 = 数据权限域 subject-options 多选；部门 = 部门树 check-strictly 勾选；id string↔number 换轨；失效主体占位呈现）。

**已知依赖边界（本轮不解决，记档）**：选择器数据源端点（system 域 subject-options / dept/tree）现权限为 `hasAnyAuthority('system:dataPerm:list','ai:kb:update')`——只持 `ai:mcp:*` 的角色打开授权面板时选项加载 403（弹窗可开、选择器无选项）。过渡口径：此类角色发放上述任一既有标识；根治方案（把 `ai:mcp:update` 加入该端点 additive 放宽，一行注解 + 2026-10-18 知识库面板同款先例）移交后续轮次——本轮拍板口径为 Java 侧零改动，不擅动。

## 4. 缓存与性能口径

- **授权判定每运行查库一次，不设缓存**。量级论证：授权表行数 = server 数（个位数到十位数）× 主体授权数（百位数内），单条三分支 OR + distinct 查询走 idx_subject，毫秒级；agent 运行本身是秒级 LLM 操作，且运行链既有口径就是「每运行查一次工具注册表，不设缓存」（2026-10-29 agent 契约 §7），授权查询同量级同口径。实时查库换即时生效（授权保存下一运行即生效，无快照失效问题）——与平台权限标识体系的「重新登录才生效」形成本轮选型的核心差异点，拍板采纳。
- 豁免用户零额外查询（短路在查库之前）。
- compiled agent 缓存修复后命中率按「(agent, 版本, 用户面) 组合」计——管理面规模（agents × 常用用户面）下 32 条 LRU 充足，不足时重组装成本为毫秒级零网络操作，可忽略。

## 5. 错误码与权限标识

- **错误码零新增**：授权两端点复用 403（无操作权限）/ 1001（参数校验）/ 5021（MCP 服务不存在）；运行面静默缩窄不产生新错误码。AI 段 5xxx 下一可用仍为 **5030**（5029 已被 agent 绑定 MCP 服务轮占用）。
- **权限标识零新增、零菜单种子**：两端点挂既有 `ai:mcp:update`；运行面豁免消费既有 `ai:mcp:list`（运行端点本身的 `ai:agent:run` 不变）。种子唯一新增是 cloud_ai 库一张表 + 一行初始授权（§1.3）。e2e 聚合脚本零同步（菜单总数不变）。

## 6. 测试策略

后端（cloud-base-ai/tests/，TDD 先红后绿）：

- `test_mcp_server_dao.py` 扩：`list_authorized_server_ids` 三分支（用户直授/角色 IN/部门 IN）、多主体同 server distinct 去重、空身份空集、单分支空列表不拼 IN ()；`save_server_grants` 全删全插、create_by 落值；uk 撞重上抛。
- `test_mcp_server_manage_service.py` 扩：get/save 的 5021 分支、三组去重、全空清空、保存事务性（mock 异常回滚断言未落库）。
- `test_mcp_server_router.py` 扩：两端点 403（无 ai:mcp:update）/1001（数字串、越界、超 100）/5021/正常往返。
- `test_agent_run_service.py` 扩：server 门四态——授权命中注入 / 默认拒绝剔除 / 豁免短路（不触 DAO）/ 与 required_perms 门交集（授权了 server 但工具门不满足仍剔除）；静默不抛错。
- `test_agent_runtime.py` 扩：缓存键隔离——同 agent 同版本、两个不同工具面的用户各得其所（互不串面）；同用户授权变更后指纹变化触发重组装；grant 保存不调 reset_cache 语义正确。
- 架构守护测试（test_architecture_guard.py）自动覆盖新实体列 comment 等机械规则，无需专项。

前端：ServerPermsDialog 组件按 frontend-page 规范用浏览器自动化验证（打开回显/勾选保存/失效占位/错误分支），e2e 场景见计划前端章。

## 7. 边界与明确不做（本轮）

- Java 侧 cloud-system（`@McpRequirePerm` 体系、网关、身份头）**零改动**——五头身份链路 2026-10-18 已就绪。
- 工具级 required_perms 机制**不改动**（审核端点、种子、过滤逻辑原样）；「把具体工具授给具体用户」仍只能通过平台权限标识间接表达，不在本轮。
- 绑定面（options 端点、绑定校验）与管理面回显**不做授权过滤**。
- agent 级「谁能跑哪个 agent」、多租户、stdio 传输——均不在本轮。
