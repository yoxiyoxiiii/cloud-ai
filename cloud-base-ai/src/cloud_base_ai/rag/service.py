"""RAG 域业务编排：router → service → 管线组件（分层依赖单向）。

AI 对话轮（2026-10-13）查询链首个实例（无检索的退化 RAG = 纯生成，spec §3）：
service 负责历史截断（尾 history_limit 条，D10）、LLM 调用编排与异常转译——
LLM 异常 catch 必须 log.error 记根因后转 5001（规范第 3 条；msg 不含 key 与上游原文），
工厂 5002（未配置）透传不重复转译；帧收口在 router（D12）。
"""

import logging
import uuid
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import NamedTuple

import pdfmuse
from fastapi import UploadFile
from fastapi.concurrency import run_in_threadpool
from langchain_core.documents import Document
from langchain_milvus import Milvus
from minio.error import S3Error
from sqlalchemy.exc import IntegrityError

from cloud_base_ai.config import get_settings
from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.models.model_factory import get_chat_model
from cloud_base_ai.rag import dao, chunking, vectorstore
from cloud_base_ai.rag.entities import AiKnowledgeBase, AiKbChunk, AiKnowledgeBaseFiles
from cloud_base_ai.rag.generation import to_lc_messages
from cloud_base_ai.rag.schemas import (
    AiPageResult,
    CreateKBReq,
    KbFileVo,
    KbVo,
    RagChatAnswerVo,
    RagChatDto,
    ReembeddingReq,
    UpdateKBReq,
)
from cloud_base_ai.storage import get_storage

logger = logging.getLogger(__name__)

CODE_LLM_CALL_FAILED: int = 5001
MSG_LLM_CALL_FAILED: str = "LLM 调用失败，请稍后重试"
CODE_KB_NAME_EXISTS: int = 5003
MSG_KB_NAME_EXISTS: str = "知识库名称已存在"
CODE_KB_NOT_EXISTS: int = 5004
MSG_KB_NOT_EXISTS: str = "知识库不存在"
CODE_FILE_NOT_EXISTS: int = 5005
MSG_FILE_NOT_EXISTS: str = "文件不存在"
CODE_FILE_NO_ORIGINAL: int = 5006
MSG_FILE_NO_ORIGINAL: str = "文件无原件，请删除后重新上传"
CODE_FILE_ORIGINAL_READ_FAILED: int = 5007
MSG_FILE_ORIGINAL_READ_FAILED: str = "文件原件读取失败，请删除后重新上传"
CODE_VECTOR_PURGE_FAILED: int = 5008
MSG_VECTOR_PURGE_FAILED: str = "向量清理失败，请重试"
CODE_CHUNK_PARAM_INVALID: int = 1001
MSG_CHUNK_PARAM_INVALID: str = "分片重叠必须小于分片大小"
# 父块分片大小固定 500 不开放（文件表无父块快照列——开放需 DDL，欠账登记 docs/handoff/ai.md）
PARENT_CHUNK_SIZE_FIXED: int = 500


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
async def page_list_kbs(page: int, size: int, name: str | None = None) -> AiPageResult[KbVo]:
    """分页查活知识库（契约 §4）：id 倒序（新建居首），name 包含匹配可选过滤。

    VO 在 service 层逐字段构造（对齐 Java「Service 层统一转换、禁三方拷贝工具」口径）。
    """
    rows, total = await dao.page_list_kbs(current_session(), page, size, name)
    vos = [KbVo(id=row.id, name=row.name, description=row.description,
                create_time=row.create_time) for row in rows]
    return AiPageResult(total=total, rows=vos)


@transactional
async def update_kb(kb_id: int, req: UpdateKBReq) -> None:
    """编辑知识库（契约 §5）：先校验存在（5004），再查重排除自身（5003），
    最后 name+description 全量覆盖落库（并发窗口/墓碑占键由 uk_name 兜底同转 5003）。"""
    session = current_session()
    kb = await dao.find_kb_by_id(session, kb_id)
    if kb is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    same_name = await dao.find_kb_by_name(session, req.name)
    if same_name is not None and same_name.id != kb_id:
        # 保持自身当前名放行（same_name 即本库），改名为他库现有名才拒绝
        raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS)
    try:
        kb.name = req.name
        kb.description = req.description
        await dao.update_kb(session, kb)
    except IntegrityError as e:
        logger.error("update_kb duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS) from e


@transactional
async def delete_kb(kb_id: int) -> None:
    """删除知识库（契约 §6）：chunk → 文件行 → KB 三表软删级联，同一事务，
    无「库下有文件阻止删除」前置约束；物理向量不动（孤儿向量治理记账
    docs/handoff/ai.md，检索轮做 collection 隔离时统一清理）。"""
    session = current_session()
    if await dao.find_kb_by_id(session, kb_id) is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    await dao.delete_chunks_by_kb(session, kb_id)
    await dao.delete_kb_files_by_kb(session, kb_id)
    rowcount = await dao.delete_kb_by_id(session, kb_id)
    if rowcount == 0:
        # 并发窗口兜底：校验存在后被并发删除；raise 回滚本事务级联（对方事务已删，无害）
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)


@transactional
async def page_list_kb_files(kb_id: int, page: int, size: int) -> AiPageResult[KbFileVo]:
    """分页列某知识库文件：先校验库存在（5004，与上传端点同语义），再分页查文件。"""
    session = current_session()
    if await dao.find_kb_by_id(session, kb_id) is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    rows, total = await dao.page_list_kb_files(session, kb_id, page, size)
    vos = [KbFileVo(id=row.id, file_name=row.file_name,
                    file_split_type=row.file_split_type, create_time=row.create_time,
                    file_chunk_size=row.file_chunk_size,
                    file_chunk_overlap=row.file_chunk_overlap)
           for row in rows]
    return AiPageResult(total=total, rows=vos)


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

    # 文件原件入 MinIO：对象键=kb/{kb_id}/{uuid}{后缀}，即文件表 file_path 的新语义
    # （旧 uuid 占位自此退役）。SDK 为同步客户端，run_in_threadpool 包装防阻塞事件循环；
    # 失败语义与 Milvus 同源——异常上抛回滚本事务（此时零 DB 写），后置步骤失败留孤儿对象
    # （handoff 一致性欠账统一治理）
    storage = get_storage()
    suffix = Path(file.filename or "").suffix
    object_name = f"kb/{kb_id}/{uuid.uuid4().hex}{suffix}"
    await run_in_threadpool(storage.ensure_bucket)
    await run_in_threadpool(
        storage.put_bytes, object_name, file_data, file.content_type or "application/octet-stream")

    # 存储文件信息到文件表
    ai_knowledge_base_files = await dao.save_kb_file(session=session,
                                                     kb_file=AiKnowledgeBaseFiles(kb_id=kb_id,
                                                                                  file_path=object_name,
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
    save_list: Sequence[AiKbChunk] = await dao.save_chunks(session=session, chunks=ai_kb_chunk_list)

    # 子文档写入到向量数据库中（collection 名随构造传入：构造后属性赋值不触发
    # 字段表重载，对既有集合插入会剥掉 metadata，见 vectorstore.get_milvus 注释）
    milvus: Milvus = vectorstore.get_milvus("test123")
    child_rows = [item for item in save_list
                  if item.chunk_type == AiKbChunk.ChunkTypeEnum.CHILD]

    milvus.add_documents(all_child_doc_list, ids=[str(item.id) for item in child_rows])

    return kb_id


class ReembeddingContext(NamedTuple):
    """重嵌入阶段一产出的纯值上下文。

    不返回 ORM 实体：阶段一事务在方法返回时提交关闭，实体上的惰性加载随之不可用；
    纯值在事务外传递无陷阱。
    """

    file_id: int
    kb_id: int
    file_path: str
    chunk_size: int
    chunk_overlap: int
    old_child_ids: list[int]


@transactional
async def _load_reembedding_context(req: ReembeddingReq) -> ReembeddingContext:
    """重嵌入阶段一（读事务）：文件行校验 → 生效参数与关系校验 → 历代子块 id 清单。

    - 5005：文件不存在（活记录视角；所在库已级联软删的文件行同此）；
    - 5006：file_path 不以 kb/ 开头即存量早期行（裸 uuid，MinIO 无对象）；
    - 生效参数 = 请求值 ?? 文件行快照值；「重叠 >= 分片大小」在生效值上判定
      （只传其一时静态校验覆盖不了该组合）。
    """
    session = current_session()
    file_row = await dao.find_kb_file_by_id(session, req.file_id)
    if file_row is None:
        raise BusinessException(MSG_FILE_NOT_EXISTS, CODE_FILE_NOT_EXISTS)
    if not file_row.file_path.startswith("kb/"):
        raise BusinessException(MSG_FILE_NO_ORIGINAL, CODE_FILE_NO_ORIGINAL)
    chunk_size = req.chunk_size if req.chunk_size is not None else file_row.file_chunk_size
    chunk_overlap = req.chunk_overlap if req.chunk_overlap is not None \
        else file_row.file_chunk_overlap
    if chunk_overlap >= chunk_size:
        raise BusinessException(MSG_CHUNK_PARAM_INVALID, CODE_CHUNK_PARAM_INVALID)
    # 历代清单先于新代 id 生成（阶段二才产生），保证删除清单与新增向量零交集
    old_child_ids = list(await dao.list_child_ids_by_file(session, req.file_id))
    return ReembeddingContext(file_id=req.file_id, kb_id=file_row.kb_id,
                              file_path=file_row.file_path, chunk_size=chunk_size,
                              chunk_overlap=chunk_overlap, old_child_ids=old_child_ids)


@transactional
async def _replace_file_chunks(ctx: ReembeddingContext, parent_docs: list[Document],
                               child_docs: list[Document]) -> list[int]:
    """重嵌入阶段二（写事务）：软删旧代块 → 新代父子块落库 → 文件行快照三值更新。

    三写同一事务，任一步失败整体回滚（Milvus 未动，直接重试即可）；返回新代子块行
    id 清单——向量主键 = 子块行 id 字符串化。
    """
    session = current_session()
    await dao.delete_chunks_by_file(session, ctx.file_id)
    chunk_rows = _build_chunk_rows(ctx.kb_id, ctx.file_id, parent_docs) \
                 + _build_chunk_rows(ctx.kb_id, ctx.file_id, child_docs)
    saved = await dao.save_chunks(session, chunk_rows)
    file_row = await dao.find_kb_file_by_id(session, ctx.file_id)
    file_row.file_chunk_count = len(child_docs)
    file_row.file_chunk_size = ctx.chunk_size
    file_row.file_chunk_overlap = ctx.chunk_overlap
    await dao.update_kb_file(session, file_row)
    return [row.id for row in saved
            if row.chunk_type == AiKbChunk.ChunkTypeEnum.CHILD]


def _build_chunk_rows(kb_id: int, file_id: int,
                      docs: Sequence[Document]) -> list[AiKbChunk]:
    """分片 Document 转块实体（与上传链同构：uuid/type/parent_id 都在 metadata）。"""
    return [AiKbChunk(kb_id=kb_id, file_id=file_id,
                      chunk_id=doc.metadata["id"],
                      parent_chunk_id=doc.metadata["parent_id"] or "",
                      chunk_type=doc.metadata["type"], seq_no=0,
                      content=doc.page_content)
            for doc in docs]


def _swap_vectors(child_docs: list[Document], new_child_ids: Sequence[int],
                  old_child_ids: Sequence[int], file_id: int) -> None:
    """向量置换（MySQL 事务提交后执行）：先写新后删旧。

    先写后删的中间失败形态是新旧并存而非检索空洞（旧向量命中后被软删过滤，不产脏内容）；
    删除清单 = 历代子块 id（含软删行），让原样重试具备完全收敛性。Milvus 实例同步调用
    与上传链同形态。
    """
    milvus: Milvus = vectorstore.get_milvus("test123")
    milvus.add_documents(child_docs, ids=[str(i) for i in new_child_ids])
    if not old_child_ids:
        # 空清单必须跳过 delete：langchain-milvus 对空 ids 列表会触发断言错误
        return
    purged = milvus.delete(ids=[str(i) for i in old_child_ids])
    if not purged:
        # delete 恒返 True（目标本就不存在也算成功）；False 即真失败——MySQL 已置换、
        # 旧向量未清，原样重试同一请求即收敛（历代清单含残留代）
        logger.error("reembedding purge old vectors failed, file_id=%s ids=%s",
                     file_id, list(old_child_ids))
        raise BusinessException(MSG_VECTOR_PURGE_FAILED, CODE_VECTOR_PURGE_FAILED)


async def reembedding(req: ReembeddingReq) -> None:
    """重新向量化单文件（契约 §8）：读原件 → 重新分片 → MySQL 置换 → 向量先写新后删旧。

    编排方法不开事务不碰 current_session——MySQL 事务边界在两个 @transactional 阶段
    方法上各自提交；MinIO/Milvus 外部调用全在事务外，失败面收敛到向量一侧（5005/5006/
    5007 零副作用，向量段失败重试即收敛）。
    """
    ctx = await _load_reembedding_context(req)
    try:
        file_data = await run_in_threadpool(get_storage().get_bytes, ctx.file_path)
    except S3Error as e:
        logger.error("reembedding read original failed, path=%s: %s: %s",
                     ctx.file_path, type(e).__name__, e)
        raise BusinessException(
            MSG_FILE_ORIGINAL_READ_FAILED, CODE_FILE_ORIGINAL_READ_FAILED) from e
    file_content = pdfmuse.to_text(file_data)
    parent_docs, child_docs = chunking.parent_child_split_file(
        file_content=file_content, parent_chunk_size=PARENT_CHUNK_SIZE_FIXED,
        child_chunk_size=ctx.chunk_size, child_chunk_overlap=ctx.chunk_overlap)
    new_child_ids = await _replace_file_chunks(ctx, parent_docs, child_docs)
    _swap_vectors(child_docs, new_child_ids, ctx.old_child_ids, req.file_id)
