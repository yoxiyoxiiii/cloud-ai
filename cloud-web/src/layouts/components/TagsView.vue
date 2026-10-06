<script setup lang="ts">
/**
 * 多标签页导航条（升级设计 D7）
 * - 页签：点击切换路由；自带关闭图标（EP el-tag closable，内部 close 已 stopPropagation）
 * - 关闭当前签落点 = 左邻 → 右邻 → /dashboard（一个不剩时的确定性落点，无固定签）
 * - bar 右端单一下拉五动作：刷新当前页（removeCached + /redirect 中转，D6）/关闭当前/关闭其他/关闭右侧/全部关闭
 * - 稳定类名是 e2e 内部契约：.tags-view / .tags-view-item(.active) / .tags-actions，勿随意改名
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useTagsStore, type TagView } from '../../stores/tags'

const route = useRoute()
const router = useRouter()
const tagsStore = useTagsStore()

const tags = computed(() => tagsStore.visitedTags)

function isActive(tag: TagView): boolean {
  return tag.path === route.fullPath
}

/** 点击页签切换（当前签点击无操作） */
async function goTo(path: string): Promise<void> {
  if (path !== route.fullPath) {
    await router.push(path)
  }
}

/** 关闭页签：关闭当前签才跳落点（左邻→右邻→/dashboard），关闭后台签不跳转 */
async function handleClose(tag: TagView): Promise<void> {
  const wasActive = isActive(tag)
  const target = tagsStore.removeTag(tag.path)
  if (wasActive) {
    await router.push(target ? target.path : '/dashboard')
  }
}

async function handleCommand(command: string | number | object): Promise<void> {
  const cmd = String(command)
  if (cmd === 'refresh') {
    // 刷新当前页（D6）：先剪枝缓存再中转回原地址 → 目标页全新挂载
    const name = route.name ? String(route.name) : ''
    if (!name) {
      return
    }
    tagsStore.removeCached(name)
    await router.replace('/redirect' + route.fullPath)
    return
  }
  if (cmd === 'closeCurrent') {
    const target = tagsStore.removeTag(route.fullPath)
    await router.push(target ? target.path : '/dashboard')
    return
  }
  if (cmd === 'closeOthers') {
    tagsStore.closeOthers(route.fullPath)
    return
  }
  if (cmd === 'closeRight') {
    tagsStore.closeRight(route.fullPath)
    return
  }
  if (cmd === 'closeAll') {
    tagsStore.closeAll()
    await router.push('/dashboard')
  }
}
</script>

<template>
  <div class="tags-view">
    <div class="tags-view-scroll">
      <el-tag
        v-for="tag in tags"
        :key="tag.path"
        class="tags-view-item"
        :class="{ active: isActive(tag) }"
        :effect="isActive(tag) ? 'dark' : 'plain'"
        size="small"
        closable
        @click="goTo(tag.path)"
        @close="handleClose(tag)"
      >
        {{ tag.title }}
      </el-tag>
    </div>
    <el-dropdown class="tags-actions" trigger="click" @command="handleCommand">
      <span class="tags-actions-trigger" data-testid="tags-actions-trigger">
        <el-icon :size="12"><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="refresh">刷新当前页</el-dropdown-item>
          <el-dropdown-item command="closeCurrent">关闭当前</el-dropdown-item>
          <el-dropdown-item command="closeOthers">关闭其他</el-dropdown-item>
          <el-dropdown-item command="closeRight">关闭右侧</el-dropdown-item>
          <el-dropdown-item command="closeAll">全部关闭</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.tags-view {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 34px;
  padding: 0 8px 0 12px;
  box-sizing: border-box;
  background-color: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}

.tags-view-scroll {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.tags-view-scroll::-webkit-scrollbar {
  display: none;
}

.tags-view-item {
  cursor: pointer;
  flex-shrink: 0;
}

.tags-actions-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  cursor: pointer;
  color: var(--el-text-color-secondary);
  outline: none;
}

.tags-actions-trigger:hover {
  background-color: var(--el-fill-color);
}
</style>
