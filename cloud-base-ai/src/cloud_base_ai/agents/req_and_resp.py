from pydantic import BaseModel, Field


class AgentChatReq(BaseModel):
    userMsg: str = Field(description="用户问题")