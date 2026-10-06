/**
 * 多标签页状态（升级设计 D4/D5/D7）
 * 纯内存态、不持久化：缓存组件实例无法跨刷新恢复，持久化只会恢复"壳"（页签条）
 * 而页面仍重挂载；多账号共用浏览器时残留上一账号页签有信息泄漏观感。
 * 会话清理：登录页挂载时 closeAll()（统一覆盖手动退出/401 清态/直访三条路径）。
 */
import { defineStore } from 'pinia'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

export interface TagView {
  /** 完整路径（含 query）：页签唯一键与点击跳转目标 */
  path: string
  /** 展示标题（route.meta.title，缺省用 name） */
  title: string
  /** 组件名（= route.name）：keep-alive include 按它匹配 */
  name: string
}

interface TagsState {
  visitedTags: TagView[]
  cachedNames: string[]
}

export const useTagsStore = defineStore('tags', {
  state: (): TagsState => ({ visitedTags: [], cachedNames: [] }),
  actions: {
    /** 路由→页签同步（升级设计 D5）：name 非空且非中转路由才建签；visited 按 path 去重、cached 按 name 去重 */
    addTag(route: RouteLocationNormalizedLoaded): void {
      const name = route.name ? String(route.name) : ''
      if (!name || name === 'Redirect') {
        return
      }
      const tag: TagView = {
        path: route.fullPath,
        title: route.meta.title ?? name,
        name,
      }
      if (!this.visitedTags.some((t) => t.path === tag.path)) {
        this.visitedTags.push(tag)
      }
      if (!this.cachedNames.includes(tag.name)) {
        this.cachedNames.push(tag.name)
      }
    },
    /** 关闭单个页签：返回跳转落点（左邻 → 右邻 → null=一个不剩），不负责跳转 */
    removeTag(path: string): TagView | null {
      const idx = this.visitedTags.findIndex((t) => t.path === path)
      if (idx === -1) {
        return null
      }
      this.visitedTags.splice(idx, 1)
      this.syncCached()
      return this.visitedTags[idx - 1] ?? this.visitedTags[idx] ?? null
    },
    /** 只保留指定页签，其余全关 */
    closeOthers(path: string): void {
      this.visitedTags = this.visitedTags.filter((t) => t.path === path)
      this.syncCached()
    },
    /** 关闭指定页签右侧全部 */
    closeRight(path: string): void {
      const idx = this.visitedTags.findIndex((t) => t.path === path)
      if (idx >= 0) {
        this.visitedTags = this.visitedTags.slice(0, idx + 1)
      }
      this.syncCached()
    },
    /** 全部关闭（含缓存清空；登录页 onMounted 调用做会话清理，升级设计 D4） */
    closeAll(): void {
      this.visitedTags = []
      this.cachedNames = []
    },
    /** 剪枝单个缓存（刷新语义：配合 /redirect 中转实现全新挂载，升级设计 D6）；幂等 */
    removeCached(name: string): void {
      this.cachedNames = this.cachedNames.filter((n) => n !== name)
    },
    /** cachedNames 收缩为剩余 visited 的 name 去重集（防剪不掉或多剪，升级设计 D7）；内部辅助 */
    syncCached(): void {
      this.cachedNames = [...new Set(this.visitedTags.map((t) => t.name))]
    },
  },
})
