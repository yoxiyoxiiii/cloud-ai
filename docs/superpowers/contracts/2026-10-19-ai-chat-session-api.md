# AI 对话会话管理 API 契约（会话 CRUD + 消息回显 + chat 端点 breaking 修订，2026-10-19 首立）

> 前后端唯一对齐物。本轮覆盖：会话创建/列表/删除三端点（§2/§3/§4）、消息回显端点（§5）、
> 对话两端点 `/rag/chat` 与 `/rag/chat/stream` 的 **breaking 入参修订**（§6）。
> 设计依据：`docs/superpowers/specs/2026-10-19-ai-chat-session-design.md`。
>
> **2026-10-19 breaking 变更声明（消费方=前端 chat 页同轮收敛）**：对话两端点入参从
> `{"messages":[...]}` 换轨为 `{"session_id":…,"question":"…"}`——历史改由服务端拼装落档（单源），
> 前端不再上送历史数组。旧入参形态得 1001（缺必填字段）。**SSE 帧协议零变更**
> （`cloud-web/src/utils/sse.ts` 不触碰，帧形态原样——母契约 §3 冻结口径延续）。
>
> 通用口径沿用母契约 `2026-10-13-ai-rag-chat-api.md` §1（网关路径链/鉴权/R 结构）与
> kb 契约 `2026-10-14-ai-kb-api.md` §1（AI 域出参 snake_case、id/total 为 JSON number、
> 分页参数名 page/size）——本文不重复。

## 1. 端点总览与通用约定

| 端点 | 外部路径（经网关 :18080） | 方法 | 作用 |
|---|---|---|---|
| 创建会话 | `/ai/rag/chat/sessions` | POST | 新建一条会话（「创建会话」与「新建会话」同义，一个端点） |
| 会话列表 | `/ai/rag/chat/sessions/list` | GET | 当前登录用户的会话分页（仅本人） |
| 删除会话 | `/ai/rag/chat/sessions/{session_id}` | DELETE | 软删会话 + 物理清消息（双清） |
| 消息回显 | `/ai/rag/chat/sessions/{session_id}/messages` | GET | 该会话历史消息（升序，≤100 条） |
| 对话（修订） | `/ai/rag/chat` | POST | §6 breaking 修订 |
| 对话流式（修订） | `/ai/rag/chat/stream` | POST | §6 breaking 修订（SSE 帧协议不变） |

- **权限标识：全部端点复用 `ai:chat:list`**（2026-10-19 拍板，零新种子零重登——会话是持对话
  权限用户的纯私有从属资源，无管理动作分立诉求）。
- **归属规则**：会话归属 = 创建时网关注入的 `X-User-Id`。任何按 session_id 的操作（chat/回显/删除）
  服务端先校验「会话存在（deleted=0）→ 5012；归属本人 → 5013」，**归属校验先于一切消息读写**。
- **本地直连调试（绕网关）须手工带头**：`X-User-Perms: ai:chat:list` + `X-User-Id: <数字>` +
  `X-User-Account: <账号>`。X-User-Id 缺失时：列表返回空集（不算错误）；创建得 1001；
  chat/回显/删除得 5013（身份头缺失=归属永不匹配）。
- **错误码**：403 无操作权限 / 1001 参数校验 / 5001 LLM 调用失败 / 5002 LLM 未配置（chat 端点）/
  **5012 会话不存在（本轮新立，活记录视角：无此 id 或已删，沿 5004 先例）** /
  **5013 无权访问该会话（本轮新立，沿 5011「存在但无权」先例）** / 500 系统异常。
  AI 段占用至 5013，下一可用 **5014**。
- **服务端行为记档**：消息存 Redis Cluster（键 `rag:messages:{session_id}`，上限 100 条/会话，
  无 TTL——历史随会话生命周期存续，删除会话即双清）；会话元数据存 MySQL（标题服务端生成：
  默认「新对话」，首问取前 16 字符更新）；列表 id 倒序（新建居首，不做活跃置顶）。

## 2. POST /ai/rag/chat/sessions 创建会话（perms `ai:chat:list`）

- 入参：**无 body**。
- 语义：为当前用户新建一条会话（标题默认「新对话」，首问后由服务端更新为问句前 16 字符）。
  建了不问的空会话只占一行元数据，无消息键；删除走 §4。
- 返回 `R<ChatSessionVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| data.id | number | 会话 id（JSON number，后续 chat/回显/删除的引用键） |
| data.title | string | 创建恒为「新对话」 |
| data.create_time | string | 'yyyy-MM-dd HH:mm:ss' |

- 示例：`{"code":200,"msg":"操作成功","data":{"id":12,"title":"新对话","create_time":"2026-10-19 10:30:00"}}`
- 分支：200 / 403 / **1001（X-User-Id 缺失或非整数——无法落归属，拒绝创建）** / 500。

## 3. GET /ai/rag/chat/sessions/list 会话分页（perms `ai:chat:list`）

- 入参（query）：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| page | number | 1 | 页码，从 1 起，越界钳制到 1 |
| size | number | 10 | 每页条数，钳制 1..200 |

- 语义：**仅当前登录用户自己的会话**，id 倒序（新建居首），软删不可见。
- 返回 `R<AiPageResult<ChatSessionVo>>`（total 为 JSON number；rows 元素三字段同 §2）：

```json
{"code":200,"msg":"操作成功","data":{"total":2,"rows":[
  {"id":13,"title":"介绍一下你自己","create_time":"2026-10-19 11:00:00"},
  {"id":12,"title":"新对话","create_time":"2026-10-19 10:30:00"}]}}
```

- 分支：200 / 403 / 500。身份头缺失 = `total:0, rows:[]`（空集语义，非错误）。

## 4. DELETE /ai/rag/chat/sessions/{session_id} 删除会话（perms `ai:chat:list`）

- 路径参数：session_id int 必填（非整数 → 1001）。
- 语义：**双清**——先物理删 Redis 消息键，后 MySQL 软删会话行（顺序保障最坏中间态可重删收敛、
  无孤儿键，见设计 §3.3）。删除不可恢复（用户视角）；删除后该 id 的一切操作得 5012。
- 返回 `R<null>`：data 恒 null。
- 分支：200 / 403 / 1001 session_id 非整数 / **5012 会话不存在（含二次删除）** /
  **5013 会话存在但非本人** / 500。
- 并发删除：两请求同删一个会话，后者按 5012 拒（活记录视角，先到先得）。

## 5. GET /ai/rag/chat/sessions/{session_id}/messages 消息回显（perms `ai:chat:list`）

- 路径参数：session_id int 必填（非整数 → 1001）。入参：无 body、无 query。
- 语义：该会话全部保留消息（≤100 条，超上限丢最旧），**时间升序（旧→新）**；切换会话/刷新后
  前端拉此端点重建消息区。空会话（新建未发问/消息已随删除清空）→ `messages: []`。
- 返回 `R<SessionMessagesVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| data.messages | array | 消息数组，升序 |
| data.messages[].role | string | 枚举 `user` \| `assistant` |
| data.messages[].content | string | 消息文本（流式中断的半截答照实回显——所见即所得） |

- 示例：`{"code":200,"msg":"操作成功","data":{"messages":[
  {"role":"user","content":"你好"},{"role":"assistant","content":"你好！有什么可以帮你？"}]}}`
- 分支：200 / 403 / 1001 / 5012 / 5013 / 500。
- **不设分页**：单会话上限 100 条，全量返回（分页化与上限放开一并移交）。

## 6. 母契约 §2/§3 修订：对话两端点入参换轨（breaking，perms `ai:chat:list` 不变）

**入参**（`application/json`，流式/非流式**完全相同**）：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| session_id | number | 必填，整数 ≥1，**必须 JSON number**（数字串 `"12"` 拒收 → 1001，沿 kb-perm 契约 strict 先例） | 目标会话（§2 创建所得） |
| question | string | 必填，1..4000 字符，**strip 后非空**（纯空白 → 1001） | 本次提问（历史不再上送） |

```json
{ "session_id": 12, "question": "你好，介绍一下你自己" }
```

**旧形态拒绝**：`{"messages":[...]}` 在新模型下缺必填字段 → 1001（服务端忽略未知字段，不会误收）。

**服务端行为（两端点一致，设计 §4.2）**：

1. 校验会话（5012 不存在 / 5013 非本人）——发生在流开始前；
2. 问落档；首问时若标题为默认「新对话」，服务端更新标题为问句前 16 字符（前端可同口径乐观展示，
   刷新后以服务端为准）；
3. 历史由服务端拼装：该会话尾 `history_limit`（默认 20）条（含本次问）送 LLM；
4. 答在**收口时落档**：正常完成、LLM 失败、用户主动停止三条路径统一——已生成的半截答照落
   （所见即所得）；LLM 零 token 失败则无答行（问保留，重试重发会产生重复问，接受）。

**出参与帧协议零变化**：非流式 `R<RagChatAnswerVo{answer, model}>` 原样；SSE token 帧 / `[DONE]` /
msg 帧形态原样（`utils/sse.ts` 不触碰）。

**SSE 错误层次（母契约 §3 层次表扩入 5012/5013）**：

| 场景 | 形态 | 说明 |
|---|---|---|
| 权限不足 403 / 参数失败 1001 / **会话不存在 5012 / 非本人 5013** | HTTP 200 + `R{code:…}` JSON（**非 SSE 帧**） | 均在流开始前拒绝 |
| LLM 未配置 5002 / 调用失败 5001 | SSE `{"msg":…}` 帧 + `[DONE]` | 流已开始（既有） |

- 衍生边界（沿母契约既有记档，勿当 bug 报）：前端 sse.ts 对「200 + R JSON（无 data: 行）」走
  onDone 完成兜底——5012/5013 表现为空 assistant 气泡静默（典型场景：另一标签页删了会话后继续
  发问）。前端可在列表刷新时自然发现会话消失，不为该边界面改 sse.ts。

**前端对接改动清单**：`api/rag.ts` `chatRag` 签名换轨（入参 `ChatSendPayload{session_id, question}`）；
chat 页 `send()` 的 ssePost body 换 `{session_id, question}`；types/api.ts 新增
`ChatSessionVo`/`SessionMessageVo`/`SessionMessagesVo`/`ChatSessionPageQuery`/`ChatSendPayload`
（`RagChatMessage` 保留给消息渲染复用）。

## 7. 菜单/权限种子

零新增（全端点复用菜单 181 `ai:chat:list`，2026-10-19 拍板）。落库脚本仅 `cloud_ai` 库新表
`ai_chat_session`（DDL 见设计 §3.1，基线 cloud_ai.sql 同步 + 增量脚本，pymysql 落库沿中文入库规则）。

## 8. e2e 快照影响

- **菜单零变化**：侧边串/菜单数断言（menu/role/nav/dict/bpmn/dataperm 各脚本）零改动。
- `run-ai-chat-e2e.mjs`（`npm run e2e:aichat`）**本轮重构**：新增 session 四端点 route mock +
  stream 请求体断言 `{session_id, question}` + AC6 从「会话本地态（零 API）」改为服务端行为断言
  （详见 plan 前端章）。`e2e:aichat` 不进 run-e2e.mjs 聚合的先例口径不变。

## 9. 验证记录（2026-10-19 双路实证回填）

结论先行：**直连矩阵 21/21 通过、网关全链 9/9 通过、6379 独立性闭环通过、kb/sso 回归通过、
测试数据清零回基线**。验证脚本 `cloud-base-ai/scripts/p5_stage_a.py`（直连矩阵）与
`cloud-base-ai/scripts/p5_gateway.py`（网关全链），从 cloud-base-ai 目录内执行：
`uv run python scripts/p5_stage_a.py` / `uv run python scripts/p5_gateway.py`（uv 用绝对路径）。

环境：MySQL 127.0.0.1:3306（原生）/ Nacos 容器 8848 / Redis 单机 6379 容器（Java 会话）+
redis-cluster 容器 7000（AI 消息存储，单节点集群 cluster_state:ok / known_nodes:1）/
sso 9201 + system 9202 + gateway 18080 + AI 8010（java -jar / uvicorn 自起，验后停服交还）。

### 9.1 直连矩阵（:8010 手工头 X-User-Perms/X-User-Id/X-User-Account，21/21）

- **主链与标题**：admin 创建会话（id=1，title=新对话，create_time 'yyyy-MM-dd HH:mm:ss'）→
  列表含新行 → 非流式对话一问（`e2e_session_title_check_ABCDEFGH_more_tail`，answer 385 字）→
  回显两条（user 问在前 assistant 答在后）→ pymysql utf8mb4 回查
  `SELECT title FROM ai_chat_session WHERE id=…` = 问句前 16 字符 `e2e_session_titl`（DB 真值，
  非接口回显）。
- **归属矩阵**：X-User-Id=2 对 admin 会话 chat/回显/删除三端点全 5013（admin 侧随后回显 200 不受
  影响）；X-User-Id 缺失 → 列表 `{"total":0,"rows":[]}` 且创建 1001；session_id=999999 → 5012；
  创建后删除 200、二次删除 5012。
- **1001 分支（7 项）**：session_id 数字串 "12" / 0 / 缺失；question 空串 / 4001 字 / 纯空白；
  旧形态 `{"messages":[…]}` ——全部 1001。
- **上限与无 TTL**：真灌 105 条（message_store.append_message，真 redis-cluster）→ 回显恒 100 条、
  首条 m005（最旧 5 条被修剪）末条 m104；`TTL rag:messages:{id}` = -1（无过期）。
- **半截答**：真 LLM 流式对话消费前 3 个 token 帧后掐断连接（用户停止生成等价物）→ 回显末条为
  assistant 半截答（7 字符，服务端 finally 收口落档），user 问完整保留。

### 9.2 网关全链（admin/admin123 经 :18080，9/9）

登录 200 → `POST /ai/rag/chat/sessions` 创建（title=新对话）→ `POST /ai/rag/chat/stream`：
HTTP 200 + text/event-stream，113 个 `data:{"content":…}` 增量帧 + `data:[DONE]` 终帧，
拼接 answer 200 字 → 列表标题已改问句前 16 字符 `e2e_session_gw_f` → 回显两条且
**assistant 全文与流增量拼接逐字一致**（收口落档一致性）→ 旧入参形态经网关 1001 →
删除 200 → 删除后回显 5012 → 列表 total 回基线 0 → kb 域回归 `GET /ai/rag/kb/list` 200。

### 9.3 Redis 双实例独立性（6379 停起闭环）

- 6379 停机窗口内 AI 直连链路（创建/问落档/回显/删除）两次独立实测全通——消息存储走
  redis-cluster 7000，与 6379 无关；其中一次 LLM 调用被上游 429 限流（供应商侧负载饱和，
  与本链路无关，见 9.5）。
- 6379 重起（docker stop→start，PING PONG）后 sso 登录经网关即时 200。另实测一例：6379 停约
  40s 后重起 4s 内的首发登录出现过一次 500（连接池陈旧连接），数秒后自愈——Java 侧
  Lettuce 重连窗口的瞬态行为，非数据损坏，记档备查。

### 9.4 回归与清理

- kb 域零变化（列表 200）；sso 登录全程可用（含 6379 重起后）。
- 测试数据清理：`ai_chat_session` 活行（deleted=0）清零（pymysql 查证 live_rows=0；软删 9 行
  留档为逻辑删除设计预期）；redis-cluster 无 `rag:messages:*` 残键（双清的 DEL 段有效）；
  会话列表接口 total 回基线 0。

### 9.5 备注

验证时段后段 LLM 上游持续 429 限流（OpenAIRateLimitError，供应商侧算力饱和）——LLM 依赖项
（对话/流式/半截答/标题生成）均已在限流前完成实证；429 期间创建/问落档/回显/删除等存储链路
照常工作（问悬置无答行、5001 转译符合契约 §6 行为定义）。
