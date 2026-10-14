"""RAG 域业务编排：router → service → 管线组件（分层依赖单向）。

AI 对话轮（2026-10-13）查询链首个实例（无检索的退化 RAG = 纯生成，spec §3）：
service 负责历史截断（尾 history_limit 条，D10）、LLM 调用编排与异常转译——
LLM 异常 catch 必须 log.error 记根因后转 5001（规范第 3 条；msg 不含 key 与上游原文），
工厂 5002（未配置）透传不重复转译；帧收口在 router（D12）。
"""

import logging
from collections.abc import AsyncIterator

from sqlalchemy.exc import IntegrityError

from cloud_base_ai.config import get_settings
from cloud_base_ai.db.tx import current_session, transactional
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.models.model_factory import get_chat_model
from cloud_base_ai.rag import dao
from cloud_base_ai.rag.entities import AiKnowledgeBase
from cloud_base_ai.rag.generation import to_lc_messages
from cloud_base_ai.rag.schemas import RagChatAnswerVo, RagChatDto, CreateKBReq

logger = logging.getLogger(__name__)

CODE_LLM_CALL_FAILED: int = 5001
MSG_LLM_CALL_FAILED: str = "LLM 调用失败，请稍后重试"
CODE_KB_NAME_EXISTS: int = 5003
MSG_KB_NAME_EXISTS: str = "知识库名称已存在"


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
    session = current_session()
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