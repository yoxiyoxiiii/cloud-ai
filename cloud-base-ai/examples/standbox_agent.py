from deepagents import create_deep_agent
from deepagents.backends import LocalShellBackend
from langchain_core.messages import SystemMessage, HumanMessage

from cloud_base_ai.models import model_factory

if __name__ == '__main__':
    agent = create_deep_agent(
        model=model_factory.get_chat_model(),
        backend=LocalShellBackend(root_dir="./",virtual_mode=True)
    )

    result = agent.invoke({
        "messages":[
            SystemMessage(content="你是一个本地智能体助手"),
            HumanMessage(content="创建一个txt 文件，并写入hello world ")
        ]
    })

    for msg in result["messages"]:
        print(msg)
