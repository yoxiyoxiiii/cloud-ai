<script setup lang="ts">
/**
 * 智能体下拉选择器（契约 §2.1 list 端点驱动）：停用项禁选——运行与创建会话对停用
 * 智能体统一 5015 拦截（契约 §4 口径），前端提前禁选省一次失败往返；
 * 纯选择器组件（列表加载 + 选中态），会话加载与切换由父组件接手
 */
import { ref } from 'vue'
import { pageAgents } from '../../../../api/agent'
import type { AgentSummaryVo } from '../../../../types/api'

/** 智能体状态（契约 §2.1）：0=启用 1=停用 */
const STATUS_DISABLED = 1

interface Props {
  /** 选中智能体 id（v-model:agent-id） */
  agentId: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:agentId': [id: number]
  /** 选中项变化（含初始默认选中）；携带完整行——父组件可读名称等信息 */
  change: [agent: AgentSummaryVo]
}>()

const options = ref<AgentSummaryVo[]>([])
const loading = ref(false)

/** 拉智能体全量（量级个位数一页取足）；失败拦截器已 toast，下拉按空集渲染 */
async function loadAgents(): Promise<void> {
  loading.value = true
  try {
    const page = await pageAgents({ page: 1, size: 200 })
    options.value = page.rows
    // 默认选中首个启用项（id 倒序新建居首）；无可用智能体保持 0 由父组件渲染空态
    if (!props.agentId) {
      const first = page.rows.find((a) => a.status !== STATUS_DISABLED)
      if (first) {
        emit('update:agentId', first.id)
        emit('change', first)
      }
    }
  } catch {
    // 拦截器已统一 toast（如 403：使用者未发放 ai:agent:list）
  } finally {
    loading.value = false
  }
}

function handleChange(id: number): void {
  const hit = options.value.find((a) => a.id === id)
  if (hit) {
    emit('change', hit)
  }
}

// 挂载即加载（页面/弹窗打开取一次最新清单）
loadAgents()
</script>

<template>
  <el-select
    :model-value="props.agentId"
    :loading="loading"
    placeholder="选择智能体"
    class="agent-picker"
    @update:model-value="(id: number) => emit('update:agentId', id)"
    @change="handleChange"
  >
    <el-option
      v-for="a in options"
      :key="a.id"
      :value="a.id"
      :label="a.name"
      :disabled="a.status === STATUS_DISABLED"
    >
      <!-- 停用项给出禁选原因，避免"点不动"困惑 -->
      <span>{{ a.name }}</span>
      <span v-if="a.status === STATUS_DISABLED" class="disabled-mark">（已停用）</span>
    </el-option>
  </el-select>
</template>

<style scoped>
.agent-picker {
  width: 100%;
}
.disabled-mark {
  color: #909399;
  font-size: 12px;
}
</style>
