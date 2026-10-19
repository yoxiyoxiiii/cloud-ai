"""会话消息的 Redis Cluster 存储：每会话一条 LIST（键前缀 rag:messages:，头插）。

键结构与「上限 100 条、无 TTL」的决策依据见
docs/superpowers/specs/2026-10-19-ai-chat-session-design.md §3.2：回显是产品功能不是
缓存，TTL 会让历史闲置 N 天后凭空消失（恰好违背「刷新不丢历史」目标）；生命周期终点
统一收敛到删除端点的双清。会话存在性与归属的每一次判定都以 MySQL 为准——本模块的键
只在归属校验通过后才被触碰。

命令面 LPUSH/LTRIM/LLEN/LRANGE/DEL 全单键：单节点集群零 CROSSSLOT 面，未来扩多主
节点天然兼容。同步调用口径：LPUSH/LTRIM 级内存操作为毫秒级，service 直接同步调用
（不套 run_in_threadpool）。
"""

from cloud_base_ai.rag.schemas import RagChatMessage
from cloud_base_ai.redis_client import get_redis_client

RAG_MESSAGE_KEY_PREFIX = "rag:messages:"

# 每会话保留条数上限：大于 LLM 段 history_limit（默认 20）留回显深度；头插后即时修剪，
# 防长会话无限增长
MAX_MESSAGES_PER_SESSION = 100


def _key(session_id: int) -> str:
    return f"{RAG_MESSAGE_KEY_PREFIX}{session_id}"


def append_message(session_id: int, role: str, content: str) -> None:
    """追加一条会话消息：LPUSH 头插 + LTRIM 修剪到上限，无 EXPIRE（见模块 docstring）。"""
    client = get_redis_client()
    key = _key(session_id)
    payload = RagChatMessage(role=role, content=content).model_dump_json()
    client.lpush(key, payload)
    client.ltrim(key, 0, MAX_MESSAGES_PER_SESSION - 1)


def list_messages(session_id: int, limit: int | None = None) -> list[RagChatMessage]:
    """读会话消息，时间升序（旧→新）返回；limit=最近 N 条（None=全部保留段）。"""
    client = get_redis_client()
    key = _key(session_id)
    total = client.llen(key)
    count = total if limit is None else min(limit, total)
    if count <= 0:
        # 空会话短路：lrange(0, -1) 语义是「全取」而非「空取」
        return []
    raw = client.lrange(key, 0, count - 1)  # 头插存储：索引 0 = 最新
    return [RagChatMessage.model_validate_json(item) for item in reversed(raw)]


def delete_messages(session_id: int) -> None:
    """删除会话消息键（会话删除双清的 Redis 段）；键不存在为 no-op，天然幂等。"""
    client = get_redis_client()
    client.delete(_key(session_id))
