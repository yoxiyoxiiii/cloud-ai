<script setup lang="ts">
/**
 * 通知模板 新增/编辑 弹窗（契约 2026-11-08-notify-api §1.3/§1.4）
 * 编辑回显行数据；code 编辑可改（与用户 account 锁定不同——契约 §1.4 允许，撞键 3043）；
 * 内置模板（isBuiltin=1）仅文案提示「禁删可改」（删除拦截在列表页按钮 + 后端 3049 双防线）
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { addTemplate, editTemplate } from '../../../../api/notify'
import { NOTIFY_LEVEL_MAP, NOTIFY_TYPE_MAP, type NotifyTemplateVo } from '../../../../types/api'

export type TemplateFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: TemplateFormMode
  /** 编辑时传入行数据；新增时缺省 */
  template?: NotifyTemplateVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface TemplateFormData {
  code: string
  name: string
  titleTemplate: string
  contentTemplate: string
  type: number
  level: number
  variablesDesc: string
}

/** 消息类型/级别默认值（契约 §1.3 值域：1公告 2提醒 / 0普通 1重要 2紧急） */
const TYPE_NOTICE = 1
const LEVEL_NORMAL = 0

/** 类型/级别下拉候选（值域固定，文案走 types 常量映射，禁散落字面量） */
const typeOptions = Object.entries(NOTIFY_TYPE_MAP).map(([value, label]) => ({ value: Number(value), label }))
const levelOptions = Object.entries(NOTIFY_LEVEL_MAP).map(([value, label]) => ({ value: Number(value), label }))

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<TemplateFormData>({
  code: '',
  name: '',
  titleTemplate: '',
  contentTemplate: '',
  type: TYPE_NOTICE,
  level: LEVEL_NORMAL,
  variablesDesc: '',
})

const rules: FormRules<TemplateFormData> = {
  // code 校验为后端契约口径（§1.3：2-50 位小写下划线编码）的前端同步兜底，失败后端亦 1001
  code: [
    { required: true, message: '请输入模板编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{1,49}$/, message: '小写字母开头，仅含小写字母/数字/下划线，2-50 位', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入模板名称', trigger: 'blur' },
    { min: 2, max: 50, message: '模板名称长度为 2-50 位', trigger: 'blur' },
  ],
  titleTemplate: [
    { required: true, message: '请输入标题模板', trigger: 'blur' },
    { min: 1, max: 100, message: '标题模板长度为 1-100 位', trigger: 'blur' },
  ],
  contentTemplate: [
    { required: true, message: '请输入内容模板', trigger: 'blur' },
    { min: 1, max: 1000, message: '内容模板长度为 1-1000 位', trigger: 'blur' },
  ],
  type: [{ required: true, message: '请选择消息类型', trigger: 'change' }],
  level: [{ required: true, message: '请选择消息级别', trigger: 'change' }],
  variablesDesc: [{ max: 500, message: '变量说明不超过 500 字符', trigger: 'blur' }],
}

const title = computed(() => (props.mode === 'add' ? '新增模板' : '编辑模板'))

/** 内置模板提示（仅编辑态有意义；新增恒为用户创建模板） */
const isBuiltin = computed(() => props.mode === 'edit' && props.template?.isBuiltin === 1)

/** 打开时初始化：编辑回显行数据，新增给默认值；先清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.template) {
      form.code = props.template.code
      form.name = props.template.name
      form.titleTemplate = props.template.titleTemplate
      form.contentTemplate = props.template.contentTemplate
      form.type = props.template.type
      form.level = props.template.level
      form.variablesDesc = props.template.variablesDesc
    } else {
      form.code = ''
      form.name = ''
      form.titleTemplate = ''
      form.contentTemplate = ''
      form.type = TYPE_NOTICE
      form.level = LEVEL_NORMAL
      form.variablesDesc = ''
    }
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value) {
    return
  }
  loading.value = true
  try {
    if (props.mode === 'add') {
      await addTemplate({ ...form })
      ElMessage.success('新增成功')
    } else if (props.template) {
      await editTemplate({ id: props.template.id, ...form })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（3043 模板编码已存在 / 1001 参数不合法）；弹窗保持打开可改后重提
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="560px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-alert
      v-if="isBuiltin"
      class="builtin-alert"
      type="info"
      :closable="false"
      show-icon
      title="内置模板：禁删可改（内容调整不影响已发送消息——发送时点快照）"
    />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="模板编码" prop="code">
        <el-input v-model="form.code" placeholder="如 welcome_notice" :disabled="loading" />
      </el-form-item>
      <el-form-item label="模板名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入模板名称" :disabled="loading" />
      </el-form-item>
      <el-form-item label="标题模板" prop="titleTemplate">
        <el-input
          v-model="form.titleTemplate"
          placeholder="支持 ${变量} 占位符，如 欢迎 ${nickname}"
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="内容模板" prop="contentTemplate">
        <el-input
          v-model="form.contentTemplate"
          type="textarea"
          :rows="4"
          placeholder="支持 ${变量} 占位符，变量用 ${变量名} 引用"
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="消息类型" prop="type">
        <el-radio-group v-model="form.type" :disabled="loading">
          <el-radio v-for="opt in typeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="消息级别" prop="level">
        <el-radio-group v-model="form.level" :disabled="loading">
          <el-radio v-for="opt in levelOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="变量说明" prop="variablesDesc">
        <el-input
          v-model="form.variablesDesc"
          type="textarea"
          :rows="2"
          placeholder="人工维护的变量文档，如 nickname=用户昵称（可空）"
          :disabled="loading"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.builtin-alert {
  margin-bottom: 12px;
}
</style>
