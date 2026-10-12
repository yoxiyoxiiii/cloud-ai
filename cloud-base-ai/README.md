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
