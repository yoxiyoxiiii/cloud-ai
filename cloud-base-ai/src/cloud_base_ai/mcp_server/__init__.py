"""MCP Server 配置化管理域（契约 2026-10-30-mcp-server-mgmt-api.md）。

ai_mcp_server 注册表承载外部/内置 server 连接配置（凭据真值在 .env 不入库）；
工具同步（tools/list → ai_tool 草稿）与审核发布见 sync_service，管理面 CRUD 见 manage_service。
"""
