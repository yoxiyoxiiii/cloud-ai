"""RAG 域业务编排：router → service → 管线组件（分层依赖单向）。

AI 对话轮（2026-10-13）查询链首个实例（无检索的退化 RAG = 纯生成，spec §3）：
service 负责历史截断（尾 history_limit 条，D10）、LLM 调用编排与异常转译——
LLM 异常 catch 必须 log.error 记根因后转 5001（规范第 3 条；msg 不含 key 与上游原文），
工厂 5002（未配置）透传不重复转译；帧收口在 router（D12）。
"""

import logging
import uuid
from collections.abc import AsyncIterator, Sequence

import pdfmuse
from fastapi import UploadFile
from langchain_core.documents import Document
from langchain_milvus import Milvus
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.config import get_settings
from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.models.model_factory import get_chat_model
from cloud_base_ai.rag import dao, chunking, vectorstore
from cloud_base_ai.rag.entities import AiKnowledgeBase, AiKbChunk, AiKnowledgeBaseFiles
from cloud_base_ai.rag.generation import to_lc_messages
from cloud_base_ai.rag.schemas import RagChatAnswerVo, RagChatDto, CreateKBReq

logger = logging.getLogger(__name__)

CODE_LLM_CALL_FAILED: int = 5001
MSG_LLM_CALL_FAILED: str = "LLM 调用失败，请稍后重试"
CODE_KB_NAME_EXISTS: int = 5003
MSG_KB_NAME_EXISTS: str = "知识库名称已存在"
CODE_KB_NOT_EXISTS: int = 5004
MSG_KB_NOT_EXISTS: str = "知识库不存在"


async def chat_answer(dto: RagChatDto) -> RagChatAnswerVo:
    """非流式对话：ainvoke 一次成型，返回完整回答 VO（model = 配置的模型名，配置即事实）。"""
    cfg = get_settings().llm
    messages = to_lc_messages(dto.messages[-cfg.history_limit:])
    try:
        model = get_chat_model()
        result = await model.ainvoke(messages)
    except BusinessException:
        raise  # 工厂 5002 透传，不重复转译
    except Exception as e:
        logger.error("LLM ainvoke failed: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_LLM_CALL_FAILED, CODE_LLM_CALL_FAILED) from e
    # deepseek-flash 走标准 content 通道（str）；str() 兜底防御非文本 content 形态
    return RagChatAnswerVo(answer=str(result.content), model=cfg.model)


async def stream_chat_answer(dto: RagChatDto) -> AsyncIterator[str]:
    """流式对话 async generator：逐 AIMessageChunk yield 增量文本（content 非空 str 才 yield）。

    异常向消费方（router event generator）传播：5002 透传、其余转 5001——
    帧化与 [DONE] 收口由 router 统一处理（D12），本层只保证异常语义。
    """
    cfg = get_settings().llm
    messages = to_lc_messages(dto.messages[-cfg.history_limit:])
    try:
        model = get_chat_model()
        async for chunk in model.astream(messages):
            content = chunk.content
            if isinstance(content, str) and content:
                yield content
    except BusinessException:
        raise  # 工厂 5002 透传，不重复转译
    except Exception as e:
        logger.error("LLM astream failed: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_LLM_CALL_FAILED, CODE_LLM_CALL_FAILED) from e


@transactional
async def create_kb(req: CreateKBReq) -> int:
    """创建知识库：查重（只看未删除的记录）→ 新增 → 返回 id。

    事务边界在 @transactional：正常返回提交，任何异常（含业务异常 5003）回滚。
    撞重双防：先查重挡住常规重名；并发窗口或"已软删记录仍占用唯一键"的情况
    由 IntegrityError 兜底——先 log.error 记根因，再转业务码 5003。
    """
    # 获取当前事务
    session = current_session()
    # 查询当前知识库是否已经存在
    exists = await dao.find_kb_by_name(session, req.name)
    if exists is not None:
        raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS)
    try:
        kb = await dao.save_kb(
            session, AiKnowledgeBase(name=req.name, description=req.description)
        )
    except IntegrityError as e:
        logger.error("create_kb duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS) from e
    return kb.id


@transactional
async def upload_file_by_kb(kb_id: int, file: UploadFile) -> int:
    """
    上传文件到知识库
    :param kb_id: 知识库ID
    :param file: 文件
    :return:
    """
    session = current_session()
    kb = await dao.find_kb_by_id(session=session, kb_id=kb_id)
    if kb is None:
        # 知识库不存在
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    # 知识库存在
    # 切分文件
    file_data: bytes = file.file.read()
    file_content = pdfmuse.to_text(file_data)
    parent_and_child_docs: tuple[list[Document], list[Document]] = chunking.parent_child_split_file(
        file_content=file_content,
        child_chunk_size=128,
        child_chunk_overlap=20,
        parent_chunk_size=500)

    parent_doc_list = parent_and_child_docs[0]
    all_child_doc_list = parent_and_child_docs[1]

    # 上传文件到文档服务器（todo(暂时不做)）,存储文件信息到文件表
    ai_knowledge_base_files = await dao.save_kb_file(session=session,
                                                     kb_file=AiKnowledgeBaseFiles(kb_id=kb_id,
                                                                                  file_path=uuid.uuid4().hex,
                                                                                  file_name=file.filename,
                                                                                  file_chunk_count=len(
                                                                                      all_child_doc_list),
                                                                                  file_chunk_size=128,
                                                                                  file_chunk_overlap=20,
                                                                                  file_split_type=AiKnowledgeBaseFiles.SplitTypeEnum.PARENT_CHILD.value

                                                                                  )
                                                     )

    # 父文档进MYSQL;子文档进度向量数据库
    ai_kb_chunk_list: list[AiKbChunk] = []
    for doc in parent_doc_list:
        ai_kb_chunk = AiKbChunk(kb_id=kb_id,
                                file_id=ai_knowledge_base_files.id,
                                chunk_id=doc.metadata["id"],
                                parent_chunk_id=doc.metadata["parent_id"] or "",
                                chunk_type=doc.metadata["type"],
                                seq_no=0,
                                content=doc.page_content)
        ai_kb_chunk_list.append(ai_kb_chunk)

    for doc in all_child_doc_list:
        ai_kb_chunk = AiKbChunk(kb_id=kb_id,
                                file_id=ai_knowledge_base_files.id,
                                chunk_id=doc.metadata["id"],
                                parent_chunk_id=doc.metadata["parent_id"] or "",
                                chunk_type=doc.metadata["type"],
                                seq_no=0,
                                content=doc.page_content)
        ai_kb_chunk_list.append(ai_kb_chunk)

    # 存入mysql (子/父都存入)
    save_list:Sequence[AiKbChunk] = await dao.save_chunks(session=session, chunks=ai_kb_chunk_list)

    # 子文档写入到向量数据库中
    milvus: Milvus = vectorstore.get_milvus()
    milvus.collection_name = "test123"
    child_rows = [item for item in save_list
                  if item.chunk_type == AiKbChunk.ChunkTypeEnum.CHILD]


    milvus.add_documents(all_child_doc_list, ids=[str(item.id) for item in child_rows])

    return kb_id
