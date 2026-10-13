/**
 * AI 对话接口（契约 2026-10-13-ai-rag-chat-api，cloud-base-ai，网关前缀 /ai）
 * 流式端点 /ai/rag/chat/stream 不在本文件——axios/XHR 无法流式读取，见 utils/sse.ts
 */
import { request } from '../utils/request'
import type { RagChatAnswerVo, RagChatMessage } from '../types/api'

/** AI 对话·非流式（契约 §1，perms ai:chat:list）：LLM 耗时远超默认 15s，单请求放大超时 */
export function chatRag(messages: RagChatMessage[]): Promise<RagChatAnswerVo> {
  return request<RagChatAnswerVo>({
    url: '/ai/rag/chat',
    method: 'post',
    data: { messages },
    timeout: 120000,
  })
}
