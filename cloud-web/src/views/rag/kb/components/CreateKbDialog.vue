<script setup lang="ts">
/**
 * 新建知识库弹窗：name 1..50 + description ≤500 双必填（契约 2026-10-14-ai-kb-api §2——
 * DDL 列可空、必填由入参层表达）；成功 emit success(新库 id) 供父组件刷新左栏并自动选中；
 * 业务错误（如 5003 重名）由 request.ts 拦截器统一 toast，此处 catch 留空且不关弹窗（可改名重试）
 */
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createKb } from '../../../../api/rag'
import type { CreateKbPayload } from '../../../../types/api'

interface Props {
  modelValue: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 携带新知识库 id（契约 §2 返回值）：父组件刷新左栏后按 id 选中新库 */
  success: [kbId: number]
}>()

interface KbFormData {
  name: string
  description: string
}

/** 契约 §2 长度上限：name 1..50、description ≤500（双必填） */
const NAME_MAX = 50
const DESC_MAX = 500

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<KbFormData>({ name: '', description: '' })

const rules: FormRules<KbFormData> = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { min: 1, max: NAME_MAX, message: `名称长度不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  description: [
    { required: true, message: '请输入描述信息', trigger: 'blur' },
    { max: DESC_MAX, message: `描述不超过 ${DESC_MAX} 个字符`, trigger: 'blur' },
  ],
}

/** 打开时重置表单并清校验残留（本弹窗仅新增态，无编辑回显） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    form.name = ''
    form.description = ''
    formRef.value?.clearValidate()
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value) {
    return
  }
  loading.value = true
  try {
    const payload: CreateKbPayload = { name: form.name, description: form.description }
    const kbId = await createKb(payload)
    ElMessage.success('新建成功')
    emit('success', kbId)
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 5003 名称已存在）；弹窗保持打开供改名重试
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="新建知识库"
    width="520px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="名称" prop="name">
        <el-input
          v-model="form.name"
          :maxlength="NAME_MAX"
          placeholder="请输入知识库名称"
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="4"
          :maxlength="DESC_MAX"
          show-word-limit
          placeholder="请输入描述信息"
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
