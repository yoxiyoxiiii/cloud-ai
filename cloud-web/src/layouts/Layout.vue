<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from './components/Sidebar.vue'
import Navbar from './components/Navbar.vue'
import TagsView from './components/TagsView.vue'
import Watermark from './components/Watermark.vue'
import { useAppStore } from '../stores/app'
import { useTagsStore } from '../stores/tags'

const appStore = useAppStore()
const tagsStore = useTagsStore()
const route = useRoute()

// 单一 watcher 同步页签（升级设计 D5）：Layout 是所有受保护路由的父级，
// watcher 生命周期与标签页一致；跳过中转路由与公开页（immediate 覆盖首屏建签）
watch(
  () => route.fullPath,
  () => {
    if (route.name && route.name !== 'Redirect' && !route.meta.public) {
      tagsStore.addTag(route)
    }
  },
  { immediate: true },
)
</script>

<template>
  <el-container class="layout">
    <el-aside :width="appStore.sidebarCollapsed ? '64px' : '200px'" class="layout-aside">
      <Sidebar />
    </el-aside>
    <el-container>
      <el-header height="48px" class="layout-header">
        <Navbar />
      </el-header>
      <TagsView />
      <el-main class="layout-main">
        <!-- keep-alive include 按组件名匹配（视图必须 defineOptions name = route.name）；
             不设 :key——同名不同 fullPath 复用同一缓存实例（升级设计 D5，当前无带 query 页面） -->
        <router-view v-slot="{ Component }">
          <keep-alive :include="tagsStore.cachedNames">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
  <!-- 全局水印：Layout 根级兄弟节点，fixed 覆盖全屏不进 DOM 事件链（升级设计 D8） -->
  <Watermark />
</template>

<style scoped>
.layout {
  height: 100vh;
}

.layout-aside {
  border-right: 1px solid var(--el-border-color-light);
}

.layout-header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-light);
}

.layout-main {
  background-color: var(--el-fill-color-lighter);
}
</style>
