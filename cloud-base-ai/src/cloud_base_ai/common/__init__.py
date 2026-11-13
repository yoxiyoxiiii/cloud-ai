"""跨域共享层：rag / agent 双服务都用的模型与工具放这里。

双服务拆分后域包（rag / agent / mcp_server / mcp）互禁 import，
公共定义唯一去处是本包（守护测试强制，common 不得反向依赖任何域包）。
"""
