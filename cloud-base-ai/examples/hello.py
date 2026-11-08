import asyncio

from deepagents import create_deep_agent, SubAgent
from langchain_core.messages import HumanMessage, AIMessageChunk

from cloud_base_ai.models import model_factory


def get_user_info() -> dict:
    """
    使用该工具可以获取用户信息
    该接口可以提供：username和age 等用户基本信息
    :return: 用户基本信息
    """
    return {"user_name": "张三", "age": 30}


user_subagent = {
    "name": "user_subagent",
    "description": "用户信息subagent",
    "system_prompt": """
        角色：你是一个专业的用户信息获取助手，可以使用以下工具
        职责：
            1. 获取用户信息相关信息
        工具：
            1. get_user_info：获取用户基本信息
        禁止：
            1. 不能自己构造用户数据，所有的用户数据都必须通过工具获取，如果查询不到对应的用户信息，直接回答“暂无该用户信息”
            2. 如果用户查询的是非用户相关的问题，直接回答“只能提供用户信息相关问题，其他问题暂时无法回答”
    
    """,
    "tools": [get_user_info],
    "model": model_factory.get_chat_model()
}

# user_subagent = SubAgent(
#     name="",
#     description="",
#     system_prompt="",
#     tools=[],
#     model=model_factory.get_chat_model()
# )

async def main():
    agent = create_deep_agent(
        model=model_factory.get_chat_model(),
        # tools=[get_user_info],  # 给agent 配置工具
        system_prompt="""
        角色：你是一个专业的智能助手
        职责：可以获取用户的基本信息。如果用户没有咨询用户相关问题，你可以根据自己的知识进行作答
        工具：使用subagent：user_subagent 进行用户信息的获取
        禁止：
            1. 如果用户咨询的是用户信息相关问题，你只能通过user_subagent 的回答来进行整合结果，不能自己杜撰
        """,
        subagents=[user_subagent]
    )

    async for chunk, metadata in agent.astream(input={
        "messages": [
            HumanMessage(content="张三多少岁？")
        ]
    },
        stream_mode="messages" # token by token 输出
    ):

        # 返回AIMessageChunk 和 ToolMessage 两种对象类型
        # print(type(chunk))
        if (isinstance(chunk, AIMessageChunk)
                and isinstance(chunk.content ,str)
                and chunk.content
        ):
            print(chunk.content)



        # print(chunk.content)



if __name__ == '__main__':
    # agent = create_deep_agent(
    #     model=model_factory.get_chat_model(),
    #     tools=[get_user_info],  # 给agent 配置工具
    #     system_prompt="""
    #     角色：你是一个专业的用户信息获取助手，可以使用以下工具
    #     职责：
    #         1. 获取用户信息相关信息
    #     工具：
    #         1. get_user_info：获取用户基本信息
    #     禁止：
    #         1. 不能自己构造用户数据，所有的用户数据都必须通过工具获取，如果查询不到对应的用户信息，直接回答“暂无该用户信息”
    #         2. 如果用户查询的是非用户相关的问题，直接回答“只能提供用户信息相关问题，其他问题暂时无法回答”
    #     """
    # )

    # result =  agent.invoke({"messages":[
    #     HumanMessage(content="张三多少岁？")
    # ]})
    # print(result["messages"][-1].content)

    asyncio.run(main=main())
