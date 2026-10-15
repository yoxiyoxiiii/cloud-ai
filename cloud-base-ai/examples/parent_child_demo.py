"""父子文档检索（Parent-Child Retrieval，又叫 small-to-big）最小可运行案例。

解决什么问题：
- 块切太小 → 向量检索准，但交给大模型的上下文不够，答非所问
- 块切太大 → 上下文够，但向量被"稀释"，检索不准
- 方案：用小块（子块）做向量检索，命中后回捞大块（父块）交给大模型
  —— "小块搜索、大块生成"

核心机制只有三件事：
1. 子块 metadata 里记 parent_id（子找父的唯一线索）
2. 向量库里只放子块（检索用小块）
3. 查询时：子块命中 → 按 parent_id 回捞父块（返回大块）

运行前提（一次性）：
    ollama pull bge-m3          # 中文效果好的嵌入模型（约 1.2GB）
    # Ollama 服务保持运行（默认 127.0.0.1:11434）
运行方式（cloud-base-ai 工程根）：
    uv run python examples/parent_child_demo.py
"""

import sys

from langchain_core.documents import Document
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

# Windows 控制台默认 GBK，中文输出会乱码——强制 UTF-8（Git Bash/IDE 均正常显示）
sys.stdout.reconfigure(encoding="utf-8")

# ---------- 1. 语料：三份"公司制度"文档（子块细节藏在父块上下文里） ----------

RAW_DOCS: list[Document] = [
    Document(
        page_content=(
            "差旅报销制度。员工因公出差产生的交通费、住宿费可在回来后 7 个工作日内提交报销。"
            "国内差旅住宿标准为每晚 400 元，一线城市上浮 20%。"
            "单笔报销金额超过 3000 元的，需部门负责人与财务总监双审批，"
            "3000 元以下仅需部门负责人审批。报销款项将在审批通过后 10 个工作日内打入工资卡。"
            "同行多人出差原则上由一人统一垫付，避免重复报销。"
        ),
        metadata={"source": "差旅报销制度"},
    ),
    Document(
        page_content=(
            "考勤与请假制度。标准工作时间为 9:00-18:00，午休一小时。"
            "迟到 30 分钟以内每月前两次不扣款，第三次起每次扣款 50 元。"
            "年假天数按司龄计算：满 1 年 5 天，满 3 年 10 天，满 5 年 15 天。"
            "年假可跨年结转一次，最多结转 5 天，第二年年底前未休自动清零。"
            "病假需提供二级以上医院证明，按当地最低工资的 80% 计发。"
        ),
        metadata={"source": "考勤与请假制度"},
    ),
    Document(
        page_content=(
            "AI 基座部署手册。cloud-base-ai 服务部署前需准备 Python 3.11 与 uv。"
            "依赖安装使用 uv sync，启动使用 uvicorn cloud_base_ai.main:app --port 8010。"
            "服务启动后自动注册到 Nacos（127.0.0.1:8848），由网关 18080 的 /ai 前缀路由。"
            "大模型密钥配置在本地 .env 文件，六键前缀 CLOUD_BASE_AI_LLM__，真值不入库。"
            "数据库连接默认本机 MySQL 3306 的 cloud_ai 库，可用环境变量覆盖。"
        ),
        metadata={"source": "AI 基座部署手册"},
    ),
]

# ---------- 2. 切块：父块要"上下文够"，子块要"语义聚焦" ----------

# 父块 ~500 字：一段完整的制度说明，交给大模型足够回答问题
parent_splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=0)
# 子块 ~120 字：一两个句子，一个子块只讲一件事，向量最"纯"
child_splitter = RecursiveCharacterTextSplitter(chunk_size=120, chunk_overlap=20)

# ---------- 3. 建索引（纯内存，不联网）：父块进"文档库"，子块带 parent_id ----------

docstore: dict[str, Document] = {}      # 父块仓库（真实系统=数据库表）
children_all: list[Document] = []

for i, parent in enumerate(parent_splitter.split_documents(RAW_DOCS)):
    pid = f"parent_{i}"
    docstore[pid] = parent
    for child in child_splitter.split_documents([parent]):
        child.metadata["parent_id"] = pid     # 核心：子块记住父亲是谁
        children_all.append(child)

# ---------- 4. 检索：子块命中 → 去重 → 回捞父块 ----------


def retrieve_parents(vectorstore: InMemoryVectorStore, query: str,
                     k: int = 3) -> list[Document]:
    """先搜子块（小块准），再回捞父块（大块全）。"""
    hits = vectorstore.similarity_search(query, k=k)
    # dict.fromkeys：按出现顺序去重——多个子块命中同一个父块只返回一次
    pids = list(dict.fromkeys(h.metadata["parent_id"] for h in hits))
    return [docstore[pid] for pid in pids]


def main() -> None:
    # 联网步骤放 main 里：环境没备好时走统一提示，不甩一屏堆栈
    embeddings = OllamaEmbeddings(model="bge-m3")
    vectorstore = InMemoryVectorStore.from_documents(children_all, embeddings)

    print(f"父块 {len(docstore)} 个，子块 {len(children_all)} 个（只有子块进了向量库）\n")

    query = "报销超过多少需要双审批？"
    print(f"查询：{query}\n" + "=" * 60)

    hits = vectorstore.similarity_search_with_score(query, k=3)
    print("\n【第一步】子块命中（向量检索的是这些小块）：")
    for doc, score in hits:
        print(f"  [{score:.3f}] parent_id={doc.metadata['parent_id']} "
              f"({len(doc.page_content)} 字)：{doc.page_content[:50]}...")

    parents = retrieve_parents(vectorstore, query)
    print(f"\n【第二步】回捞父块（实际交给大模型的是这些大块）：")
    for p in parents:
        print(f"  --- {p.metadata['source']} ({len(p.page_content)} 字) ---")
        print(f"  {p.page_content}\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # 连不上 Ollama 等环境问题给个友好提示
        print(f"运行失败：{type(e).__name__}: {e}\n"
              "请确认：① Ollama 服务已启动（默认 11434 端口）；"
              "② 已执行 ollama pull bge-m3")
