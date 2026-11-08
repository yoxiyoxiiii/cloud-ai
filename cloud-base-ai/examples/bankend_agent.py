from deepagents import create_deep_agent
from deepagents.backends import FilesystemBackend
from langchain_core.messages import HumanMessage

from cloud_base_ai.models import model_factory

if __name__ == '__main__':
    agent = create_deep_agent(
        model=model_factory.get_chat_model(),
        # 本地文件操作
        backend=FilesystemBackend(root_dir="./", virtual_mode=True),
        system_prompt="""
        角色：你是一个专业的文件助手
        """
    )

    result = agent.invoke({
        "messages":[
            HumanMessage(content="当前目前有哪些文件？其中，AGENTS.md 有哪些核心内容？")
        ]
    })

    for msg in result["messages"]:
        print("type:{}, 内容：{}",type(msg), msg)

    # print(result["messages"][-1].content)