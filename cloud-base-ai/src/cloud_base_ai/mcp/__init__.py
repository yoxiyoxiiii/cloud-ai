"""MCP 客户端通道包：对 cloud-system MCP server 的连接封装与工具可见性过滤。

通道原语归 client.py，工具权限映射归 perm.py（契约 §5 唯一实现处）；
对话编排接入（LLM 工具调用）不在本包——见轮次移交备忘。
"""
