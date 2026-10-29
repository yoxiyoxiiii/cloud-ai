<script setup lang="ts">
/**
 * 智能体对话使用页（/agent/chat，契约 2026-10-29-agent-platform-api §3 运行 + §4 会话）：
 * 左栏 = 智能体下拉（停用禁选）+ 该智能体下本人会话列表；右栏 = 对话面板（流式渲染）。
 * 会话按智能体隔离（端点以路径 agent_id 圈定）；切换智能体重拉会话并重置激活。
 * 无 ai:agent:run 权限的用户接口层 403（菜单本身由种子 190 的 perms 控制可见）
 */
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { pageAgentSessions, createAgentSession, deleteAgentSession } from '../../../api/agent'
import type { AgentSessionVo } from '../../../types/api'
import AgentPicker from './components/AgentPicker.vue'
import SessionSidebar from './components/SessionSidebar.vue'
import AgentChatPanel from './components/AgentChatPanel.vue'

/** = pathToRouteName('/agent/chat')，keep-alive include 依赖，不可省 */
defineOptions({ name: 'AgentChat' })

const agentId = ref(0)
const sessions = ref<AgentSessionVo[]>([])
const activeSessionId = ref(0)
const sessionsLoading = ref(false)

/** 拉当前智能体下本人会话（id 倒序新建居首）；首屏 50 条；激活居首者或回空态 */
async function loadSessions(): Promise<void> {
  if (!agentId.value) {
    sessions.value = []
    activeSessionId.value = 0
    return
  }
  sessionsLoading.value = true
  try {
    const page = await pageAgentSessions(agentId.value, { page: 1, size: 50 })
    sessions.value = page.rows
    activeSessionId.value = page.rows[0]?.id ?? 0
  } catch {
    // 拦截器已统一 toast（如 403）；列表缺席按空态渲染
  } finally {
    sessionsLoading.value = false
  }
}

/** 切换智能体：会话域随智能体切换整体重置（端点以路径 agent_id 圈定，跨智能体会话不互通） */
function handleAgentChange(): void {
  void loadSessions()
}

/** 新建会话（契约 §4：无 body，标题默认「新对话」）→ 置顶激活 */
async function handleCreate(): Promise<void> {
  if (!agentId.value) {
    return
  }
  try {
    const vo = await createAgentSession(agentId.value)
    sessions.value.unshift(vo)
    activeSessionId.value = vo.id
  } catch {
    // 拦截器已统一 toast（如智能体停用 5015）；创建失败不入列表
  }
}

/** 删除会话（双清不可恢复）：确认框前置，文案含标题 */
async function handleRemove(id: number): Promise<void> {
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
    await deleteAgentSession(id)
  } catch {
    return // 拦截器已统一 toast；删除失败保留列表行
  }
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeSessionId.value === id) {
    activeSessionId.value = sessions.value[0]?.id ?? 0 // 激活剩余居首者或回空态
  }
}

/** 面板自建会话（无会话直接输入的兜底路径）：置顶激活 */
function handleSessionCreated(vo: AgentSessionVo): void {
  sessions.value.unshift(vo)
  activeSessionId.value = vo.id
}

/** 一次提问发出：会话标题乐观更新（服务端同口径取前 16 字，刷新后以服务端为准） */
function handleSent(question: string): void {
  const target = sessions.value.find((s) => s.id === activeSessionId.value)
  if (target && target.title === '新对话') {
    target.title = question.slice(0, 16)
  }
}
</script>

<template>
  <div class="agent-chat-page">
    <!-- 左：智能体选择 + 会话列表 -->
    <aside class="side">
      <AgentPicker v-model:agent-id="agentId" @change="handleAgentChange" />
      <div v-if="agentId" v-loading="sessionsLoading" class="sessions-wrap">
        <SessionSidebar
          :sessions="sessions"
          :active-id="activeSessionId"
          @create="handleCreate"
          @select="(id: number) => (activeSessionId = id)"
          @remove="handleRemove"
        />
      </div>
    </aside>

    <!-- 右：对话面板（流式）；未选智能体时占位提示 -->
    <section class="main">
      <template v-if="agentId">
        <AgentChatPanel
          :agent-id="agentId"
          :session-id="activeSessionId"
          @session-created="handleSessionCreated"
          @sent="handleSent"
        />
      </template>
      <div v-else class="no-agent">暂无可用智能体（需存在启用中的智能体）</div>
    </section>
  </div>
</template>

<style scoped>
.agent-chat-page {
  display: flex;
  height: 100%;
  gap: 12px;
}
/* 左栏：灰底面板（智能体选择 + 会话列表） */
.side {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
  overflow: hidden;
}
.sessions-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.no-agent {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
</style>
