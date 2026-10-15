"""分块阶段（chunk）：文本切分策略落点（依赖 langchain-text-splitters，当前未装）。"""
import uuid

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

from cloud_base_ai.exceptions import BusinessException


def split_file(file_content: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """
    文件内容分片
    :param chunk_overlap: 重叠长度
    :param chunk_size: 分块大小
    :param file_content: 文件内容
    :return: 分割后的文件块
    """
    text_splitter = RecursiveCharacterTextSplitter(chunk_size=chunk_size, chunk_overlap=chunk_overlap)
    # 分割后的list 集合
    file_chunk_list: list[str] = text_splitter.split_text(file_content)

    return file_chunk_list


def parent_child_split_file(file_content: str,
                            parent_chunk_size: int,
                            child_chunk_size: int,
                            child_chunk_overlap: int
                            ) -> tuple[list[Document], list[Document]]:
    """
    父子文件分片策略
    手动管理文档分片：
        1. 子文档分片（小）入向量库：类似索引
        2. 父亲文档分片（大/完整）入MYSQL库
        3. 1+2 关联上，结合起来类似：通过索引检索到完整的文档，然后给到LLM进行推理
        4. 不直接使用ParentDocumentRetriever 原因是管理粒度比较粗
        5. 先切分父文档（大），然后再把父文档-切分小块（子）
    :param child_chunk_overlap: 子文档重叠
    :param child_chunk_size:  子文档大小
    :param parent_chunk_size: 父文档大小
    :param file_content: 文件内容
    :return: 分割后的文件块
    """
    if parent_chunk_size <= child_chunk_size:
        raise BusinessException(msg="父文档分片必须大于子文档分片", code=-1)

    # 父文档分片
    parent_text_splitter = RecursiveCharacterTextSplitter(chunk_size=parent_chunk_size, chunk_overlap=0)
    # 子文档分片
    child_text_splitter = RecursiveCharacterTextSplitter(chunk_size=child_chunk_size, chunk_overlap=child_chunk_overlap)

    # 开始切分
    # 分割后的list 集合
    parent_chunk_list: list[str] = parent_text_splitter.split_text(file_content)
    parent_docs: list[Document] = []
    all_child_docs: list[Document] = []

    for parent_file_chunk in parent_chunk_list:
        parent_doc_id = uuid.uuid4().hex
        parent_doc = Document(page_content=parent_file_chunk, metadata={"id": parent_doc_id,"type":0,"parent_id":""})

        # 在父文档分块基础上切分子文档块
        child_chunk_list: list[str] = child_text_splitter.split_text(parent_file_chunk)
        child_docs: list[Document] = []
        for child_chunk in child_chunk_list:
            child_doc = Document(page_content=child_chunk,
                                 metadata={"parent_id": parent_doc_id,
                                           "id": uuid.uuid4().hex,
                                           "type":1
                                           }
                                 )
            child_docs.append(child_doc)
            all_child_docs.append(child_doc)

        # 父文档关联上子文档
        parent_doc.metadata["child_docs"]= child_docs
        parent_docs.append(parent_doc)


    # 返回父文档
    return parent_docs, all_child_docs
