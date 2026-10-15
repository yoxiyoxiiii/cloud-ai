"""向量存储阶段（store）：langchain_core VectorStore 实现封装位：milvus。"""
from functools import lru_cache
from langchain_milvus import Milvus

from cloud_base_ai.models import model_factory


@lru_cache
def get_milvus() -> Milvus:
    """
    单例
    :return:
    """
    vector_store = Milvus(
        embedding_function=model_factory.get_embedding_model(),
        connection_args={"uri": "http://localhost:19530"},
    )

    return vector_store
