"""RAG 域 API 入口（对话契约 2026-10-13-ai-rag-chat-api.md + 知识库契约 2026-10-14-ai-kb-api.md）。

- 鉴权：路由级依赖 require_perms——X-User-Perms 缺失经全局 handler
  转 HTTP 200 + R{code:403} JSON（非 SSE 帧，D6 层次差异记档）；
- 知识库四权限分立挂四个 router：创建+上传 ai:kb:add / 查询 ai:kb:list /
  编辑 ai:kb:update / 删除 ai:kb:delete——路由级依赖保证挂进来的新端点自动受保护；
- 流式端点对 R<T>/DTO 规范显式豁免（spec D5）：StreamingResponse 帧协议不包 R、无统一 VO；
  帧序列化 json.dumps(payload, ensure_ascii=False)——帧内可读中文，JSON 转义保证
  content 内换行不破坏帧定界；
- 绝不裸断流（D12）：generator catch 全量，任何异常（含首 token 前 5002/5001）都以
  msg 帧 + [DONE] 收口。
"""

import json
import logging

from fastapi import APIRouter, Depends, Query, UploadFile, File
from fastapi.responses import StreamingResponse

from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.rag import service
from cloud_base_ai.rag.schemas import (
    AiPageResult,
    CreateKBReq,
    KbFileVo,
    KbVo,
    RagChatAnswerVo,
    RagChatDto,
    UpdateKBReq,
)
from cloud_base_ai.rag.service import chat_answer, stream_chat_answer
from cloud_base_ai.response import R
from cloud_base_ai.security import require_perms

logger = logging.getLogger(__name__)

DONE_FRAME: str = "data: [DONE]\n\n"

router = APIRouter(prefix="/rag/chat", dependencies=[Depends(require_perms("ai:chat:list"))])
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
async def chat(dto: RagChatDto) -> R[RagChatAnswerVo]:
    """非流式对话（契约 §2）：ainvoke 一次成型，R<RagChatAnswerVo> 两行式返回。"""
    vo = await chat_answer(dto)
    return R.ok(vo)


@router.post("/stream")
async def chat_stream(dto: RagChatDto) -> StreamingResponse:
    """流式对话（契约 §3）：SSE token 帧 N 个 + [DONE] 终帧；异常 = msg 帧 + [DONE]。"""

    async def event_gen():
        try:
            async for token in stream_chat_answer(dto):
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
    """
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


@kb_delete_router.delete("/{kb_id}")
async def delete_kb(kb_id: int) -> R[None]:
    """删除知识库（契约 §6）：KB/文件/chunk 三表级联软删，data 恒 null。"""
    await service.delete_kb(kb_id)
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