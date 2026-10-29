<script setup lang="ts">
/**
 * 会话侧栏（契约 §4 会话三端点的列表展示件）：新建按钮 + 会话列表（id 倒序新建居首）
 * + 行内删除；纯展示/事件组件——数据加载与删除确认由父组件（对话页）持有
 */
import { Delete } from '@element-plus/icons-vue'
import type { AgentSessionVo } from '../../../../types/api'

interface Props {
  /** 会话列表（父组件维护，id 倒序） */
  sessions: AgentSessionVo[]
  /** 激活会话 id（0 = 无激活） */
  activeId: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  /** 点击新建（父组件调创建端点） */
  create: []
  /** 选中会话 */
  select: [id: number]
  /** 点击行内删除图标（父组件弹确认框） */
  remove: [id: number]
}>()
</script>

<template>
  <div class="session-sidebar">
    <div class="title">对话列表</div>
    <el-button type="primary" class="new-btn" @click="emit('create')">新建对话</el-button>
    <div class="list">
      <div
        v-for="s in props.sessions"
        :key="s.id"
        :class="['session-item', { active: s.id === props.activeId }]"
        @click="emit('select', s.id)"
      >
        <span class="name">{{ s.title }}</span>
        <el-icon class="del" @click.stop="emit('remove', s.id)"><Delete /></el-icon>
      </div>
      <div v-if="props.sessions.length === 0" class="empty">暂无对话</div>
    </div>
  </div>
</template>

<style scoped>
.session-sidebar {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
}
.title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  padding: 0 0 8px 2px;
}
.new-btn {
  margin-bottom: 8px;
}
.list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  flex-shrink: 0;
}
/* 灰底上的色阶：hover 深一档，活动色用 primary 浅底（对比不足用 #d9ecff） */
.session-item:hover {
  background: #ebedf0;
}
.session-item.active {
  background: #d9ecff;
  color: #409eff;
}
.session-item .name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-item .del {
  flex-shrink: 0;
}
.empty {
  color: #909399;
  font-size: 13px;
  text-align: center;
  padding: 12px 0;
}
</style>
