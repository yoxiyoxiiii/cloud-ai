<script setup lang="ts">
/**
 * 公共 bpmn 只读渲染组件（计划 F2 / 设计 D2）：
 * - NavigatedViewer 经 dynamic import 加载（分包铁律 D1：bpmn-js 不进主包；
 *   本组件自身不注册 viewRegistry，被消费处一律 defineAsyncComponent 引入，
 *   下方两个 CSS 静态 import 随之进入同一 async chunk）
 * - marker 双高亮：activeIds 主高亮（绿——当前活动节点 / 终态结束节点，契约 §3 三态矩阵
 *   由消费处组好 id 集合传入）、completedIds 浅色（已执行路径）；多余 id 对 addMarker 无害（D2）
 * - importXML 解析失败留空态（理论不发生——XML 出自引擎）；onUnmounted destroy 防泄漏
 */
import { onMounted, onUnmounted, ref, watch } from 'vue'
import type Canvas from 'diagram-js/lib/core/Canvas'
// bpmn-js 基础样式（djs 容器/字体）——随本组件 async chunk 分包（消费处 defineAsyncComponent 前提）
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'

interface Props {
  /** BPMN 2.0 XML 原文（契约 §2.1 data.xml——部署时原始资源） */
  xml: string
  /** 主高亮节点 id 集（activeActivityIds，终态时消费处将 endActivityId 并入） */
  activeIds?: string[]
  /** 浅色高亮节点 id 集（completedActivityIds——已执行路径） */
  completedIds?: string[]
}

const props = defineProps<Props>()

const containerRef = ref<HTMLElement>()
const loading = ref(false)
const failed = ref(false)

/** viewer 实例（类型为纯类型引用，编译期擦除——不构成对 bpmn-js 的静态依赖） */
let viewer: import('bpmn-js/lib/NavigatedViewer').default | undefined

/** 渲染序号：xml/高亮快速变更时旧异步结果作废（竞态防抖） */
let renderSeq = 0

/** 渲染主流程：懒加载 Viewer → importXML → fit-viewport → 双类 marker */
async function render(): Promise<void> {
  const xml = props.xml
  if (!xml || !containerRef.value) {
    return
  }
  const seq = ++renderSeq
  loading.value = true
  failed.value = false
  try {
    if (!viewer) {
      const { default: NavigatedViewer } = await import('bpmn-js/lib/NavigatedViewer')
      if (seq !== renderSeq || !containerRef.value) {
        return
      }
      viewer = new NavigatedViewer({ container: containerRef.value })
    }
    await viewer.importXML(xml)
    if (seq !== renderSeq) {
      return
    }
    const canvas = viewer.get<Canvas>('canvas')
    canvas.zoom('fit-viewport')
    for (const id of props.activeIds ?? []) {
      canvas.addMarker(id, 'bpmn-highlight-active')
    }
    for (const id of props.completedIds ?? []) {
      canvas.addMarker(id, 'bpmn-highlight-completed')
    }
  } catch {
    if (seq === renderSeq) {
      failed.value = true
    }
  } finally {
    if (seq === renderSeq) {
      loading.value = false
    }
  }
}

onMounted(() => {
  void render()
})

/** xml 与高亮集任一变更即重渲染（importXML 幂等，marker 随画布重建重挂） */
watch([() => props.xml, () => props.activeIds, () => props.completedIds], () => {
  void render()
})

onUnmounted(() => {
  renderSeq++
  viewer?.destroy()
  viewer = undefined
})
</script>

<template>
  <div class="bpmn-viewer" v-loading="loading">
    <div ref="containerRef" class="bpmn-viewer-container"></div>
    <el-empty v-if="failed" class="bpmn-viewer-empty" description="流程图渲染失败" :image-size="60" />
  </div>
</template>

<style scoped>
/* 固定高容器（计划 F2）：djs 需有确定尺寸容器才能计算 viewport */
.bpmn-viewer {
  position: relative;
  height: 360px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
}

.bpmn-viewer-container {
  height: 100%;
  width: 100%;
}

/* 失败空态覆盖在容器上（不与半渲染画布叠加争位） */
.bpmn-viewer-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: var(--el-bg-color);
}

/* R5：diagram-js 元素由 bpmn-js 命令式创建、不带 scoped 属性——高亮选择器须 :deep() 穿透；
 * marker 类挂在 g.djs-element 上，形状与连线分治（bpm.io 官方高亮样板形态）。
 * 终态结束节点会同时挂 completed+active 两类 marker（completedActivityIds 含 end 事件）——
 * 同 specificity 下后声明者胜，故 active 组放在 completed 组之后 */
/* 浅色（已执行路径） */
:deep(.bpmn-highlight-completed:not(.djs-connection) .djs-visual > :nth-child(1)) {
  fill: #f0f9eb !important;
  stroke: #b3e19d !important;
}

:deep(.bpmn-highlight-completed.djs-connection .djs-visual > :nth-child(1)) {
  stroke: #b3e19d !important;
}

/* 主高亮（绿，EP success 色系）：当前活动节点 / 终态结束节点 */
:deep(.bpmn-highlight-active:not(.djs-connection) .djs-visual > :nth-child(1)) {
  fill: #e1f3d8 !important;
  stroke: #67c23a !important;
  stroke-width: 2 !important;
}

:deep(.bpmn-highlight-active.djs-connection .djs-visual > :nth-child(1)) {
  stroke: #67c23a !important;
}
</style>
