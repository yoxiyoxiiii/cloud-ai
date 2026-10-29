<script setup lang="ts">
/**
 * 智能体对话面板（契约 2026-10-29-agent-platform-api §3.2 流式运行 + §4 会话/回显）：
 * 消息区 + 输入区，流式走 utils/sse.ts 的 ssePost（帧协议逐字沿 RAG 对话母契约，
 * sse.ts 属冻结红线零改动）。双消费形态：
 * - 对话使用页（/agent/chat）：父组件持会话侧栏，sessionId 随激活会话切换（消息按会话缓存）
 * - 管理页试跑弹窗（embedded）：单会话形态，sessionId 缺省时首问前自动建会话
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ssePost } from '../../../../utils/sse'
import { useStickToBottom } from '../../../../composables/useStickToBottom'
import { createAgentSession, listAgentSessionMessages } from '../../../../api/agent'
import type { AgentSessionVo } from '../../../../types/api'

interface Props {
  /** 目标智能体 id */
  agentId: number
  /** 激活会话 id（0 = 无会话：首问前自动创建——试跑与对话页兜底共用该路径） */
  sessionId: number
  /** 嵌入形态（管理页试跑弹窗）：高度收紧适配弹窗，对话行为与页内一致 */
  embedded?: boolean
}

const props = withDefaults(defineProps<Props>(), { embedded: false })

const emit = defineEmits<{
  /** 面板自建会话成功（对话页据此置顶侧栏并激活；试跑弹窗不消费） */
  sessionCreated: [vo: AgentSessionVo]
  /** 一次提问已发出（父组件据此乐观更新会话标题：新对话 → 问句前 16 字） */
  sent: [question: string]
}>()

interface LocalMsg {
  id: number
  role: 'user' | 'assistant'
  content: string
}

/** 消息按会话分置缓存——切换会话命中免拉、未命中拉 §4 回显 */
const msgCache = reactive(new Map<number, LocalMsg[]>())
const input = ref('')
const streaming = ref(false)
let abort: AbortController | null = null
let seq = 0

/**
 * 渲染用会话 id：父组件切换会话时随 props 走；面板自建会话（试跑弹窗 embedded /
 * 对话页未选会话直接输入的兜底）后转跟新建 sid——props.sessionId 恒 0 的形态下，
 * 若仍按 props 取缓存则消息永远渲染不出来
 */
const activeSid = ref(props.sessionId)

/** 激活会话消息（回显未落缓存前按空数组渲染） */
const activeMessages = computed(() => msgCache.get(activeSid.value) ?? [])
/** 空会话引导文案（无任何消息时显示，代替空白） */
const isEmpty = computed(() => activeMessages.value.length === 0)
/** 当前会话最后一条内容（空 = 还没收到首 token，显示"思考中"） */
const lastContent = computed(() => activeMessages.value[activeMessages.value.length - 1]?.content ?? '')

const listEl = ref<HTMLElement>()
const { stick, scrollToBottom } = useStickToBottom(listEl)

/** 拉会话回显并落缓存；LocalMsg.id 用索引合成（仅作渲染 key，非服务端字段） */
async function loadSessionMessages(sessionId: number): Promise<void> {
  try {
    const vo = await listAgentSessionMessages(sessionId)
    // 回填竞态守卫：拉取期间用户已在本会话发出消息（send 已建缓存）则丢弃本次陈旧结果
    const existing = msgCache.get(sessionId)
    if (existing && existing.length > 0) {
      return
    }
    msgCache.set(sessionId, vo.messages.map((m, i) => ({ id: i + 1, role: m.role, content: m.content })))
  } catch {
    // 拦截器已统一 toast（5019/5020 等）；缓存缺席按空数组渲染
  }
}

// 切换会话：缓存命中直接用，未命中拉回显（父组件改 sessionId 后由此 watch 接手）
watch(
  () => props.sessionId,
  (id) => {
    activeSid.value = id
    if (id && !msgCache.has(id)) {
      void loadSessionMessages(id)
    }
  },
  { immediate: true },
)

async function send(): Promise<void> {
  const q = input.value.trim()
  if (!q || streaming.value) {
    return
  }
  // 无激活会话（试跑弹窗 / 对话页未建会话直接输入的兜底）：先建会话再发
  let sid = props.sessionId
  if (!sid) {
    try {
      const vo = await createAgentSession(props.agentId)
      sid = vo.id
      // 自建会话后渲染源转跟新 sid（见 activeSid 注释），随后消息才会出现在面板上
      activeSid.value = sid
      msgCache.set(sid, [])
      emit('sessionCreated', vo)
    } catch {
      return // 创建失败（拦截器已 toast，如智能体停用 5015），本次发送终止
    }
  }
  if (!msgCache.has(sid)) {
    msgCache.set(sid, [])
  }
  const msgs = msgCache.get(sid)!
  input.value = ''
  msgs.push({ id: ++seq, role: 'user', content: q })
  emit('sent', q)
  msgs.push({ id: ++seq, role: 'assistant', content: '' })
  const aiIdx = msgs.length - 1

  streaming.value = true
  abort = new AbortController()
  try {
    // 已知边界（契约 §3.2 记档）：流开始前的拒绝（403/1001/5015/5019/5020）为 HTTP 200 +
    // R JSON 非 SSE 帧，sse.ts 定死按完成兜底 → 表现为空气泡静默；sse.ts 属契约冻结红线，本组件不特殊处理
    await ssePost(
      `/api/ai/agent/${props.agentId}/run/stream`,
      { session_id: sid, question: q },
      {
        onToken: (t) => {
          msgs[aiIdx].content += t
          if (stick.value) {
            nextTick(() => scrollToBottom(false))
          }
        },
        onDone: () => undefined, // 服务端已落档，不主动拉回显（切换/刷新时经回显端点重建）
        onError: (msg) => {
          if (!msgs[aiIdx].content) {
            msgs[aiIdx].content = `（${msg}）`
          }
        },
      },
      abort.signal,
    )
  } catch {
    // 用户主动停止（AbortError）：保留已生成部分（服务端收口落半截答）
  } finally {
    streaming.value = false
    abort = null
  }
}

function stop(): void {
  abort?.abort()
}
</script>

<template>
  <div :class="['agent-chat-panel', { embedded: props.embedded }]">
    <div ref="listEl" class="msg-list">
      <!-- 空会话引导：无消息时占位（试跑与新建会话共用） -->
      <div v-if="isEmpty && !streaming" class="empty-hint">输入问题，开始与智能体对话</div>
      <div v-for="m in activeMessages" :key="m.id" :class="['msg-row', m.role]">
        <!-- 回答保留换行与缩进（pre-wrap）；不做 markdown 渲染——智能体回答按纯文本消费 -->
        <div class="bubble">{{ m.content }}</div>
      </div>
      <div v-if="streaming && !lastContent" class="msg-row assistant">
        <div class="bubble">思考中…</div>
      </div>
    </div>
    <div class="input-bar">
      <el-input
        v-model="input"
        type="textarea"
        :rows="props.embedded ? 2 : 3"
        resize="none"
        placeholder="输入问题，Enter 发送 / Shift+Enter 换行"
        :disabled="streaming"
        @keydown.enter.exact.prevent="send"
      />
      <el-button v-if="!streaming" type="primary" :disabled="!input.trim()" @click="send">发送</el-button>
      <el-button v-else type="danger" plain @click="stop">停止生成</el-button>
    </div>
  </div>
</template>

<style scoped>
/* 面板骨架：占满父容器，消息区弹性伸展 / 输入区贴底；embedded 形态父容器高度由弹窗给定 */
.agent-chat-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.msg-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
.empty-hint {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}
.msg-row {
  display: flex;
  margin-bottom: 12px;
}
.msg-row:last-child {
  margin-bottom: 0;
}
.msg-row.user {
  justify-content: flex-end;
}
/* 气泡：限宽防长文撑满整行，pre-wrap 保留回答里的换行 */
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 8px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}
.msg-row.user .bubble {
  background: #409eff;
  color: #fff;
}
.msg-row.assistant .bubble {
  background: #f5f7fa;
}
/* 输入区：与消息区同款白底卡片，输入框与按钮横排 */
.input-bar {
  flex-shrink: 0;
  display: flex;
  gap: 12px;
  align-items: flex-end;
  padding: 12px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
</style>
