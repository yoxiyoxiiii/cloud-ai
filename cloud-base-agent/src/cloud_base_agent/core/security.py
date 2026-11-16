"""Agent 域鉴权豁免：MCP server 授权豁免（MCP_SERVER_EXEMPT_PERMS）。

身份核心（五头解析 / Identity / require_perms）在共享库 cloud_ai_common.security；
本模块只挂 agent 唯一消费的豁免口径，rag 侧知识库豁免在 rag 工程侧。
"""

from cloud_ai_common.security import Identity

# MCP server 管理豁免权限集合：持 ai:mcp:list（管理面最小公共权限，管理角色必持）
# 即豁免 server 授权门——能管即能用，全量 server 免授权。豁免只免「server 授权门」
# 这一层；工具级 required_perms 门对所有人（含豁免者）照常生效（豁免不外溢改写既有门）。
MCP_SERVER_EXEMPT_PERMS: frozenset[str] = frozenset({"ai:mcp:list"})


def is_mcp_server_exempt(identity: Identity) -> bool:
    """MCP server 授权豁免判定：perms 含 ai:mcp:list 即全量 server 免授权可用
    （运行面装配门短路，不查授权表）。"""
    return any(perm in MCP_SERVER_EXEMPT_PERMS for perm in identity.perms)
