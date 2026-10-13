## Dependencies

- AI 栈：`langchain` / `deepagents` / `langchain-openai`（连带 `langchain-anthropic`、`langchain-google-genai` 为 deepagents 硬依赖）
- 基础设施：`nacos-sdk-python`（import 名 `v2.nacos`）/ SQLAlchemy 2.x（`sqlalchemy[asyncio]`，greenlet 经 extra）+ `alembic` + `aiomysql`(运行时) + `pymysql`(迁移) / `redis`
- 版本以 `uv.lock` 为冻结真相源；设计见 `docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`

## Nacos Registration

- 服务名 `cloud-base-ai`（临时实例，DEFAULT_GROUP@public），注册/心跳/断线自愈由 nacos-sdk-python gRPC 承载；停机先 deregister 再关连接（lifespan）。
- 配置 env 覆盖（pydantic-settings，前缀 `CLOUD_BASE_AI_`，嵌套分隔 `__`）：`NACOS__SERVER_ADDR` / `NACOS__NAMESPACE_ID` / `NACOS__GROUP_NAME` / `NACOS__SERVICE_NAME` / `NACOS__IP` / `APP_PORT`（外层非嵌套字段无分隔参与）。
- 网关路由：`/ai/**` → `lb://cloud-base-ai`（StripPrefix=1，JWT 认证链路继承）；本地冒烟 `curl :8010/demo/ping`；设计见 docs/superpowers/specs/2026-10-12-cloud-base-ai-nacos-registration-design.md。

## RAG Package Skeleton

- 业务域子包 `src/cloud_base_ai/rag/`（2026-10-12 骨架占位，零实现代码）：按 RAG 数据流阶段平铺——ingestion → chunking → embedding → vectorstore → retrieval → generation，横切件 router / service / schemas。
- 深度硬顶 2 层（rag/ 下无子包）；供应商预留为模块级单文件（embedding / vectorstore），config 零供应商键；分包细则见设计文档 §5。
- 依赖注意：langchain-text-splitters / langchain-community / vectorstore 实现包当前未装，RAG 功能实现轮再新增——目录骨架非可用链路。
- 设计见 docs/superpowers/specs/2026-10-12-cloud-base-ai-rag-skeleton-design.md。

## AI Chat

RAG 域首个功能（2026-10-13，纯 LLM 流式对话，零检索）：设计见 `docs/superpowers/specs/2026-10-13-ai-rag-chat-design.md`，契约见 `docs/superpowers/contracts/2026-10-13-ai-rag-chat-api.md`。

- 端点（经网关 :18080，AI 侧 StripPrefix 后）：`POST /ai/rag/chat`（非流式，`R<RagChatAnswerVo>`）、`POST /ai/rag/chat/stream`（流式 SSE：token 帧 `data: {"content":...}` + 终帧 `data: [DONE]`；业务错误 = `data: {"msg":...}` 帧 + `[DONE]`，绝不裸断流）。
- 配置：复制 `.env.example` 为 `.env` 后填写（`.env` 已被 .gitignore 忽略；key 真值不提交不入档）。优先级：真实环境变量 > `.env` > 代码默认值。键名（嵌套分隔符为双下划线 `__`）：`CLOUD_BASE_AI_LLM__API_KEY`（DeepSeek API key，空 = 端点报 5002）/ `CLOUD_BASE_AI_LLM__BASE_URL`（默认 `https://api.deepseek.com`）/ `CLOUD_BASE_AI_LLM__MODEL`（默认 `deepseek-flash`）/ `CLOUD_BASE_AI_LLM__TIMEOUT`（默认 60 秒）/ `CLOUD_BASE_AI_LLM__MAX_RETRIES`（默认 1）/ `CLOUD_BASE_AI_LLM__HISTORY_LIMIT`（默认 20 条，服务层历史截断）。
- 鉴权：权限标识 `ai:chat:list`（两端点共用）——AI 侧读网关注入的 `X-User-Perms` 校验。**本地直连调试（绕网关 :8010）须手工带 header `X-User-Perms: ai:chat:list`**，否则 HTTP 200 + `R{code:403}`。
- 错误码：403 无操作权限 / 1001 参数校验 / 5001 LLM 调用失败 / 5002 LLM 未配置（AI 段 5xxx，后续从 5003 接续）。
