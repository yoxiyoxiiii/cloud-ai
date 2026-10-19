"""Milvus 真连接冒烟：验证 ai_kb_chunk 子块向量的写入、命中、删除全链路。

为什么需要它：单测用内存替身，覆盖不到 Milvus 的真实行为。已踩过的坑是——
langchain-milvus 的主键类型由 auto_id 决定：auto_id=False 建 VARCHAR 主键，
auto_id=True 建 INT64 主键，传字符串 id 进 INT64 主键会报类型错。
同一个 collection 名第一次建表时的参数会固定表结构。

场景 [5]（集合隔离轮）验证一库一集合模型的外部事实：不同集合数据互不可见
（含同主键不冲突），以及 drop_collection 清单例缓存后同名集合经懒建重建可写。

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
from cloud_base_ai.rag import vectorstore

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
    iso_names: list[str] = []
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

    # [5] 一库一集合隔离 + drop 后懒建重建（经 vectorstore 封装，覆盖单例缓存清除路径）
    stamp = int(time.time())
    name_a, name_b = f"e2e_smoke_iso_a_{stamp}", f"e2e_smoke_iso_b_{stamp}"
    iso_names = [name_a, name_b]
    try:
        vectorstore.get_milvus(name_a).add_documents(
            [Document(page_content="差旅住宿标准为每晚 400 元", metadata={"kb": "A"})],
            ids=["901"])  # 两集合刻意同主键：共享集合时后者会覆盖前者，隔离即不覆盖
        vectorstore.get_milvus(name_b).add_documents(
            [Document(page_content="年假按司龄计算，满一年五天", metadata={"kb": "B"})],
            ids=["901"])
        hit_a = vectorstore.get_milvus(name_a).similarity_search("住宿标准是多少", k=1)
        hit_b = vectorstore.get_milvus(name_b).similarity_search("住宿标准是多少", k=1)
        kb_a = hit_a[0].metadata.get("kb") if hit_a else None
        kb_b = hit_b[0].metadata.get("kb") if hit_b else None
        print(f"[5a] 同主键双集合隔离：A 集合命中 kb={kb_a}，B 集合命中 kb={kb_b}")
        if (kb_a, kb_b) != ("A", "B"):
            failures.append(f"集合隔离失效：期望 (A, B)，实际 ({kb_a}, {kb_b})")

        vectorstore.drop_collection(name_a)
        rebuilt = vectorstore.get_milvus(name_a)  # 缓存已清：重新构造并懒建同名集合
        rebuilt.add_documents(
            [Document(page_content="报销单需在出差返回后一周内提交", metadata={"kb": "A2"})],
            ids=["902"])
        hit_r = rebuilt.similarity_search("报销时限", k=1)
        kb_r = hit_r[0].metadata.get("kb") if hit_r else None
        print(f"[5b] drop 后同名懒建重建：重建后写入检索命中 kb={kb_r}")
        if kb_r != "A2":
            failures.append(f"drop 后懒建重建失败：期望 A2，实际 {kb_r}")
    finally:
        for n in iso_names:
            vectorstore.drop_collection(n)
        print(f"[5c] 已清理临时 collection {iso_names}")

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
