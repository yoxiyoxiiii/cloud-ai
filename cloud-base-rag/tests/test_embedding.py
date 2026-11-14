"""embedding 工厂断言（cloud_base_rag.embedding，三工程拆分后从原共享
test_model_factory.py 随功能迁入；chat 工厂用例在 cloud-ai-common/tests/）。

OllamaEmbeddings 构造零网络，真构造断言 base_url/model 传递与单例语义；
monkeypatch 工厂模块的 get_settings 指向空 env 的 Settings。
"""

import pytest
from langchain_ollama import OllamaEmbeddings

from cloud_base_rag import embedding
from cloud_base_rag.config import Settings
from cloud_base_rag.embedding import get_embedding_model


@pytest.fixture(autouse=True)
def clear_cache():
    get_embedding_model.cache_clear()
    yield
    get_embedding_model.cache_clear()


def _use_embedding(cfg, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(embedding, "get_settings",
                        lambda: Settings(_env_file=None, embedding_llm=cfg))


def test_embedding_construct_passes_params(monkeypatch: pytest.MonkeyPatch) -> None:
    from cloud_base_rag.config import EmbeddingModelSettings
    _use_embedding(EmbeddingModelSettings(base_url="http://127.0.0.1:11434",
                                          model="qllama/bge-large-zh-v1.5:latest"), monkeypatch)
    e = get_embedding_model()  # OllamaEmbeddings 构造零网络调用
    assert isinstance(e, OllamaEmbeddings)
    assert e.base_url == "http://127.0.0.1:11434"
    assert e.model == "qllama/bge-large-zh-v1.5:latest"


def test_embedding_factory_cached(monkeypatch: pytest.MonkeyPatch) -> None:
    from cloud_base_rag.config import EmbeddingModelSettings
    _use_embedding(EmbeddingModelSettings(), monkeypatch)  # 默认值即可（本地端点零鉴权）
    assert get_embedding_model() is get_embedding_model()
