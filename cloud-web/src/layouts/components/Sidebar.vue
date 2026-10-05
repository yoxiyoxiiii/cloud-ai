<script setup lang="ts">
import type { Component } from 'vue'
import { useRoute } from 'vue-router'
import { Monitor, User } from '@element-plus/icons-vue'

interface MenuItem {
  path: string
  title: string
  icon: Component
}

/** 静态菜单（设计 §8）：不读 menu/tree 接口（契约先行、MVP 不消费） */
const menus: MenuItem[] = [
  { path: '/system/user', title: '用户管理', icon: User },
  { path: '/dashboard', title: '工作台', icon: Monitor },
]

const route = useRoute()
</script>

<template>
  <el-menu :default-active="route.path" router>
    <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
      <el-icon><component :is="menu.icon" /></el-icon>
      <span>{{ menu.title }}</span>
    </el-menu-item>
  </el-menu>
</template>
