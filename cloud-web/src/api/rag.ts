/**
 * AI 接口（cloud-base-ai，网关前缀 /ai）：
 * 对话 = 契约 2026-10-13-ai-rag-chat-api；知识库管理 = 契约 2026-10-14-ai-kb-api。
 * 流式端点 /ai/rag/chat/stream 不在本文件——axios/XHR 无法流式读取，见 utils/sse.ts。
 * AI 域出参口径（kb 契约 §1）：字段 snake_case、id/total 为 JSON number（非 Java 域
 * Long→String）、分页查询参数名 page/size（非 pageNum/pageSize）
 */
import { request } from '../utils/request'
import type {
  AiPageResult,
  CreateKbPayload,
  KbFileVo,
  KbPageQuery,
  KbVo,
  RagChatAnswerVo,
  RagChatMessage,
  ReembedPayload,
  UpdateKbPayload,
} from '../types/api'

/** AI 对话·非流式（契约 2026-10-13-ai-rag-chat-api §1，perms ai:chat:list）：LLM 耗时远超默认 15s，单请求放大超时 */
export function chatRag(messages: RagChatMessage[]): Promise<RagChatAnswerVo> {
  return request<RagChatAnswerVo>({
    url: '/ai/rag/chat',
    method: 'post',
    data: { messages },
    timeout: 120000,
  })
}

/**
 * 知识库分页列表（kb 契约 §4，perms ai:kb:list）：query 参数 page/size/name（AI 域参数名）；
 * id 倒序（新建库居首）；name 仅非空时携带（空串与不传同为不过滤，契约 §4）
 */
export function pageKbs(query: KbPageQuery): Promise<AiPageResult<KbVo>> {
  return request<AiPageResult<KbVo>>({
    url: '/ai/rag/kb/list',
    method: 'get',
    params: { page: query.page, size: query.size, name: query.name || undefined },
  })
}

/** 编辑知识库（kb 契约 §5，perms ai:kb:update）：name+description 全量覆盖（双必填）；改名为他库现有名得 5003、目标不存在得 5004；data 恒 null */
export function updateKb(kbId: number, payload: UpdateKbPayload): Promise<null> {
  return request<null>({ url: `/ai/rag/kb/${kbId}`, method: 'put', data: payload })
}

/** 删除知识库（kb 契约 §6，perms ai:kb:delete）：三表级联软删（KB/文件/chunk 同一事务）；目标不存在（含二次删）得 5004；data 恒 null */
export function deleteKb(kbId: number): Promise<null> {
  return request<null>({ url: `/ai/rag/kb/${kbId}`, method: 'delete' })
}

/** 新建知识库（kb 契约 §2，perms ai:kb:add）：返回新知识库 id（JSON number）；活记录重名或墓碑占键得 5003 */
export function createKb(payload: CreateKbPayload): Promise<number> {
  return request<number>({ url: '/ai/rag/kb', method: 'post', data: payload })
}

/**
 * 上传知识库文件（kb 契约 §3，perms ai:kb:add）：multipart——FormData 交 axios 自动设
 * Content-Type 与 boundary（同 deployDefinition 先例）；kb_id 为 query 参数；仅支持 PDF/DOCX
 * （其他类型后端 500）；返回 data = kb_id（非文件 id）；分片+向量写入为长操作，
 * timeout 放大至 120s（沿 chatRag 先例）
 */
export function uploadKbFile(kbId: number, file: File): Promise<number> {
  const formData = new FormData()
  formData.append('file', file)
  return request<number>({
    url: '/ai/rag/kb/upload',
    method: 'post',
    params: { kb_id: kbId },
    data: formData,
    timeout: 120000,
  })
}

/** 知识库文件分页入参（kb 契约 §7）：AI 域参数名 page/size（非 Java 域 pageNum/pageSize）；越界后端钳制不报错 */
export interface KbFilePageQuery {
  /** 页码（1 起） */
  page: number
  /** 每页条数（1..200） */
  size: number
}

/** 知识库文件分页（kb 契约 §7，perms ai:kb:list）：先校验库存在（5004）再分页，文件 id 倒序 */
export function pageKbFiles(kbId: number, query: KbFilePageQuery): Promise<AiPageResult<KbFileVo>> {
  return request<AiPageResult<KbFileVo>>({
    url: `/ai/rag/kb/${kbId}/files`,
    method: 'get',
    params: query,
  })
}

/**
 * 文件重新向量化（kb 契约 §8，perms ai:kb:update）：body JSON，data 恒 null；
 * 同步单文件「分片→落库→向量化」置换旧代，时长与上传同量级——timeout 放大至 120s
 * （沿 uploadKbFile/chatRag 先例）；错误分支 5005 文件不存在 / 5006 文件无原件 /
 * 5007 原件读取失败（以上零副作用）/ 5008 向量清理失败（重试即收敛）均拦截器统一 toast
 */
export function reembedKbFile(payload: ReembedPayload): Promise<null> {
  return request<null>({
    url: '/ai/rag/kb/reembedding',
    method: 'post',
    data: payload,
    timeout: 120000,
  })
}
