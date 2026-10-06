<script setup lang="ts">
// EP 按需模式下 locale 的全局配置点：ConfigProvider 包裹根节点
// （全量模式的 app.use(ElementPlus, { locale }) 已随全量引入一并移除）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
