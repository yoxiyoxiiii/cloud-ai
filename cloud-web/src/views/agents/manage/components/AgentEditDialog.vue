<script setup lang="ts">
/**
 * 智能体新建/编辑弹窗（契约 §2.2 创建 + §2.4 全量编辑 + §2.6 工具注册表）：
 * - 工具多选数据源 = 工具注册表端点（只读，量级个位数，弹窗打开时拉取）
 * - 编辑回显走详情端点（列表行不含 system_prompt/tool_ids）
 * - 编辑提交恒带 status：取列表行当前值（状态变更由列表行开关负责，弹窗不改状态），
 *   全量覆盖语义下不带该字段会把状态打回默认——两处编辑路径共用人显式传值规避
 * 业务错误（5016 重名/5017 工具无效/1001）拦截器统一 toast，弹窗保持打开可改可重试
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createAgent, getAgent, listAgentTools, updateAgent } from '../../../../api/agent'
import type { AgentSummaryVo, CreateAgentPayload, ToolVo } from '../../../../types/api'

export type AgentEditMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: AgentEditMode
  /** 编辑时传入列表行（取 id 与当前 status）；新增时缺省 */
  agent?: AgentSummaryVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface AgentFormData {
  name: string
  description: string
  temperature: number
  system_prompt: string
  tool_ids: number[]
}

/** 契约 §2.2 长度与默认值：name 1..50、description ≤512、system_prompt 1..8000、temperature 默认 0.7（0..2 步进 0.1） */
const NAME_MAX = 50
const DESC_MAX = 512
const PROMPT_MAX = 8000
const TEMPERATURE_DEFAULT = 0.7
const TEMPERATURE_MIN = 0
const TEMPERATURE_MAX = 2
const TEMPERATURE_STEP = 0.1

const formRef = ref<FormInstance>()
const loading = ref(false)
/** 编辑回显拉取详情中（列表行缺提示词/工具绑定，回显前表单禁提交） */
const detailLoading = ref(false)
const tools = ref<ToolVo[]>([])
const form = reactive<AgentFormData>({
  name: '',
  description: '',
  temperature: TEMPERATURE_DEFAULT,
  system_prompt: '',
  tool_ids: [],
})

const title = computed(() => (props.mode === 'add' ? '新建智能体' : '编辑智能体'))

const rules: FormRules<AgentFormData> = {
  name: [
    { required: true, message: '请输入智能体名称', trigger: 'blur' },
    { min: 1, max: NAME_MAX, message: `名称长度不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  system_prompt: [
    { required: true, message: '请输入系统提示词', trigger: 'blur' },
    { min: 1, max: PROMPT_MAX, message: `提示词长度不超过 ${PROMPT_MAX} 个字符`, trigger: 'blur' },
  ],
  description: [{ max: DESC_MAX, message: `描述不超过 ${DESC_MAX} 个字符`, trigger: 'blur' }],
}

/** 工具多选选项标签：显示名（含工具标识便于辨识） */
function toolLabel(t: ToolVo): string {
  return `${t.name}（${t.tool_key}）`
}

/** 拉工具注册表（契约 §2.6）：失败拦截器已 toast，选项按空集渲染（不阻塞其余表单项） */
async function loadTools(): Promise<void> {
  try {
    tools.value = await listAgentTools()
  } catch {
    // 拦截器已统一 toast
  }
}

/** 编辑回显：详情端点取提示词与工具绑定（列表行无此两字段） */
async function loadDetail(id: number): Promise<void> {
  detailLoading.value = true
  try {
    const detail = await getAgent(id)
    form.name = detail.name
    form.description = detail.description ?? ''
    form.temperature = detail.temperature
    form.system_prompt = detail.system_prompt
    form.tool_ids = [...detail.tool_ids]
  } catch {
    // 拦截器已统一 toast；回显失败保持空表单，提交按钮由 detailLoading 收口防误覆盖
  } finally {
    detailLoading.value = false
  }
}

/** 打开时初始化：拉工具选项；edit 拉详情回显、add 置默认值；并清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    void loadTools()
    if (props.mode === 'edit' && props.agent) {
      void loadDetail(props.agent.id)
    } else {
      form.name = ''
      form.description = ''
      form.temperature = TEMPERATURE_DEFAULT
      form.system_prompt = ''
      form.tool_ids = []
    }
    formRef.value?.clearValidate()
  },
)

/** 提交互转：description 空串转 undefined（不传=清空，对齐全量覆盖语义）；出参即创建契约体 */
function buildPayload(): CreateAgentPayload {
  const description = form.description.trim() || undefined
  return {
    name: form.name.trim(),
    system_prompt: form.system_prompt,
    description,
    temperature: form.temperature,
    tool_ids: form.tool_ids,
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value || detailLoading.value) {
    return
  }
  loading.value = true
  try {
    if (props.mode === 'add') {
      await createAgent(buildPayload())
      ElMessage.success('新建成功')
    } else if (props.agent) {
      // 状态取列表行当前值（行内开关是状态的编辑入口）；详情回显失败时禁止提交已由 detailLoading 拦
      await updateAgent(props.agent.id, { ...buildPayload(), status: props.agent.status })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 5016 名称已存在）；弹窗保持打开供改名重试
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="640px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" :maxlength="NAME_MAX" placeholder="请输入智能体名称" :disabled="loading" />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          :maxlength="DESC_MAX"
          show-word-limit
          placeholder="请输入描述（可选）"
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="采样温度" prop="temperature">
        <el-input-number
          v-model="form.temperature"
          :min="TEMPERATURE_MIN"
          :max="TEMPERATURE_MAX"
          :step="TEMPERATURE_STEP"
          :disabled="loading"
        />
        <span class="field-hint">0~2，越高回答越发散</span>
      </el-form-item>
      <el-form-item label="系统提示词" prop="system_prompt">
        <el-input
          v-model="form.system_prompt"
          type="textarea"
          :rows="6"
          :maxlength="PROMPT_MAX"
          show-word-limit
          placeholder="定义智能体的角色、能力与输出要求"
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="绑定工具" prop="tool_ids">
        <el-select v-model="form.tool_ids" multiple placeholder="不绑定则为纯对话智能体" class="tool-select">
          <el-option v-for="t in tools" :key="t.id" :value="t.id" :label="toolLabel(t)" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading || detailLoading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tool-select {
  width: 100%;
}
.field-hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
