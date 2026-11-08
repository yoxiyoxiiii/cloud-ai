/**
 * 通知中心 store（契约 2026-11-08-notify-api §3 / 计划 F3）：未读数 + 30s 轮询 + 新消息弹框
 * - 轮询唯一接口 = §3.1 summary（unreadCount + latestId 水位锚）；latestId 变化才拉 §3.2
 *   第一页做增量弹框——**登录首拉只初始化水位不弹存量**（防登录被历史消息轰炸）
 * - 水位为内存态（刷新重置为首拉值，不持久化——跨会话弹存量违反「只弹增量」语义）
 * - 单轮增量 >3 条只弹最新 3 条（防风暴）；document.hidden 跳过本轮（后台标签页不空转）
 * - 生命周期挂 Navbar onMounted startPolling / onUnmounted stopPolling——登出时 Layout
 *   卸载自然停轮询，重登后首拉重新初始化水位（故 startPolling 重置水位哨兵）
 * - 弹框 ElNotification 为函数式 API（按需引入红线）：本文件手动 import，样式在 main.ts 单独引入
 */
import { defineStore } from 'pinia'
import { ElNotification } from 'element-plus'
import type { NotificationHandle } from 'element-plus'
import router from '../router'
import { getNotifySummary, pageMyNotify, readNotify, readNotifyAll } from '../api/notify'
import type { NotifyUserMessageVo } from '../types/api'

/** 轮询间隔（计划 F3：30s；拉大可接受——summary 是轻查询，弹框即时性非目标） */
export const NOTIFY_POLL_INTERVAL_MS = 30_000

/** 单轮增量弹框上限（计划 F3：>3 条只弹最新 3 条） */
const POPUP_LIMIT = 3

/**
 * 弹出错峰间隔：EP 按「已存在通知的实测 offsetHeight」累加堆叠偏移，同一 tick 连续创建
 * 时前序通知的 v-show 尚未翻真（Vue 渲染队列异步 flush），实测高度为 0——三条会互相重叠，
 * 且关闭时的高度补偿会把后序通知顶到负坐标（屏幕外不可见）。逐条隔一个宏任务弹出，
 * 保证每条创建时前序已完成布局（60ms 对用户不可感知）
 */
const POPUP_STAGGER_MS = 60

/** 增量拉取窗口（§3.2 第一页条数）：30s 窗口内新消息超此数按限流截断，水位仍推进 */
const INCREMENT_PAGE_SIZE = 20

/** level→弹框 (type, duration) 映射（契约 §3.2 level 弹框样式）：紧急常驻不自动关闭 */
const LEVEL_POPUP: Record<number, { type: 'info' | 'warning' | 'error'; duration: number }> = {
  0: { type: 'info', duration: 4500 },
  1: { type: 'warning', duration: 8000 },
  2: { type: 'error', duration: 0 },
}

/** 轮询定时器（模块级非响应式——不进 devtools 快照，不触发多余渲染） */
let timer: ReturnType<typeof setInterval> | null = null

interface NotifyState {
  /** 未读数（summary.unreadCount 的 Number 消费——契约 Long→String） */
  unreadCount: number
  /**
   * 水位：已观察过的最新收件行 id；'0' = 未初始化（首拉将写入当前 latestId）。
   * 雪花 id 19 位超 Number 安全域，比较一律走 BigInt
   */
  latestId: string
}

export const useNotifyStore = defineStore('notify', {
  state: (): NotifyState => ({ unreadCount: 0, latestId: '0' }),
  actions: {
    /**
     * 开轮询（Navbar onMounted 调用；幂等——已开不重开）：
     * 重置水位哨兵（重登场景重新初始化，防用上一会话水位误判增量），立即首拉
     */
    startPolling(): void {
      if (timer) {
        return
      }
      this.latestId = '0'
      this.unreadCount = 0
      void this.poll(true)
      timer = setInterval(() => void this.poll(false), NOTIFY_POLL_INTERVAL_MS)
    },
    /** 停轮询并复位（Navbar onUnmounted 调用——登出 Layout 卸载自然触达） */
    stopPolling(): void {
      if (timer) {
        clearInterval(timer)
        timer = null
      }
      this.latestId = '0'
      this.unreadCount = 0
    },
    /**
     * 单轮轮询：首拉（first=true）只初始化水位不弹存量；常规轮 latestId 变化才拉
     * 第一页做增量弹框。失败静默（后端未起/网络抖动不toast轰炸——下一轮自然重试）
     */
    async poll(first: boolean): Promise<void> {
      if (document.hidden) {
        return
      }
      let summary
      try {
        summary = await getNotifySummary()
      } catch {
        return
      }
      this.unreadCount = Number(summary.unreadCount)
      const previous = this.latestId
      if (first) {
        this.latestId = summary.latestId
        return
      }
      if (!(BigInt(summary.latestId) > BigInt(previous))) {
        return
      }
      // 水位先推进再弹（弹框失败不重弹——通知非可靠投递，丢弹可接受）
      this.latestId = summary.latestId
      try {
        const page = await pageMyNotify({ pageNum: 1, pageSize: INCREMENT_PAGE_SIZE })
        const increments = page.rows.filter((row) => BigInt(row.id) > BigInt(previous))
        if (increments.length === 0) {
          return
        }
        const toPop = increments.slice(0, POPUP_LIMIT)
        toPop.forEach((row, index) => {
          setTimeout(() => this.popNotification(row), POPUP_STAGGER_MS * index)
        })
      } catch {
        // 增量页拉取失败静默（水位已推进，不重试不重弹）
      }
    },
    /** 弹一条新消息：level 定样式与停留时长；点击=标已读(失败静默)+关闭+有链接跳转 */
    popNotification(row: NotifyUserMessageVo): void {
      const style = LEVEL_POPUP[row.level] ?? LEVEL_POPUP[0]
      const handle: NotificationHandle = ElNotification({
        title: row.title,
        message: row.content,
        type: style.type,
        duration: style.duration,
        onClick: () => {
          void this.markRead(row.id)
          handle.close()
          if (row.linkUrl) {
            void router.push(row.linkUrl)
          }
        },
      })
    },
    /**
     * 单条已读（幂等）：成功后静默刷新未读数（不本地减——重复点击幂等成功会重复减，
     * 以 summary 为准最简）；失败静默（弹框点击链路不打扰，行按钮链路拦截器已 toast）
     */
    async markRead(id: string): Promise<void> {
      try {
        await readNotify(id)
        await this.refreshCount()
      } catch {
        // 静默（幂等重复调用/3048 均不弹）
      }
    },
    /** 全部已读：返回影响行数（调用方 toast「已读 N 条」）；成功后未读数归零 */
    async markReadAll(): Promise<number> {
      const count = await readNotifyAll()
      this.unreadCount = 0
      return count
    },
    /** 仅刷新未读数（markRead 后同步角标；不做水位/弹框逻辑） */
    async refreshCount(): Promise<void> {
      try {
        const summary = await getNotifySummary()
        this.unreadCount = Number(summary.unreadCount)
      } catch {
        // 静默
      }
    },
  },
})
