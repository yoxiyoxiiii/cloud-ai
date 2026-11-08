<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Bell, Expand, Fold, FullScreen, Moon, Sunny } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { useAppStore } from '../../stores/app'
import { useNotifyStore } from '../../stores/notify'
import { pageMyNotify } from '../../api/notify'
import { NOTIFY_LEVEL_TAG, NOTIFY_TYPE_MAP, type NotifyUserMessageVo } from '../../types/api'
import Breadcrumb from './Breadcrumb.vue'
import MenuSearch from './MenuSearch.vue'

const router = useRouter()
const authStore = useAuthStore()
const appStore = useAppStore()
const notifyStore = useNotifyStore()

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
  // 通知轮询挂 Navbar 生命周期：登出时 Layout 卸载自然停轮询（notify store 头注释）
  notifyStore.startPolling()
})

onUnmounted(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  notifyStore.stopPolling()
})

/* ============ 通知铃铛下拉（契约 §3.2 最近未读 5 条） ============ */

/** popover 实例（跳转前手动收起；trigger=click 下路由切换不会自动关） */
const notifyPopoverRef = ref<{ hide: () => void }>()

const recentRows = ref<NotifyUserMessageVo[]>([])
const recentLoading = ref(false)

/** 下拉展开时拉最近未读 5 条（失败静默——拦截器已 toast） */
async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    const page = await pageMyNotify({ pageNum: 1, pageSize: 5, readFlag: 0 })
    recentRows.value = page.rows
  } catch {
    // 拦截器已统一 toast，面板保持现状
  } finally {
    recentLoading.value = false
  }
}

/** 全部已读（契约 §3.4）：toast「已读 N 条」+ 面板刷新（角标随 store 联动归零） */
async function handleReadAll(): Promise<void> {
  try {
    const count = await notifyStore.markReadAll()
    ElMessage.success(`已读 ${count} 条`)
    await loadRecent()
  } catch {
    // 拦截器已统一 toast
  }
}

/** 查看全部 → 消息中心（静态路由，登录即可达）；先收起 popover */
function goNotifyCenter(): void {
  notifyPopoverRef.value?.hide()
  void router.push('/notify/center')
}

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
      <el-popover
        ref="notifyPopoverRef"
        placement="bottom-end"
        :width="360"
        trigger="click"
        popper-class="notify-popover"
        @show="loadRecent"
      >
        <template #reference>
          <button type="button" class="navbar-icon-btn navbar-bell" title="通知">
            <el-badge :value="notifyStore.unreadCount" :hidden="notifyStore.unreadCount === 0" :max="99">
              <el-icon :size="16"><Bell /></el-icon>
            </el-badge>
          </button>
        </template>
        <div class="notify-panel">
          <div class="notify-panel-header">通知</div>
          <div v-if="recentLoading" class="notify-empty">加载中…</div>
          <div v-else-if="recentRows.length === 0" class="notify-empty">暂无未读消息</div>
          <div v-else class="notify-list">
            <div v-for="row in recentRows" :key="row.id" class="notify-item">
              <span class="notify-dot" :class="`notify-dot--${NOTIFY_LEVEL_TAG[row.level] ?? 'info'}`" />
              <div class="notify-item-main">
                <div class="notify-item-title">{{ row.title }}</div>
                <div class="notify-item-meta">
                  <span>{{ NOTIFY_TYPE_MAP[row.type] ?? row.type }}</span>
                  <span>{{ row.sendTime }}</span>
                </div>
              </div>
            </div>
          </div>
          <div class="notify-panel-footer">
            <el-button
              link
              type="primary"
              size="small"
              :disabled="notifyStore.unreadCount === 0"
              @click="handleReadAll"
            >
              全部已读
            </el-button>
            <el-button link type="primary" size="small" @click="goNotifyCenter">查看全部</el-button>
          </div>
        </div>
      </el-popover>
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

/* 通知下拉面板（el-popover 插槽内容编译期带本组件 scope id，scoped 样式可达） */
.notify-panel {
  display: flex;
  flex-direction: column;
  max-height: 420px;
}

.notify-panel-header {
  font-weight: 600;
  padding: 2px 4px 8px;
}

.notify-empty {
  padding: 24px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.notify-list {
  overflow-y: auto;
}

.notify-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.notify-item:last-child {
  border-bottom: none;
}

.notify-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--el-color-info);
}

.notify-dot--warning {
  background: var(--el-color-warning);
}

.notify-dot--danger {
  background: var(--el-color-danger);
}

.notify-item-main {
  flex: 1;
  min-width: 0;
}

.notify-item-title {
  font-size: 13px;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notify-item-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.notify-panel-footer {
  display: flex;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 4px;
}
</style>
