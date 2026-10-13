/**
 * SSE 流式通道（契约 §2）：POST + Authorization header——EventSource 做不到（不能带
 * 自定义 header 且仅 GET），axios/XHR 无法增量读取响应体，故独立于 request.ts。
 * 401 语义与 request.ts 对齐：清态带 redirect 跳登录（网关对无效 token 回真实 401）。
 */
import { START_LOCATION } from 'vue-router'
import router from '../router'
import { clearAuth, getAuth } from './storage'

export interface SseCallbacks {
  onToken: (text: string) => void
  onDone: () => void
  onError: (msg: string) => void
}

function handle401(): void {
  clearAuth()
  if (router.currentRoute.value === START_LOCATION) {
    return
  }
  if (router.currentRoute.value.path !== '/login') {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

export async function ssePost(
  url: string,
  body: unknown,
  cb: SseCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const auth = getAuth()
  let resp: Response
  try {
    resp = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(auth?.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
      },
      body: JSON.stringify(body),
      signal,
    })
  } catch {
    cb.onError('网络异常')   // 用户 abort 的 AbortError 由调用方 catch，不会到这
    return
  }
  if (resp.status === 401) {
    handle401()
    cb.onError('登录已失效')
    return
  }
  if (!resp.ok || !resp.body) {
    cb.onError(`请求失败（HTTP ${resp.status}）`)   // 503 = cloud-base-ai 未启动
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 帧以空行分隔；一个网络 chunk 可能含多帧或半帧，缓冲区循环切分
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const dataLine = frame.split('\n').find((l) => l.startsWith('data:'))
      if (!dataLine) continue
      const payload = dataLine.slice(5).trim()
      if (payload === '[DONE]') {
        await reader.cancel()
        cb.onDone()
        return
      }
      try {
        const evt = JSON.parse(payload) as { content?: string; msg?: string }
        if (typeof evt.content === 'string') {
          cb.onToken(evt.content)
        } else if (evt.msg) {
          cb.onError(evt.msg)
        }
      } catch {
        // 半截 JSON 容忍跳过
      }
    }
  }
  cb.onDone()   // 服务端断流未发 [DONE]：按完成兜底
}
