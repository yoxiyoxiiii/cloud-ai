<script setup lang="ts">
/**
 * 智能体新建/编辑弹窗（契约 §2.2 创建 + §2.4 全量编辑 + §2.6 工具注册表 + §2.8 MCP 服务选项）：
 * - 工具多选数据源 = 工具注册表端点、MCP 服务多选数据源 = 绑定选项端点（均只读，
 *   量级个位数，弹窗打开时并行拉取）
 * - MCP 服务为订阅绑定（2026-10-31 修订）：绑定后该 server 名下已发布工具全部进入
 *   agent 工具面；选项只含启用未删行——详情回显的绑定 id 不在选项集时（server 已停用/
 *   软删）el-select 按原始编号显示，沿工具侧既有处置不动态注入灰选项
 * - 编辑回显走详情端点（列表行不含 system_prompt/tool_ids/mcp_server_ids）
 * - 编辑提交恒带 status 与 mcp_server_ids：status 取列表行当前值（状态变更由列表行开关
 *   负责，弹窗不改状态）；全量覆盖语义下任一漏带即清空对应配置，与 status 恒带同理
 * 业务错误（5016 重名/5017 工具无效/5029 MCP 服务无效/1001）拦截器统一 toast，
 * 弹窗保持打开可改可重试
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createAgent, getAgent, listAgentMcpServerOptions, listAgentTools, updateAgent } from '../../../../api/agent'
import type {
  AgentMcpServerOptionVo,
  AgentSummaryVo,
  CreateAgentPayload,
  ToolVo,
} from '../../../../types/api'

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
  mcp_server_ids: number[]
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
/** 编辑回显拉取详情中（列表行缺提示词/两类绑定，回显前表单禁提交） */
const detailLoading = ref(false)
const tools = ref<ToolVo[]>([])
const servers = ref<AgentMcpServerOptionVo[]>([])
const form = reactive<AgentFormData>({
  name: '',
  description: '',
  temperature: TEMPERATURE_DEFAULT,
  system_prompt: '',
  tool_ids: [],
  mcp_server_ids: [],
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

/** MCP 服务多选选项标签：服务名 + 名下已发布工具数（订阅规模提示） */
function serverLabel(s: AgentMcpServerOptionVo): string {
  return `${s.name}（已发布 ${s.published_tool_count} 工具）`
}

/** 拉工具注册表（契约 §2.6）：失败拦截器已 toast，选项按空集渲染（不阻塞其余表单项） */
async function loadTools(): Promise<void> {
  try {
    tools.value = await listAgentTools()
  } catch {
    // 拦截器已统一 toast
  }
}

/** 拉 MCP 服务绑定选项（契约 §2.8）：失败处置同工具注册表（选项空集渲染，不阻塞表单） */
async function loadServers(): Promise<void> {
  try {
    servers.value = await listAgentMcpServerOptions()
  } catch {
    // 拦截器已统一 toast
  }
}

/** 编辑回显：详情端点取提示词与两类绑定（列表行无这些字段） */
async function loadDetail(id: number): Promise<void> {
  detailLoading.value = true
  try {
    const detail = await getAgent(id)
    form.name = detail.name
    form.description = detail.description ?? ''
    form.temperature = detail.temperature
    form.system_prompt = detail.system_prompt
    form.tool_ids = [...detail.tool_ids]
    form.mcp_server_ids = [...detail.mcp_server_ids]
  } catch {
    // 拦截器已统一 toast；回显失败保持空表单，提交按钮由 detailLoading 收口防误覆盖
  } finally {
    detailLoading.value = false
  }
}

/** 打开时初始化：并行拉两类选项；edit 拉详情回显、add 置默认值；并清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    void loadTools()
    void loadServers()
    if (props.mode === 'edit' && props.agent) {
      void loadDetail(props.agent.id)
    } else {
      form.name = ''
      form.description = ''
      form.temperature = TEMPERATURE_DEFAULT
      form.system_prompt = ''
      form.tool_ids = []
      form.mcp_server_ids = []
    }
    formRef.value?.clearValidate()
  },
)

/** 提交互转：description 空串转 undefined（不传=清空，对齐全量覆盖语义）；
 * mcp_server_ids 恒带（空数组=清空订阅，漏带即误清空，对齐 status/tool_ids 恒带口径） */
function buildPayload(): CreateAgentPayload {
  const description = form.description.trim() || undefined
  return {
    name: form.name.trim(),
    system_prompt: form.system_prompt,
    description,
    temperature: form.temperature,
    tool_ids: form.tool_ids,
    mcp_server_ids: form.mcp_server_ids,
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
      <el-form-item label="MCP 服务" prop="mcp_server_ids">
        <el-select
          v-model="form.mcp_server_ids"
          multiple
          placeholder="可多选订阅 MCP 服务（可选）"
          class="tool-select"
        >
          <el-option v-for="s in servers" :key="s.id" :value="s.id" :label="serverLabel(s)" />
        </el-select>
        <div class="field-help">
          绑定 MCP 服务后，其名下已发布工具将自动进入该智能体的工具面（后续新增并审核发布的工具同样自动生效）；已停用/删除的服务以编号显示
        </div>
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
.field-help {
  width: 100%;
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
}
</style>
