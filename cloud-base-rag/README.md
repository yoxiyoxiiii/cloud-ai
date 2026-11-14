# cloud-base-rag

RAG 服务（独立 uv 工程，:8011，Nacos 服务名 cloud-base-rag）：

- 知识库管理（ai_knowledge_base / files / chunk / grant，库 cloud_ai_rag）
- 会话与问答（`/rag/chat` 非流式 + `/rag/chat/stream` SSE，契约 2026-10-13-ai-rag-chat-api.md）
- 检索管线：ingest → chunk → embed(Ollama) → store(Milvus) → retrieve → generate(DeepSeek)
- MCP 服务端 `/mcp`：rag_search_kb 工具（双主体认证，契约 2026-11-13-rag-mcp-tools-api.md）

共享底座（db 事务 / 统一返回 / 身份核心 / Redis 集群 / Nacos 注册 / chat 工厂）以
uv path dependency 引自 `../cloud-ai-common`（editable）。

```bash
uv venv --python "C:/Users/jy-root/.conda/envs/cloud-ai/python.exe" && uv sync
uv run uvicorn cloud_base_rag.app:app --host 127.0.0.1 --port 8011
```

配置走本工程 `.env`（前缀 `CLOUD_BASE_AI_` 不变，模板见 `.env.example`）；测试
`uv run pytest`（在本工程目录执行）。
