"""检索阶段（retrieve）：MCP 工具 rag_search_kb 的检索编排。

职责边界：kb 存在/可见性前置校验（防越权面，判定链与 service.page_list_kb_files
同源同判）→ Milvus 向量检索 → 契约出参 dict（2026-11-13-rag-mcp-tools-api.md §3.3）。
工具定义与 FastMCP 实例在 mcp_tools.py；本模块不 import agent/mcp_server/mcp 域。

事务口径：MySQL 点查走短事务辅助函数（@transactional 只包校验段，Milvus 检索
不占数据库事务）；检索全程只读。
"""

import logging

from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import dao, vectorstore
from cloud_base_ai.rag.entities import AiKnowledgeBase
# 错误码常量与 service 层同源（文本即契约 §3.4，两处共用防漂移；本模块不得被
# service 反向 import——chat 接检索轮若需引用，先把这些常量下沉到公共位）
from cloud_base_ai.rag.service import (
    CODE_KB_NOT_EXISTS,
    CODE_KB_NO_ACCESS,
    MSG_KB_NOT_EXISTS,
    MSG_KB_NO_ACCESS,
)
from cloud_base_ai.security import Identity, is_mgmt_exemption

logger = logging.getLogger(__name__)

CODE_KB_SEARCH_FAILED: int = 5030
MSG_KB_SEARCH_FAILED: str = "知识库检索失败"

MIN_TOP_K: int = 1
MAX_TOP_K: int = 20
DEFAULT_TOP_K: int = 5


@transactional
async def _load_accessible_kb(identity: Identity, kb_id: int) -> AiKnowledgeBase:
    """kb 存在校验 + 可见性裁决（短事务收口 MySQL 段）。

    判定链与 kb 文件列表端点同源同判：管理豁免（perms 含任一 ai:kb:add/update/delete）
    → 本人建库（create_by=账号）→ 授权命中（角色 ∪ 部门祖先链 ∪ 直授用户），
    任一不过抛业务异常（文本即契约 §3.4）。与 service 侧的差异：identity 须非
    None——HTTP 面的匿名豁免语义归 service，本函数调用方（MCP 工具层）已保证
    身份注入，None 属调用缺陷直接抛错。
    """
    session = current_session()
    kb = await dao.find_kb_by_id(session, kb_id)
    if kb is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    if is_mgmt_exemption(identity):
        return kb
    # account 空值归一 ""：防 None 比较被渲染成 IS NULL 误中 NULL 创建行（同 service 口径）
    if kb.create_by == (identity.account or ""):
        return kb
    visible_ids = await dao.list_visible_kb_ids(
        session, identity.user_id, identity.role_ids, identity.dept_ids)
    if kb_id not in visible_ids:
        raise BusinessException(MSG_KB_NO_ACCESS, CODE_KB_NO_ACCESS)
    return kb


async def search_kb_chunks(
    identity: Identity, kb_id: int, query: str, top_k: int = DEFAULT_TOP_K
) -> dict:
    """检索编排入口：校验 → Milvus 检索 → 契约出参。

    top_k 越界钳制到 1..20（协议层 schema 已拦一道，此处兜底防直调越界）；
    Milvus/embedding 异常 log.error 记根因后转 5030（msg 不含连接串真值）；
    空命中 = chunks 空列表合法结果。
    """
    kb = await _load_accessible_kb(identity, kb_id)
    k = max(MIN_TOP_K, min(top_k, MAX_TOP_K))
    try:
        store = vectorstore.get_milvus(kb.collection_name)
        hits = await store.asimilarity_search_with_score(query, k=k)
    except Exception as e:
        logger.error("search_kb_chunks failed, kb_id=%s: %s: %s",
                     kb_id, type(e).__name__, e)
        raise BusinessException(MSG_KB_SEARCH_FAILED, CODE_KB_SEARCH_FAILED) from e
    chunks = [
        {
            # chunk_id = 向量 metadata id（kb_file_chunk 子块行 id 字符串化，上传链写入）
            "chunk_id": str(doc.metadata.get("id", "")),
            "content": doc.page_content,
            # langchain-milvus 默认 L2 索引返回距离（越小越好），取负归一为越大越相关
            "score": -float(score),
        }
        for doc, score in hits
    ]
    return {"kb_id": kb_id, "chunks": chunks}
