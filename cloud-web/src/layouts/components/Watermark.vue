<script setup lang="ts">
/**
 * 全局水印（升级设计 D8，手写不引三方库）
 * - 离屏 canvas（200×120）绘制当前账号（rotate ≈ -22°，字号 14）→ dataURL → 单个
 *   .app-watermark 的 background repeat 平铺（无 resize 监听需求）
 * - fixed 全屏 + pointer-events:none + z-index 9999（高于 EP 弹窗/message）：水印盖住
 *   弹窗与消息但半透明稀疏，不遮挡可读性；不进 DOM 事件链（既有 32 项 e2e 在水印
 *   常驻下全过即"不挡交互"的回归证明）
 * - 文字颜色是唯一不走 EP CSS 变量的色值（canvas 位图需显式色值）：light 态
 *   rgba(0,0,0,0.13) / dark 态 rgba(255,255,255,0.13)，watch theme 重绘
 * - account 为空整个组件 v-if 不渲染；不做防篡改对抗（记录移交，设计 §8.3）
 */
import { onMounted, ref, watch } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useAppStore } from '../../stores/app'

const authStore = useAuthStore()
const appStore = useAppStore()

/** 水印平铺背景（PNG dataURL） */
const watermarkUrl = ref('')

/** canvas 专用文字颜色：深浅两态（一次性位图，无法用 CSS 变量） */
const INK_LIGHT = 'rgba(0, 0, 0, 0.13)'
const INK_DARK = 'rgba(255, 255, 255, 0.13)'

/** 离屏 canvas 绘制单个水印瓦片 → dataURL */
function drawWatermark(): void {
  const canvas = document.createElement('canvas')
  canvas.width = 200
  canvas.height = 120
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return
  }
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.translate(canvas.width / 2, canvas.height / 2)
  ctx.rotate((-22 * Math.PI) / 180)
  ctx.font = '14px sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = appStore.theme === 'dark' ? INK_DARK : INK_LIGHT
  ctx.fillText(authStore.account, 0, 0)
  watermarkUrl.value = canvas.toDataURL('image/png')
}

onMounted(() => {
  drawWatermark()
})

// 主题切换即时重绘（canvas 位图不随 CSS 变量自动翻转）
watch(
  () => appStore.theme,
  () => {
    drawWatermark()
  },
)
</script>

<template>
  <div
    v-if="authStore.account"
    class="app-watermark"
    :style="watermarkUrl ? { backgroundImage: `url(${watermarkUrl})` } : undefined"
  />
</template>

<style scoped>
.app-watermark {
  position: fixed;
  inset: 0;
  z-index: 9999;
  pointer-events: none;
  user-select: none;
  background-repeat: repeat;
}
</style>
