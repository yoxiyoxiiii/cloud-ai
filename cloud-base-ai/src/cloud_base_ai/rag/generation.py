"""生成阶段（generate）：LLM 模型工厂与消息转换（AI 对话轮 2026-10-13，spec D2）。

langchain-openai ChatOpenAI 直连 DeepSeek（OpenAI 兼容端点）：
- ChatOpenAI 构造零网络调用（openai client 纯本地对象），单测可真构造；
- use_responses_api=False 必须显式传——模型名可触发 Responses API 误路由（独立于
  base_url 推断），DeepSeek 仅支持 chat completions，被误路由到 /responses 即失败；
- 模型对象进程内单例（lru_cache 工厂，避免每请求重建 client 连接池；异常不缓存）；
- ChatDeepSeek provider 包迁移显式 OUT（deepseek-flash 走标准 content 通道，
  未来需 reasoning 通道再立项——spec D2 记档）。
"""

from collections.abc import Sequence
from functools import lru_cache

from langchain.messages import AIMessage, HumanMessage
from langchain_core.messages import BaseMessage
from langchain_openai import ChatOpenAI

from cloud_base_ai.config import LLMSettings, get_settings
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag.schemas import RagChatMessage

CODE_LLM_NOT_CONFIGURED: int = 5002


@lru_cache
def get_chat_model() -> ChatOpenAI:
    """进程内单例工厂：读 Settings.llm 构造 ChatOpenAI（lru_cache——成功值缓存、异常不缓存）。

    api_key 为空 = 未配置态：抛 5002（对齐「缺配置是可观测的运行时错误，不是启动错误」，
    同 Nacos 注册降级哲学 D7）。测试后须 cache_clear() 防跨用例污染。
    """
    cfg: LLMSettings = get_settings().llm
    if not cfg.api_key:
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", CODE_LLM_NOT_CONFIGURED)
    return ChatOpenAI(
        model=cfg.model,
        api_key=cfg.api_key,
        base_url=cfg.base_url,
        timeout=cfg.timeout,
        max_retries=cfg.max_retries,
        use_responses_api=False,  # 防 Responses API 误路由（spec D2 实查坑），勿删
    )


def to_lc_messages(history: Sequence[RagChatMessage]) -> list[BaseMessage]:
    """RagChatMessage 列表转 langchain 消息列表：user→HumanMessage、assistant→AIMessage。"""
    mappings: dict[str, type[BaseMessage]] = {"user": HumanMessage, "assistant": AIMessage}
    return [mappings[m.role](content=m.content) for m in history]
