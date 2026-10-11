## Dependencies

- AI 栈：`langchain` / `deepagents` / `langchain-openai`（连带 `langchain-anthropic`、`langchain-google-genai` 为 deepagents 硬依赖）
- 基础设施：`nacos-sdk-python`（import 名 `v2.nacos`）/ SQLAlchemy 2.x（`sqlalchemy[asyncio]`，greenlet 经 extra）+ `alembic` + `aiomysql`(运行时) + `pymysql`(迁移) / `redis`
- 版本以 `uv.lock` 为冻结真相源；设计见 `docs/superpowers/specs/2026-10-11-cloud-base-ai-dependency-baseline-design.md`
