<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createUser, updateUser } from '../../../../api/user'
import type { SysUserVo } from '../../../../types/api'

export type UserFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: UserFormMode
  /** 编辑时传入行数据；新增时缺省 */
  user?: SysUserVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface UserFormData {
  account: string
  nickname: string
  password: string
  status: number
}

/** 用户状态（契约 §3）：0=正常 1=停用 */
const STATUS_NORMAL = 0
const STATUS_DISABLED = 1

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<UserFormData>({
  account: '',
  nickname: '',
  password: '',
  status: STATUS_NORMAL,
})

const rules: FormRules<UserFormData> = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  // 密码 6-32 位为前端约定兜底（契约 §7.4：后端无强约束）
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' },
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const title = computed(() => (props.mode === 'add' ? '新增用户' : '编辑用户'))

/** 打开时初始化：编辑回显行数据（account 只读展示），新增给默认值 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.user) {
      form.account = props.user.account
      form.nickname = props.user.nickname
      form.password = ''
      form.status = props.user.status
    } else {
      form.account = ''
      form.nickname = ''
      form.password = ''
      form.status = STATUS_NORMAL
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
      await createUser({
        account: form.account,
        nickname: form.nickname,
        password: form.password,
        status: form.status,
      })
      ElMessage.success('新增成功')
    } else if (props.user) {
      // account 与 password 不可改（契约 §3.4），仅提交 id/nickname/status
      await updateUser({
        id: props.user.id,
        nickname: form.nickname,
        status: form.status,
      })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如账号已存在的业务 msg）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="480px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="账号" prop="account">
        <el-input
          v-if="mode === 'add'"
          v-model="form.account"
          placeholder="请输入账号"
          :disabled="loading"
        />
        <!-- 编辑时 account 不可改，只读展示 -->
        <el-input v-else :model-value="form.account" disabled />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" placeholder="请输入昵称" :disabled="loading" />
      </el-form-item>
      <el-form-item v-if="mode === 'add'" label="初始密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-32 位"
          show-password
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="form.status" :disabled="loading">
          <el-radio :value="STATUS_NORMAL">正常</el-radio>
          <!-- 内置用户（admin）仅禁「停用」项（保护契约 §7.2/§2：停用→3017）；
               「正常」不禁、弹窗不整体锁死——昵称可改是合法运维；新增模式 user 缺省 → 不禁 -->
          <el-radio :value="STATUS_DISABLED" :disabled="user?.builtin ?? false">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>
