"""generation 工厂与消息转换断言（B7 / spec D2）：全 mock 零真 key 零网络。

- 缺 key → BusinessException 5002（用例前后 cache_clear 防跨用例缓存污染；
  lru_cache 异常不缓存——补断言成功值缓存语义）；
- ChatOpenAI 真构造（构造零网络）断言六参数传递——use_responses_api=False 防误路由红线。
"""

import pytest
from langchain.messages import AIMessage, HumanMessage

from cloud_base_ai.config import LLMSettings, Settings
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import generation
from cloud_base_ai.rag.generation import get_chat_model, to_lc_messages
from cloud_base_ai.rag.schemas import RagChatMessage


@pytest.fixture(autouse=True)
def clear_cache():
    get_chat_model.cache_clear()  # 前后清缓存，防单例跨用例污染
    yield
    get_chat_model.cache_clear()


def use_llm(llm: LLMSettings, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(generation, "get_settings", lambda: Settings(_env_file=None, llm=llm))


def test_missing_key_raises_5002(monkeypatch: pytest.MonkeyPatch) -> None:
    use_llm(LLMSettings(api_key=""), monkeypatch)  # 默认即空，显式表达意图
    with pytest.raises(BusinessException) as ei:
        get_chat_model()
    assert ei.value.code == 5002
    assert "CLOUD_BASE_AI_LLM__API_KEY" in ei.value.msg  # msg 提示配置键名（契约 §5）


def test_construct_passes_params(monkeypatch: pytest.MonkeyPatch) -> None:
    use_llm(LLMSettings(api_key="test-key", base_url="https://api.deepseek.com",
                        model="deepseek-flash", timeout=60.0, max_retries=1), monkeypatch)
    m = get_chat_model()  # ChatOpenAI 构造零网络调用，真构造断言
    assert m.model_name == "deepseek-flash"
    assert m.openai_api_base == "https://api.deepseek.com"
    assert m.request_timeout == 60.0
    assert m.max_retries == 1
    assert m.use_responses_api is False  # 防 Responses API 误路由（DeepSeek 不支持），勿删断言


def test_factory_cached(monkeypatch: pytest.MonkeyPatch) -> None:
    use_llm(LLMSettings(api_key="test-key"), monkeypatch)
    assert get_chat_model() is get_chat_model()  # 进程内单例（连接池复用）


def test_to_lc_messages() -> None:
    history = [RagChatMessage(role="user", content="q"),
               RagChatMessage(role="assistant", content="a"),
               RagChatMessage(role="user", content="q2")]
    msgs = to_lc_messages(history)
    assert len(msgs) == 3
    assert isinstance(msgs[0], HumanMessage) and msgs[0].content == "q"
    assert isinstance(msgs[1], AIMessage) and msgs[1].content == "a"
    assert isinstance(msgs[2], HumanMessage) and msgs[2].content == "q2"
