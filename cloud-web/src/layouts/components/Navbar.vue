<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../../stores/auth'
import Breadcrumb from './Breadcrumb.vue'

const router = useRouter()
const authStore = useAuthStore()

async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    // logoutAction 失败也清本地态（设计 §6），跳转由本组件处理
    await authStore.logoutAction()
    router.push('/login')
  }
}
</script>

<template>
  <div class="navbar">
    <Breadcrumb />
    <el-dropdown @command="handleCommand">
      <span class="navbar-account">
        {{ authStore.account }}
        <el-icon><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.navbar-account {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--el-text-color-primary);
  outline: none;
}
</style>
