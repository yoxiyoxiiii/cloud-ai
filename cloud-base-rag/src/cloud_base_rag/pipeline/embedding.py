"""向量化阶段（embed）：OllamaEmbeddings 单例工厂（rag 检索管线专属）。

chat 模型工厂（ChatOpenAI/DeepSeek）是两侧共享面，在 cloud_ai_common.model_factory；
embedding 只服务 RAG 检索管线（chunk 向量化），随 rag 工程走。
"""

from functools import lru_cache

from langchain_ollama import OllamaEmbeddings

from cloud_base_rag.config import EmbeddingModelSettings, get_settings


@lru_cache
def get_embedding_model() -> OllamaEmbeddings:
    """Embedding 进程内单例工厂：读 Settings.embedding_llm 构造 OllamaEmbeddings。

    本地 Ollama 端点（默认 http://127.0.0.1:11434）零鉴权，无未配置态；
    类名是 OllamaEmbeddings（复数，langchain-ollama 1.1.0 实查）。
    """
    cfg: EmbeddingModelSettings = get_settings().embedding_llm
    return OllamaEmbeddings(base_url=cfg.base_url, model=cfg.model)
