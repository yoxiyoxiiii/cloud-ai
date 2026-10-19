"""向量存储阶段（store）：langchain_core VectorStore 实现封装位：milvus。"""
from functools import lru_cache

from langchain_milvus import Milvus
from pymilvus import MilvusClient

from cloud_base_ai.config import get_settings
from cloud_base_ai.models import model_factory


@lru_cache
def get_milvus(collection_name: str) -> Milvus:
    """
    向量库单例工厂，按 collection 名各缓存一份。
    collection_name 必须在构造期传入：langchain-milvus 只在构造时对目标集合装载
    字段表（fields），构造后再对 collection_name 属性赋值不会触发重载——届时对
    既有集合插入会按空字段表剥掉全部 metadata 而报 DataNotMatchException
    （上传链实测踩坑，勿改回属性赋值形态）。
    :param collection_name: 目标 collection 名
    :return: Milvus 向量库实例
    """
    vector_store = Milvus(
        embedding_function=model_factory.get_embedding_model(),
        collection_name=collection_name,
        connection_args={"uri": get_settings().milvus.uri},
    )

    return vector_store


def drop_collection(collection_name: str) -> None:
    """删集合并清空单例缓存：drop 后同名懒建重建依赖缓存清除（旧实例持有已删集合
    的字段表缓存，复用它 add 既不会重建集合也可能按旧 schema 写入废数据）。
    has 判断在前，drop 不存在的集合的幂等重删安全；低频管理操作，客户端不缓存。"""
    client = MilvusClient(uri=get_settings().milvus.uri)
    if client.has_collection(collection_name):
        client.drop_collection(collection_name)
    get_milvus.cache_clear()
