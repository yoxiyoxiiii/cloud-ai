"""生成阶段（generate）：消息转换。

模型对象构造统一走共享库 chat 工厂（cloud_ai_common.model_factory），
本模块保留生成阶段的入参转换；后续生成编排逻辑在此扩展。
"""

from collections.abc import Sequence

from langchain.messages import AIMessage, HumanMessage
from langchain_core.messages import BaseMessage

from cloud_base_rag.schemas import RagChatMessage


def to_lc_messages(history: Sequence[RagChatMessage]) -> list[BaseMessage]:
    """RagChatMessage 列表转 langchain 消息列表：user→HumanMessage、assistant→AIMessage。"""
    mappings: dict[str, type[BaseMessage]] = {"user": HumanMessage, "assistant": AIMessage}
    return [mappings[m.role](content=m.content) for m in history]
