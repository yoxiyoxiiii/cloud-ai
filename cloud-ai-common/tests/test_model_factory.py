"""chat 模型工厂断言（cloud_ai_common.model_factory）：全 mock 零真 key 零网络。

缺 key → BusinessException 5002（用例前后 cache_clear 防跨用例缓存污染；
lru_cache 异常不缓存）；ChatOpenAI 真构造（构造零网络）断言六参数传递——
use_responses_api=False 防误路由红线。embedding 工厂（Ollama）归 rag 工程，用例在
cloud-base-rag/tests/。
"""

import pytest

from cloud_ai_common import model_factory
from cloud_ai_common.config import LLMSettings
from cloud_ai_common.exceptions import BusinessException
from cloud_ai_common.model_factory import get_chat_model
from tests.conftest import SettingsStub


@pytest.fixture(autouse=True)
def clear_cache():
    get_chat_model.cache_clear()  # 前后清缓存，防单例跨用例污染
    yield
    get_chat_model.cache_clear()


def use_llm(llm: LLMSettings, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(model_factory, "get_settings",
                        lambda: SettingsStub(_env_file=None, llm=llm))


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


# ---------- temperature 扩展（平台 Agent 轮：每智能体温度） ----------


def test_temperature_none_keeps_class_default(monkeypatch: pytest.MonkeyPatch) -> None:
    use_llm(LLMSettings(api_key="test-key"), monkeypatch)
    m = get_chat_model()
    # None 不传参 → ChatOpenAI 类默认 None（请求省略该字段由服务端取默认），
    # 既有调用方（RAG 域）行为不变
    assert m.temperature is None


def test_temperature_value_cached_per_key(monkeypatch: pytest.MonkeyPatch) -> None:
    use_llm(LLMSettings(api_key="test-key"), monkeypatch)
    m_default = get_chat_model()
    m_half = get_chat_model(0.5)
    assert m_half is get_chat_model(0.5)  # 同温度键单例
    assert m_half is not m_default  # None 与 0.5 分开缓存（lru_cache 键扩展）
    assert m_half.temperature == 0.5
    assert m_default.temperature is None
