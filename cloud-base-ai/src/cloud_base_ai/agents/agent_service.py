"""
agent service
"""
from deepagents import create_deep_agent
from langchain_core.messages import HumanMessage, SystemMessage

from cloud_base_ai.models import model_factory


async def chat_stream(user_msg: str):
    """
    agent 基础对话
    流式返回
    :return:
    """
    agent = create_deep_agent(
        model=model_factory.get_chat_model()
    )

    async for chunk, _metadata in agent.astream(input={"messages": [
        SystemMessage(content="你是一个AI 智能助手"),
        HumanMessage(content=user_msg)
    ]},
            stream_mode="messages" # 按照token 返回
    ):
        # 类似反射获取对象属性值
        content = getattr(chunk, "content", "")
        if isinstance(content, str) and content :
            yield content



