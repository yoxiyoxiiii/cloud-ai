"""RAG 域鉴权豁免：知识库管理豁免（MGMT_PERMS）。

身份核心（五头解析 / Identity / require_perms）在共享库 cloud_ai_common.security；
本模块只挂 rag 唯一消费的豁免口径，Agent 服务工程有自己的豁免模块（工程级路径
禁令禁止本工程出现对方包名）。
"""

from cloud_ai_common.security import Identity

# 知识库管理权限集合：持任一者即管理豁免（列表全量可见、文件免实例校验）。
# 豁免只影响可见性；管理动作本身仍由各端点 require_perms 独立裁决（双闸门）。
MGMT_PERMS: frozenset[str] = frozenset({"ai:kb:add", "ai:kb:update", "ai:kb:delete"})


def is_mgmt_exemption(identity: Identity) -> bool:
    """管理豁免判定：perms 含任一知识库管理权限（MGMT_PERMS）即全量可见。"""
    return any(perm in MGMT_PERMS for perm in identity.perms)
