<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { resetUserPassword } from '../../../../api/user'
import type { SysUserVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  user?: SysUserVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ password: '', confirm: '' })

/** 密码 6-32 位为前端约定兜底（契约 §7.4） */
const rules: FormRules<typeof form> = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

const title = computed(() => `重置密码${props.user ? `（${props.user.account}）` : ''}`)

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    form.password = ''
    form.confirm = ''
    formRef.value?.clearValidate()
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value || !props.user) {
    return
  }
  loading.value = true
  try {
    await resetUserPassword(props.user.id, form.password)
    ElMessage.success('密码重置成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="440px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="新密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-32 位"
          show-password
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirm">
        <el-input
          v-model="form.confirm"
          type="password"
          placeholder="再次输入新密码"
          show-password
          :disabled="loading"
          @keyup.enter="handleSubmit"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>
