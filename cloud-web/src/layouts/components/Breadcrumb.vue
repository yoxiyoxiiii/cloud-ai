<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 取 route.matched 过滤 meta.title 渲染（设计 §8，无额外抽象） */
const items = computed(() =>
  route.matched
    .filter((record) => record.meta.title)
    .map((record) => ({ key: record.path, title: record.meta.title as string })),
)
</script>

<template>
  <el-breadcrumb separator="/">
    <el-breadcrumb-item v-for="item in items" :key="item.key">
      {{ item.title }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>
