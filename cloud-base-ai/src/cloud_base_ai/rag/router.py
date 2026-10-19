"""RAG 域 API 入口（对话契约 2026-10-13-ai-rag-chat-api.md + 会话契约 2026-10-19-ai-chat-session-api.md
+ 知识库契约 2026-10-14-ai-kb-api.md）。

- 鉴权：路由级依赖 require_perms——X-User-Perms 缺失经全局 handler
  转 HTTP 200 + R{code:403} JSON（非 SSE 帧，层次差异沿母契约 §3 记档）；
- 知识库四权限分立挂四个 router：创建+上传 ai:kb:add / 查询 ai:kb:list /
  编辑 ai:kb:update / 删除 ai:kb:delete——路由级依赖保证挂进来的新端点自动受保护；
- 会话四端点复用 ai:chat:list（会话是持对话权限用户的纯私有从属资源，契约 §1）；
- 流式端点对 R<T>/DTO 规范显式豁免（母契约记档）：StreamingResponse 帧协议不包 R、无统一 VO；
  帧序列化 json.dumps(payload, ensure_ascii=False)——帧内可读中文，JSON 转义保证
  content 内换行不破坏帧定界；
- 绝不裸断流：generator catch 全量，流开始后的异常（5002/5001）以 msg 帧 + [DONE] 收口；
  流开始前的拒绝（403/1001/5012/5013）发生在 StreamingResponse 构造之前——prepare_chat
  先行调用，错误形态是 R JSON 而非 SSE 帧（会话契约 §6 SSE 错误层次）。
"""

import json
import logging
from contextlib import aclosing

from fastapi import APIRouter, Depends, Query, UploadFile, File
from fastapi.responses import StreamingResponse

from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import service
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
    ReembeddingReq,
    SessionMessagesVo,
    UpdateKBReq,
)
from cloud_base_ai.rag.service import chat_answer, prepare_chat, stream_chat_answer
from cloud_base_ai.response import R
from cloud_base_ai.security import require_perms

logger = logging.getLogger(__name__)

DONE_FRAME: str = "data: [DONE]\n\n"

router = APIRouter(prefix="/rag/chat", dependencies=[Depends(require_perms("ai:chat:list"))])
# 会话四端点独立 router（契约 §2-§5）：全端点复用 ai:chat:list，路由级依赖防线同上。
session_router = APIRouter(prefix="/rag/chat/sessions",
                           dependencies=[Depends(require_perms("ai:chat:list"))])
kb_router = APIRouter(prefix="/rag/kb", dependencies=[Depends(require_perms("ai:kb:add"))])
# 读端点独立 router：权限与写端点不同（查询 ai:kb:list / 创建+上传 ai:kb:add），
# 路由级依赖保证今后挂进来的新端点自动受保护（漏写依赖即放行的防线）。
kb_read_router = APIRouter(prefix="/rag/kb", dependencies=[Depends(require_perms("ai:kb:list"))])
# 编辑/删除各挂独立 router：权限按动作分立（ai:kb:update / ai:kb:delete），
# 路由级依赖防线延续——挂进 router 的新端点自动受保护。
kb_update_router = APIRouter(prefix="/rag/kb", dependencies=[Depends(require_perms("ai:kb:update"))])
kb_delete_router = APIRouter(prefix="/rag/kb", dependencies=[Depends(require_perms("ai:kb:delete"))])


def _sse(payload: dict[str, str]) -> str:
    """单帧序列化：ensure_ascii=False 保帧内中文可读（UTF-8 输出）。"""
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


@router.post("")
async def chat(req: ChatSendReq) -> R[RagChatAnswerVo]:
    """非流式对话（契约 §6）：session_id+question 入参，ainvoke 一次成型，
    R<RagChatAnswerVo> 两行式返回。"""
    vo = await chat_answer(req)
    return R.ok(vo)


@router.post("/stream")
async def chat_stream(req: ChatSendReq) -> StreamingResponse:
    """流式对话（契约 §6）：SSE token 帧 N 个 + [DONE] 终帧；异常 = msg 帧 + [DONE]。

    prepare_chat 先于 StreamingResponse 构造——5012/5013（及入参 1001）以 R JSON
    拒绝而非 SSE 帧。event_gen 显式 aclosing 包住 service generator：客户端断开
    （用户停止生成）时关闭传播到 service 层，其 finally 落半截答。
    """
    prepared = await prepare_chat(req)

    async def event_gen():
        # aclosing 保证消费方（客户端）提前关闭 event_gen 时，service generator
        # 也被显式关闭——其 finally 落档路径因此触发（半截答照落）。
        async with aclosing(stream_chat_answer(prepared)) as gen:
            try:
                async for token in gen:
                    yield _sse({"content": token})
                yield DONE_FRAME
            except BusinessException as e:
                yield _sse({"msg": e.msg})
                yield DONE_FRAME
            except Exception:
                logger.exception("SSE stream unexpected error")
                yield _sse({"msg": "系统异常，请稍后重试"})
                yield DONE_FRAME

    return StreamingResponse(event_gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache"})


@session_router.post("")
async def create_session() -> R[ChatSessionVo]:
    """创建会话（契约 §2）：无 body，归属取 X-User-Id；标题默认「新对话」。"""
    vo = await service.create_session()
    return R.ok(vo)


@session_router.get("/list")
async def page_list_sessions(
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，钳制 1..200"),
) -> R[AiPageResult[ChatSessionVo]]:
    """当前用户会话分页（契约 §3）：仅本人、id 倒序（新建居首）、软删不可见。"""
    result = await service.page_list_sessions(page, size)
    return R.ok(result)


@session_router.delete("/{session_id}")
async def delete_session(session_id: int) -> R[None]:
    """删除会话（契约 §4）：Redis 消息键物理删 + MySQL 软删双清，data 恒 null。"""
    await service.delete_session(session_id)
    return R.ok(None)


@session_router.get("/{session_id}/messages")
async def list_session_messages(session_id: int) -> R[SessionMessagesVo]:
    """消息回显（契约 §5）：时间升序全量（≤100 条），空会话 messages 空数组。"""
    vo = await service.list_session_messages(session_id)
    return R.ok(vo)


@kb_router.post("")
async def create_kb(req: CreateKBReq) -> R[int]:
    """
    创建一个知识库
    :param req: 请求对象
    :return: 返回ID
    """
    kb_id: int = await service.create_kb(req)
    return R.ok(kb_id)


@kb_router.post("/upload")
async def upload_file_by_kb(
        kb_id: int = Query(..., description="目标知识库ID"),
        file: UploadFile = File(..., description="待上传的文件，单文件")
) -> R[int]:
    """i
    知识库文件上传：
    1. 查询当前知识库
    2. 对上传的文件设置分片策略（递归分片/父子文档分片）
    3. 对上传的文件进行分片
       3.1.分片内容存储到数据库中
    4. 保存文件到文件服务器
    :param file: 上传的文件
    :param kb_id: 目标知识库ID
    :return: 返回ID
    """
    kb_id: int = await service.upload_file_by_kb(kb_id, file)
    return R.ok(kb_id)


@kb_read_router.get("/list")
async def page_list_kbs(
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，越界钳制到 1..200"),
        name: str | None = Query(default=None, max_length=50,
                                 description="名称包含匹配；不传或空串=不过滤"),
) -> R[AiPageResult[KbVo]]:
    """知识库分页列表（契约 §4）：id 倒序（新建居首），软删不可见，total 为 JSON number。"""
    result = await service.page_list_kbs(page, size, name)
    return R.ok(result)


@kb_update_router.put("/{kb_id}")
async def update_kb(kb_id: int, req: UpdateKBReq) -> R[None]:
    """编辑知识库（契约 §5）：name+description 全量覆盖语义，data 恒 null。"""
    await service.update_kb(kb_id, req)
    return R.ok(None)


@kb_update_router.post("/reembedding")
async def reembedding(req: ReembeddingReq) -> R[None]:
    """重新向量化单文件（契约 §8）：分片参数可选（不传=沿用该文件快照），同步完成置换，
    data 恒 null；权限与编辑同用 ai:kb:update。"""
    await service.reembedding(req)
    return R.ok(None)


@kb_delete_router.delete("/{kb_id}")
async def delete_kb(kb_id: int) -> R[None]:
    """删除知识库（契约 §6）：KB/文件/chunk 三表级联软删，data 恒 null。"""
    await service.delete_kb(kb_id)
    return R.ok(None)


@kb_update_router.get("/{kb_id}/perms")
async def find_kb_perms(kb_id: int) -> R[KbPermsVo]:
    """查询知识库授权（契约 2026-10-18-kb-permission-api.md §2）：按主体类型三组回显，
    无授权组空数组；权限复用 ai:kb:update（读授权清单与保存同权限，不开放给 list-only）。"""
    vo = await service.find_kb_perms(kb_id)
    return R.ok(vo)


@kb_update_router.put("/{kb_id}/perms")
async def save_kb_perms(kb_id: int, req: KbPermsSaveReq) -> R[None]:
    """保存知识库授权（契约 2026-10-18-kb-permission-api.md §3）：三组全量覆盖
    （全空/不传 = 清空授权），重复 id 服务端静默去重，data 恒 null。"""
    await service.save_kb_perms(kb_id, req)
    return R.ok(None)


@kb_read_router.get("/{kb_id}/files")
async def page_list_kb_files(
        kb_id: int,
        page: int = Query(default=1, description="页码，从 1 起，越界钳制到 1"),
        size: int = Query(default=10, description="每页条数，越界钳制到 1..200"),
) -> R[AiPageResult[KbFileVo]]:
    """知识库文件分页（契约 §5）：先校验库存在（5004），id 倒序，软删不可见。"""
    result = await service.page_list_kb_files(kb_id, page, size)
    return R.ok(result)