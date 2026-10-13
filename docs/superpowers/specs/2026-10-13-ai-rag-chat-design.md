# cloud-base-ai RAG 入门案例——LLM 流式对话设计（2026-10-13）

> 轨道：全栈（后端 cloud-base-ai 四模块 + 前端注释锚/e2e + 种子 SQL）。A 级流程阶段二产物；阶段一需求分析与拍板结论见本文 §2。API 契约：`docs/superpowers/contracts/2026-10-13-ai-rag-chat-api.md`（前后端唯一对齐物，前端 `sse.ts` 已定死的线上协议是契约的既定输入）。

## 1. 背景与目标

rag/ 域（R12 骨架，10 文件一行 docstring 占位）迎来**首个功能实现轮**：纯 LLM 流式对话——前端页面/SSE 客户端/类型/npm 依赖用户已完整实现（`cloud-web/src/views/rag/chat/` + `src/utils/sse.ts` + `src/api/rag.ts`），本轮补齐后端契约对侧并全链激活：LLM 接入（DeepSeek，OpenAI 兼容）+ SSE 流式 + 网关透传 + AI 侧鉴权 + 菜单种子 + e2e。**前端页面与 SSE 客户端零改动**（协议已被前端定死，后端对齐）。

上轮移交兑现：R12 移交 #1（实现轮）/#3（首个端点轮：router 注册 + main.py 解除零触碰 + X-User-* 消费）、R11 移交 #4（X-User-* 消费约定）部分兑现、#9（LLM key 载体，.env 口径——Nacos 配置中心消费仍留移交）。

**零表变更**（无业务表、无 DDL；sys_menu 种子是配置数据非表结构）。

## 2. 拍板结论（阶段一呈报后拍板，2026-10-13）

1. **Q1 LLM key 载体 = .env 起步**：Settings 增 `llm` 嵌套段，pydantic-settings 显式 `env_file=".env"`（绝对路径解析，见 D1）+ `.gitignore` 补 `.env`。key 真值不进 git/文档/代码——spec/plan/契约一律引用配置键名，backend-agent 用占位符，真值由主控验收阶段写入本地 `.env`。`.env.example` 模板（空值）可入库。Nacos 配置中心消费独立立项记移交。
2. **Q2 非流式端点保留**：后端 `ainvoke` 非流式 + 前端 `chatRag()`/`RagChatAnswerVo` 留用（e2e/调试稳定锚）。
3. **Q3 e2e 分层**：e2e 用 Playwright route 拦截返假 SSE 流（回归稳定）；后端真链路真调 DeepSeek 冒烟取证（本地直连 + 经网关带 token 双层）；真实 LLM 调用不进全量回归。
4. **Q5 权限与菜单**：标识 `ai:chat:list`（流式/非流式共用一个使用权限）；顶级 M「AI 助手」+ 子菜单 C「对话」（id 段接续，见 D13）；前端 `rag:chat:list` 注释同步改 `ai:chat:list`。
5. **Q4（主控认可）鉴权深度**：AI 侧校验 X-User-Perms——FastAPI Depends 读网关注入 header 校验，首个 X-User-* 消费样板，做成可复用小组件；拒绝形态定稿见 D6。
6. **Q6（主控认可）错误码新立 5xxx AI 段**：5001 起，完整码表见 D8。

## 3. 领域定位与形态记档

- **纯 LLM 对话、零检索**：RAG 管线六阶段只激活 generation（生成）——「RAG 之前先通 LLM」的第一步；retrieval 与索引链（ingestion/chunking/embedding/vectorstore）本轮不动、继续占位。后续检索进来时 service 在查询链中插入 retrieval 步骤，本轮结构零返工。
- **会话完全无状态**：前端本地态会话（无后端持久化，页面源码注释「持久化见迭代方向」），每请求上送全量 `messages` 历史，后端无会话表/会话 id/多轮记忆。刷新即丢、多端不同步，入门案例预期形态。
- **结构性数据流沿 R12 §3 查询链声明**：`router → service → generation`（本轮无 retrieval 调用），schemas 被各层引用。

## 4. 架构（文字版）

```
浏览器（cloud-web :5173 dev）
  │ fetch POST /api/ai/rag/chat/stream  (Authorization: Bearer <jwt>)
  ▼ vite dev proxy /api → :18080（rewrite 去 /api）
cloud-gateway :18080（WebFlux，既有零改动）
  │ /ai/** → lb://cloud-base-ai + StripPrefix=1
  │ AuthGlobalFilter：JWT 验签 + Redis 在线 → 注入 X-User-Id/Account/Perms 透传
  ▼ 响应体不改写、逐 DataBuffer 透传（SSE 流式透传，验证链实证）
cloud-base-ai :8010（FastAPI）
  │ /rag/chat/stream
  │ require_perms("ai:chat:list")——读 X-User-Perms（逗号分隔），拒绝=HTTP 200 + R{code:403}
  ▼
rag/router.py ── 请求校验(RagChatDto) ──► rag/service.py（历史截断/编排/异常转译）
  │                                          │
  │ StreamingResponse(text/event-stream)     ▼
  │ data: {"content":...} 帧 ◄────────── rag/generation.py
  │ data: [DONE] 终帧                 ChatOpenAI(base_url=DeepSeek, astream)
  ▼                                    缺 key → BusinessException("LLM 未配置…", 5002)
DeepSeek API（https://api.deepseek.com，OpenAI 兼容，model deepseek-flash）
```

非流式端点 `/rag/chat` 同链路，出 `R<RagChatAnswerVo>`（HTTP 200 + body.code）。

## 5. 决策记录

### D1 LLM 配置 = .env 文件 + 嵌套 env 双通道（拍板 1）

`config.py` 增 `LLMSettings`（pydantic BaseModel，同 NacosSettings 形态）：

```python
class LLMSettings(BaseModel):
    api_key: str = ""                        # 真值仅存本地 .env，不入库不入档
    base_url: str = "https://api.deepseek.com"
    model: str = "deepseek-flash"
    timeout: float = 60.0                    # 单次 LLM 请求超时（秒）
    max_retries: int = 1                     # openai SDK 内建重试次数
    history_limit: int = 20                  # 服务层历史截断（条，D10）

class Settings(BaseSettings):
    nacos: NacosSettings = NacosSettings()
    llm: LLMSettings = LLMSettings()
    app_port: int = 8010
    model_config = SettingsConfigDict(
        env_prefix="CLOUD_BASE_AI_", env_nested_delimiter="__",
        env_file=str(BASE_DIR / ".env"), env_file_encoding="utf-8",
    )
```

- **`BASE_DIR` 绝对路径坑（关键）**：pydantic-settings 的 `env_file` 相对路径按 **进程 cwd** 解析，而起服命令 `uv run --project cloud-base-ai uvicorn ...` 的 cwd 是**仓库根**——`.env` 相对路径会指向仓库根而非工程目录。定稿 `BASE_DIR = Path(__file__).resolve().parents[2]`（`src/cloud_base_ai/config.py` 三层上 = `cloud-base-ai/`），`.env` 跟工程走。
- **优先级**：真实环境变量 > `.env` 文件 > 字段默认值（pydantic-settings 默认序）——CI/临时覆盖用 env，常态用 `.env`。
- **非敏感项给默认值**：`base_url`/`model` 用拍板输入值直接做默认（非 secret，可入库，零配置即连 DeepSeek）；`api_key` 默认空串 = 未配置态（端点调用时报 5002，不阻断启动——同 D7 注册降级哲学：缺配置是可观测的运行时错误，不是启动错误）。
- **`.env` 不存在时静默忽略**（pydantic-settings DotEnvSettingsSource 既有行为）——未写 `.env` 服务照常起，仅 LLM 端点报 5002。
- 配置键形态（文档/`​.env.example` 引用口径，真值永不落档）：`CLOUD_BASE_AI_LLM__API_KEY` / `CLOUD_BASE_AI_LLM__BASE_URL` / `CLOUD_BASE_AI_LLM__MODEL` / `CLOUD_BASE_AI_LLM__TIMEOUT` / `CLOUD_BASE_AI_LLM__MAX_RETRIES` / `CLOUD_BASE_AI_LLM__HISTORY_LIMIT`。
- `.gitignore` 追加 `.env`（现两行 `.venv/`、`__pycache__/`）；`.env.example` 入库（全部键名 + 空值/注释）。

### D2 LLM 接入 = langchain-openai `ChatOpenAI` 直连 DeepSeek（用户指定 + 实查记档）

- **零新增依赖**：`langchain-openai>=1.7.0` 已在 pyproject（venv 实查 1.7.0 在装）；ChatOpenAI 构造零网络调用（openai client 纯本地对象），单测可真构造。
- **官方警告记档（reference-langchain 实查 2026-10-13）**：ChatOpenAI 只对齐官方 OpenAPI 规范，第三方端点的非标字段（如 DeepSeek reasoner 系的 `reasoning_content`）**不提取不保留**；官方建议 DeepSeek 场景用 provider 包 `ChatDeepSeek`。本轮 deepseek-flash 走标准 `content` 通道，无碍——**ChatDeepSeek 迁移显式 OUT**（若未来需要 reasoning 通道再立项）。
- **`use_responses_api=False` 必须显式传（防误路由坑，实查）**：ChatOpenAI 会依据模型名推断走 Chat Completions 还是 Responses API，独立于 base_url——DeepSeek 仅支持 chat completions，被误路由到 `/responses` 即请求失败。官方文档明确建议对接 OpenAI 兼容供应商时显式设置。
- 模型对象**进程内单例**（`functools.lru_cache` 工厂；线程/协程安全可复用，避免每请求重建 client 连接池）。

### D3 rag/ 落位 = 零新文件拓扑（沿 R12 §3 数据流声明）

chat 落 rag 域既有四文件，深度硬顶 2 层不突破、`rag/` 下零新增模块：端点 `router.py`、编排 `service.py`、LLM 封装 `generation.py`、DTO `schemas.py`；LLM 配置扩基座 `config.py`（R12 D6「配置走 Settings 扩展」的兑现）。chat 即 rag 域查询链的首个实例（无检索的退化 RAG = 纯生成），retrieval 本轮不被调用，未来检索进来 service 加步骤即可——与 R12 结构声明零冲突。

### D4 X-User-Perms 校验组件 = 基座新平铺模块 `security.py`（拍板 5 / R11 移交 #4 兑现）

- **落位**：`src/cloud_base_ai/security.py` 基座平铺**新增**（非重组——R12 §5「基座不因业务域到达而重组」禁的是重组存量，新增横切件是平铺的自然生长；理由：鉴权依赖未来被所有 AI 域端点复用，放 rag/ 域内会错归属）。
- **契约事实对齐（Java 源码实查）**：网关注入 `X-User-Perms`，值为权限标识逗号连接（`SecurityConstants.PERMS_SEPARATOR = ","`）；Java 侧 HeaderAuthFilter 按 `,` split 构建 authorities，@PreAuthorize 拒绝 = `ErrorCode.FORBIDDEN(403, "无操作权限")`。Python 组件逐项对齐：header 名 `X-User-Perms`、分隔 `,`（split 后 strip 防御空格）、拒绝文案「无操作权限」。
- **信任边界**：与 Java 服务层同款——信任网关注入（网关已剥离外部伪造 X-User-*），AI 侧不二次验签。**本地直连调试（绕过网关）需手工带 `X-User-Perms: ai:chat:list` header**，契约记档。
- 形态：`require_perms(required: str)` 返回 FastAPI 依赖函数，内部抛 `BusinessException("无操作权限", 403)`；挂在 `APIRouter(dependencies=[...])` 级（rag 两端点共用，后续端点复用同一组件）。**注意 R11 既有签名为 `(msg, code=1002)`——msg 在前，勿按 Java `(code, msg)` 习惯写反；属性 `.msg`/`.code`**。

### D5 流式端点对 R<T>/DTO 规范的豁免记档（工程规范第 2/3 条）

SSE 端点返回 `StreamingResponse(media_type="text/event-stream")`——**帧协议不包 R、无统一 VO**，属工程规范「路由返回 R[模型]」与「禁裸 dict」的显式豁免（同 R11 D11 `/health` 裸 JSON 先例，本条随本轮记入规范豁免清单）。帧协议四形态（对齐前端 `sse.ts` 已定死解析逻辑，逐字段见契约 §3）：

| 帧 | 形态 | 语义 |
|---|---|---|
| token | `data: {"content": "<增量文本>"}\n\n` | 一次 LLM 增量 |
| 结束 | `data: [DONE]\n\n` | 流正常收口 |
| 错误 | `data: {"msg": "<文案>"}\n\n` | 随后必发 [DONE] |
| 分隔 | 空行 | 帧边界（SSE 标准） |

- **JSON 序列化定稿**：`json.dumps(payload, ensure_ascii=False)`——帧内可读中文（UTF-8，starlette 对 text/* 自动补 charset=utf-8），JSON 转义保证 content 内换行不破坏帧定界。
- **响应头**：`Cache-Control: no-cache`（SSE 惯例，防中间层缓存）；无 nginx 故无 X-Accel-Buffering 需求。
- **非流式端点不豁免**：正常走 `R[RagChatAnswerVo]` 两行式返回。

### D6 拒绝/校验失败的响应形态 = 统一 R JSON（HTTP 200 + body.code）

- **权限不足（403）**：`require_perms` 抛 `BusinessException("无操作权限", 403)` → 既有全局 handler → HTTP 200 + `R{code:403, msg:"无操作权限"}`——**对齐 Java @PreAuthorize 拒绝语义**（CLAUDE.md 认证链路约定原文）。**不采用**「进入端点后以 SSE msg 帧表达 403」方案：① 统一 Depends 组件形态是可复用样板（D4 目标），端点内手工校验破坏组件一致性；② 两端点拒绝形态分裂；③ 403 属鉴权层错误，SSE 帧是业务流通道，层次不同。
- **入参校验失败（1001）**：FastAPI RequestValidationError 在端点进入前抛 → 既有 handler → HTTP 200 + `R{code:1001}`（无 data: 帧）。
- **衍生边界记档（已知，不改）**：前端 `sse.ts` 对 200 + 非 SSE body（R JSON）找不到 `data:` 行 → 走 onDone 完成兜底——403/1001 时表现为空 assistant 气泡静默完成。**接受理由**：正常路径前端 `input.trim()` 已挡空入参；403 仅出现在 perms 快照过期（改权限未重登）的边缘竞态；改 `sse.ts` 属用户已定死协议的反向变更，本轮不动。契约逐条记档该边界。

### D7 非流式端点保留（拍板 2）

`POST /rag/chat` → `R<RagChatAnswerVo{answer, model}>`：`ainvoke` 一次成型；`model` 取 `settings.llm.model`（配置的模型名，配置即事实）；前端 `chatRag()`/`RagChatAnswerVo` 已实现留用（120s 放大超时已写）。价值：e2e/调试稳定锚（非流式断言语义简单）、R<T> 规范的 AI 域首个标准载体。

### D8 错误码 = 新立 5xxx AI 段（拍板 6，接续分配规则记档）

既有分段 1xxx 通用 / 2xxx 认证 / 3xxx system / 4xxx bpmn 已占满 2-4 千，AI 服务是第五个服务域——**5xxx AI 段从 5001 起**：

| code | 语义 | 触发 |
|---|---|---|
| 5001 | LLM 调用失败 | LLM 网络异常/超时/限流/key 无效等（service 层 catch，log.error 记根因后转译；msg 不含 key 与上游原始响应） |
| 5002 | LLM 未配置 | `api_key` 为空时调用 LLM 端点（get_chat_model 工厂抛） |

- 5001/5002 已占，**AI 段后续新增从 5003 接续**；1xxx 通用段（200/403/1001/1002/500）继续由既有 handler/依赖产生，不受影响。
- **流式端点的 5xxx 表达**：流内 LLM 失败 → SSE `data: {"msg": "..."}` 帧 + `[DONE]`（帧协议无 code 位——前端定死只读 msg；5001/5002 码值仍记入服务端日志与契约码表，非流式端点有完整 code 消费位）。

### D9 权限标识 = `ai:chat:list`（拍板 4）

svc 段 `ai` 与网关路由前缀 `/ai/**` 同源（对齐 `system:mqDlq:list` 等既有 `<svc>:<entity>:<action>` 体系）；entity `chat`、action `list`（对话使用权限，流式/非流式共用一个）；前端 `api/rag.ts` 注释的 `rag:chat:list` 同步修正。无按钮级 F 权限（页面无受控按钮）。

### D10 输入防御性边界（架构自决）

- **入参上限**（schemas 层，RequestValidationError → 1001）：`messages` 1..50 条；单条 `content` 1..4000 字符；`role` 枚举 `user|assistant`（Literal）。
- **服务层历史截断**：取 `history[-history_limit:]`（默认最近 20 条 ≈ 10 轮）——防长会话 context 爆量与费用滥用；deepseek-flash context 远大于此，20 条是防御值非能力值。截断在服务层做（非 schemas），`history_limit` 是 config 键可调。
- 首条消息不强制 user（LLM API 兼容任意开头，不虚构业务约束）。

### D11 LLM 超时与重试 = timeout 60s / max_retries 1（架构自决）

流式首 token 通常秒级、长回答分钟内；60s 单请求超时 + SDK 内建重试 1 次——重试过多会放大流式端点挂起时间（每次重试都全超时），取 1 平衡可用性与挂起。均 config 键可调（D1）。

### D12 流中途异常收口与客户端 abort（架构自决）

- **SSE 生成器内 catch 全量**：router 层 event generator 以 `try/except BusinessException/Exception` 包裹整个 `async for`——任何时点失败（含首 token 前的 5002）都发 `data: {"msg":...}` + `data: [DONE]` 收口，**绝不裸断流**（前端断流兜底按完成处理，会掩盖错误；显式 msg 帧让首 token 前失败可见）。中途失败（已发若干 content）时前端 content 非空对 msg 静默——前端定死行为，无害记档。
- **客户端 abort（停止生成）**：starlette StreamingResponse 在客户端断开时 close generator（`aclose()` 链传播）→ langchain `astream` 内部清理上游连接——标准路径零显式代码；验收冒烟以「停止后服务端无悬挂请求」观察记档（实现时验证项）。

### D13 菜单种子 = id 18/181，顶级 M「AI 助手」sort 4（拍板 4 细化）

- **id 接续**（基线 cloud_system.sql 实查）：已占 10-17/20-21/30-34 + 各百位子段；**18 段空闲**——`18` M「AI 助手」、`181` C「对话」（预留 182/183 给 AI 域后续菜单）。
- M path 空串 + sort 4（顶级 M 序：系统管理 1/认证管理 2/流程管理 3/AI 助手 4——认证管理子级 path 空剪空不进导航，故侧边第 3 个目录）；C `path=/rag/chat`（与前端 viewRegistry/路由名 RagChat 零改动对齐）、`perms=ai:chat:list`、icon `ChatDotRound`(M)/`ChatLineRound`(C)。
- admin 绑定（sys_role_menu 增量显式 id）+ 基线 cloud_system.sql 同步 18 段 + 权限快照时效头注（admin 须重新登录生效，沿先例）。

### D14 e2e = 分层策略（拍板 3）

- **e2e 全量（新脚本 + 快照同步）**：Playwright `page.route` 拦截 `**/api/ai/rag/chat/stream` 返假 SSE 流——零真实 LLM 依赖、回归稳定；AI 服务不需起（mock 网络层），但登录/菜单链需要 sso/system/gateway（种子先落）。
- **真实 LLM 冒烟（不进 e2e）**：实现轮后端验证链含真调 DeepSeek 取证（本地直连 + 经网关带 token 双层、token 增量逐帧到达断言）；key 真值由主控验收阶段注入（backend-agent 冒烟用无 key 分支：5002 帧取证）。
- **既有快照同步**：菜单 18 段使侧边栏快照过期——影响 7 脚本 10+ 处（清单见契约 §6）。

### D15 网关 SSE 透传 = 理论支持 + 验证链实证（风险项显式管理）

- **代码事实**：AuthGlobalFilter 通过路径纯 `chain.filter` 透传（拒绝路径才写响应体）；网关 yml 零 httpclient response-timeout 配置（默认无响应超时，长流友好）；WebFlux/Reactor-Netty 按响应序透传 DataBuffer 流，理论支持 SSE。
- **未实证即风险**：流式经网关从未真机跑过——验证链列**必验项**：经网关逐帧增量到达（非一次性缓冲到流尾）。若实测被缓冲：排查方向为网关 httpclient 配置（response-timeout/buffer），fallback 备选=网关 yml 显式流式配置——**不改 AI 侧与前端**（两端已定死），实在不行最后才考虑前端直连 AI 端口（破坏统一入口，须回主控重议）。

## 6. 封装设计（文件清单全量）

| 文件 | 动作 | 职责 |
|---|---|---|
| `cloud-base-ai/src/cloud_base_ai/config.py` | Modify | `LLMSettings` 六字段 + Settings 挂 `llm` 段 + `BASE_DIR`/`env_file`（D1） |
| `cloud-base-ai/src/cloud_base_ai/security.py` | Create | `require_perms(required)` → FastAPI 依赖；X-User-Perms 逗号解析 + 403 拒绝（D4，文案「无操作权限」对齐 Java ErrorCode） |
| `cloud-base-ai/src/cloud_base_ai/rag/schemas.py` | Modify | `RagChatMessage`（role Literal/content 1..4000）、`RagChatDto`（messages 1..50）、`RagChatAnswerVo`（answer/model）（D10） |
| `cloud-base-ai/src/cloud_base_ai/rag/generation.py` | Modify | `get_chat_model()` lru_cache 工厂（D2 参数集：model/api_key/base_url/timeout/max_retries/use_responses_api=False；缺 key 抛 5002）+ `to_lc_messages()`（RagChatMessage → langchain.messages BaseMessage 列表） |
| `cloud-base-ai/src/cloud_base_ai/rag/service.py` | Modify | `chat_answer(dto)`（ainvoke → RagChatAnswerVo，5001 转译）、`stream_chat_answer(dto)`（async generator yield str 增量，截断 history_limit，LLM 异常 log.error 后抛 5001）——LLM 异常 catch 在 service（规范「catch 必须 log 根因」），帧收口在 router |
| `cloud-base-ai/src/cloud_base_ai/rag/router.py` | Modify | `APIRouter(prefix="/rag/chat", dependencies=[Depends(require_perms("ai:chat:list"))])`；`POST ""` 非流式两行式返回；`POST "/stream"` StreamingResponse + event generator（D5 帧序列化/ensure_ascii=False/D12 catch 全量） |
| `cloud-base-ai/src/cloud_base_ai/main.py` | Modify | `app.include_router(rag_router)`（R12 D6 零触碰解除点；lifespan/既有端点零改动） |
| `cloud-base-ai/.env.example` | Create | 全部 LLM 键名 + 空值 + 注释（api_key 行注明「真值不提交」） |
| `cloud-base-ai/.gitignore` | Modify | 追加 `.env` |
| `cloud-base-ai/README.md` | Modify | 增「AI Chat」小节：.env 配置说明（键名形态）、两端点、本地直连调试需带 X-User-Perms |
| `cloud-base-ai/tests/test_settings.py` | Modify | llm 段默认值 / env 覆盖 / `.env` 文件加载（tmp 文件 + `_env_file` 显式传参）——**用 `Settings(_env_file=None)` 构造隔离真 .env 污染**（防验收期真 key 使「默认空」断言翻车） |
| `cloud-base-ai/tests/test_security.py` | Create | require_perms：命中/未命中/空 header/含空格分隔 → code 403 文案断言 |
| `cloud-base-ai/tests/test_rag_schemas.py` | Create | DTO 校验矩阵（role 枚举/content 边界/条数边界） |
| `cloud-base-ai/tests/test_rag_generation.py` | Create | 缺 key → 5002（lru_cache cache_clear 防污染）；ChatOpenAI 真构造断言参数传递（零网络） |
| `cloud-base-ai/tests/test_rag_service.py` | Create | monkeypatch get_chat_model：历史截断取尾 20、chat_answer 返回 VO、LLM 异常 → 5001 且 log.error |
| `cloud-base-ai/tests/test_rag_router.py` | Create | TestClient：403（无/错 perms）/1001（非法入参）/非流式 200 结构/流式帧序全文断言（mock service 流 → `data: {"content"...}`×N + `[DONE]`）/流式 5001 msg 帧/带 perms 通过 |
| `cloud-base/scripts/sql/2026-10-13-ai-rag-chat.sql` | Create | 菜单种子 18/181 + admin 绑定 + 前置断言头注（D13；含基线同步说明） |
| `cloud-base/scripts/sql/cloud_system.sql` | Modify | 菜单段追加 18 段两行（语义等价注释，沿 17 段先例） |
| `cloud-web/src/api/rag.ts` | Modify | perms 注释 `rag:chat:list`→`ai:chat:list`；契约锚日期 2026-10-12→2026-10-13 |
| `cloud-web/src/types/api.ts` | Modify | 契约锚注释日期修正（992 行附近段） |
| `cloud-e2e/run-ai-chat-e2e.mjs` | Create | e2e 新脚本（D14：route mock SSE 五场景 + 会话本地态） |
| cloud-e2e 既有 7 脚本 | Modify | 快照同步（清单见契约 §6） |

**关键示意（router 流式核心，≤30 行）**：

```python
def _sse(payload: dict[str, str]) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"

@router.post("/stream")
async def chat_stream(dto: RagChatDto) -> StreamingResponse:
    async def event_gen():
        try:
            async for token in stream_chat_answer(dto):
                yield _sse({"content": token})
            yield "data: [DONE]\n\n"
        except BusinessException as e:
            yield _sse({"msg": e.msg})
            yield "data: [DONE]\n\n"
        except Exception:
            log.exception("SSE stream unexpected error")
            yield _sse({"msg": "系统异常，请稍后重试"})
            yield "data: [DONE]\n\n"
    return StreamingResponse(event_gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache"})
```

（`astream` 为 async generator——本机 langchain-core 1.6.9 源码实查 `async def astream(...) -> AsyncIterator`，直接 `async for`，无需 await。）

## 7. 数据流（流式逐帧时序）

1. 前端 `ssePost` fetch POST（Bearer）→ 网关验签 + 在线检查 + 注入 X-User-* → StripPrefix → AI `/rag/chat/stream`。
2. FastAPI 路由依赖 `require_perms`（X-User-Perms 含 `ai:chat:list` 放行；否则 R JSON 403 终止）。
3. pydantic 校验 RagChatDto（失败 → R JSON 1001 终止）。
4. service：截断 history（尾 20 条）→ to_lc_messages → `get_chat_model()`（缺 key 抛 5002）→ `astream`。
5. 逐 AIMessageChunk：`chunk.content` 非空 str → `data: {"content": ...}` 帧（HTTP 200 + chunked 已提交）。
6. 流尽 → `data: [DONE]`；任何异常 → msg 帧 + [DONE]（D12）。
7. 客户端 abort → starlette aclose 链传播 → astream 清理（D12）。

## 8. 错误处理（全路径汇总）

| 场景 | HTTP | 表达 | 前端表现（定死行为） |
|---|---|---|---|
| 无/坏 token | 401 | 网关 R JSON（真实状态码） | 清态跳登录 |
| AI 未起 | 503 | 网关 lb 无实例 | 「请求失败（HTTP 503）」 |
| perms 缺失 | 200 | `R{code:403}` | SSE 静默完成（空气泡）——D6 记档 |
| 入参非法 | 200 | `R{code:1001}` | 同上（前端 trim 已挡主路径） |
| key 未配置 | 200 | 首帧 `{"msg":"LLM 未配置…"}` + [DONE] | 首帧错误可见（（msg）气泡） |
| LLM 调用失败 | 200 | `{"msg":"LLM 调用失败…"}` + [DONE]（首 token 前可见；中途静默） | 前端 content 空才展示 |
| 正常 | 200 | content 帧…[DONE] | 增量渲染 |
| 服务端未知异常（非流式） | 200 | `R{code:500}` | request.ts 分流 |

服务端日志纪律：service 层 catch LLM 异常必须 `log.error`（含根因类型）再转 5001（规范第 3 条）；msg 文案不回显上游原文与 key。

## 9. 测试策略

**单测（pytest，全 mock 零真 key 零网络）**：六文件覆盖 §6——重点：① 流式帧序以 **TestClient 拉全 body 做全文精确断言**（`'data: {"content": "你"}\n\n...data: [DONE]\n\n'`，mock service 层 async generator，不碰 ChatOpenAI）；② settings 测试用 `_env_file=None` 隔离真 .env；③ lru_cache 工厂测试后 `cache_clear()`。

**真机冒烟链（后端验证，两段）**：
- **段一（backend-agent，无 key）**：起服 → 既有 /demo/ping//health 零回归 → 无 X-User-Perms 直连 `/rag/chat/stream` 断言 code 403 → 带 header 断言 5002 msg 帧（LLM 未配置——证明全链除 LLM 外就绪）→ 非流式同分支 → 停服收口。
- **段二（主控验收，注入真 key 后）**：起四件套（sso→system→gateway→ai）→ admin/admin123 token → 经网关 `/ai/rag/chat/stream` python 逐帧消费脚本（断言 ≥3 帧增量、帧间隔 > 0、[DONE] 收口）——**SSE 过网关透传实证（D15 必验）** → 本地直连 `:8010` 带 X-User-Perms 同断言 → 非流式 `/ai/rag/chat` 断言 `R.ok` answer 非空 + model=deepseek-flash → 停服收口四端口全空。

**e2e**：新脚本 run-ai-chat-e2e.mjs（五场景：菜单可达/markdown 渲染/停止生成/断流兜底/错误帧 + 会话本地态管理）+ 既有 7 脚本快照同步 → 全量回归 FAIL 计数门（`grep -c '[FAIL]'`，exit 0 假绿陷阱记档）。

**既有回归**：既有 19 pytest 全绿（config.py 改动波及 test_settings 既有用例——env_file 增量不破坏既有断言，实跑验证）；网关零改动无 Java 回归面；前端两次 build（vue-tsc + vite）。

## 10. 明确不做（范围外）

会话持久化/多端同步/标题后端生成；检索/向量/embedding/chunking 任何索引链代码；Nacos 配置中心消费（.env 优先，独立立项）；ChatDeepSeek provider 包迁移；token 用量统计与展示；system prompt 注入（无配置键，YAGNI——需要时加 config 键零结构变更）；免鉴权白名单对称化（R11 契约 §5 既定另行立项）；/inner 端点（AI 无服务间调用面）；sse.ts/页面任何改动（协议已定死，含 D6 衍生边界接受）；Java 服务代码改动（仅 SQL 种子文件与基线同步）；多 worker 验证（uvicorn 单进程起步）。

## 11. 移交事项

1. **SSE 网关透传实证结论**回填：若发现缓冲/截断行为，网关 yml 流式配置项立项（D15 fallback 路径）。
2. **Nacos 配置中心消费**（R11 移交 #9 剩余）：LLM 配置从 .env 迁 Nacos `cloud-base-ai.yaml`（拉取时机/失败降级/env 优先级设计）。
3. **ChatDeepSeek 迁移评估**：若后续接 reasoning 通道模型（reasoner 系列）或需 DeepSeek 特有参数。
4. **RAG 检索轮**：retrieval/embedding/vectorstore 启用（必增依赖，R12 移交 #1 剩余）；service 查询链插入检索步骤；system prompt/config 键届时一并议。
5. **abort 清理实证**：长生成中途停止后 DeepSeek 侧连接是否即时关闭（D12 观察项，若悬挂则 service 层加显式 aclose）。
6. **会话持久化**：前端本地态→后端会话表（表设计/多端同步届时立项——用户页面注释「持久化见迭代方向」即此）。
