# Agent 绑定 MCP 服务设计（server 级订阅语义绑定）

> 全栈轮（cloud-base-ai 后端 + cloud-web 前端 + 种子 SQL + e2e）。需求分析四项拍板（P1-P4）已用户确认，见 §1.2。契约修订直接落 `docs/superpowers/contracts/2026-10-29-agent-platform-api.md`（修订记录段 §8）；实施计划 `docs/superpowers/plans/2026-10-31-agent-mcp-server-binding.md`。
>
> **本轮不动的面**：agent 运行编排/SSE/会话链路（`run_service` 的 run/stream/session 各方法零改动，仅 `_load_visible_tools` 换数据源动词）；`mcp/perm.py` visible_tools 零改动；MCP 管理面端点与契约零改动（P3 修复只是 service 内部补调用）；`mcp/client.py` 通道零改动；无新菜单种子。

## 0. 结论速览

1. 新增纯关系表 `ai_agent_mcp_server`（uk(agent_id, server_id)，物理删全删全插，沿 ai_agent_tool 先例）承载 server 级绑定；agent 创建/编辑入参加 `mcp_server_ids`。
2. 装配查询定稿为**单查询 OR 子查询**：`ai_tool JOIN ai_mcp_server WHERE (tool_id IN 绑定子查询 OR server_id IN 绑定子查询) AND 可见过滤`——`list_bound_tools` 改造更名 `list_effective_tools`，运行装配与列表/详情 tool_names 共用。
3. 两路绑定自由混用、运行面取并集、集合语义天然去重（§3 D2）；server 级绑定是**订阅语义**：server 名下工具经同步+审核发布后自动进 agent 工具面，无需编辑 agent。
4. 新端点 `GET /ai/agent/mcp-server/options`（perms `ai:agent:list`）供编辑弹窗取「启用未删 server + 已发布工具数」选项。
5. 错误码 5029「MCP服务不存在或已删除」（mcp_server_ids 含软删/不存在项；**停用行可绑**=预绑定）。
6. P3 缓存缝隙修复：`mcp_server/manage_service.py` 的 update_server/delete_server 改「非事务外壳 + @transactional 内核 + 提交后 `runtime.reset_cache()`」——上一轮契约 §4 停用语义在缓存温热期不成立的缺陷就此修复，记档进 agent 契约 §8。

## 1. 需求与拍板

### 1.1 需求回述

agent 现只支持按单个工具绑定（tool_ids → ai_agent_tool）。本需求增加第二种绑定形态：按 MCP server 整体绑定，agent 获得该 server 名下**全部已发布工具**的可用面。管控粒度止于 server 级。

### 1.2 拍板决策记录（2026-10-31 用户确认）

| # | 问题 | 拍板 |
|---|---|---|
| P1 | 两种绑定可否混用 | **自由混绑、运行面并集**；tool_ids 与 mcp_server_ids 两区独立勾选，装配取并集（集合语义天然去重）；detail 双字段分立回显 |
| P2 | 列表 tool_names 展示口径 | **合并展开**：列表 tool_names = 直接绑定 ∪ server 展开全集（所见即运行真面）；detail 仍双字段分立 |
| P3 | server 状态变化缓存缝隙（上一轮遗留） | **本轮顺手补**：update_server（停用/启用）与 delete_server（软删）成功后补调 `runtime.reset_cache()`（事务提交后），各配一个单测钉死；属对 2026-10-30 契约 §4 停用语义缺陷的修复，在 agent 契约 §8 修订段显式记档 |
| P4 | 停用 server 可否绑定 | **可绑（预绑定）**：沿 tool_ids「配置宽、运行严」先例，仅拦软删行（5029）；运行期静默过滤 |

### 1.3 「快照 vs 订阅」语义（本需求的核心模型）

- **绑定工具 = 快照语义**：指向具体工具行 id，工具面在绑定时刻冻结为列清单；server 后续新增工具不自动进入。
- **绑定 server = 订阅语义**：指向 server 行 id，工具面随注册表动态展开——server 后续同步新增并审核发布的工具**自动进入** agent 工具面（装配每运行查库 + 同步/审核既有 reset_cache 联动，两级新鲜度已闭环，无需新机制）。此语义写入契约运行面节与前端编辑弹窗提示。

## 2. 总体架构

### 2.1 改造点拓扑（文字图）

```
编辑面（管理侧）                          运行面（每次 run/stream）
─────────────────                       ─────────────────────────────
AgentEditDialog                          prepare_run
 ├ 工具多选（§2.6 不动）                   └ _load_visible_tools（换数据源动词）
 └ MCP 服务多选（新）                        └ dao.list_effective_tools(agent_id)
    选项源 GET /ai/agent/mcp-server/options    ai_tool JOIN ai_mcp_server
    （启用未删 + 已发布工具数）                  WHERE (tool.id IN 绑定工具子查询
                                                OR tool.server_id IN 绑定服务子查询)
create_agent / update_agent                AND 工具已发布 AND server 启用未删
 ├ _validate_tool_ids（既有 5017）          → visible_tools 权限门（零改动）
 └ _validate_mcp_server_ids（新 5029）      → (AiTool, AiMcpServer) 元组 → runtime.get_agent
 ├ replace_agent_tools（既有）                （缓存键 config_version 不变）
 └ replace_agent_mcp_servers（新）

server 生命周期（MCP 管理面，端点零改动）
 update_server / delete_server ──(P3 新增)──> runtime.reset_cache()
 sync / review  ──(既有)──────────────────> runtime.reset_cache()
```

### 2.2 两条数据流

**保存流**（create/update，同一 @transactional）：`_validate_mcp_server_ids`（5029，先于任何写）→ agent 行落库/update（config_version +1）→ `replace_agent_tools` 全删全插 → `replace_agent_mcp_servers` 全删全插。config_version 自增使 compiled agent 缓存自动失效——绑定变化无需显式 reset。

**装配流**（每运行）：`list_effective_tools` 单查询取并集可见集 → `visible_tools` 按工具行 required_perms 过滤（与绑定来源无关，server 级展开的工具**同样过权限门**）→ 存活元组进 `runtime.get_agent` 组装。

## 3. 关键设计与取舍

### D1 数据模型：新关联表（拍板定案，弃 JSON 列）

新表 `ai_agent_mcp_server`，沿 ai_agent_tool 先例四维度全胜 JSON 列：uk 数据库级查重（JSON 靠应用层）、全删全插动词同构复用、create_by 审计、可反查「绑了某 server 的 agent」（软删 server 影响面查询；JSON 列做不到）。纯关系表物理删、弱引用不建外键、agent 软删不级联（绑定行随软删失去可见入口）。DDL 见 §4。

### D2 装配查询：单查询 OR 子查询（定稿）

`agent/dao.py` 的 `list_bound_tools` 改造更名 `list_effective_tools`（「该 agent 的生效工具面」，两个既有调用方 `run_service._load_visible_tools`、`manage_service` 列表/详情 tool_names 随之切换；`list_visible_tools`——§2.6 工具选项端点数据源——不动）。骨架（≤30 行示意）：

```python
async def list_effective_tools(
    session: AsyncSession, agent_id: int
) -> Sequence[tuple[AiTool, AiMcpServer]]:
    """某智能体的生效工具面（并集：直接绑定 ∪ server 级绑定展开，仅可见行）。
    去重语义：工具表不 join 绑定表（绑定条件走 IN 子查询），同一工具行恒产一行。"""
    bound_tools = select(AiAgentTool.tool_id).where(AiAgentTool.agent_id == agent_id)
    bound_servers = select(AiAgentMcpServer.server_id).where(
        AiAgentMcpServer.agent_id == agent_id)
    stmt = (
        select(AiTool, AiMcpServer)
        .join(AiMcpServer, AiMcpServer.id == AiTool.server_id)
        .where(
            AiTool.status == AiTool.StatusEnum.PUBLISHED.value,
            AiMcpServer.status == AiMcpServer.StatusEnum.ENABLED.value,
            AiMcpServer.deleted == SoftDeleteMixin.DeletedEnum.NORMAL,
            or_(AiTool.id.in_(bound_tools), AiTool.server_id.in_(bound_servers)),
        )
        .order_by(AiTool.id.asc())
    )
    return (await session.execute(stmt)).all()
```

弃选：a) UNION 两查询（需 distinct 收口，两条 SQL 往返）；b) Python 层两查合并（去重逻辑上移、多一次往返）。三者在当前量级（工具十位数、agent 数十）性能无别，选 SQL 集合语义一步到位、单测可断言性最好的形态。生成 SQL 形态（MySQL）：

```sql
SELECT t.*, s.* FROM ai_tool t JOIN ai_mcp_server s ON s.id = t.server_id
WHERE t.status = 1 AND s.status = 0 AND s.deleted = 0
  AND ( t.id IN (SELECT tool_id FROM ai_agent_tool WHERE agent_id = ?)
     OR t.server_id IN (SELECT server_id FROM ai_agent_mcp_server WHERE agent_id = ?) )
ORDER BY t.id
```

索引命中（§4.2 详表）：两个子查询各命中本表 uk 前缀；主查询 tool 侧 `id IN` 走主键、`server_id IN + status` 走既有 `idx_server_status`；server 侧主键点查 join。SQLite 内存替身同语义（IN 子查询均支持）。

### D3 校验与错误码：5029，仅拦软删行

`_validate_mcp_server_ids`（manage_service，沿 `_validate_tool_ids` 形态）：数据源 `dao.list_bindable_server_ids`（全部未软删行 id，**不过滤 status**——停用可绑=预绑定，P4）；含无效项（不存在或软删）→ 5029，msg 带无效项清单。pydantic 层 `mcp_server_ids` 与 `tool_ids` 同款去重校验器（保序静默去重）。错误码 5029「MCP服务不存在或已删除」——与 5021（管理面按 id 查无活行）区分：5029 语义是「绑定入参含无效项」，对齐 5017（tool_ids 含无效项）的档位。

### D4 绑定选项端点与路由顺序

新端点 `GET /ai/agent/mcp-server/options` 挂 `agent_list_router`（perms `ai:agent:list`）——沿 §2.6 工具选项端点先例：编辑 agent 只需 ai:agent:* 权限，不依赖 ai:mcp:list（agent 管理角色未必有 MCP 管理权）；出参收窄（id/name/description/published_tool_count），不透 url/凭据键名等管理面字段。

**路由顺序坑（必须遵守）**：`agent_list_router` 已有通配 `GET /{agent_id}`；新端点路径 `/mcp-server/options` 必须注册在 `/{agent_id}` 之前（FastAPI 按注册顺序匹配，否则 `mcp-server` 被 `{agent_id}` 吞掉 → 422）。现有 `/tool/list` 正是靠先注册避坑的先例（`agent/router.py` 中 `/{agent_id}` 之前）。

出参数据源：`dao.list_enabled_servers`（status=启用 且 deleted=0）+ `dao.count_published_tools_by_server`（agent 域新动词，只聚合 status=已发布；不复用 mcp_server 域的 `count_tools_by_server`——那是三态计数的管理面摘要语义，本端点只要已发布数，独立动词避免 service 层跨域引 dao）。**已发布工具数为 0 的启用 server 照样列出**（订阅语义：绑定后 server 同步+审核出工具即自动生效）。

### D5 生命周期联动与回显口径

| server 侧事件 | agent 侧语义 |
|---|---|
| 停用 / 启用 | 运行面静默缩窄/恢复（可见过滤 + P3 缓存失效）；绑定行不动；编辑期不拦截（应急开关不得被绑定锁死） |
| 软删 | 同上（运行不可见、绑定行保留）；已删 server 的 id 在 detail 照回（配置事实，沿 tool_ids 回显先例） |
| 改名 | 无影响（绑定锚 id 不锚 name；内置行本就禁改名） |
| 工具同步/审核 | 既有 reset_cache 联动已覆盖 server 级绑定（重组装时按新注册表展开）——**订阅语义的自动生效路径** |

detail 回显：`mcp_server_ids` = 纯绑定行（`dao.list_bound_mcp_servers`，server_id 升序）；`mcp_server_names` = 同一查询带的 name（join 不过滤 deleted——软删行名字照带回显，沿 MCP 契约 §2.8「台账完整」先例）；`tool_names` = `list_effective_tools`（合并展开可见集，P2）。列表 `tool_names` 同 detail 口径（page_list_agents 逐行查询的 N+1 取舍沿用现状，量级接受）。

### D6 缓存联动与 P3 修复

绑定编辑走既有 `update_agent` → config_version SQL 原子 +1 → 缓存自动失效，**无需新机制**。

P3 修复（上一轮缺陷）：`mcp_server/manage_service.py` 的 `update_server`/`delete_server` 改「非事务外壳 + @transactional 内核 + 提交后 `runtime.reset_cache()`」——沿 `sync_service` 的既有形态（`sync_server_tools` 外壳非事务、`_apply_sync_upsert` 内核事务、reset 在提交后）。骨架：

```python
async def update_server(server_id: int, req: UpdateServerReq) -> None:
    """外壳：内核事务提交后清 compiled agent 缓存（server 启停/编辑改变 agent 生效
    工具面）。内核抛业务码（5021/5022/5023）路径不清缓存——零变更零失效。"""
    await _update_server_tx(server_id, req)
    runtime.reset_cache()

@transactional
async def _update_server_tx(server_id: int, req: UpdateServerReq) -> None:
    ...  # 原 update_server 方法体原样搬入
```

`delete_server` 同款拆分；`create_server` 不动（新行不进任何既有 agent 的装配集，无需失效）。commit 本身失败（罕见）时缓存未清，行为退化到修复前（下次编辑/同步自然失效），不劣化。router 调用点签名不变。钉死单测见 §6。

### D7 权限、种子与契约面

零新权限、零菜单种子（options 端点挂既有 `ai:agent:list`，其保护范围「§2 全部管理查询端点」自动覆盖）；MCP 管理契约 `2026-10-30-mcp-server-mgmt-api.md` 零改动；agent 契约修订五处 + 新 §2.8 + §8 修订记录（含 P3 记档），见该契约 §8。e2e 聚合脚本零同步（无菜单变更）。

## 4. DDL 与索引设计

> 增量脚本 `cloud-base/scripts/sql/2026-10-31-agent-mcp-server-binding.sql`（前置断言见脚本头）；基线 `cloud_ai.sql` 在 ai_agent_tool 段后同步追加。每列 COMMENT 强制。

### 4.1 新表 ai_agent_mcp_server

```sql
CREATE TABLE ai_agent_mcp_server
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '绑定ID（主键）',
    agent_id    BIGINT      NOT NULL COMMENT '智能体ID（ai_agent.id，弱引用不建外键；agent 软删不级联，绑定行随软删失去可见入口）',
    server_id   BIGINT      NOT NULL COMMENT 'MCP服务ID（ai_mcp_server.id，弱引用不建外键；停用行可绑=预绑定，运行期静默过滤；软删行绑定校验拦 5029）',
    create_by   VARCHAR(30) NULL COMMENT '绑定操作人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间（DB 时钟生成，应用层不传时间值）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_server (agent_id, server_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='智能体-MCP服务绑定（纯关系表：物理删除，随保存全删全插；订阅语义——server名下已发布工具自动进agent工具面）';
```

### 4.2 索引设计与命中清单

| 索引 | 命中查询 | 取舍 |
|---|---|---|
| `uk_agent_server(agent_id, server_id)` | ① 保存全删全插的 `DELETE ... WHERE agent_id=?`（前缀）；② 装配子查询 `SELECT server_id WHERE agent_id=?`（前缀反向扫）；③ 并发双插查重兜底（IntegrityError） | 一枚 uk 三用，沿 uk_agent_tool 先例；**不为**「按 server 反查绑定 agent」建 `idx_server_id`——该查询目前不存在（影响面查询属运营增强范围外），量级零星，出现时再增量 ALTER |
| （既有）`ai_tool.idx_server_status(server_id, status)` | 装配主查询 `server_id IN (...) AND status=1` 分支 | 上一轮已建，零增量 |
| （既有）`ai_agent_tool.uk_agent_tool(agent_id, tool_id)` | 装配子查询 `SELECT tool_id WHERE agent_id=?` | 零增量 |
| （既有）`ai_mcp_server` 主键 | 装配 join、detail 名回显、options 行查询 | 零增量 |

**本轮对既有表零增量索引**——新查询路径全部命中既有索引。

## 5. 错误处理

| code | 语义 | 触发 | 处置 |
|---|---|---|---|
| 5029 | MCP服务不存在或已删除 | 创建/编辑时 mcp_server_ids 含不存在或软删项 | pydantic 先行去重；service 校验先于任何写，msg 带无效项清单 |
| 5017 | 工具不存在（既有） | tool_ids 含无效项 | 不变 |
| 403/1001 | 既有 | options 端点权限 / 参数 | 不变 |

运行面既有码全部透传不变（5001/5002/5009/5010/5018/5026）；server 停用/软删导致的工具面缩窄**不报错**（静默，契约既有语义）。

## 6. 测试策略

- **dao（SQLite 内存替身，cd cloud-base-ai 内跑 pytest）**：`list_effective_tools` 五情形（纯工具绑定/纯 server 绑定/混绑并集/同一工具双路径绑定不重行/server 停用或软删缩窄）；`replace_agent_mcp_servers` 全删全插与空集清空；`list_bound_mcp_servers` 含软删行名回显；`list_bindable_server_ids` 含停用排除软删；`count_published_tools_by_server` 三态只计已发布。
- **service**：创建/编辑带 mcp_server_ids 落绑定行；5029 三触发（不存在/软删/混合）；停用 server 可绑；detail 双字段 + tool_names 合并展开；options 仅启用未删 + 已发布工具数（0 也列出）；config_version 随绑定编辑 +1。
- **P3 钉死（test_mcp_server_manage_service.py 两用例）**：预置 `runtime._agent_cache[1] = (1, object())` 哨兵 → `update_server` 停用成功 → 断言缓存空；`delete_server` 同款。另断言业务码路径（5021/5022）不清缓存。
- **router**：options 端点 listOnly 权限 403；`/agent/mcp-server/options` 与 `/agent/{agent_id}` 路由顺序（前者不被通配吞）。
- **回归**：test_agent_run_service（_load_visible_tools 换动词后全量绿）、test_agent_manage_service（detail/列表口径变化同步修）。
- **真连接冒烟（/ai-spec 纪律，扩 `scripts/smoke/smoke_mcp_server_mgmt.py` 尾段）**：建 agent 绑 SYSTEM server → 真跑一次非流式运行断言回答与 tool_calls 非空（server 级绑定装配实证）→ 停用 server → 再跑断言工具面为空 → 启用恢复 → 清理。
- **e2e**：`run-agent-e2e.mjs` 扩 AG1 场景（编辑弹窗 MCP 服务多选、options mock、提交 body 含 mcp_server_ids、detail 回显），route mock，独立入口不进聚合。

## 7. 范围外与移交备忘

1. server 用量统计 / server 管理页「被 N 个 agent 绑定」计数 / 影响面反查 UI（数据模型已不设障，需求出现再议）。
2. server 内工具的 agent 级排除（黑名单）——用户拍板不做（管控只到 server 粒度）。
3. 绑定继承 / agent 模板复制——平台无此功能形态。
4. 多实例部署时 reset_cache 跨进程失效（上一轮移交条目续挂，容器化轮一并解——P3 修复后该条目覆盖面扩大到 server 状态变化路径）。
5. 运行级/进程级 MCP 会话复用（agent 轮移交备忘续挂不动）。
