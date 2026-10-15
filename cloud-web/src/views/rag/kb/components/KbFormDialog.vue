<script setup lang="ts">
/**
 * 知识库表单弹窗（add/edit 双模式，沿 UserFormDialog 先例，替代旧 CreateKbDialog）：
 * - add（契约 §2）：name 1..50 + description ≤500 双必填（DDL 列可空、必填由入参层表达）；
 * - edit（契约 §5）：回显行数据，name+description 全量覆盖提交（双必填与创建同口径）；
 * 业务错误（5003 重名/5004 目标不存在）由 request.ts 拦截器统一 toast，
 * 此处 catch 留空且不关弹窗（可改名重试）
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createKb, updateKb } from '../../../../api/rag'
import type { KbVo } from '../../../../types/api'

export type KbFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: KbFormMode
  /** 编辑时传入行数据（回显 name/description）；新增时缺省 */
  kb?: KbVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface KbFormData {
  name: string
  description: string
}

/** 契约 §2/§5 长度上限（创建编辑同口径）：name 1..50、description ≤500（双必填） */
const NAME_MAX = 50
const DESC_MAX = 500

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<KbFormData>({ name: '', description: '' })

const title = computed(() => (props.mode === 'add' ? '新建知识库' : '编辑知识库'))

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

/** 打开时初始化：edit 回显行数据（description 可 null 降级空串），add 清空；并清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    if (props.mode === 'edit' && props.kb) {
      form.name = props.kb.name
      form.description = props.kb.description ?? ''
    } else {
      form.name = ''
      form.description = ''
    }
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
    if (props.mode === 'add') {
      await createKb({ name: form.name, description: form.description })
      ElMessage.success('新建成功')
    } else if (props.kb) {
      await updateKb(props.kb.id, { name: form.name, description: form.description })
      ElMessage.success('保存成功')
    }
    emit('success')
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
    :title="title"
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
