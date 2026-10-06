<script setup lang="ts">
/**
 * 刷新中转路由（升级设计 D6）：进入即 replace 回目标页，空模板不渲染内容。
 * 刷新动作链：removeCached(当前页 name)（keep-alive 剪枝旧实例）→ replace 到 /redirect/<目标>
 * → 本组件中转回目标页 → Layout watcher 重新 addTag → 全新挂载（数据重拉、状态归零）。
 */
import { useRoute, useRouter } from 'vue-router'

defineOptions({ name: 'Redirect' })

const route = useRoute()
const router = useRouter()

// :path(.*) 通配参数收窄（单参声明下运行时是 string，防御数组形态）
const pathParam = route.params.path
const target = Array.isArray(pathParam) ? pathParam.join('/') : pathParam

router.replace({ path: `/${target}`, query: route.query })
</script>

<template>
  <div />
</template>
