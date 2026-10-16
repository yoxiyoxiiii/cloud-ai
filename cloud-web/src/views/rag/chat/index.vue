<template>
  <div class="rag-chat">
    <!-- 左：会话列表（首版本地态，持久化见迭代方向） -->
    <aside class="sessions">
      <div class="sessions-title">对话列表</div>
      <el-button type="primary" class="new-btn" @click="newSession">新建对话</el-button>
      <div
        v-for="s in sessions"
        :key="s.id"
        :class="['session-item', { active: s.id === activeId }]"
        @click="activeId = s.id"
      >
        <span class="title">{{ s.title }}</span>
        <el-icon class="del" @click.stop="removeSession(s.id)"><Delete /></el-icon>
      </div>
    </aside>

    <!-- 右：消息区 + 输入区 -->
    <section class="main">
      <div v-if="!active" class="empty">输入问题，开始知识库对话</div>
      <template v-else>
        <div ref="listEl" class="msg-list">
          <div v-for="m in active.messages" :key="m.id" :class="['msg', m.role]">
            <div class="bubble">
              <MdRenderer v-if="m.role === 'assistant'" :content="m.content" />
              <template v-else>{{ m.content }}</template>
            </div>
          </div>
          <div v-if="streaming && !lastContent" class="msg assistant">
            <div class="bubble">思考中…</div>
          </div>
        </div>
        <div class="input-bar">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="输入问题，Enter 发送 / Shift+Enter 换行"
            :disabled="streaming"
            @keydown.enter.exact.prevent="send"
          />
          <el-button v-if="!streaming" type="primary" :disabled="!input.trim()" @click="send">
            发送
          </el-button>
          <el-button v-else type="danger" plain @click="stop">停止生成</el-button>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import MdRenderer from './components/MdRenderer.vue'
import { ssePost } from '../../../utils/sse'
import { useStickToBottom } from '../../../composables/useStickToBottom'
import type { RagChatMessage } from '../../../types/api'

/** = route.name（pathToRouteName('/rag/chat')），keep-alive include 依赖，不可省 */
defineOptions({ name: 'RagChat' })

interface LocalMsg {
  id: number
  role: 'user' | 'assistant'
  content: string
}
interface LocalSession {
  id: number
  title: string
  messages: LocalMsg[]
}

// 本地前端存储消息
const sessions = ref<LocalSession[]>([])
const activeId = ref(0)
const input = ref('')
const streaming = ref(false)
let abort: AbortController | null = null
let seq = 0

const active = computed(() => sessions.value.find((s) => s.id === activeId.value))
/** 当前会话最后一条内容（空 = 还没收到首 token，显示“思考中”） */
const lastContent = computed(() => active.value?.messages[-1]?.content ?? '')

const listEl = ref<HTMLElement>()
const { stick, scrollToBottom } = useStickToBottom(listEl)

// 新创建一个会话
function newSession(): void {
  const s: LocalSession = { id: Date.now(), title: '新对话', messages: [] }
  sessions.value.unshift(s)
  activeId.value = s.id
}

function removeSession(id: number): void {
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) {
    activeId.value = sessions.value[0]?.id ?? 0
  }
}

async function send(): Promise<void> {
  const q = input.value.trim()
  if (!q || streaming.value) {
    return
  }
  if (!active.value) {
    newSession()
  }
  const s = active.value!
  input.value = ''
  s.messages.push({ id: ++seq, role: 'user', content: q })
  if (s.title === '新对话') {
    s.title = q.slice(0, 16)
  }
  s.messages.push({ id: ++seq, role: 'assistant', content: '' })
  const aiIdx = s.messages.length - 1

  streaming.value = true
  abort = new AbortController()
  // 上送历史 = 会话内全部已定消息（含刚推入的 user；流式中途切换会话不影响本流目标 s）
  const history: RagChatMessage[] = s.messages
    .slice(0, aiIdx)
    .map((m) => ({ role: m.role, content: m.content }))

  try {
    await ssePost(
      '/api/ai/rag/chat/stream',
      { messages: history },
      {
        onToken: (t) => {
          s.messages[aiIdx].content += t
          if (stick.value) {
            nextTick(() => scrollToBottom(false))
          }
        },
        onDone: () => undefined,
        onError: (msg) => {
          if (!s.messages[aiIdx].content) {
            s.messages[aiIdx].content = `（${msg}）`
          }
        },
      },
      abort.signal,
    )
  } catch {
    // 用户主动停止（AbortError）：保留已生成部分
  } finally {
    streaming.value = false
    abort = null
  }
}

function stop(): void {
  abort?.abort()
}
</script>

<style scoped>
.rag-chat {
  display: flex;
  height: 100%;
  gap: 12px;
}
/* 左：会话列表——灰底面板，与右侧白底消息卡片形成视觉分区 */
.sessions {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
  overflow-y: auto;
}
.sessions-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  padding: 0 0 8px 2px;
}
.new-btn {
  margin-bottom: 8px;
}
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
}
/* 灰底上的色阶：hover 深一档，活动色用 primary 浅底（#ecf5ff 对比不足改 #d9ecff） */
.session-item:hover {
  background: #ebedf0;
}
.session-item.active {
  background: #d9ecff;
  color: #409eff;
}
.session-item .title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-item .del {
  flex-shrink: 0;
}
/* 右：消息区——白底卡片（边框 + 圆角），与灰底列表面板分区 */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
}
.empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
}
.msg {
  display: flex;
  margin-bottom: 12px;
}
.msg.user {
  justify-content: flex-end;
}
.msg .bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 8px;
  line-height: 1.6;
  word-break: break-word;
}
.msg.user .bubble {
  background: #409eff;
  color: #fff;
}
.msg.assistant .bubble {
  background: #f5f7fa;
}
/* 输入区与消息区的浅分隔线（层次：消息滚动区 / 输入贴底） */
.input-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}
</style>
