# RAG MCP 工具契约：cloud-base-rag ↔ cloud-base-agent（Python 侧唯一对齐物）

> 状态：随「RAG 检索 MCP 服务化」轮（设计 `docs/superpowers/specs/2026-11-13-rag-mcp-server-design.md`）定稿。
> 变更入口：契约问题由架构 agent 修订，rag 侧与 agent 侧不得单方改。
> 定位：**平台内 Python MCP 工具面**（rag 服务发布的内置工具）。Java 工具面契约见 `2026-10-17-mcp-tools-api.md`（两份独立，互不重排）；注册表/同步/审核/绑定/运行链路的通用语义以 `2026-10-30-mcp-server-mgmt-api.md`、`2026-10-30-mcp-server-permission-api.md`、`2026-10-29-agent-platform-api.md` 为准，本文不重复，只记 rag 特有部分。
> 术语：双主体认证 = `X-MCP-Client-Secret` 服务密钥 + `Authorization: Bearer <用户 JWT>`；会话 Redis = Java 侧单机 Redis 6379（键 `sso:online:{jti}`，值为 OnlineSession JSON）；jti = JWT 的会话标识声明。

## 1. 通道与认证

### 1.1 端点与传输

| 项 | 值 |
|---|---|
| 服务端 | cloud-base-rag（:8011，入口 `cloud_base_ai/rag_app.py`） |
| MCP 端点路径 | `/mcp`（streamable HTTP 单端点，有状态会话） |
| agent/同步侧直连地址 | `ai_mcp_server` 内置行 `id=2, name='RAG'` 的 url 列：`http://127.0.0.1:8011/mcp`（部署差异时管理员可编辑 url） |
| 网关可达性 | **不可达**——网关屏蔽 `/ai/rag/mcp/**`（SetStatus 403，对齐 `/system/mcp` 先例）；agent 与工具同步直连服务端口 |
| 协议 | MCP over streamable HTTP（POST，JSON-RPC 2.0） |

### 1.2 请求头（每次 MCP 请求必带）

| Header | 值 | 缺失/错误后果 |
|---|---|---|
| `X-MCP-Client-Secret` | 服务密钥（发送侧 agent 取 `.env` `CLOUD_BASE_AI_MCP__CLIENT_SECRET`；**校验侧 rag 读同一配置键同值**；真值不入库不入档不入日志） | HTTP 401 + R JSON |
| `Authorization` | `Bearer <对话/操作用户 access JWT>`（agent 运行链=当次运行用户；同步链=发起同步的管理员，均由调用方原样转发） | HTTP 401 + R JSON |

### 1.3 校验顺序与身份源（rag `RagMcpAuthMiddleware`）

1. 服务密钥常量时间比对（配置为空 = 未配置态，fail-closed 拒绝，不阻断启动）；
2. `Authorization` Bearer token **仅解 payload 取 jti（不验签）**——HS512 签名密钥只在 Java 侧 Nacos，Python 侧不持有；payload 其余声明一概不采信；
3. 会话 Redis `GET sso:online:{jti}`：键不存在（未登录/过期/注销/强退）→ 401；值 JSON 解析失败 → 401；
4. 身份五要素（userId/account/permissions/roleIds/deptIds）**全部取会话值**构建请求身份——与网关注入的五个 `X-User-*` 头同构；后续 kb 可见性裁决以此为准。

**依赖声明（Java 侧变更锚点）**：本契约消费 `sso:online:{jti}` 值的 JSON 字段 `tokenId/userId/account/permissions/roleIds/deptIds`（OnlineSession 结构，CLAUDE.md「Redis 会话契约」记档对象）。Java 侧改该结构须同步本契约。

### 1.4 认证错误 body 形态（对齐 Java McpAuthFilter 先例）

```json
{ "code": 401, "msg": "MCP 认证失败", "data": null }
```

真实 HTTP 状态码 401，非 MCP 协议帧。

## 2. 工具清单总览

| 工具名 | 用途 | 权限档位 | 承载 |
|---|---|---|---|
| `rag_search_kb` | 在指定知识库做向量检索（只读），供 AI 回答「基于某知识库内容」类问题 | 无（登录即可；**kb 级可见性在服务端强制过滤**——见 §3.4） | `rag/mcp_tools.py` 的 `rag_search_kb` 工具 → `rag/retrieval.py` 检索编排（Milvus） |

只读。工具名沿平台规则 `<服务域>_<动作>_<对象>`。本工具是 rag 服务发布的唯一工具；后续 rag 新工具同规则扩容并先修本契约。

## 3. 工具：rag_search_kb

### 3.1 定义

```json
{
  "name": "rag_search_kb",
  "description": "在指定知识库中做向量语义检索（只读）。返回与 query 最相关的文本分片列表（chunk_id/content/score）。需要知识库ID kb_id；登录即可调用，但只能检索当前用户有权访问的知识库。"
}
```

（description 与 rag 服务端 `@mcp.tool` 定义逐字同步——工具 schema 的真相源在 rag 服务端，`ai_tool` 行由同步产生；本文档与代码不一致时先修契约再改代码。）

### 3.2 入参（JSON Schema）

```json
{
  "type": "object",
  "properties": {
    "kb_id": { "type": "integer", "description": "知识库ID（ai_knowledge_base.id）" },
    "query": { "type": "string", "minLength": 1, "maxLength": 1000, "description": "检索文本" },
    "top_k": { "type": "integer", "minimum": 1, "maximum": 20, "default": 5, "description": "返回分片数上限，默认 5" }
  },
  "required": ["kb_id", "query"],
  "additionalProperties": false
}
```

示例入参：`{"kb_id": 3, "query": "请假审批流程", "top_k": 5}`

### 3.3 出参（工具正常结果，MCP content 内 JSON 文本）

```json
{
  "kb_id": 3,
  "chunks": [
    { "chunk_id": "4821", "content": "……分片文本……", "score": -14.23 }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| kb_id | number | 回显请求的知识库ID |
| chunks[].chunk_id | string | 分片行ID（`kb_file_chunk` 子块行 id 字符串化；文件级溯源回表延后，需求出现另轮） |
| chunks[].content | string | 分片文本（子块粒度） |
| chunks[].score | number | 相关度得分，**越大越相关**（实现层已归一方向——底层 Milvus 距离取负；绝对值无跨库可比性，勿做阈值判断） |

无命中/空库 = `"chunks": []` 合法结果。

### 3.4 服务端行为（kb 可见性强制）

按 §1.3 身份依次校验，任一不过即 `isError=true` + content 文本（下表），**不进入检索**：

| 步骤 | 不过的表现（content 文本） | 语义 |
|---|---|---|
| 知识库存在（软删不可见） | `知识库不存在` | 对齐 rag 域 5004 语义 |
| 可见性：管理豁免（perms 含任一 `ai:kb:add/update/delete`）或 本人建库（create_by=账号）或 授权命中（角色 ∪ 部门祖先链 ∪ 直授用户，`ai_kb_grant`） | `无权访问该知识库` | 对齐 rag 域 5011 语义，与 kb 文件列表端点同源同判 |
| 检索执行（Milvus/embedding） | `知识库检索失败` | 错误码 5030（§5），根因只进日志 |

**不使用 `PERMISSION_DENIED:` 前缀**：该前缀是 Java 工具 perms 档位拒绝的专用语义；本工具无 perms 档位，kb 级无权属业务可见性错误——agent 侧按「其它 isError」分支渲染（「工具执行出错」文本回模型自救），语义无歧义。

## 4. AI 侧消费口径（agent 侧零代码，通用流程复述一遍防漏）

- **工具入注册表**：管理员对 RAG 行（`ai_mcp_server` `id=2`）执行「同步」→ `rag_search_kb` 以**草稿**入 `ai_tool`（server_id=2）→ 管理员审核（`required_perms` 留空=登录即可）发布 → 对 agent 可见。同步需 rag 服务在跑且发起者持有效登录态（双主体头中的 JWT 即管理员自己的）。
- **server 授权门**（通用语义，对 RAG 行同样生效）：agent 运行链装配时，RAG 行须对**当前运行用户**可用——持 `ai:mcp:list` 豁免，否则须命中 `ai_mcp_server_grant`（种子已授内置管理员角色；其他用户用 RAG 工具须管理员另行授权）。未授权 = 工具静默不注入（不报错）。
- **工具级门**：`required_perms` 空 → 登录即可，恒过。
- **调用**：agent 运行时按 RAG 行路由双主体头（当次用户 JWT）；通道失败（rag 不可达/认证 401/协议错）→ 既有 5009 语义；isError → §3.4 文本回模型自救，运行不终断，审计照记。
- **超时**：RAG 行 `timeout_ms=30000`（管理员可编辑）。

## 5. 错误码总表

| code | 语义 | 触发 |
|---|---|---|
| 401（HTTP 真实状态码，body 内 code 同值） | MCP 认证失败 | 密钥不符/缺失、JWT 缺失/畸形、会话离线、会话值损坏 |
| 5030 | 知识库检索失败 | Milvus 连接/查询、embedding 调用异常（msg 同文案；根因只进日志） |
| （无新码） | 工具级 kb 不存在/无权 | isError 文本形态（§3.4），不占域码 |

5030 为 AI 段 5xxx 接续（5001-5029 已占用）；下一可用 5031。agent 侧消费沿既有：401 → 5009；5030 类 isError → 文本透传。

## 6. 种子与 e2e 影响

- 种子 SQL：`cloud-base/scripts/sql/2026-11-13-rag-mcp-server.sql`——`ai_mcp_server` 内置行 id=2（name='RAG'）+ `ai_mcp_server_grant`(2, 0, 1)；`ai_tool` **无种子**（同步产生）；**无菜单种子**（不动 sys_menu，无新 perms）。基线 `cloud_ai.sql` 同步。前置断言与不幂等口径沿 2026-10-30 轮惯例。
- e2e：聚合与 `run-mcp-e2e.mjs` 零同步（专线全 route mock，真库种子行不影响）；无侧边栏/菜单数变化。
- Java 侧改动：仅网关（`/ai/rag/mcp/**` SetStatus 403 路由 + `AuthGlobalFilter` 白名单条目 `/ai/rag/mcp`），与 `/system/mcp` 先例同款成对落地。

## 7. 与既有契约的关系（2026-11-13）

- `2026-10-17-mcp-tools-api.md`：Java 工具面（cloud-system /mcp）契约，本文与其并列互补；其 §1 认证形态是本契约 §1 的对齐样板（报文逐字同形，校验实现不同——Java 验签、Python 会话身份源）。
- `2026-10-30-mcp-server-mgmt-api.md` / `2026-10-30-mcp-server-permission-api.md`：注册表管理面与 server 级授权，RAG 行是普通内置行，端点与语义零扩展。
- `2026-10-29-agent-platform-api.md`：agent 绑定与运行面，RAG 工具经既有 mcp_server_ids/tool_ids 绑定，零扩展。
- CLAUDE.md「Redis 会话契约」：本契约 §1.3 声明的会话结构依赖即该记档对象。
