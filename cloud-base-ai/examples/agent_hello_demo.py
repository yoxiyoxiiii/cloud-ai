"""deepagents 入门 demo（学习用，不入产品路由；原 src/cloud_base_ai/agents/hello_agent.py 迁出）。

运行方式（仓库根）：uv run --project cloud-base-ai python examples/agent_hello_demo.py
前置：.env 配置 CLOUD_BASE_AI_LLM__API_KEY（真值不入日志）。
"""

import sys

from deepagents import create_deep_agent
from deepagents.backends import FilesystemBackend
from langchain_core.messages import HumanMessage, SystemMessage

from cloud_base_ai.models import model_factory

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def hello_agent():
    agent = create_deep_agent(
        model=model_factory.get_chat_model()
    )

    response = agent.invoke(input={"messages": [
        SystemMessage(content="你是一个专业的智能助手"),
        HumanMessage(content="你可以做什么？"),
    ]})

    print(response["messages"][-1].content)


def get_user_info():
    """返回固定用户信息（demo 工具样例）。"""
    return {"user_id": 1, "user_name": "张三", "age": 40}


def agent_and_tools():
    agent = create_deep_agent(
        model=model_factory.get_chat_model(),
        tools=[get_user_info],  # 设置 agent 的工具
        system_prompt="""
        你是一个专业的智能助手，可以查询用户的基本信息。
        工具：get_user_info可以获取用户的基本信息
        红线：
            1.禁止私自构造用户信息，用户信息必须通过get_user_info工具来获取
            2.如果获取到用户信息，直接回答：用户不存在
        """
    )

    response = agent.invoke(input={"messages": [
        HumanMessage(content="张三是哪里的人？")
    ]})

    print(response["messages"][-1].content)


def agent_and_filesystem_backend():
    agent = create_deep_agent(
        model=model_factory.get_chat_model(),
        # 访问本地文件系统
        backend=FilesystemBackend(root_dir="./", virtual_mode=True),
        system_prompt="你是一个智能工具助手，可以访问系统当前文件信息。记住我是李四，我18岁",
        # 本地文件记忆，全局跨线程（learning memory 随 demo 迁至 examples/）
        memory=["cloud-base-ai/examples/AGENTS.md"]
    )

    response = agent.invoke(input={"messages": [
        HumanMessage(content="当前用户是谁？")
    ]})

    print(response["messages"][-1].content)


if __name__ == '__main__':
    # hello_agent()
    # agent_and_tools()
    agent_and_filesystem_backend()
