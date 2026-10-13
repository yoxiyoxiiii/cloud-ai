import { onMounted, onUnmounted, ref, type Ref } from 'vue'

/**
 * 消息列表“粘底”滚动：距底 < threshold 视为跟随态；用户上滚即暂停跟随
 * （流继续到达但不强制滚动），流式回调按 stick 决定是否 scrollToBottom。
 */
export function useStickToBottom(el: Ref<HTMLElement | undefined>, threshold = 80) {
  const stick = ref(true)

  function onScroll(): void {
    const n = el.value
    if (!n) return
    stick.value = n.scrollHeight - n.scrollTop - n.clientHeight < threshold
  }

  function scrollToBottom(smooth = false): void {
    el.value?.scrollTo({ top: el.value.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  }

  onMounted(() => el.value?.addEventListener('scroll', onScroll, { passive: true }))
  onUnmounted(() => el.value?.removeEventListener('scroll', onScroll))

  return { stick, scrollToBottom }
}
