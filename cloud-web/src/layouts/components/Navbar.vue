<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Expand, Fold, FullScreen, Moon, Sunny } from '@element-plus/icons-vue'
import { useAuthStore } from '../../stores/auth'
import { useAppStore } from '../../stores/app'
import Breadcrumb from './Breadcrumb.vue'
import MenuSearch from './MenuSearch.vue'

const router = useRouter()
const authStore = useAuthStore()
const appStore = useAppStore()

/** 主题图标随态切换：深色态显示"去浅色"（Sunny），浅色态显示"去深色"（Moon） */
const themeIcon = computed(() => (appStore.theme === 'dark' ? Sunny : Moon))

/** 汉堡图标随态切换：展开态显示"收起"（Fold），折叠态显示"展开"（Expand） */
const collapseIcon = computed(() => (appStore.sidebarCollapsed ? Expand : Fold))

/** 全屏态（fullscreenchange 事件同步，覆盖按钮/ESC/系统快捷键等一切进出全屏路径） */
const isFullscreen = ref(false)

function syncFullscreenState(): void {
  isFullscreen.value = !!document.fullscreenElement
}

function toggleFullscreen(): void {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {})
  } else {
    // 不支持/被拒环境 promise reject：静默吞掉，无 toast 无 console（升级设计 D9，M-VERIFY 零 console）
    document.documentElement.requestFullscreen().catch(() => {})
  }
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullscreenState)
})

onUnmounted(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
})

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
    <div class="navbar-left">
      <button type="button" class="navbar-icon-btn navbar-collapse" @click="appStore.toggleSidebar()">
        <el-icon :size="16"><component :is="collapseIcon" /></el-icon>
      </button>
      <Breadcrumb />
    </div>
    <div class="navbar-right">
      <MenuSearch />
      <button type="button" class="navbar-icon-btn navbar-fullscreen" @click="toggleFullscreen()">
        <el-icon :size="16"><FullScreen /></el-icon>
      </button>
      <button type="button" class="navbar-icon-btn navbar-theme" @click="appStore.toggleTheme()">
        <el-icon :size="16"><component :is="themeIcon" /></el-icon>
      </button>
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
  </div>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.navbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 顶栏图标按钮公共样式 .navbar-icon-btn 已抽至全局 style.css（跨 Navbar/MenuSearch 组件复用） */

.navbar-account {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--el-text-color-primary);
  outline: none;
}
</style>
