<script setup lang="ts">
/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（升级设计 D5 红字坑） */
defineOptions({ name: 'Dashboard' })
</script>

<template>
  <el-card shadow="never">
    <template #header>工作台</template>
    工作台建设中
  </el-card>
</template>
