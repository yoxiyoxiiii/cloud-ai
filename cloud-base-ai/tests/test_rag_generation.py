"""generation 消息转换断言（B7 / spec D2）：模型工厂已迁 models/model_factory
（其用例见 tests/test_model_factory.py），本文件保留生成阶段入参转换。"""

from langchain.messages import AIMessage, HumanMessage

from cloud_base_ai.rag.generation import to_lc_messages
from cloud_base_ai.rag.schemas import RagChatMessage


def test_to_lc_messages() -> None:
    history = [RagChatMessage(role="user", content="q"),
               RagChatMessage(role="assistant", content="a"),
               RagChatMessage(role="user", content="q2")]
    msgs = to_lc_messages(history)
    assert len(msgs) == 3
    assert isinstance(msgs[0], HumanMessage) and msgs[0].content == "q"
    assert isinstance(msgs[1], AIMessage) and msgs[1].content == "a"
    assert isinstance(msgs[2], HumanMessage) and msgs[2].content == "q2"
