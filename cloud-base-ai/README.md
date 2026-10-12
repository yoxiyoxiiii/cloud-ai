## Dependencies

- AI 栈：`langchain` / `deepagents` / `langchain-openai`（连带 `langchain-anthropic`、`langchain-google-genai` 为 deepagents 硬依赖）
- 基础设施：`nacos-sdk-python`（import 名 `v2.nacos`）/ SQLAlchemy 2.x（`sqlalchemy[asyncio]`，greenlet 经 extra）+ `alembic` + `aiomysql`(运行时) + `pymysql`(迁移) / `redis`
- 版本以 `uv.lock` 为冻结真相源；设计见 `docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`

## Nacos Registration

- 服务名 `cloud-base-ai`（临时实例，DEFAULT_GROUP@public），注册/心跳/断线自愈由 nacos-sdk-python gRPC 承载；停机先 deregister 再关连接（lifespan）。
- 配置 env 覆盖（pydantic-settings，前缀 `CLOUD_BASE_AI_`，嵌套分隔 `__`）：`NACOS__SERVER_ADDR` / `NACOS__NAMESPACE_ID` / `NACOS__GROUP_NAME` / `NACOS__SERVICE_NAME` / `NACOS__IP` / `APP_PORT`（外层非嵌套字段无分隔参与）。
- 网关路由：`/ai/**` → `lb://cloud-base-ai`（StripPrefix=1，JWT 认证链路继承）；本地冒烟 `curl :8010/demo/ping`；设计见 docs/superpowers/specs/2026-10-12-cloud-base-ai-nacos-registration-design.md。
