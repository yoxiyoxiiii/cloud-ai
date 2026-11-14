# cloud-base-agent

Agent 服务（独立 uv 工程，:8012，Nacos 服务名 cloud-base-agent）：

- 平台智能体管理与会话运行（ai_agent / ai_agent_session / ai_agent_run_log，库 cloud_ai_agent）
- MCP server 注册表与工具同步/审核（ai_mcp_server / ai_tool / ai_mcp_server_grant）
- MCP 客户端通道（经注册表驱动的外部 server 调用，凭据走 .env 引用键）

共享底座（db 事务 / 统一返回 / 身份核心 / Redis 集群 / Nacos 注册 / chat 工厂）以
uv path dependency 引自 `../cloud-ai-common`（editable）。

```bash
uv venv --python "C:/Users/jy-root/.conda/envs/cloud-ai/python.exe" && uv sync
uv run uvicorn cloud_base_agent.app:app --host 127.0.0.1 --port 8012
```

配置走本工程 `.env`（前缀 `CLOUD_BASE_AI_` 不变，模板见 `.env.example`）；测试
`uv run pytest`（在本工程目录执行）。
