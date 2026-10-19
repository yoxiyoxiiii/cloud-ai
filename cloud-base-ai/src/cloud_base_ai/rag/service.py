"""RAG 域业务编排：router → service → 管线组件（分层依赖单向）。

对话链（2026-10-13 首轮 + 2026-10-19 会话管理轮挂接）：service 负责会话校验/归属、
问与答的落档编排（Redis message_store）、历史由服务端拼装（尾 history_limit 条单源）、
LLM 调用编排与异常转译——LLM 异常 catch 必须 log.error 记根因后转 5001（规范第 3 条；
msg 不含 key 与上游原文），工厂 5002（未配置）透传不重复转译；帧收口在 router。
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
from cloud_base_ai.rag import dao, chunking, message_store, vectorstore
from cloud_base_ai.rag.entities import (
    AiChatSession,
    AiKnowledgeBase,
    AiKbChunk,
    AiKbGrant,
    AiKnowledgeBaseFiles,
)
from cloud_base_ai.rag.generation import to_lc_messages
from cloud_base_ai.rag.schemas import (
    AiPageResult,
    ChatSendReq,
    ChatSessionVo,
    CreateKBReq,
    KbFileVo,
    KbPermsSaveReq,
    KbPermsVo,
    KbVo,
    RagChatAnswerVo,
    RagChatMessage,
    ReembeddingReq,
    SessionMessageVo,
    SessionMessagesVo,
    UpdateKBReq,
)
from cloud_base_ai.security import current_identity, is_mgmt_exemption
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
# 5011 接在 AI 段 5009/5010（MCP 客户端通道）之后
CODE_KB_NO_ACCESS: int = 5011
MSG_KB_NO_ACCESS: str = "无权访问该知识库"
# 5012/5013 接在 5011 之后（会话归属校验）
CODE_SESSION_NOT_EXISTS: int = 5012
MSG_SESSION_NOT_EXISTS: str = "会话不存在"
CODE_SESSION_NO_ACCESS: int = 5013
MSG_SESSION_NO_ACCESS: str = "无权访问该会话"
# 5014 接在 5013 之后（上传链向量写失败）；AI 段下一可用 5015
CODE_VECTOR_WRITE_FAILED: int = 5014
MSG_VECTOR_WRITE_FAILED: str = "向量写入失败，请对该文件执行重新向量化"
# 创建会话时身份缺失（X-User-Id 缺失/非整数）无法落归属——复用通用参数错误码 1001
CODE_SESSION_IDENTITY_MISSING: int = 1001
MSG_SESSION_IDENTITY_MISSING: str = "缺少用户身份（X-User-Id），无法创建会话"
CODE_CHUNK_PARAM_INVALID: int = 1001
MSG_CHUNK_PARAM_INVALID: str = "分片重叠必须小于分片大小"
# 父块分片大小固定 500 不开放（文件表无父块快照列——开放需 DDL，欠账登记 docs/handoff/ai.md）
PARENT_CHUNK_SIZE_FIXED: int = 500
# 首问改题的标题截断长度（沿用前端既有 slice(0,16) 口径，生成责任收敛服务端保证多端一致）
TITLE_MAX_CHARS: int = 16


def derive_collection_name(kb_id: int) -> str:
    """Milvus 集合名派生（命名规则唯一实现处，禁散落 f"kb_{id}" 魔法串）：
    一库一集合；换 embedding 维度时改库行 collection_name 指向 kb_{id}_v2
    新集合后逐文件重嵌入即完成蓝绿切换，本函数只管初代命名。"""
    return f"kb_{kb_id}"


class PreparedChat(NamedTuple):
    """对话前置阶段产出：目标会话 id 与送 LLM 的尾段历史（含刚落的问）。"""

    session_id: int
    history: list[RagChatMessage]


async def prepare_chat(req: ChatSendReq) -> PreparedChat:
    """对话前置阶段（流式/非流式共用）：校验会话 → 问落档 → 首问改题 → 读尾段历史。

    校验严格先于任何写（先写 Redis 再校验 = 可向他人会话键注入消息的越权面）；
    5012/5013 在此抛出——流式端点于 StreamingResponse 构造前调用本方法，错误形态
    是 R JSON 而非 SSE 帧。改题放问之后（问确实发生了才改），改题失败不回滚问
    （最坏 = 标题停留默认值，下一问补齐）。
    """
    await _load_owned_session(req.session_id)
    message_store.append_message(req.session_id, "user", req.question)
    await _rename_on_first_question(req.session_id, req.question)
    history_limit = get_settings().llm.history_limit
    history = message_store.list_messages(req.session_id, limit=history_limit)
    return PreparedChat(session_id=req.session_id, history=history)


async def chat_answer(req: ChatSendReq) -> RagChatAnswerVo:
    """非流式对话：前置（prepare_chat）→ ainvoke 一次成型 → 答收口落档 → 返回 VO。

    LLM 调用不持有事务（沿既有口径）；答在收口时落档——正常完成、LLM 失败、
    用户停止三条路径统一（LLM 零 token 失败则无答行，问悬置保留）。
    """
    prepared = await prepare_chat(req)
    cfg = get_settings().llm
    try:
        model = get_chat_model()
        result = await model.ainvoke(to_lc_messages(prepared.history))
    except BusinessException:
        raise  # 工厂 5002 透传，不重复转译
    except Exception as e:
        logger.error("LLM ainvoke failed: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_LLM_CALL_FAILED, CODE_LLM_CALL_FAILED) from e
    # deepseek-flash 走标准 content 通道（str）；str() 兜底防御非文本 content 形态
    answer = str(result.content)
    _persist_answer(prepared.session_id, answer)
    return RagChatAnswerVo(answer=answer, model=cfg.model)


async def stream_chat_answer(prepared: PreparedChat) -> AsyncIterator[str]:
    """流式对话 async generator：逐 AIMessageChunk yield 增量文本（content 非空 str 才 yield）。

    try/finally 收口统一落答：正常结束、LLM 异常、消费方提前关闭（用户停止生成，
    router 侧显式 aclosing 保证关闭传播到本层）三条路径都经过 finally——已生成的
    半截答照落（回显所见即所得）。异常向 router event generator 传播：5002 透传、
    其余转 5001；帧化与 [DONE] 收口由 router 统一处理。
    """
    parts: list[str] = []
    try:
        model = get_chat_model()
        async for chunk in model.astream(to_lc_messages(prepared.history)):
            content = chunk.content
            if isinstance(content, str) and content:
                parts.append(content)
                yield content
    except BusinessException:
        raise  # 工厂 5002 透传，不重复转译
    except Exception as e:
        logger.error("LLM astream failed: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_LLM_CALL_FAILED, CODE_LLM_CALL_FAILED) from e
    finally:
        _persist_answer(prepared.session_id, "".join(parts))


def _persist_answer(session_id: int, answer: str) -> None:
    """答收口落档：空文本不落（LLM 零 token 失败无答行，问悬置）；Redis 写失败
    log.error 后原样上抛走全局 500（不吞不转 5001——5001 语义是 LLM 调用失败）。"""
    if not answer:
        return
    try:
        message_store.append_message(session_id, "assistant", answer)
    except Exception as e:
        logger.error("answer persist failed, session_id=%s: %s: %s",
                     session_id, type(e).__name__, e)
        raise


# ---------- 会话管理（2026-10-19 会话管理轮，契约 2026-10-19-ai-chat-session-api.md §2-§5） ----------


@transactional
async def create_session() -> ChatSessionVo:
    """创建会话（契约 §2）：归属取网关注入的 X-User-Id，标题默认「新对话」（DB DEFAULT
    生成，save 后 refresh 回填）。身份缺失（X-User-Id 缺失/非整数）无法落归属 → 1001。"""
    identity = current_identity()
    if identity is None or identity.user_id is None:
        raise BusinessException(MSG_SESSION_IDENTITY_MISSING, CODE_SESSION_IDENTITY_MISSING)
    session = current_session()
    row = await dao.save_session(session, AiChatSession(user_id=identity.user_id))
    return ChatSessionVo(id=row.id, title=row.title, create_time=row.create_time)


@transactional
async def page_list_sessions(page: int, size: int) -> AiPageResult[ChatSessionVo]:
    """当前用户的会话分页（契约 §3）：仅本人、id 倒序（新建居首）、软删不可见。

    身份头缺失 = 空集语义（total:0, rows:[]，不算错误——网关全链下 X-User-Id 恒在，
    此差异只影响本地直连调试）。
    """
    identity = current_identity()
    if identity is None or identity.user_id is None:
        return AiPageResult(total=0, rows=[])
    session = current_session()
    rows, total = await dao.page_list_sessions(session, identity.user_id, page, size)
    vos = [ChatSessionVo(id=row.id, title=row.title, create_time=row.create_time)
           for row in rows]
    return AiPageResult(total=total, rows=vos)


async def list_session_messages(session_id: int) -> SessionMessagesVo:
    """消息回显（契约 §5）：归属校验先行（5012/5013），再读 Redis 消息（升序全量）。"""
    await _load_owned_session(session_id)
    msgs = message_store.list_messages(session_id)
    messages = [SessionMessageVo(role=m.role, content=m.content) for m in msgs]
    return SessionMessagesVo(messages=messages)


async def delete_session(session_id: int) -> None:
    """删除会话双清（契约 §4）：校验归属 → 先物理删 Redis 消息键，后 MySQL 软删。

    先 Redis 后 MySQL 的顺序依据（设计 §3.3）：Redis 先删、MySQL 提交失败 = 会话还在
    消息空（DEL 幂等，重删即愈）；反向则留下永不过期的孤儿键且重删被 5012 拒、无法
    自愈。Redis 删失败原样上抛走全局 500——MySQL 未动，可原样重试。
    """
    await _load_owned_session(session_id)
    try:
        message_store.delete_messages(session_id)
    except Exception as e:
        logger.error("delete_session redis del failed, session_id=%s: %s: %s",
                     session_id, type(e).__name__, e)
        raise
    await _soft_delete_session(session_id)


@transactional
async def _soft_delete_session(session_id: int) -> None:
    """双清的 MySQL 段：软删 + 审计两值；rowcount=0 为并发窗口兜底（校验后被并发删除）。"""
    session = current_session()
    rowcount = await dao.delete_session_by_id(session, session_id)
    if rowcount == 0:
        raise BusinessException(MSG_SESSION_NOT_EXISTS, CODE_SESSION_NOT_EXISTS)


@transactional
async def _load_owned_session(session_id: int) -> AiChatSession:
    """会话存在性与归属校验（MySQL 唯一真相源；Redis 键只在通过后才被触碰）：
    无活记录（含软删、二次删除）→ 5012；存在但归属非本人（含身份头缺失——归属永不
    匹配）→ 5013。归属校验先于一切消息读写。"""
    session = current_session()
    row = await dao.find_session_by_id(session, session_id)
    if row is None:
        raise BusinessException(MSG_SESSION_NOT_EXISTS, CODE_SESSION_NOT_EXISTS)
    identity = current_identity()
    if identity is None or row.user_id != identity.user_id:
        raise BusinessException(MSG_SESSION_NO_ACCESS, CODE_SESSION_NO_ACCESS)
    return row


@transactional
async def _rename_on_first_question(session_id: int, question: str) -> None:
    """首问改题：仅当标题仍为默认「新对话」时，取问句（strip 后）前 16 字符更新——
    问确实发生了才改；已有自定义标题（第二问起）不再覆盖。"""
    session = current_session()
    row = await dao.find_session_by_id(session, session_id)
    if row is None or row.title != AiChatSession.DEFAULT_TITLE:
        return
    row.title = question[:TITLE_MAX_CHARS]
    await dao.update_session(session, row)


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
    # 两步式：集合名含自增 id，须等 save_kb flush 回填后才可知；赋值随事务提交持久化
    kb.collection_name = derive_collection_name(kb.id)
    return kb.id


@transactional
async def page_list_kbs(page: int, size: int, name: str | None = None) -> AiPageResult[KbVo]:
    """分页查活知识库（契约 §4 修订，2026-10-18 权限轮）：id 倒序（新建居首），name 包含匹配可选过滤。

    可见性两态：管理豁免（perms 含任一 ai:kb:add/update/delete）全量；其余仅
    create_by = 本账号 或 被授权（角色 ∪ 部门祖先链 ∪ 直授用户）的库。
    identity 为 None（无请求上下文：本地脚本/未走鉴权依赖）按豁免处理，保持既有内部调用行为。

    VO 在 service 层逐字段构造（对齐 Java「Service 层统一转换、禁三方拷贝工具」口径）。
    """
    session = current_session()
    identity = current_identity()
    if identity is None or is_mgmt_exemption(identity):
        rows, total = await dao.page_list_kbs(session, page, size, name)
    else:
        visible_ids = await dao.list_visible_kb_ids(
            session, identity.user_id, identity.role_ids, identity.dept_ids)
        # account 空值归一为 ""：防 None 进入比较被渲染成 IS NULL 误中 NULL 创建行
        rows, total = await dao.page_list_kbs(
            session, page, size, name, account=identity.account or "", visible_ids=visible_ids)
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
async def _load_kb_collection(kb_id: int) -> str:
    """删库阶段一（读事务）：库存在性校验（5004），返回绑定集合名纯值。"""
    session = current_session()
    kb = await dao.find_kb_by_id(session, kb_id)
    if kb is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    return kb.collection_name


@transactional
async def _soft_delete_kb_cascade(kb_id: int) -> None:
    """删库阶段四（写事务）：chunk → 文件行 → 授权行 → KB 级联删除，同一事务；
    授权表是纯关系表（物理删除），其余三表软删。无「库下有文件阻止删除」前置约束。"""
    session = current_session()
    await dao.delete_chunks_by_kb(session, kb_id)
    await dao.delete_kb_files_by_kb(session, kb_id)
    await dao.delete_grants_by_kb(session, kb_id)
    rowcount = await dao.delete_kb_by_id(session, kb_id)
    if rowcount == 0:
        # 并发窗口兜底：校验存在后被并发删除；raise 回滚本事务级联（对方事务已删，无害）
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)


async def delete_kb(kb_id: int) -> None:
    """删除知识库（契约 §6，三存储全清编排）：MinIO 前缀清原件 → Milvus drop 该库
    集合 → MySQL 四级联（软删 + 授权物理删）。

    先外部清后软删：任一外部步失败时 MySQL 未动，原样重删即完整重试（若先软删再清
    外部，重试会被 5004 挡死只能靠对账兜底）。各步幂等：MinIO 空前缀=空操作、drop
    内部先 has 判断、软删条件写二次删 0 行兜底。MinIO 清失败裸上抛走全局 500（与
    上传链 MinIO 失败对称，可整段重试）；Milvus drop 失败转 5008。中间态收敛：drop
    成功后软删提交失败的极端窗口 = 集合没了、行还活——该库再上传时集合懒建自愈。
    """
    collection_name = await _load_kb_collection(kb_id)
    await run_in_threadpool(get_storage().remove_prefix, f"kb/{kb_id}/")
    try:
        await run_in_threadpool(vectorstore.drop_collection, collection_name)
    except Exception as e:
        logger.error("delete_kb drop collection failed, kb_id=%s collection=%s: %s: %s",
                     kb_id, collection_name, type(e).__name__, e)
        raise BusinessException(MSG_VECTOR_PURGE_FAILED, CODE_VECTOR_PURGE_FAILED) from e
    await _soft_delete_kb_cascade(kb_id)


@transactional
async def find_kb_perms(kb_id: int) -> KbPermsVo:
    """查询知识库授权（契约 2026-10-18-kb-permission-api.md §2）：先校验库存在（5004），
    再按主体类型三组分型回显；读授权清单与保存同权限（管理配置动作）。"""
    session = current_session()
    if await dao.find_kb_by_id(session, kb_id) is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    grants = await dao.list_grants_by_kb(session, kb_id)
    role_ids = [g.subject_id for g in grants
                if g.subject_type == AiKbGrant.SubjectTypeEnum.ROLE]
    dept_ids = [g.subject_id for g in grants
                if g.subject_type == AiKbGrant.SubjectTypeEnum.DEPT]
    user_ids = [g.subject_id for g in grants
                if g.subject_type == AiKbGrant.SubjectTypeEnum.USER]
    return KbPermsVo(role_ids=role_ids, dept_ids=dept_ids, user_ids=user_ids)


@transactional
async def save_kb_perms(kb_id: int, req: KbPermsSaveReq) -> None:
    """保存知识库授权（契约 §3）：先校验库存在（5004），再全量覆盖（按 kb_id 物理删
    全部旧授权行 → 批插新行，同一事务）；三数组全空/不传 = 清空授权。

    主体弱引用：不校验 role/dept/user 存在性（主数据在 cloud_system 库），
    失效授权永不命中（自愈语义）。同请求重复 id 由组装层去重（dict.fromkeys 保序）；
    并发保存同库的撞 uk 属极端窗口，事务级回滚走全局 500（契约 §3 分支无专用码）。
    """
    session = current_session()
    if await dao.find_kb_by_id(session, kb_id) is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    await dao.save_kb_grants(session, kb_id, _build_grants(req))


def _build_grants(req: KbPermsSaveReq) -> list[AiKbGrant]:
    """三组 id 保序去重后组装授权行实体（kb_id/create_by 由 dao 统一落值，
    create_time 由 DB DEFAULT 时钟生成——应用层不传时间）。"""
    grants: list[AiKbGrant] = []
    for subject_type, ids in (
        (AiKbGrant.SubjectTypeEnum.ROLE, req.role_ids),
        (AiKbGrant.SubjectTypeEnum.DEPT, req.dept_ids),
        (AiKbGrant.SubjectTypeEnum.USER, req.user_ids),
    ):
        for subject_id in dict.fromkeys(ids):
            grants.append(AiKbGrant(subject_type=subject_type.value, subject_id=subject_id))
    return grants


@transactional
async def page_list_kb_files(kb_id: int, page: int, size: int) -> AiPageResult[KbFileVo]:
    """分页列某知识库文件（契约 §5 修订，2026-10-18 权限轮）：三步校验——
    ① 库不存在（软删不可见）→ 5004；② 管理豁免 或 create_by=本账号 或授权命中 → 200；
    ③ 否则 → 5011（库存在但当前用户无权访问，与 5004 区分）。
    identity 为 None（无请求上下文）按豁免处理，与列表口径一致。
    """
    session = current_session()
    kb = await dao.find_kb_by_id(session, kb_id)
    if kb is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    identity = current_identity()
    if identity is not None and not is_mgmt_exemption(identity):
        account = identity.account or ""
        if kb.create_by != account:
            visible_ids = await dao.list_visible_kb_ids(
                session, identity.user_id, identity.role_ids, identity.dept_ids)
            if kb_id not in visible_ids:
                raise BusinessException(MSG_KB_NO_ACCESS, CODE_KB_NO_ACCESS)
    rows, total = await dao.page_list_kb_files(session, kb_id, page, size)
    vos = [KbFileVo(id=row.id, file_name=row.file_name,
                    file_split_type=row.file_split_type, create_time=row.create_time,
                    file_chunk_size=row.file_chunk_size,
                    file_chunk_overlap=row.file_chunk_overlap)
           for row in rows]
    return AiPageResult(total=total, rows=vos)


class UploadContext(NamedTuple):
    """上传前置阶段产出：目标库 id 与其绑定向量集合名（纯值出事务防惰性加载）。"""

    kb_id: int
    collection_name: str


@transactional
async def _load_upload_context(kb_id: int) -> UploadContext:
    """上传阶段一（读事务）：库存在性校验（5004），取绑定集合名纯值。"""
    session = current_session()
    kb = await dao.find_kb_by_id(session, kb_id)
    if kb is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    return UploadContext(kb_id=kb.id, collection_name=kb.collection_name)


@transactional
async def _persist_file_chunks(ctx: UploadContext, file_name: str, object_name: str,
                               parent_docs: list[Document],
                               child_docs: list[Document]) -> list[int]:
    """上传阶段三（写事务）：文件行 + 父子块行同一事务落库，返回子块行 id 清单
    （向量主键 = 子块行 id 字符串化；纯值出事务防惰性加载）。"""
    session = current_session()
    kb_file = await dao.save_kb_file(
        session, AiKnowledgeBaseFiles(
            kb_id=ctx.kb_id, file_path=object_name, file_name=file_name,
            file_chunk_count=len(child_docs), file_chunk_size=128, file_chunk_overlap=20,
            file_split_type=AiKnowledgeBaseFiles.SplitTypeEnum.PARENT_CHILD.value))
    chunk_rows = _build_chunk_rows(ctx.kb_id, kb_file.id, parent_docs) \
        + _build_chunk_rows(ctx.kb_id, kb_file.id, child_docs)
    saved = await dao.save_chunks(session, chunk_rows)
    return [row.id for row in saved
            if row.chunk_type == AiKbChunk.ChunkTypeEnum.CHILD]


async def upload_file_by_kb(kb_id: int, file: UploadFile) -> int:
    """上传文件到知识库（编排方法，不开事务——事务边界在两个阶段方法上）：
    ① 读上下文（5004）→ ② 解析分片 + MinIO 写原件（事务外，失败零 DB 写）→
    ③ 文件行+块行落库 → ④ 子块向量写库行绑定的集合（首传懒建集合）。

    阶段④失败转 5014：MySQL 已提交，形态收敛为「文件已登记无向量」——msg 刻意
    引导对该文件执行重嵌入（按 file_id 幂等置换）而非重试上传（重试会登记第二个
    文件行、旧行悬死）。向量主键 = 子块行 id 字符串化；Milvus 实例名取行内绑定列，
    构造期传入（构造后属性赋值不触发字段表重载，见 vectorstore.get_milvus 注释）。
    """
    ctx = await _load_upload_context(kb_id)
    file_data: bytes = file.file.read()
    file_content = pdfmuse.to_text(file_data)
    parent_docs, child_docs = chunking.parent_child_split_file(
        file_content=file_content, child_chunk_size=128, child_chunk_overlap=20,
        parent_chunk_size=PARENT_CHUNK_SIZE_FIXED)

    # 文件原件入 MinIO：对象键=kb/{kb_id}/{uuid}{后缀}，即文件表 file_path 的语义。
    # SDK 为同步客户端，run_in_threadpool 包装防阻塞事件循环；失败零 DB 写（语义保持）
    storage = get_storage()
    suffix = Path(file.filename or "").suffix
    object_name = f"kb/{ctx.kb_id}/{uuid.uuid4().hex}{suffix}"
    await run_in_threadpool(storage.ensure_bucket)
    await run_in_threadpool(
        storage.put_bytes, object_name, file_data,
        file.content_type or "application/octet-stream")

    child_ids = await _persist_file_chunks(ctx, file.filename, object_name,
                                           parent_docs, child_docs)
    try:
        milvus: Milvus = vectorstore.get_milvus(ctx.collection_name)
        milvus.add_documents(child_docs, ids=[str(i) for i in child_ids])
    except Exception as e:
        logger.error("upload write vectors failed, kb_id=%s collection=%s: %s: %s",
                     ctx.kb_id, ctx.collection_name, type(e).__name__, e)
        raise BusinessException(MSG_VECTOR_WRITE_FAILED, CODE_VECTOR_WRITE_FAILED) from e
    return ctx.kb_id


class ReembeddingContext(NamedTuple):
    """重嵌入阶段一产出的纯值上下文。

    不返回 ORM 实体：阶段一事务在方法返回时提交关闭，实体上的惰性加载随之不可用；
    纯值在事务外传递无陷阱。
    """

    file_id: int
    kb_id: int
    collection_name: str
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
    # 集合名取库行绑定列（文件行活 ⇒ 库必活：删库时文件行已级联软删、5005 在先，
    # 无需再设 5004 分支——极端数据不一致态由全局 500 兜底）
    kb_row = await dao.find_kb_by_id(session, file_row.kb_id)
    return ReembeddingContext(file_id=req.file_id, kb_id=file_row.kb_id,
                              collection_name=kb_row.collection_name,
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
                  old_child_ids: Sequence[int], file_id: int,
                  collection_name: str) -> None:
    """向量置换（MySQL 事务提交后执行）：先写新后删旧，集合取库行绑定列。

    先写后删的中间失败形态是新旧并存而非检索空洞（旧向量命中后被软删过滤，不产脏内容）；
    删除清单 = 历代子块 id（含软删行），让原样重试具备完全收敛性。Milvus 实例同步调用
    与上传链同形态。
    """
    milvus: Milvus = vectorstore.get_milvus(collection_name)
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
    _swap_vectors(child_docs, new_child_ids, ctx.old_child_ids, req.file_id,
                  ctx.collection_name)
