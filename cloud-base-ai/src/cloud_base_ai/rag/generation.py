"""生成阶段（generate）：消息转换（AI 对话轮 2026-10-13，spec D2）。

模型对象构造已迁 `cloud_base_ai/models/model_factory.py`（2026-10-13 工厂统一收口），
本模块保留生成阶段的入参转换；后续生成编排逻辑在此扩展。
"""

from collections.abc import Sequence

from langchain.messages import AIMessage, HumanMessage
from langchain_core.messages import BaseMessage

from cloud_base_ai.rag.schemas import RagChatMessage


def to_lc_messages(history: Sequence[RagChatMessage]) -> list[BaseMessage]:
    """RagChatMessage 列表转 langchain 消息列表：user→HumanMessage、assistant→AIMessage。"""
    mappings: dict[str, type[BaseMessage]] = {"user": HumanMessage, "assistant": AIMessage}
    return [mappings[m.role](content=m.content) for m in history]
