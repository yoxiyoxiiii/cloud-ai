<!-- 消息 markdown 渲染：LLM 输出不可信——md(html:false) → DOMPurify → v-html，顺序不可颠倒 -->
<template>
  <div class="md-body" v-html="html"></div>
</template>

<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
// hljs 只产出 span.hljs-* 标记，配色靠主题样式表——不导入则代码块单色（视觉核对实证）
import 'highlight.js/styles/github.css'

const props = defineProps<{ content: string }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  highlight(code: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch {
        // fallthrough：走 markdown-it 默认转义
      }
    }
    return ''
  },
})

/** 流式半截 fence 补全：奇数个 ``` 时补一个，避免代码块闪成普通文本 */
function healFence(text: string): string {
  const n = (text.match(/```/g) ?? []).length
  return n % 2 === 1 ? `${text}\n\`\`\`` : text
}

const html = ref('')
let timer: ReturnType<typeof setTimeout> | null = null
let lastInput = ''

function render(): void {
  timer = null
  html.value = DOMPurify.sanitize(md.render(healFence(lastInput)))
}

// 节流重渲（80ms）：流式高频 token 不逐次全量渲染（DOMPurify 每次都要跑——中间态同样是注入面）
watch(
  () => props.content,
  (c) => {
    lastInput = c
    if (timer === null) {
      timer = setTimeout(render, 80)
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  if (timer !== null) clearTimeout(timer)
})
</script>

<style scoped>
.md-body :deep(pre) {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  overflow: auto;
}
.md-body :deep(code) {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
}
.md-body :deep(p) {
  margin: 0 0 8px;
}
.md-body :deep(p:last-child) {
  margin-bottom: 0;
}
</style>
