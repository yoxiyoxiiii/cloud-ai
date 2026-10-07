<script setup lang="ts">
/**
 * 导航加载失败页（动态路由设计 D6）：user-nav 拉取失败的专用落点。
 * 不可跳 /login——已登录用户会被登录页守卫（public && logged → '/'）弹回，
 * 加载再失败再跳成无限重定向环（vue-router 检测到环抛错白屏）；
 * 故守卫最优先放行本页（不回弹），页内自选重试或重新登录
 */
import { useRoute, useRouter } from 'vue-router'
import { useMenuStore } from '../../stores/menu'
import { clearAuth } from '../../utils/storage'

// 组件名 = 路由 name（keep-alive 契约）
defineOptions({ name: 'MenuError' })

const route = useRoute()
const router = useRouter()
const menuStore = useMenuStore()

/** 取守卫带来的回跳目标（query.redirect，单参防御数组形态），缺省回首页 */
function redirectTarget(): string {
  const query = route.query.redirect
  const redirect = Array.isArray(query) ? query[0] : query
  return redirect || '/'
}

/** 重试：清动态路由态后回原目标——守卫将重新 ensureLoaded；再失败仍回本页（环安全） */
function handleRetry(): void {
  menuStore.reset()
  router.push(redirectTarget())
}

/** 重新登录：清 token 回登录页（登录后按 redirect query 回跳原目标） */
function handleRelogin(): void {
  clearAuth()
  router.push('/login')
}
</script>

<template>
  <div class="error-page">
    <el-card shadow="never" class="error-card">
      <el-result icon="warning" title="菜单加载失败" sub-title="菜单加载失败，请检查网络后重试">
        <template #extra>
          <el-button type="primary" @click="handleRetry">重试</el-button>
          <el-button @click="handleRelogin">重新登录</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}

.error-card {
  width: 480px;
}
</style>
