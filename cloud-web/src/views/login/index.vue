<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { useTagsStore } from '../../stores/tags'
import { APP_TITLE } from '../../constants/app'
// 登录背景图（升级设计 D11）：Pexels photo 2341830，来源页
// https://www.pexels.com/photo/2341830/ ，Pexels License（免商用、免署名、可修改）；
// 171,962 字节 ≤300KB 入库红线
import loginBg from '../../assets/login-bg.jpg'
import type { R } from '../../types/api'

interface LoginForm {
  account: string
  password: string
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const tagsStore = useTagsStore()

onMounted(() => {
  // 会话清理（升级设计 D4）：登录页是"进入新会话"的必经点（手动退出/401 清态跳转/直接访问），
  // 在此重置页签可统一覆盖三条路径；缓存的列表页组件随 cachedNames 清空被 keep-alive 自动剪枝
  tagsStore.closeAll()
})

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
  <div class="login-page" :style="{ backgroundImage: `url(${loginBg})` }">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="login-title">{{ APP_TITLE }}</div>
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
  /* 底色取背景图主色：图未加载/加载失败时兜底，不至于刺眼白屏（升级设计 D11）。
     登录页深色底不走 EP 变量：登录页无主题切换入口，卡片内部仍全走 EP 变量 */
  background-color: #0b1e3f;
  background-size: cover;
  background-position: center;
}

.login-card {
  width: 380px;
}

.login-title {
  font-size: 20px;
  font-weight: 700;
  text-align: center;
}

.login-alert {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
}
</style>
