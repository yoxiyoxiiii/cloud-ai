<script setup lang="ts">
import Sidebar from './components/Sidebar.vue'
import Navbar from './components/Navbar.vue'
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="layout-aside">
      <Sidebar />
    </el-aside>
    <el-container>
      <el-header height="48px" class="layout-header">
        <Navbar />
      </el-header>
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
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
