<script setup lang="ts">
/**
 * 发送通知弹窗（契约 2026-11-08-notify-api §2.1）——单弹窗内三段式：
 * ①选模板（列表行「发送」进入时预选，可换）②变量表单（前端解析模板 `${var}` 动态生成
 * 输入框 + 本地实时预览——解析正则与后端一致 `^[a-zA-Z][a-zA-Z0-9_]*$`）③接收人三档
 * （按用户/按角色/全员）+ linkUrl 可选。
 * 变量**不做前端必填拦截**：留空提交由后端 3045 拦截并 toast msg 原文（动态列缺失变量名，
 * e2e 验证路径）；接收人配套与 linkUrl 格式做前端友好校验（后端 1001 亦拦）。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageUser } from '../../../../api/user'
import { listRoles } from '../../../../api/role'
import { pageTemplate, sendNotify } from '../../../../api/notify'
import type { NotifyTemplateVo, SysRoleVo, SysUserVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 列表行进入时预选的模板（可换） */
  presetTemplate?: NotifyTemplateVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

/** 接收人维度（契约 §2.1）：1按用户 2按角色 3全员 */
const RECEIVER_USER = 1
const RECEIVER_ROLE = 2
const RECEIVER_ALL = 3

/** 与后端 NotifyTemplateParser 一致的占位符正则（契约 §2.1 变量键 `^[a-zA-Z][a-zA-Z0-9_]*$`） */
const VAR_PATTERN = /\$\{([a-zA-Z][a-zA-Z0-9_]*)\}/g

const loading = ref(false)
/** 模板候选（发送弹窗内可换模板——拉第一页 100 条足够演示域规模） */
const templateOptions = ref<NotifyTemplateVo[]>([])
const templateId = ref('')
const variables = reactive<Record<string, string>>({})
const receiverType = ref(RECEIVER_ALL)
const userIds = ref<string[]>([])
const roleIds = ref<string[]>([])
const linkUrl = ref('')

/** 用户/角色候选（计划 F2：复用既有列表函数，无 options 端点则拉第一页 pageSize 200） */
const userOptions = ref<SysUserVo[]>([])
const roleOptions = ref<SysRoleVo[]>([])

const selectedTemplate = computed(() => templateOptions.value.find((t) => t.id === templateId.value))

/** 解析选中模板占位符（保序去重——LinkedHashSet 语义的数组版） */
const variableNames = computed<string[]>(() => {
  const tpl = selectedTemplate.value
  if (!tpl) {
    return []
  }
  const names: string[] = []
  const seen = new Set<string>()
  for (const text of [tpl.titleTemplate, tpl.contentTemplate]) {
    for (const match of text.matchAll(VAR_PATTERN)) {
      if (!seen.has(match[1])) {
        seen.add(match[1])
        names.push(match[1])
      }
    }
  }
  return names
})

/** 本地实时预览：变量已填替换为值，未填保留 `${var}` 占位符原样 */
function renderPreview(text: string): string {
  return text.replace(VAR_PATTERN, (raw, name: string) => {
    const value = variables[name]
    return value ? value : raw
  })
}

const titlePreview = computed(() => (selectedTemplate.value ? renderPreview(selectedTemplate.value.titleTemplate) : ''))
const contentPreview = computed(() =>
  selectedTemplate.value ? renderPreview(selectedTemplate.value.contentTemplate) : '',
)

/** 接收人切换清另一维选择（防脏数据残留——后端按 receiverType 取配套字段） */
watch(receiverType, () => {
  userIds.value = []
  roleIds.value = []
})

/** 模板切换清变量值（新模板变量空间不同） */
watch(templateId, () => {
  for (const key of Object.keys(variables)) {
    delete variables[key]
  }
})

async function loadOptions(): Promise<void> {
  try {
    const [tplPage, userPage, roles] = await Promise.all([
      pageTemplate({ pageNum: 1, pageSize: 100 }),
      pageUser({ pageNum: 1, pageSize: 200 }),
      listRoles(),
    ])
    templateOptions.value = tplPage.rows
    userOptions.value = userPage.rows
    roleOptions.value = roles
  } catch {
    // 拦截器已统一 toast；候选空集时表单仍可提交（模板必选校验拦截）
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    // 预选进入行模板（可换）；无预选回默认全员档
    templateId.value = props.presetTemplate?.id ?? ''
    receiverType.value = RECEIVER_ALL
    userIds.value = []
    roleIds.value = []
    linkUrl.value = ''
    for (const key of Object.keys(variables)) {
      delete variables[key]
    }
    void loadOptions()
  },
)

/** 前端友好校验（后端 1001 权威）：接收人配套非空 + linkUrl 非空须 / 开头 */
function validate(): string {
  if (!templateId.value) {
    return '请选择模板'
  }
  if (receiverType.value === RECEIVER_USER && userIds.value.length === 0) {
    return '请选择接收用户'
  }
  if (receiverType.value === RECEIVER_ROLE && roleIds.value.length === 0) {
    return '请选择接收角色'
  }
  if (linkUrl.value && !linkUrl.value.startsWith('/')) {
    return '跳转链接须为 / 开头的前端相对路由'
  }
  return ''
}

async function handleSubmit(): Promise<void> {
  if (loading.value) {
    return
  }
  const error = validate()
  if (error) {
    ElMessage.warning(error)
    return
  }
  loading.value = true
  try {
    // 变量值只带已填项（空值视同缺失，交由后端 3045 按模板提取集判缺——msg 列缺失变量名）
    const filled: Record<string, string> = {}
    for (const name of variableNames.value) {
      if (variables[name]) {
        filled[name] = variables[name]
      }
    }
    const result = await sendNotify({
      templateId: templateId.value,
      receiverType: receiverType.value,
      userIds: receiverType.value === RECEIVER_USER ? userIds.value : undefined,
      roleIds: receiverType.value === RECEIVER_ROLE ? roleIds.value : undefined,
      variables: Object.keys(filled).length > 0 ? filled : undefined,
      linkUrl: linkUrl.value || undefined,
    })
    ElMessage.success(`发送成功：${result.receiverCount} 人`)
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（3045 变量缺失 msg 原文 / 3046 超长 / 3047 收件人为空）；弹窗保持打开
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="发送通知"
    width="640px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form label-width="90px">
      <!-- ① 选模板 -->
      <el-divider content-position="left">选择模板</el-divider>
      <el-form-item label="模板">
        <el-select
          v-model="templateId"
          placeholder="请选择模板"
          filterable
          class="send-template-select"
          :disabled="loading"
        >
          <el-option
            v-for="tpl in templateOptions"
            :key="tpl.id"
            :label="`${tpl.name}（${tpl.code}）`"
            :value="tpl.id"
          />
        </el-select>
      </el-form-item>

      <!-- ② 变量表单 + 实时预览 -->
      <template v-if="selectedTemplate">
        <el-divider content-position="left">模板变量</el-divider>
        <el-form-item v-if="variableNames.length === 0" label=" ">
          <span class="send-hint">该模板无变量，直接进入下一步</span>
        </el-form-item>
        <el-form-item v-for="name in variableNames" :key="name" :label="name">
          <el-input v-model="variables[name]" :placeholder="`请输入 ${name} 的值`" :disabled="loading" />
        </el-form-item>
        <el-form-item v-if="selectedTemplate.variablesDesc" label="变量说明">
          <span class="send-hint send-desc">{{ selectedTemplate.variablesDesc }}</span>
        </el-form-item>
        <el-form-item label="预览">
          <div class="send-preview">
            <div class="send-preview-title">{{ titlePreview }}</div>
            <div class="send-preview-content">{{ contentPreview }}</div>
          </div>
        </el-form-item>
      </template>

      <!-- ③ 接收人三档 + linkUrl -->
      <el-divider content-position="left">接收人</el-divider>
      <el-form-item label="接收维度">
        <el-radio-group v-model="receiverType" :disabled="loading">
          <el-radio :value="RECEIVER_USER">按用户</el-radio>
          <el-radio :value="RECEIVER_ROLE">按角色</el-radio>
          <el-radio :value="RECEIVER_ALL">全员</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="receiverType === RECEIVER_USER" label="接收用户">
        <el-select
          v-model="userIds"
          multiple
          filterable
          placeholder="请选择接收用户（发送时点有效用户快照）"
          class="send-template-select"
          :disabled="loading"
        >
          <el-option
            v-for="user in userOptions"
            :key="user.id"
            :label="`${user.nickname}（${user.account}）`"
            :value="user.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="receiverType === RECEIVER_ROLE" label="接收角色">
        <el-select
          v-model="roleIds"
          multiple
          filterable
          placeholder="请选择接收角色（按角色展开有效成员）"
          class="send-template-select"
          :disabled="loading"
        >
          <el-option v-for="role in roleOptions" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="receiverType === RECEIVER_ALL" label=" ">
        <span class="send-hint">将发送给全部有效用户（发送时点快照，之后入离职不回溯）</span>
      </el-form-item>
      <el-form-item label="跳转链接">
        <el-input
          v-model="linkUrl"
          placeholder="可选，前端相对路由（如 /bpmn/task），留空纯展示"
          :disabled="loading"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">发送</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.send-template-select {
  width: 100%;
}

.send-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.send-desc {
  word-break: break-all;
}

.send-preview {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-light);
}

.send-preview-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.send-preview-content {
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--el-text-color-regular);
}
</style>
