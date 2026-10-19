<template>
  <div class="rag-chat">
    <!-- 左：会话列表（服务端会话，契约 2026-10-19-ai-chat-session-api） -->
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
          <div v-for="m in activeMessages" :key="m.id" :class="['msg', m.role]">
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
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import MdRenderer from './components/MdRenderer.vue'
import { ssePost } from '../../../utils/sse'
import { useStickToBottom } from '../../../composables/useStickToBottom'
import { createChatSession, deleteChatSession, listSessionMessages, pageChatSessions } from '../../../api/rag'
import type { R } from '../../../types/api'

/** = route.name（pathToRouteName('/rag/chat')），keep-alive include 依赖，不可省 */
defineOptions({ name: 'RagChat' })

interface LocalMsg {
  id: number
  role: 'user' | 'assistant'
  content: string
}
/** 左栏会话行（服务端 ChatSessionVo 的页面投影——create_time 不参与渲染，不保留） */
interface LocalSession {
  id: number
  title: string
}

// 会话列表与消息缓存（契约 2026-10-19-ai-chat-session-api）：列表 id 倒序新建居首；
// 消息按会话分置缓存——切换会话缓存命中免拉、未命中拉 §5 回显
const sessions = ref<LocalSession[]>([])
const msgCache = reactive(new Map<number, LocalMsg[]>())
const activeId = ref(0)
const input = ref('')
const streaming = ref(false)
let abort: AbortController | null = null
let seq = 0

const active = computed(() => sessions.value.find((s) => s.id === activeId.value))
/** 激活会话消息（回显未落缓存前按空数组渲染） */
const activeMessages = computed(() => msgCache.get(activeId.value) ?? [])
/** 当前会话最后一条内容（空 = 还没收到首 token，显示“思考中”） */
const lastContent = computed(() => activeMessages.value[-1]?.content ?? '')

const listEl = ref<HTMLElement>()
const { stick, scrollToBottom } = useStickToBottom(listEl)

/** 首屏拉本人会话并自动激活首条（无会话保持空态）；首屏 50 条，滚动分页移交后续轮 */
onMounted(async () => {
  try {
    const res = await pageChatSessions({ page: 1, size: 50 })
    sessions.value = res.rows.map((r) => ({ id: r.id, title: r.title }))
    if (sessions.value.length > 0) {
      activeId.value = sessions.value[0].id // 触发下方 watch 拉回显
    }
  } catch {
    // 拦截器已统一 toast（403/500 等）；列表缺席按空态渲染
  }
})

/** 拉会话回显并落缓存；LocalMsg.id 用索引合成（仅作渲染 key，非服务端字段） */
async function loadSessionMessages(sessionId: number): Promise<void> {
  try {
    const vo = await listSessionMessages(sessionId)
    // 回填竞态守卫：拉取期间用户已在本会话发出消息（send 已建缓存）则丢弃本次陈旧结果
    const existing = msgCache.get(sessionId)
    if (existing && existing.length > 0) {
      return
    }
    msgCache.set(sessionId, vo.messages.map((m, i) => ({ id: i + 1, role: m.role, content: m.content })))
  } catch {
    // 拦截器已统一 toast（5012/5013 等）；缓存缺席按空数组渲染
  }
}

// 切换会话：缓存命中直接用，未命中拉回显（模板仍直赋 activeId，加载由此 watch 接手）
watch(activeId, (id) => {
  if (id && !msgCache.has(id)) {
    void loadSessionMessages(id)
  }
})

/** 新建会话（契约 §2）：服务端建行（标题默认「新对话」）→ 置顶激活，消息区空 */
async function newSession(): Promise<void> {
  try {
    const vo = await createChatSession()
    sessions.value.unshift({ id: vo.id, title: vo.title })
    msgCache.set(vo.id, [])
    activeId.value = vo.id
  } catch {
    // 拦截器已统一 toast（403/1001/500）；创建失败不入列表
  }
}

/** 删除会话（契约 §4 双清不可恢复）：确认框前置；5012（他端已删）静默收敛——照移出本地列表 */
async function removeSession(id: number): Promise<void> {
  const target = sessions.value.find((s) => s.id === id)
  const confirmed = await ElMessageBox.confirm(
    `确定删除对话「${target?.title ?? ''}」吗？对话消息将一并删除且不可恢复。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteChatSession(id)
  } catch (e) {
    if (!isBizCode(e, 5012)) {
      ElMessage.error(errMsgOf(e) || '删除失败')
      return
    }
    // 5012 会话不存在（典型：另一标签页已删）——服务端已无此行，静默移出本地列表即收敛
  }
  sessions.value = sessions.value.filter((s) => s.id !== id)
  msgCache.delete(id)
  if (activeId.value === id) {
    activeId.value = sessions.value[0]?.id ?? 0 // 激活剩余居首者或回空态（watch 接手拉回显）
  }
}

/** 拦截器业务失败 reject 的是 R 体（utils/request.ts），此处仅判业务码做分支 */
function isBizCode(e: unknown, code: number): boolean {
  return typeof e === 'object' && e !== null && (e as R).code === code
}

/** 取 R 体 msg 供页面自行 toast（deleteChatSession 走 skipErrorMessage） */
function errMsgOf(e: unknown): string {
  if (typeof e === 'object' && e !== null && 'msg' in e) {
    return String((e as { msg?: unknown }).msg ?? '')
  }
  return ''
}

async function send(): Promise<void> {
  const q = input.value.trim()
  if (!q || streaming.value) {
    return
  }
  // 无激活会话（用户未点新建直接输入的兜底路径）：先建会话再发
  if (!active.value) {
    await newSession()
    if (!active.value) {
      return // 创建失败（拦截器已 toast），本次发送终止
    }
  }
  // 发送时捕获目标会话与消息数组——流式中途切换会话不影响本流目标（既有行为保持）
  const s = active.value!
  input.value = ''
  if (!msgCache.has(s.id)) {
    msgCache.set(s.id, [])
  }
  const msgs = msgCache.get(s.id)!
  msgs.push({ id: ++seq, role: 'user', content: q })
  if (s.title === '新对话') {
    s.title = q.slice(0, 16) // 首问乐观更新（契约 §6：服务端同口径落库，刷新后以服务端为准）
  }
  msgs.push({ id: ++seq, role: 'assistant', content: '' })
  const aiIdx = msgs.length - 1

  streaming.value = true
  abort = new AbortController()
  try {
    // 已知边界（契约 §6 记档，勿当 bug）：5012/5013 在流开始前被拒 = HTTP 200 + R JSON（非 SSE 帧），
    // sse.ts 定死按完成兜底 → 表现为空气泡静默（典型：另一标签页删了会话后继续发问），
    // 列表刷新时会话自然消失；sse.ts 属契约冻结红线，本页不特殊处理
    await ssePost(
      '/api/ai/rag/chat/stream',
      { session_id: s.id, question: q }, // 契约 §6 breaking 换轨：历史不再上送，服务端拼装落档
      {
        onToken: (t) => {
          msgs[aiIdx].content += t
          if (stick.value) {
            nextTick(() => scrollToBottom(false))
          }
        },
        onDone: () => undefined, // 服务端已落档，不主动拉回显（刷新/切换时经 §5 回显）
        onError: (msg) => {
          if (!msgs[aiIdx].content) {
            msgs[aiIdx].content = `（${msg}）`
          }
        },
      },
      abort.signal,
    )
  } catch {
    // 用户主动停止（AbortError）：保留已生成部分（服务端收口同样落半截答，契约 §6）
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
