<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Cloudy, Monitor } from '@element-plus/icons-vue'
import { useAppStore } from '../../stores/app'
import { useMenuStore } from '../../stores/menu'
import { resolveIcon } from '../../constants/icons'
import { APP_TITLE } from '../../constants/app'

const route = useRoute()
const appStore = useAppStore()
const menuStore = useMenuStore()

const collapsed = computed(() => appStore.sidebarCollapsed)

/** 默认展开目录（设计 D10 条件 4）：根级 M 节点 id 列表——子项初始即在渲染树内，
 *  innerText 稳定不赌 EP 折叠动画实现细节（e2e M1 精确串断言依赖）；
 *  守卫先 ensureLoaded 再放行 Layout，故 Sidebar 挂载时 navTree 已就位 */
const openedIds = computed<string[]>(() =>
  menuStore.navTree.filter((node) => node.type === 'M').map((node) => node.id),
)
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
      :default-openeds="openedIds"
    >
      <!-- 动态导航树（设计 D7/D10）：根级 M → el-sub-menu（标题 .el-sub-menu__title 不入
           M1 断言域）；根级 C（孤儿提升场景）→ 直接 el-menu-item；children 恒 C；
           折叠态 tooltip 依赖 #title 插槽（EP 仅在 collapse 且 slots.title 存在时渲染） -->
      <template v-for="node in menuStore.navTree" :key="node.id">
        <el-sub-menu v-if="node.type === 'M'" :index="node.id">
          <template #title>
            <el-icon><component :is="resolveIcon(node.icon)" /></el-icon>
            <span>{{ node.name }}</span>
          </template>
          <el-menu-item v-for="child in node.children" :key="child.id" :index="child.path">
            <el-icon><component :is="resolveIcon(child.icon)" /></el-icon>
            <template #title>{{ child.name }}</template>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item v-else :index="node.path">
          <el-icon><component :is="resolveIcon(node.icon)" /></el-icon>
          <template #title>{{ node.name }}</template>
        </el-menu-item>
      </template>
      <!-- 静态工作台尾挂（设计 D10 条件 3）：模板顺序=DOM 顺序，恰为末项——
           M1 精确串 '用户管理,角色管理,菜单管理,工作台' 依赖此结构，勿移到动态树之前 -->
      <el-menu-item index="/dashboard">
        <el-icon><Monitor /></el-icon>
        <template #title>工作台</template>
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
