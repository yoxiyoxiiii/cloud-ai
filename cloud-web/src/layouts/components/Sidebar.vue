<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Cloudy } from '@element-plus/icons-vue'
import { useAppStore } from '../../stores/app'
import { APP_TITLE } from '../../constants/app'
import { MENU_ITEMS } from '../../constants/menus'

const route = useRoute()
const appStore = useAppStore()

const collapsed = computed(() => appStore.sidebarCollapsed)
</script>

<template>
  <div class="sidebar">
    <!-- 品牌区必须是 el-menu 的兄弟节点而非其内部元素（升级设计 D3）：
         e2e 以 .el-menu .el-menu-item 断言菜单项数量与顺序（M1 精确串），
         品牌区若做成 menu-item 会混入断言域 -->
    <div class="sidebar-brand" :class="{ 'sidebar-brand--collapsed': collapsed }">
      <el-icon :size="22" class="sidebar-brand-icon"><Cloudy /></el-icon>
      <!-- v-show 而非 v-if：折叠瞬间隐藏不卸载，避免品牌区高度/宽度抖动 -->
      <span v-show="!collapsed" class="sidebar-brand-title">{{ APP_TITLE }}</span>
    </div>
    <el-menu
      class="sidebar-menu"
      :default-active="route.path"
      router
      :collapse="collapsed"
      :collapse-transition="false"
    >
      <!-- 折叠态 tooltip 依赖 #title 插槽（EP 仅在 collapse 且 slots.title 存在时渲染 tooltip） -->
      <el-menu-item v-for="menu in MENU_ITEMS" :key="menu.path" :index="menu.path">
        <el-icon><component :is="menu.icon" /></el-icon>
        <template #title>{{ menu.title }}</template>
      </el-menu-item>
    </el-menu>
  </div>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  padding: 0 16px;
  box-sizing: border-box;
  border-bottom: 1px solid var(--el-border-color-light);
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
}

/* 折叠态仅图标居中 */
.sidebar-brand--collapsed {
  justify-content: center;
  padding: 0;
}

.sidebar-brand-title {
  font-size: 14px;
  font-weight: 600;
}

.sidebar-menu {
  flex: 1;
  overflow-y: auto;
  /* 去掉 el-menu 默认右边框，统一由 .layout-aside 的边框负责（避免双线） */
  border-right: none;
}
</style>
