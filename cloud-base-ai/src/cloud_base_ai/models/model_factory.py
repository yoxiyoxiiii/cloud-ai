"""模型工厂（cloud_base_ai.models）：langchain 模型对象的进程内单例构造。

与 rag/ 管线阶段解耦——阶段模块只管编排，模型获取统一走本工厂
（lru_cache 单例，成功值缓存/异常不缓存；测试后 cache_clear() 防污染）。
"""

from functools import lru_cache

from langchain_openai import ChatOpenAI
from langchain_ollama import OllamaEmbeddings

from cloud_base_ai.config import EmbeddingModelSettings, LLMSettings, get_settings
from cloud_base_ai.exceptions import BusinessException

CODE_LLM_NOT_CONFIGURED: int = 5002


@lru_cache
def get_chat_model(temperature: float | None = None) -> ChatOpenAI:
    """Chat 模型进程内单例工厂：读 Settings.llm 构造 ChatOpenAI（langchain-openai 直连
    DeepSeek，OpenAI 兼容端点；ChatOpenAI 构造零网络调用，单测可真构造）。

    - temperature=None（默认）不传该参数——保持既有调用方的 ChatOpenAI 类默认行为不变；
      非 None 时按值传入（平台 Agent 每智能体温度，lru_cache 键随之扩展为
      （temperature,）——不同温度各缓存一个实例，None 与显式传值分开缓存）；
    - use_responses_api=False 必须显式传——模型名可触发 Responses API 误路由（独立于
      base_url 推断），DeepSeek 仅支持 chat completions，被误路由到 /responses 即失败；
    - api_key 为空 = 未配置态：抛 5002（对齐「缺配置是可观测的运行时错误，不是启动错误」，
      同 Nacos 注册降级哲学 D7）；
    - ChatDeepSeek provider 包迁移显式 OUT（deepseek-flash 走标准 content 通道）。
    """
    cfg: LLMSettings = get_settings().llm
    if not cfg.api_key:
        raise BusinessException("LLM 未配置（CLOUD_BASE_AI_LLM__API_KEY）", CODE_LLM_NOT_CONFIGURED)
    extra: dict = {}
    if temperature is not None:
        extra["temperature"] = temperature
    return ChatOpenAI(
        model=cfg.model,
        api_key=cfg.api_key,
        base_url=cfg.base_url,
        timeout=cfg.timeout,
        max_retries=cfg.max_retries,
        use_responses_api=False,  # 防 Responses API 误路由（spec D2 实查坑），勿删
        **extra,
    )


@lru_cache
def get_embedding_model() -> OllamaEmbeddings:
    """Embedding 进程内单例工厂：读 Settings.embedding_llm 构造 OllamaEmbeddings。

    本地 Ollama 端点（默认 http://127.0.0.1:11434）零鉴权，无未配置态；
    类名是 OllamaEmbeddings（复数，langchain-ollama 1.1.0 实查）。
    """
    cfg: EmbeddingModelSettings = get_settings().embedding_llm
    return OllamaEmbeddings(base_url=cfg.base_url, model=cfg.model)
