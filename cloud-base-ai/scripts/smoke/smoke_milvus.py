"""Milvus 真连接冒烟：验证 ai_kb_chunk 子块向量的写入、命中、删除全链路。

为什么需要它：单测用内存替身，覆盖不到 Milvus 的真实行为。已踩过的坑是——
langchain-milvus 的主键类型由 auto_id 决定：auto_id=False 建 VARCHAR 主键，
auto_id=True 建 INT64 主键，传字符串 id 进 INT64 主键会报类型错。
同一个 collection 名第一次建表时的参数会固定表结构。

运行前提：Milvus 在 localhost:19530 运行，Ollama 在运行且已拉取 bge-m3。
运行方式（在 cloud-base-ai 工程根目录）：
    uv run python scripts/smoke/smoke_milvus.py

脚本使用独立的临时 collection（名字带时间戳），结束时自动删除，不碰业务 collection。
"""

import sys
import time

from langchain_core.documents import Document
from langchain_milvus import Milvus

from cloud_base_ai.models import model_factory

sys.stdout.reconfigure(encoding="utf-8")  # Windows 控制台默认 GBK，强制 UTF-8 防中文乱码

MILVUS_URI = "http://localhost:19530"


def main() -> int:
    name = f"e2e_smoke_{int(time.time())}"
    store = Milvus(
        embedding_function=model_factory.get_embedding_model(),
        collection_name=name,
        connection_args={"uri": MILVUS_URI},
    )
    failures: list[str] = []
    try:
        docs = [
            Document(page_content="差旅住宿标准为每晚 400 元", metadata={"parent_chunk_id": "p-1"}),
            Document(page_content="年假按司龄计算，满一年五天", metadata={"parent_chunk_id": "p-2"}),
        ]
        # 主键用数字字符串，模拟「MySQL 行 id 当向量主键」的真实用法
        ids = ["101", "102"]
        store.add_documents(docs, ids=ids)
        print(f"[1] 写入成功，主键 {ids}")

        if store.auto_id:
            failures.append("auto_id 变成了 True：带 ids 写入时不应被自动改写")

        hits = store.similarity_search("住宿标准是多少", k=1)
        got_parent = hits[0].metadata.get("parent_chunk_id") if hits else None
        print(f"[2] 检索命中 parent_chunk_id={got_parent}，pk={hits[0].metadata.get('pk') if hits else None}")
        if got_parent != "p-1":
            failures.append(f"检索结果不符合预期：期望 p-1，实际 {got_parent}")

        store.delete(ids=["101"])
        left = store.similarity_search("住宿标准是多少", k=2)
        left_pks = [d.metadata.get("pk") for d in left]
        print(f"[3] 删除 101 后剩余 pk={left_pks}")
        if "101" in left_pks:
            failures.append("删除后 101 仍能检索到")
    finally:
        store.drop()
        print(f"[4] 已删除临时 collection {name}")

    if failures:
        print("\n冒烟失败：")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("\n冒烟通过")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # 连不上 Milvus/Ollama 等环境问题给友好提示，不甩整屏堆栈
        print(f"冒烟无法运行：{type(e).__name__}: {e}")
        print("请确认：Milvus 在 19530 端口运行、Ollama 在运行且已 ollama pull bge-m3")
        sys.exit(2)
