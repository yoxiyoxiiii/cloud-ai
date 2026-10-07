<script setup lang="ts">
/**
 * 菜单搜索（升级设计 D10）
 * - 顶栏 .navbar-search 图标按钮 + 全局快捷键 Ctrl+K / Ctrl+Shift+K（含 meta 判断，preventDefault）；
 *   仅 Ctrl/Meta 组合态拦键——裸 Shift+K 不拦（会污染输入框大写 K，主控裁决澄清）
 * - 数据源 menuStore.menuItems（动态路由单一来源：user-nav 树扁平化 C 项 + 工作台尾挂，
 *   与 Sidebar 动态树同源——原 constants/menus.ts 已删除）
 * - el-dialog 外层 v-if 包裹：默认零 DOM（e2e 契约：初始 .menu-search-dialog count === 0）
 * - 交互：输入即过滤（title 不区分大小写 includes，空关键词全量）；↑↓ 环回移动高亮、
 *   Enter/点击行跳转并关闭、Esc 走 el-dialog 默认关闭；无结果 el-empty；@opened 聚焦输入框
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import type { InputInstance } from 'element-plus'
import { useMenuStore } from '../../stores/menu'
import type { MenuItem } from '../../stores/menu'

const router = useRouter()
const menuStore = useMenuStore()

const visible = ref(false)
const keyword = ref('')
const activeIndex = ref(0)
const inputRef = ref<InputInstance>()

/** 过滤：空关键词全量；title 不区分大小写 includes（不做拼音/模糊匹配，无新依赖红线） */
const filtered = computed<MenuItem[]>(() => {
  const items = menuStore.menuItems
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return items
  }
  return items.filter((m) => m.title.toLowerCase().includes(kw))
})

function open(): void {
  if (visible.value) {
    return
  }
  visible.value = true
  keyword.value = ''
  activeIndex.value = 0
}

function close(): void {
  visible.value = false
}

/** 全局快捷键：Ctrl+K / Ctrl+Shift+K（含 meta），preventDefault 拦截浏览器默认行为 */
function onGlobalKeydown(e: KeyboardEvent): void {
  const key = e.key?.toLowerCase()
  if (key === 'k' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    open()
  }
}

/** ↑↓ 环回移动高亮 */
function moveActive(delta: number): void {
  const n = filtered.value.length
  if (n === 0) {
    return
  }
  activeIndex.value = (activeIndex.value + delta + n) % n
}

/** Enter 跳高亮项（高亮项不存在时 no-op，设计 §6） */
function enterSelect(): void {
  const item = filtered.value[activeIndex.value]
  if (!item) {
    return
  }
  go(item)
}

/** 跳转并关闭弹层 */
function go(item: MenuItem): void {
  close()
  router.push(item.path)
}

/** 弹层动画完成后聚焦输入框 */
async function focusInput(): Promise<void> {
  await nextTick()
  inputRef.value?.focus()
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <button type="button" class="navbar-icon-btn navbar-search" @click="open">
    <el-icon :size="16"><Search /></el-icon>
  </button>
  <!-- 外层 v-if：关闭态整个弹层（含 overlay）零 DOM -->
  <el-dialog
    v-if="visible"
    v-model="visible"
    class="menu-search-dialog"
    title="菜单搜索"
    width="480px"
    append-to-body
    @opened="focusInput"
  >
    <el-input
      ref="inputRef"
      v-model="keyword"
      placeholder="搜索菜单，↑↓ 选择，Enter 跳转"
      clearable
      @keydown.up.prevent="moveActive(-1)"
      @keydown.down.prevent="moveActive(1)"
      @keydown.enter.prevent="enterSelect"
    />
    <div v-if="filtered.length" class="menu-search-list">
      <div
        v-for="(item, i) in filtered"
        :key="item.path"
        class="menu-search-item"
        :class="{ 'is-active': i === activeIndex }"
        @click="go(item)"
        @mousemove="activeIndex = i"
      >
        <span class="menu-search-item-title">{{ item.title }}</span>
        <span class="menu-search-item-path">{{ item.path }}</span>
      </div>
    </div>
    <el-empty v-else description="无匹配菜单" :image-size="60" />
  </el-dialog>
</template>

<style scoped>
.menu-search-list {
  max-height: 300px;
  overflow-y: auto;
  margin-top: 8px;
}

.menu-search-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-radius: 4px;
  cursor: pointer;
}

.menu-search-item.is-active {
  background-color: var(--el-fill-color);
}

.menu-search-item-title {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.menu-search-item-path {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
