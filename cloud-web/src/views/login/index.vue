<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import type { R } from '../../types/api'

interface LoginForm {
  account: string
  password: string
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
/** 内联错误（后端 msg 直接展示，不逐码映射——契约 §1 错误码处理策略） */
const errorMsg = ref('')

const form = reactive<LoginForm>({ account: '', password: '' })

const rules: FormRules<LoginForm> = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 从 reject 体提取提示文案：业务失败 reject R（有 msg），网络失败 reject axios 错误（有 message） */
function extractMsg(e: unknown): string {
  if (typeof e === 'object' && e !== null) {
    const body = e as R & { message?: string }
    if (typeof body.msg === 'string' && body.msg) {
      return body.msg
    }
    if (typeof body.message === 'string' && body.message) {
      return body.message
    }
  }
  return '登录失败，请稍后重试'
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value) {
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    await authStore.loginAction(form.account, form.password)
    form.account = ''
    form.password = ''
    const redirectQuery = route.query.redirect
    const redirect = Array.isArray(redirectQuery) ? redirectQuery[0] : redirectQuery
    await router.push(redirect || '/')
  } catch (e: unknown) {
    errorMsg.value = extractMsg(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="login-title">cloud-web 登录</div>
      </template>
      <el-alert
        v-if="errorMsg"
        :title="errorMsg"
        type="error"
        show-icon
        :closable="false"
        class="login-alert"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="账号" prop="account">
          <el-input
            v-model="form.account"
            placeholder="请输入账号"
            :disabled="loading"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            :disabled="loading"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            class="login-submit"
            native-type="submit"
            :loading="loading"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background-color: var(--el-fill-color-lighter);
}

.login-card {
  width: 380px;
}

.login-title {
  font-size: 16px;
  font-weight: 600;
  text-align: center;
}

.login-alert {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
}
</style>
