# cloud-base-ai AI 对话（RAG 入门案例）API 契约（2026-10-13）

> 全栈轮（cloud-base-ai 后端 + cloud-web 前端注释锚修正 + 菜单种子 + e2e）。本文件是本轮**前后端唯一对齐物**。设计依据：`docs/superpowers/specs/2026-10-13-ai-rag-chat-design.md`。
>
> 前置契约：`2026-10-12-cloud-base-ai-nacos-registration-api.md` §1 网关路由 / §2 R<T> 结构**沿用不变**（本文不重复 R 结构字段表）。R11 契约 §5 预告的「AI 侧消费 X-User-*」本轮到期兑现（§2）。
>
> 线上 SSE 帧协议由前端 `cloud-web/src/utils/sse.ts` 先行定死（用户已实现），本文 §3 是其后端对齐定稿——**帧形态的任何变更须经架构修订本契约，两端不得单方改**。
>
> **2026-10-19 修订（breaking，现行口径见 `2026-10-19-ai-chat-session-api.md` §6）**：§2/§3 两端点
> 入参已换轨为 `{session_id, question}`（对话会话化——历史改由服务端拼装落档）；本文 §2 入参表与
> 服务端行为记档的历史形态仅作存档。SSE 帧协议（§3）与错误码 5001/5002 **未变**，继续有效。

## 1. 网关路径链与鉴权

| 端点 | 外部路径（经网关 :18080） | StripPrefix 后（AI :8010） | 方法 |
|---|---|---|---|
| 非流式对话 | `/ai/rag/chat` | `/rag/chat` | POST |
| 流式对话 | `/ai/rag/chat/stream` | `/rag/chat/stream` | POST |

- 认证继承 AuthGlobalFilter 全链路（JWT + Redis 在线），网关注入 `X-User-Id / X-User-Account / X-User-Perms`（逗号分隔）透传 AI。
- **权限标识：`ai:chat:list`**（两端点共用一个使用权限；无按钮级 F 权限）。AI 侧校验 X-User-Perms——拒绝 = HTTP 200 + `R{code:403, msg:"无操作权限"}`（对齐 Java @PreAuthorize 语义与 ErrorCode.FORBIDDEN 文案）。
- **本地直连调试（绕网关）须手工带 header**：`X-User-Perms: ai:chat:list`。
- 响应透传：网关不改写 body（SSE 流式透传——验证链实证项，见 §7）。
- 无/坏 token → HTTP 401 + R JSON（网关既有语义）；AI 未启动 → 网关 503。

## 2. 端点一：POST /ai/rag/chat（非流式）

> ⚠️ 2026-10-19 起**已废止本节入参形态**（breaking 换轨 `{session_id, question}`），现行口径见
> `2026-10-19-ai-chat-session-api.md` §6；下表保留作历史存档。

**入参** `application/json`（body，字段名前后端一致）：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| messages | array | 必填，1..50 条 | 对话历史（含本次提问，按时间升序） |
| messages[].role | string | 枚举 `user` \| `assistant` | 说话方 |
| messages[].content | string | 必填，1..4000 字符 | 消息文本 |

```json
{ "messages": [ { "role": "user", "content": "你好，介绍一下你自己" } ] }
```

**出参** `R<RagChatAnswerVo>`（HTTP 恒 200，结构沿 R11 契约 §2）：

| 字段 | 类型 | 说明 |
|---|---|---|
| data.answer | string | LLM 完整回答（markdown 文本） |
| data.model | string | 实际使用的模型名（= 配置 `CLOUD_BASE_AI_LLM__MODEL`） |

```json
{ "code": 200, "msg": "操作成功", "data": { "answer": "你好！我是……", "model": "deepseek-flash" } }
```

**错误码**：403（权限）/1001（参数）/5001（LLM 调用失败）/5002（LLM 未配置）/500（系统异常）——码表见 §5。

**前端对接**：`cloud-web/src/api/rag.ts` `chatRag(messages)` 已实现（timeout 120s 放大）。

**服务端行为记档**：历史截断取**最近 20 条**（`history_limit` 可配）后送 LLM；超时 60s + SDK 重试 1 次。

## 3. 端点二：POST /ai/rag/chat/stream（流式，SSE）

**入参**：与端点一**完全相同**的 JSON body。

**响应**：`Content-Type: text/event-stream; charset=utf-8`，`Cache-Control: no-cache`，HTTP 200（鉴权/校验通过后即提交，chunked 逐帧推送）。

**帧协议**（SSE 标准，帧以空行 `\n\n` 分隔，每帧一个 `data:` 行；JSON 序列化不转义中文，字段内换行经 JSON 转义不破坏帧定界）：

| 帧 | 字节形态 | 语义 | 前端消费（sse.ts 定死） |
|---|---|---|---|
| token | `data: {"content":"<增量文本>"}\n\n` | 一次 LLM 增量 | `onToken` 追加渲染 |
| 结束 | `data: [DONE]\n\n` | 流正常收口（必为最后一帧） | `onDone`，取消 reader |
| 错误 | `data: {"msg":"<文案>"}\n\n` | 业务错误（随后必发 `[DONE]`） | `onError`；气泡已含内容时静默 |

示例流：

```
data: {"content":"你"}

data: {"content":"好"}

data: [DONE]

```

**服务端时序保证**：正常流 = N 个 token 帧 + `[DONE]`；任何异常（含首 token 前）= 0..N 个 token 帧 + 1 个 msg 帧 + `[DONE]`——**绝不裸断流**（断流未发 [DONE] 是服务端 bug；前端对此有按完成兜底，但该兜底不应被触达）。

**错误表达的层次差异（记档，设计 D5/D6/D8）**：

| 场景 | 形态 | 说明 |
|---|---|---|
| 权限不足 | HTTP 200 + `R{code:403}` JSON（**非 SSE 帧**） | 流未开始即被依赖拒绝 |
| 参数校验失败 | HTTP 200 + `R{code:1001}` JSON（非 SSE 帧） | pydantic 校验先于端点 |
| LLM 未配置/调用失败 | SSE `{"msg":...}` 帧 + `[DONE]` | 流已开始（帧协议内表达） |

**衍生边界（已知并接受，勿当 bug 报）**：前端 sse.ts 对「200 + R JSON（无 data: 行）」走 onDone 完成兜底——403/1001 表现为空 assistant 气泡静默；正常主路径前端 `input.trim()` 已挡空入参，403 仅在权限快照过期（改权限未重登）边缘竞态出现。改 sse.ts 属协议反向变更，须回架构修契约。

## 4. SSE 网关透传实证项（D15 风险管理）

理论支持（AuthGlobalFilter 通过路径纯透传 + WebFlux DataBuffer 流转发 + 网关无响应超时配置），**未经真机实证**——验证链必验：经网关消费，断言 token 帧逐帧增量到达（帧到达时间差 > 0，非流尾一次性缓冲）。若被缓冲：网关 yml 流式配置立项，**两端零改动**。

## 5. 错误码（本轮新立 5xxx AI 段，从 5001 起）

| code | 语义 | 触发与文案 |
|---|---|---|
| 403 | 无操作权限 | X-User-Perms 不含 `ai:chat:list`；文案对齐 Java `ErrorCode.FORBIDDEN`：「无操作权限」 |
| 1001 | 参数校验失败 | 字段约束不满足（§2 表）；msg 带字段名拼接（既有 handler） |
| 5001 | LLM 调用失败 | LLM 网络异常/超时/限流/key 无效——服务端 log.error 记根因，msg 不含 key 与上游原文；流式端点走 msg 帧 |
| 5002 | LLM 未配置 | `CLOUD_BASE_AI_LLM__API_KEY` 为空时调用；msg 提示配置键名 |
| 500 | 系统异常 | 兜底（既有） |

**接续规则**：AI 段 5001/5002 已占，后续 AI 域新增从 **5003** 接续；2xxx 认证段 AI 侧不经手（网关职责）。

## 6. 菜单/权限种子与 e2e 快照影响

### 6.1 种子（`cloud-base/scripts/sql/2026-10-13-ai-rag-chat.sql`，基线 cloud_system.sql 同步）

```sql
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(18,  0, 'AI 助手', '',             'M', '',          'ChatDotRound', 4, 1, NOW()),
(181, 18, '对话',   'ai:chat:list', 'C', '/rag/chat', 'ChatLineRound', 1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (18, 181);
```

- 前置断言：`SELECT id FROM sys_menu WHERE id IN (18, 181)` 应 0 行（含墓碑物理判断）；绑定段无防重（撞 uk 报错，先例口径）。
- **权限快照时效**：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后 `ai:chat:list` 生效。
- 侧边呈现：顶级目录「AI 助手」（第 4 个 M，sort 4）→ 子项「对话」（path `/rag/chat`，前端 viewRegistry/路由名 RagChat 已注册零改动）。

### 6.2 e2e 快照影响清单（逐脚本逐行，死信治理轮同款先例）

| 脚本 | 位置 | 变更 |
|---|---|---|
| `run-menu-e2e.mjs` | :52 SEED_MENU_IDS 数组 | +`'18','181'`（44→46）；:41 注释同步 |
| `run-menu-e2e.mjs` | :211 侧边顺序串 | 12 项串追加「对话」（实测 DOM 入序定位，预计 `…流程定义,对话,工作台`）；:13 「恰 44」注释同步 |
| `run-role-e2e.mjs` | :300 SEED_MENU_IDS / :308 断言 | +`'18','181'`；文案「恰 44 个」→46（+18 段） |
| `run-nav-e2e.mjs` | :172 根级恰 2 断言 | →恰 3；新增 AI 助手 M 形状断言（icon `ChatDotRound`/parentId 0/子级恰 1 C：path `/rag/chat` name 对话 icon 非空） |
| `run-nav-e2e.mjs` | :224 附近侧边串 | 同 menu :211（12→13 项） |
| `run-dict-e2e.mjs` | :260 侧边顺序串 | 同上（含 :254 注释） |
| `run-bpmn-e2e.mjs` | :568 侧边顺序串 | 同上 |
| `run-dataperm-e2e.mjs` | :219 侧边顺序串（:210/:10 文案） | 同上（12 项→13 项文案同步） |

（侧边串为 el-menu-item 文本域精确串——「对话」入序位置以实测 DOM 为准，M 目录标题不入此域。）

## 7. 配置键（key 真值永不落任何仓库文件）

| 键（env / .env 同名） | 默认 | 说明 |
|---|---|---|
| `CLOUD_BASE_AI_LLM__API_KEY` | 空 | DeepSeek API key（**真值仅本地 .env**；空 = 端点报 5002） |
| `CLOUD_BASE_AI_LLM__BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容端点 |
| `CLOUD_BASE_AI_LLM__MODEL` | `deepseek-flash` | 模型名 |
| `CLOUD_BASE_AI_LLM__TIMEOUT` | `60` | 单次 LLM 请求超时（秒） |
| `CLOUD_BASE_AI_LLM__MAX_RETRIES` | `1` | SDK 内建重试次数 |
| `CLOUD_BASE_AI_LLM__HISTORY_LIMIT` | `20` | 服务层历史截断（条） |

优先级：真实 env > `.env`（工程目录下，绝对路径解析）> 默认值。模板 `.env.example` 入库（空值）。

## 8. 前端类型对齐（cloud-web/src/types/api.ts 既有预留，零新增）

`RagChatMessage{role:'user'|'assistant', content:string}`、`RagChatAnswerVo{answer:string, model:string}` 与本文 §2/§3 一致。**注释锚修正**：`types/api.ts`（992 行附近）与 `api/rag.ts` 的「契约 2026-10-12-ai-rag-chat-api」→「**2026-10-13**-ai-rag-chat-api」（本文）；`api/rag.ts` perms 注释 `rag:chat:list` → `ai:chat:list`。
