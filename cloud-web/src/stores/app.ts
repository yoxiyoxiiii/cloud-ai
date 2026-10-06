/**
 * 全局外观唯一来源（升级设计 §3.2）
 * - state 初始化自 storage（F5 保持偏好）
 * - toggleTheme：切换主题 + 同步 html.dark 类 + 落盘
 * - toggleSidebar：切换侧栏折叠 + 落盘（F2 消费）
 * - 水印/自定义样式经 EP CSS 变量自动跟随深浅，不额外发事件（canvas 水印除外，自行 watch theme）
 */
import { defineStore } from 'pinia'
import { getAppPrefs, setAppPrefs, type AppPrefs } from '../utils/storage'

export const useAppStore = defineStore('app', {
  state: (): AppPrefs => {
    const prefs = getAppPrefs()
    return {
      theme: prefs.theme,
      sidebarCollapsed: prefs.sidebarCollapsed,
    }
  },
  actions: {
    /** 落盘当前 state（保持 theme 与 html.dark 类一致） */
    persist(): void {
      setAppPrefs({ theme: this.theme, sidebarCollapsed: this.sidebarCollapsed })
    },
    toggleTheme(): void {
      this.theme = this.theme === 'dark' ? 'light' : 'dark'
      document.documentElement.classList.toggle('dark', this.theme === 'dark')
      this.persist()
    },
    toggleSidebar(): void {
      this.sidebarCollapsed = !this.sidebarCollapsed
      this.persist()
    },
  },
})
