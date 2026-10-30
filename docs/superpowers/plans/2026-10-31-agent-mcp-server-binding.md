# Agent 绑定 MCP 服务实施计划（2026-10-31）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** agent 支持绑定 MCP server（server 级订阅语义，其名下已发布工具全部进入工具面），与既有工具级绑定自由混用、运行面取并集；顺手修复 server 状态变化不失效 compiled agent 缓存的上一轮缺陷。

**Architecture:** 新纯关系表 `ai_agent_mcp_server`（uk(agent_id,server_id)，全删全插）；装配查询改单查询 OR 子查询（`list_bound_tools` 更名 `list_effective_tools`）；新选项端点 `GET /ai/agent/mcp-server/options`（ai:agent:list）；mcp_server service 的 update/delete 补「提交后 reset_cache」外壳。设计全文 `docs/superpowers/specs/2026-10-31-agent-mcp-server-binding-design.md`（下称「设计文档」），契约修订已落 `docs/superpowers/contracts/2026-10-29-agent-platform-api.md` §2.1/§2.2/§2.3/§2.4/§2.8/§3/§5/§8。

**Tech Stack:** cloud-base-ai（Python/FastAPI/SQLAlchemy async，pytest SQLite 内存替身）、cloud-web（Vue3+TS+Element Plus 按需引入）、cloud-e2e（playwright route mock）。

**纪律**：/ai-spec 分层（router→service→dao）；测试一律 `cd cloud-base-ai` 内跑（pytest rootdir 陷阱）；mvn/uv 不在 PATH 用绝对路径；无新菜单种子（e2e 聚合脚本零同步）；无真值入档；每任务完成即 commit（主控提交前核对 HEAD）。

---

## 1. 后端章（cloud-base-ai + 种子 SQL）

### B1 DDL + 实体 + 种子 SQL

**文件清单**：
- Create: `cloud-base/scripts/sql/2026-10-31-agent-mcp-server-binding.sql`
- Modify: `cloud-base/scripts/sql/cloud_ai.sql`（ai_agent_tool 段之后追加同款建表语句）
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/entities.py`（新增 `AiAgentMcpServer`）
- Modify: `cloud-base-ai/tests/test_agent_entities.py`（新实体列集断言）

**内容**：
- 建表 SQL 全文 = 设计文档 §4.1（逐字，含全列 COMMENT 与 uk_agent_server）。增量脚本头部前置断言注释段沿 `2026-10-30-mcp-server-mgmt.sql` 先例：①`SHOW TABLES LIKE 'ai_agent_mcp_server'` 应 0 行（防重放）；②`SELECT COUNT(*) FROM ai_mcp_server` 应 ≥1（上一轮内置行 SYSTEM 存在，依赖已跑）。脚本只建表、无数据变更、无 cloud_system 段。基线 `cloud_ai.sql` 同步追加（新环境跑基线即得，增量脚本注明「基线已同步，新环境无需本增量」）。
- 实体（沿 `AiAgentTool` 形态，纯关系表：无软删列、无 AuditMixin）：

```python
class AiAgentMcpServer(Base):
    """智能体-MCP服务绑定（纯关系表，沿 AiAgentTool 先例：物理删除、随保存全删全插）。

    订阅语义：绑定后该 server 名下已发布工具自动进入 agent 工具面（装配每运行查库）。
    停用行可绑（预绑定，运行期静默过滤）；软删行绑定校验拦 5029。
    """

    __tablename__ = "ai_agent_mcp_server"
    __table_args__ = (
        UniqueConstraint("agent_id", "server_id", name="uk_agent_server"),
        {"comment": "智能体-MCP服务绑定（纯关系表：物理删除，随保存全删全插；"
                    "订阅语义——server名下已发布工具自动进agent工具面）"},
    )

    id: Mapped[int] = mapped_column(_ID_BIGINTEGER, primary_key=True, autoincrement=True,
                                    comment="绑定ID（主键）")
    agent_id: Mapped[int] = mapped_column(BigInteger, nullable=False,
                                          comment="智能体ID（ai_agent.id，弱引用不建外键；"
                                                  "agent 软删不级联，绑定行随软删失去可见入口）")
    server_id: Mapped[int] = mapped_column(BigInteger, nullable=False,
                                           comment="MCP服务ID（ai_mcp_server.id，弱引用不建外键；"
                                                   "停用行可绑=预绑定，运行期静默过滤；软删行绑定校验拦 5029）")
    create_by: Mapped[str | None] = mapped_column(String(30), comment="绑定操作人账号")
    create_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=text("CURRENT_TIMESTAMP"),
        comment="绑定时间（DB 时钟生成，应用层不传时间值）",
    )
```

- 测试：`test_agent_entities.py` 追加 `AiAgentMcpServer` 列集断言用例（id/agent_id/server_id/create_by/create_time 五列、uk 约束名），沿既有 `AiAgentTool` 断言形态。

**验收标准**：增量 SQL 经 pymysql 在 cloud_ai 真跑一次（`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python <脚本>`，charset="utf8mb4"；前置断言拦重放）；`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai pytest tests/test_agent_entities.py -v` 全绿（含新用例）；实体每列 comment（守护测试覆盖）。

### B2 dao 扩展：装配并集查询 + 绑定动词

**文件清单**：
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/dao.py`
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/run_service.py`（仅 `_load_visible_tools` 内一行换动词）
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/manage_service.py`（`page_list_agents`/`find_agent_detail` 两处调用点同步机械替换——否则更名即破；对无 server 绑定的存量数据并集结果=原可见集，行为等价，B3 再做 VO 与 docstring 口径扩展）
- Modify: `cloud-base-ai/tests/test_agent_dao.py`（新用例组）
- Modify: `cloud-base-ai/tests/test_agent_run_service.py`（若 mock dao 名需同步，机械替换）

**内容**：
- `list_bound_tools` 更名改造为 `list_effective_tools`（签名 `(session, agent_id) -> Sequence[tuple[AiTool, AiMcpServer]]`），SQLAlchemy 骨架逐字按设计文档 §3 D2（or_ + 两个 IN 子查询；需在文件头 import 补 `or_` 与 `AiAgentMcpServer`）。docstring 写明并集语义与去重依据（工具表不 join 绑定表，同一工具行恒产一行）。
- 新动词（均模块函数收 session、写动词 flush 收尾不 commit）：

```python
async def list_bound_mcp_servers(session, agent_id: int) -> Sequence[tuple[int, str]]:
    """某智能体绑定的 server（纯绑定行，server_id 升序）——detail 回显 mcp_server_ids/
    mcp_server_names 数据源。join ai_mcp_server 不筛 deleted：软删 server 名照回（台账完整，
    沿 mcp 契约 §2.8 先例）。返回 (server_id, name) 元组序列。"""

async def replace_agent_mcp_servers(session, agent_id: int, server_ids: Sequence[int]) -> None:
    """保存 server 绑定 = 按 agent_id 全删全插（与 replace_agent_tools 同构，同一事务由
    service 的 @transactional 收口）；create_by 取 current_account、create_time 走 DB DEFAULT。"""

async def delete_agent_mcp_servers(session, agent_id: int) -> int:
    """物理删除某智能体全部 server 绑定行（命中 uk_agent_server 前缀）；返回影响行数。"""

async def list_bindable_server_ids(session) -> Sequence[int]:
    """全部未软删 server 的 id（id 升序）——绑定校验 5029 数据源；不过滤 status
    （停用可绑=预绑定，拍板 P4）。"""

async def list_enabled_servers(session) -> Sequence[AiMcpServer]:
    """启用未删 server 行（id 升序）——绑定选项端点（契约 §2.8）数据源。"""

async def count_published_tools_by_server(session, server_ids: Sequence[int]) -> dict[int, int]:
    """按 server 聚合已发布（status=1）工具数——选项端点 published_tool_count 数据源；
    空集返回空 dict。不复用 mcp_server 域 count_tools_by_server（三态计数是管理面摘要
    语义，此处只要已发布数，避免 service 层跨域引 dao）。"""
```

- `run_service._load_visible_tools`：`dao.list_bound_tools(...)` → `dao.list_effective_tools(...)`（其余逻辑零改动）。
- dao 测试新用例（SQLite 替身，先写测试再实现）：`list_effective_tools` 五情形——纯工具绑定、纯 server 绑定、混绑并集、同一工具双路径绑定（直接绑 T + 绑其 server）结果不重行、server 停用或软删后缩窄；`replace_agent_mcp_servers` 全删全插与空集清空；`list_bound_mcp_servers` 含软删行名回显；`list_bindable_server_ids` 含停用排除软删；`count_published_tools_by_server` 三态只计已发布、空集空 dict。

**验收标准**：`pytest tests/test_agent_dao.py tests/test_agent_run_service.py tests/test_agent_manage_service.py -v` 全绿（既有 list_bound_tools 用例更名随迁）；全仓 grep `list_bound_tools` 仅剩历史文档命中（代码零残留）。

### B3 manage_service + schemas + router：CRUD 扩展与选项端点

**文件清单**：
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/schemas.py`
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/manage_service.py`
- Modify: `cloud-base-ai/src/cloud_base_ai/agent/router.py`
- Modify: `cloud-base-ai/tests/test_agent_manage_service.py`、`cloud-base-ai/tests/test_agent_router.py`

**内容**：
- schemas：`CreateAgentReq` 加 `mcp_server_ids: list[int] = Field(default_factory=list, description="绑定MCP服务ID列表")` + 与 tool_ids 同款 `_dedupe` 校验器（去重保序，两个校验器各自独立命名如 `_dedupe_tools`/`_dedupe_servers`）；`AgentDetailVo` 加 `mcp_server_ids: list[int]`、`mcp_server_names: list[str]`；新增 `AgentMcpServerOptionVo`（id/name/description/published_tool_count，契约 §2.8 逐字段）并进 `__all__`。
- manage_service：
  - 常量 `CODE_MCP_SERVER_INVALID: int = 5029` / `MSG_MCP_SERVER_INVALID: str = "MCP服务不存在或已删除"`；
  - `_validate_mcp_server_ids(session, ids)`（沿 `_validate_tool_ids` 形态：数据源 `dao.list_bindable_server_ids`，含无效项 raise 5029 msg 带无效项清单，先于任何写）；
  - `create_agent`/`update_agent`：校验后分别调 `dao.replace_agent_mcp_servers(session, agent.id, mcp_server_ids)`（与 replace_agent_tools 并列，同事务）；
  - `find_agent_detail`：加 `dao.list_bound_mcp_servers`，出参 `mcp_server_ids=[sid for sid, _ in rows]`、`mcp_server_names=[name for _, name in rows]`；
  - `page_list_agents`：`dao.list_bound_tools` 调用点换 `dao.list_effective_tools`（tool_names 合并展开，P2；docstring 口径同步改「生效工具面」）；
  - 新增 `list_mcp_server_options() -> list[AgentMcpServerOptionVo]`（@transactional：`dao.list_enabled_servers` + `dao.count_published_tools_by_server`，published_tool_count 取 counts.get(id, 0)——0 也列出）。
- router：`agent_list_router` 新端点，**注册位置必须在 `GET /{agent_id}` 之前**（FastAPI 顺序匹配，否则 `/mcp-server/options` 被 `{agent_id}` 通配吞成 422；现有 `/tool/list` 即靠先注册避坑）：

```python
@agent_list_router.get("/mcp-server/options")
async def list_mcp_server_options() -> R[list[AgentMcpServerOptionVo]]:
    """MCP 服务绑定选项（契约 §2.8）：启用未删 server + 已发布工具数；注册顺序须在
    /{agent_id} 之前（通配路由避坑，沿 /tool/list 先例）。"""
    vos = await manage_service.list_mcp_server_options()
    return R.ok(vos)
```

- 测试：service 层——创建/编辑带 mcp_server_ids 落绑定行；5029 三触发（不存在 id / 软删 server id / 混入清单）；停用 server 可绑不 5029；detail 双字段回显（含软删 server 名照回）；列表 tool_names 合并展开断言；options 仅启用未删 + 计数正确 + 0 工具 server 也列出；编辑 config_version +1 随绑定生效。router 层——options 端点正常 200；listOnly perms（无 ai:agent:update）下 403 语义不受影响；`/agent/mcp-server/options` 请求不被 `/{agent_id}` 路由吞（返回 options 数据而非 422/5015）。

**验收标准**：`pytest tests/test_agent_manage_service.py tests/test_agent_router.py -v` 全绿；`test_architecture_guard.py` 全绿；契约 §2.8/§2.2/§2.3/§2.4 出入参与实现逐字段一致（以契约为准绳自查一遍）。

### B4 P3 缓存修复：server 状态变化 → reset_cache

**文件清单**：
- Modify: `cloud-base-ai/src/cloud_base_ai/mcp_server/manage_service.py`
- Modify: `cloud-base-ai/tests/test_mcp_server_manage_service.py`

**内容**：
- `update_server`/`delete_server` 拆「非事务外壳 + @transactional 内核 + 提交后 reset_cache」，沿 `sync_service.sync_server_tools` 形态（设计文档 §3 D6 骨架）：原方法体原样搬入 `_update_server_tx`/`_delete_server_tx`（@transactional），外壳方法保留原名与签名供 router 调用，正常返回后调 `runtime.reset_cache()`（文件头 import `from cloud_base_ai.agent import runtime`）。docstring 写明：内核抛业务码（5021/5022/5023）路径不清缓存——零变更零失效；commit 失败（罕见）缓存未清，行为退化到修复前不劣化。`create_server` 不动（新行不进任何既有 agent 装配集）。
- 测试两用例（钉死拍板 P3）：预置哨兵 `runtime._agent_cache[1] = (1, object())` → `update_server` 停用成功 → 断言 `runtime._agent_cache == {}`；`delete_server` 软删同款。补一负例：目标 server 不存在（5021）→ 缓存不清。既有 server CRUD 用例随方法拆分全量绿（行为不变，仅事务形态重构）。

**验收标准**：`pytest tests/test_mcp_server_manage_service.py tests/test_mcp_server_sync_service.py -v` 全绿；grep 确认 update_server/delete_server 外壳均含 `runtime.reset_cache()`；契约 §8 修复记档段与实现一致。

### B5 真连接冒烟：server 级绑定端到端

**文件清单**：
- Modify: `cloud-base-ai/scripts/smoke/smoke_mcp_server_mgmt.py`（尾段追加）

**内容**：沿脚本既有形态（真实登录 admin JWT → 网关/直连调用）：创建测试 agent（绑定 SYSTEM server，mcp_server_ids=[1]，tool_ids=[]）→ 真跑一次非流式运行断言回答非空且 tool_calls 含内置工具调用（server 级装配实证）→ 停用 SYSTEM server → 再跑断言工具面为空（模型无工具可用仍正常回答，tool_calls 空）→ 重新启用 → 删除测试 agent 清理。步骤打印与断言文案中文，凭据/key 真值零输出。

**验收标准**：前置服务（sso/system/gateway/cloud-base-ai）在跑时脚本独立执行通过，全部断言绿；任一断言红即停回报不带病过。

---

## 2. 前端章（cloud-web + cloud-e2e）

### F1 types + api

**文件清单**：
- Modify: `cloud-web/src/types/api.ts`（AgentDetailVo/AgentSummaryVo 相邻段，约 L1229-L1290）
- Modify: `cloud-web/src/api/agent.ts`

**内容**：
- `CreateAgentPayload` 加 `mcp_server_ids?: number[]`（UpdateAgentPayload extends 自动继承）；`AgentDetailVo` 加 `mcp_server_ids: number[]`、`mcp_server_names: string[]`；新增 `AgentMcpServerOptionVo { id: number; name: string; description: string | null; published_tool_count: number }`。
- `api/agent.ts` 新增（沿 `listAgentTools` 形态，路径 `/agent/mcp-server/options`——请求封装统一加 /ai 前缀的封装层不动）：

```typescript
export function listAgentMcpServerOptions(): Promise<AgentMcpServerOptionVo[]> {
  return request.get<AgentMcpServerOptionVo[]>('/agent/mcp-server/options')
}
```

（以本文件既有 request 封装实际形态为准，逐字对齐 `listAgentTools` 的写法。）

**验收标准**：`cd cloud-web && npx vue-tsc --noEmit` 零错误；字段名与契约 §2.2/§2.3/§2.8 逐字段一致。

### F2 编辑弹窗：MCP 服务多选区

**文件清单**：
- Modify: `cloud-web/src/views/agents/manage/components/AgentEditDialog.vue`

**内容**：
- `AgentFormData` 加 `mcp_server_ids: number[]`；`servers = ref<AgentMcpServerOptionVo[]>([])`；`loadServers()`（弹窗打开时与 `loadTools()` 并行调用，失败静默——拦截器已 toast，沿工具选项处置）；`serverLabel(s)` = `` `${s.name}（已发布 ${s.published_tool_count} 工具）` ``。
- 表单在工具多选之后加「MCP 服务」多选（el-select multiple，选项 label 用 serverLabel，value=id；组件按需引入现状不动）；多选区下帮助文案一行：「绑定 MCP 服务后，其名下已发布工具将自动进入该智能体的工具面（后续新增并审核发布的工具同样自动生效）」；另注「已停用/删除的服务以编号显示」（detail 回显的绑定 id 不在选项集时 el-select 按原始值显示，沿工具侧既有处置，不动态注入灰选项）。
- `loadDetail` 回显 `form.mcp_server_ids = [...detail.mcp_server_ids]`；新增模式置 `[]`；提交 payload 恒带 `mcp_server_ids`（全量覆盖语义，编辑时不可漏带——漏带即清空绑定，与 status 恒带同理，弹窗头注释补一句）。
- 5029 业务错误由拦截器统一 toast，弹窗保持打开可改可重试（沿 5016/5017 处置，零新逻辑）。

**验收标准**：新增/编辑两模式手动冒烟（dev 起 cloud-web + cloud-base-ai）：新增勾选一个 server 提交成功；编辑回显选中态正确、改选保存后再次编辑回显一致；选停用态绑定（若有）不阻断；`vue-tsc` 零错误；无全量 ElementPlus 引入（守护既有约束）。

### F3 e2e：run-agent-e2e.mjs 扩场景

**文件清单**：
- Modify: `cloud-e2e/run-agent-e2e.mjs`

**内容**：AG1 场景扩展（route mock 层，不进聚合）：
- mock `GET */agent/mcp-server/options` → 两个 server（`{ id: 1, name: 'SYSTEM', description: null, published_tool_count: 2 }`、`{ id: 2, name: 'WEATHER', ..., published_tool_count: 0 }`）。
- 新增弹窗断言：MCP 服务多选区存在、选项标签含「已发布 2 工具」字样、勾选 SYSTEM 提交 → 断言 POST body 含 `mcp_server_ids: [1]` 且 `tool_ids: []`（并集语义下单选 server 的 body 形态）。
- 编辑回显断言：detail mock 带 `mcp_server_ids: [1]`、`mcp_server_names: ['SYSTEM']` → 打开编辑弹窗选中态回显。
- 混绑场景：同时勾工具与 server 提交，body 两字段并存。
- 侧边顺序串与既有断言零改动（无菜单变更）；判读沿 MEMORY 陷阱（grep -c '[FAIL]'，exit 0 是假绿）。

**验收标准**：`cd cloud-e2e && npm run e2e:agent`（有头）全绿；`E2E_HEADLESS=1` 复跑一致；新增断言失败时截图落 artifacts 供排查。

---

## 3. 移交后续阶段的备忘

1. 多实例部署时 reset_cache 跨进程失效：P3 修复后覆盖面扩大（server 状态变化路径也依赖进程内缓存失效），容器化轮一并解（上一轮移交条目更新）。
2. server 管理页「被 N 个 agent 绑定」计数/影响面反查 UI：数据模型已支持（uk 可按 server_id 反查但无索引，出现需求时增量 `ALTER TABLE ai_agent_mcp_server ADD INDEX idx_server_id (server_id)`）。
3. 编辑弹窗对「已绑定但停用/软删项」的灰选项展示：本轮沿裸 id 显示，体验优化（动态注入禁用选项）记档待议。

## 4. 给涉及端 agent 的任务清单

**backend-agent**（章序执行，B1→B5；每任务 TDD：先测试后实现，完成即 commit）：
- B1 新表 SQL+基线+实体+列集测试（设计文档 §4）
- B2 dao 六动词+装配更名+run_service 一行随迁（设计文档 §3 D2）
- B3 schemas/service/router：5029 校验、CRUD 双绑定、detail 双字段、列表合并展开、§2.8 options 端点（路由顺序坑见设计文档 §3 D4）
- B4 P3 缓存修复+钉死单测（设计文档 §3 D6）
- B5 冒烟扩段（设计文档 §6）
- 全量验收：`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai pytest -v` 全绿（含 test_architecture_guard）

**frontend-agent**（F1→F3；契约 `2026-10-29-agent-platform-api.md` §2.2/§2.3/§2.8 为唯一对齐物）：
- F1 types/api 两文件（契约逐字段）
- F2 编辑弹窗多选区（订阅语义文案 + 回显裸 id 处置）
- F3 e2e 扩场景（route mock，不进聚合）
- 全量验收：`vue-tsc --noEmit` 零错误 + `npm run e2e:agent` 全绿
