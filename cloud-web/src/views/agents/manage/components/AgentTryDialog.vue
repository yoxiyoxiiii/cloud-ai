<script setup lang="ts">
/**
 * 智能体试跑弹窗（管理页「试跑」入口，运行面走对话面板组件的 embedded 形态）：
 * 内嵌对话使用页的 AgentChatPanel——单会话形态（首问前自动建会话，会话保留在服务端，
 * 对话使用页可继续该会话）。destroy-on-close 保证每次试跑从干净状态开始。
 * 按钮可见性由父页 v-perms ai:agent:run 控制（管理与使用分离，契约 §1：未发放者
 * 试跑同样 403 无特例——前端隐藏 + 后端执法同向）
 */
import AgentChatPanel from '../../chat/components/AgentChatPanel.vue'
import type { AgentSummaryVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 目标智能体行（取 id 与名称）；可缺省——标题插值随组件渲染即求值，空表下兜底必入 undefined */
  agent?: AgentSummaryVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="props.agent ? `试跑 · ${props.agent.name}` : '试跑'"
    width="720px"
    destroy-on-close
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <!-- 会话 id 传 0：面板在首问前自动建会话（embedded 单会话形态）；v-if 兼顾 agent 可缺省的类型收窄 -->
    <div class="try-body">
      <AgentChatPanel v-if="props.agent" :agent-id="props.agent.id" :session-id="0" embedded />
    </div>
  </el-dialog>
</template>

<style scoped>
/* 试跑弹窗体：给定高度承载消息区滚动（面板自身按 100% 高度铺满） */
.try-body {
  height: 480px;
}
</style>
