<script setup lang="ts">
/**
 * 兜底页（动态路由设计 D5）：一处承接三类场景——
 * 1. 真 404（路径不存在）；2. 无权限直链（未绑菜单的 path 不被动态层注册，落 catchAll）；
 * 3. 未注册 path 的已绑菜单（viewRegistry 兜底也复用本组件）
 * 挂 Layout children（渲染侧边栏），catchAll 特异性恒最低，与 addRoute 顺序无竞争
 */
import { useRouter } from 'vue-router'

// 组件名 = 路由 name（keep-alive 契约：include 按名匹配，视图必须显式固定）
defineOptions({ name: 'NotFound' })

const router = useRouter()

function goDashboard(): void {
  router.push('/dashboard')
}
</script>

<template>
  <div class="error-page">
    <el-card shadow="never" class="error-card">
      <el-result icon="warning" title="404" sub-title="页面不存在或无访问权限">
        <template #extra>
          <el-button type="primary" @click="goDashboard">返回工作台</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}

.error-card {
  width: 480px;
}
</style>
