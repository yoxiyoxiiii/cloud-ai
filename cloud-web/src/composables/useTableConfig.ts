/**
 * 表格列配置 composable（设计 2026-10-23-table-config-design.md D7-D11；契约面见
 * api/tableConfig.ts，本文件非契约面）
 *
 * 职责单点收敛：
 * - 合并函数（D7）：权限隐藏列过滤（权限赢）→ 已存配置按存序重排+应用显隐/宽 →
 *   新列按页面定义原序追加；页面 v-for 直接消费合并结果，**列显隐唯一落点**（页面禁散写列级 v-if）
 * - 保存编排（D11）：拖拽列序/列宽走 800ms 防抖、面板确定即时保存；失败 toast（拦截器）
 *   + 内存态回退最近一次已知服务端态（不出现"看起来保存了其实没存"）
 * - 表头拖拽挂载（D9）：sortablejs 经 ProTable expose 的 getTable() 定位 thead tr，
 *   DOM 查询/锁列过滤/onEnd 先还原 DOM 再改数据源的口径全部封装本文件
 *   （EP 版本升级的 DOM 结构风险单点隔离）
 *
 * 与 usePermColumns 的组合是函数引用传参：isHidden 内部读响应式 Set，本文件 computed
 * 调用时依赖收集成立，权限清单拉取完成列集自动重算——usePermColumns 本体零改。
 */
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ElMessage } from 'element-plus'
import Sortable from 'sortablejs'
import type { SortableEvent } from 'sortablejs'
import { getTableConfig, resetTableConfig, saveTableConfig } from '../api/tableConfig'
import type { TableColumnConfig } from '../types/api'
import type { ProTableExposed } from '../components/ProTable.vue'

/** 拖拽/列宽变更的保存防抖静默期（D11） */
const SAVE_DEBOUNCE_MS = 800
/** 表头右缘列宽调整热区宽（px）：热区内按下交给 EP 列宽拖拽，不启动列拖拽（POC 实测口径） */
const RESIZE_ZONE_PX = 8
/** 契约 §2.2 列宽合法区间：防 EP 拖拽越界值被服务端 1002 拒掉 */
const WIDTH_MIN = 40
const WIDTH_MAX = 1000

/** 页面列定义元素（锁列外，设计 D12）：key = VO 字段名 */
export interface ColumnDef {
  /** 列标识（与数据权限 columnKey 同一命名空间，契约 §1） */
  key: string
  /** 列头文案 */
  label: string
  /** 弹性最小宽（用户未自定义列宽时生效） */
  minWidth?: number
  /** 页面默认固定宽（如状态列 80） */
  width?: number
  align?: 'left' | 'center' | 'right'
  /** 富内容列标记（模板 v-for 内按 key 分支写自定义格） */
  slot?: boolean
}

/** 合并后列（页面渲染与配置面板共用的有序清单） */
export interface MergedColumn extends ColumnDef {
  /** 用户自选显隐（权限过滤后的最终可见性） */
  visible: boolean
  /** 生效列宽（渲染用）：用户自定义宽优先，缺省回落页面定义 width（都无则 undefined=按 minWidth 弹性） */
  width?: number
  /** 用户自定义列宽（持久化只存它——页面默认宽不固化进配置，后续改默认宽仍可生效） */
  userWidth?: number
  /** 数据权限脱敏标注（面板「已脱敏」tag 用；脱敏列允许用户隐藏） */
  masked: boolean
}

/** 配置面板确定回调的载荷：面板内拖拽后的全量有序列 + 勾选显隐 */
export interface PanelSaveItem {
  key: string
  visible: boolean
}

/** useTableConfig 入参 */
interface UseTableConfigOptions {
  /** 页面列定义数组（锁列外：选择列由 ProTable 注入、操作列页面模板直写） */
  defs: ColumnDef[]
  /** ProTable 模板 ref——经其 getTable() 定位表头挂 sortable */
  table: Ref<ProTableExposed | undefined>
  /** ProTable 是否注入了勾选列（锁列判定前导占位用） */
  selectable?: boolean
  /** 列级数据权限隐藏判定（传 usePermColumns 的 isHidden；不传=无权限面） */
  isColumnHidden?: (key: string) => boolean
  /** 列级数据权限脱敏判定（面板标注用） */
  isColumnMasked?: (key: string) => boolean
}

/** useTableConfig 返回面（设计 D8） */
export interface UseTableConfigReturn {
  /** 合并后全量有序列（含 visible/masked 标志——配置面板数据源） */
  columns: Ref<MergedColumn[]>
  /** 仅可见列（表格 v-for 渲染源） */
  visibleColumns: Ref<MergedColumn[]>
  /** 拉取本人配置（页面 onMounted 与分页/权限清单并行调；失败降级默认列序） */
  load: () => Promise<void>
  /** 即时保存（面板确定走此路）；失败自动回退内存态 */
  saveNow: () => Promise<void>
  /** 防抖保存（拖拽列序/列宽回调内部走此路） */
  saveDebounced: () => void
  /** 恢复默认：DELETE + 内存态清空回页面默认列序 */
  reset: () => Promise<void>
  /** 表头拖拽回调（新序的可见列 key 数组；内部落配置+防抖保存） */
  applyOrder: (orderedVisibleKeys: string[]) => void
  /** 列宽拖拽回调（@header-dragend 透传转发；内部落配置+防抖保存） */
  applyWidth: (key: string, width: number) => void
  /** 配置面板确定回调（面板内排序+勾选结果；内部落配置+即时保存） */
  applyPanel: (items: PanelSaveItem[]) => void
}

/**
 * 消费一张表格的列配置。示例：
 * `const tc = useTableConfig(TABLE_KEY_USER, { defs, table: tableRef, selectable: false, isColumnHidden: permColumns.isHidden })`
 */
export function useTableConfig(
  tableKey: string,
  options: UseTableConfigOptions,
): UseTableConfigReturn {
  const { defs, table } = options
  /** 前导锁列数：勾选列（ProTable 注入在首）占 1，恒不参与拖拽 */
  const leadingLocked = options.selectable ? 1 : 0

  /** 当前配置态（null=无配置，按页面默认）；这是唯一可变源，columns 由它派生 */
  const config = ref<TableColumnConfig[] | null>(null)
  /** 最近一次已知服务端态（load/保存成功后快照）——保存失败回退锚点（D11） */
  let lastPersisted: TableColumnConfig[] | null = null

  const columns = computed<MergedColumn[]>(() => {
    // ① 权限赢：隐藏列整列剔除（不进渲染也不进面板）
    const hidden = options.isColumnHidden
    const defByKey = new Map(defs.map((d) => [d.key, d]))
    const allowedDefs = defs.filter((d) => !(hidden?.(d.key) ?? false))
    // ② 已存配置 ∩ 允许列：按存序重排，应用 visible；width 覆盖默认宽
    const merged: MergedColumn[] = []
    const seen = new Set<string>()
    for (const saved of config.value ?? []) {
      const def = defByKey.get(saved.key)
      if (!def || seen.has(def.key) || (hidden?.(def.key) ?? false)) {
        continue
      }
      seen.add(def.key)
      merged.push({
        ...def,
        visible: saved.visible,
        width: saved.width ?? def.width,
        userWidth: saved.width ?? undefined,
        masked: options.isColumnMasked?.(def.key) ?? false,
      })
    }
    // ③ 未配置列（新列/配置未覆盖）按定义原序追加（新列不丢）
    for (const def of allowedDefs) {
      if (!seen.has(def.key)) {
        merged.push({
          ...def,
          visible: true,
          width: def.width,
          userWidth: undefined,
          masked: options.isColumnMasked?.(def.key) ?? false,
        })
      }
    }
    return merged
  })

  const visibleColumns = computed<MergedColumn[]>(() => columns.value.filter((c) => c.visible))

  // ---------- 持久化（D11） ----------

  /** 把当前合并态固化为完整配置数组：存量键（当前不可见的配置键，如权限回收列）原样保留不清洗（设计 D1） */
  function materialize(): TableColumnConfig[] {
    const liveKeys = new Set(columns.value.map((c) => c.key))
    const out: TableColumnConfig[] = columns.value.map((c) => ({
      key: c.key,
      visible: c.visible,
      width: c.userWidth ?? null,
    }))
    for (const saved of config.value ?? []) {
      if (!liveKeys.has(saved.key)) {
        out.push(saved)
      }
    }
    return out
  }

  /** 落库并维护回退锚点；失败回退内存态到最近一次已知服务端态（toast 由拦截器统一）。
   *  showSuccessToast：面板确定等显式动作给成功反馈；拖拽/列宽防抖保存静默（高频不扰） */
  async function persist(showSuccessToast: boolean): Promise<void> {
    const payload = config.value ?? []
    try {
      await saveTableConfig(tableKey, { columns: payload })
      lastPersisted = payload.map((c) => ({ ...c }))
      if (showSuccessToast) {
        ElMessage.success('列配置已保存')
      }
    } catch {
      config.value = lastPersisted ? lastPersisted.map((c) => ({ ...c })) : null
    }
  }

  let debounceTimer: ReturnType<typeof setTimeout> | undefined

  function saveDebounced(): void {
    if (debounceTimer) {
      clearTimeout(debounceTimer)
    }
    debounceTimer = setTimeout(() => {
      debounceTimer = undefined
      void persist(false)
    }, SAVE_DEBOUNCE_MS)
  }

  async function load(): Promise<void> {
    try {
      const vo = await getTableConfig(tableKey)
      const cols = vo?.columns ?? null
      config.value = cols ? cols.map((c) => ({ ...c })) : null
      lastPersisted = cols ? cols.map((c) => ({ ...c })) : null
    } catch {
      // 个人偏好非安全面：拉取失败按页面默认列序渲染（fail-open 仅丢偏好，拦截器已 toast）
    }
  }

  async function reset(): Promise<void> {
    try {
      await resetTableConfig(tableKey)
      config.value = null
      lastPersisted = null
      ElMessage.success('已恢复默认列配置')
    } catch {
      // 拦截器已 toast；内存态保持现状（恢复动作未生效，不假装成功）
    }
  }

  // ---------- 变更入口（拖拽/列宽/面板） ----------

  /** 拖拽落序：orderedVisibleKeys 为新序的可见列 key（拖拽只在可见列间发生）；
   * 不可见列/存量键保位保留 */
  function applyOrder(orderedVisibleKeys: string[]): void {
    const ordered = [...orderedVisibleKeys]
    const out: TableColumnConfig[] = []
    let cursor = 0
    for (const col of columns.value) {
      if (!col.visible) {
        out.push({ key: col.key, visible: false, width: col.userWidth ?? null })
        continue
      }
      const key = ordered[cursor]
      cursor += 1
      const src = key === undefined ? undefined : columns.value.find((c) => c.key === key)
      if (!src) {
        continue // 防御：DOM 序与列集不一致（竞态窗口）时丢弃该位，不落错序
      }
      out.push({ key: src.key, visible: true, width: src.userWidth ?? null })
    }
    config.value = out
    saveDebounced()
  }

  function applyWidth(key: string, width: number): void {
    const clamped = Math.min(WIDTH_MAX, Math.max(WIDTH_MIN, Math.round(width)))
    const next = materialize()
    const entry = next.find((c) => c.key === key)
    if (entry) {
      entry.width = clamped
    }
    config.value = next
    saveDebounced()
  }

  function applyPanel(items: PanelSaveItem[]): void {
    const widthByKey = new Map((config.value ?? []).map((c) => [c.key, c.width ?? null]))
    const liveKeys = new Set(items.map((i) => i.key))
    const out: TableColumnConfig[] = items.map((item) => ({
      key: item.key,
      visible: item.visible,
      width: widthByKey.get(item.key) ?? null,
    }))
    for (const saved of config.value ?? []) {
      if (!liveKeys.has(saved.key)) {
        out.push(saved)
      }
    }
    config.value = out
    void persist(true)
  }

  // ---------- 表头拖拽挂载（D9，DOM 操作单点） ----------

  /** sortable filter 事件实为 pointerdown（含坐标）：集中收窄取 clientX（本文件唯一断言点） */
  function clientXOf(event: Event): number {
    return (event as MouseEvent).clientX
  }

  /** 定位表头行：ProTable expose 的 el-table 实例 → $el 下 .el-table__header thead tr */
  function headerRowEl(): HTMLTableRowElement | undefined {
    const root = table.value?.getTable()?.$el
    if (!(root instanceof Element)) {
      return undefined
    }
    return root.querySelector<HTMLTableRowElement>('.el-table__header thead tr') ?? undefined
  }

  /** 锁列与列宽热区判定：前导勾选列、尾部操作列/gutter（索引界外）不拖；
   *  th 右缘热区内按下交给 EP 列宽调整（POC 实测双交互不冲突的口径） */
  function isLockedOrResizeZone(event: Event, target: HTMLElement): boolean {
    const tr = target.parentElement
    if (!tr) {
      return true
    }
    const idx = Array.prototype.indexOf.call(tr.children, target)
    const visibleCount = visibleColumns.value.length
    if (idx < leadingLocked || idx >= leadingLocked + visibleCount) {
      return true
    }
    const rect = target.getBoundingClientRect()
    return rect.right - clientXOf(event) < RESIZE_ZONE_PX
  }

  let sortable: Sortable | undefined

  /** 幂等挂载：tr 元素未变则复用（列集变化只增删 th 不换行元素）；
   *  EP 重建表头（极端场景）后 tr 引用变化即重挂 */
  function ensureSortable(): void {
    const tr = headerRowEl()
    if (!tr) {
      return
    }
    if (sortable && sortable.el === tr) {
      return
    }
    sortable?.destroy()
    sortable = Sortable.create(tr, {
      animation: 150,
      // 默认 true 会 preventDefault 被过滤的 pointerdown，压掉兼容 mousedown 导致
      // EP 列宽拖拽失效（sortablejs 1.15 pointer 事件 + EP mousedown 监听，POC 实证）
      preventOnFilter: false,
      filter: (event, target) => isLockedOrResizeZone(event, target),
      onEnd: (evt: SortableEvent) => onDragEnd(evt),
    })
  }

  /** onEnd 口径（D9/POC）：先还原 DOM（th 放回原位），再改数据源由 Vue v-for keyed 重排接管 */
  function onDragEnd(evt: SortableEvent): void {
    const { oldIndex, newIndex, item } = evt
    const tr = item.parentElement
    if (!tr || oldIndex === undefined || newIndex === undefined || oldIndex === newIndex) {
      return
    }
    tr.removeChild(item)
    tr.insertBefore(item, tr.children[oldIndex] ?? null)
    const from = oldIndex - leadingLocked
    const to = newIndex - leadingLocked
    const count = visibleColumns.value.length
    if (from < 0 || to < 0 || from >= count || to >= count) {
      return // 锁列位滑入（filter 漏网兜底）：不落序
    }
    const keys = visibleColumns.value.map((c) => c.key)
    const moved = keys.splice(from, 1)[0]
    if (moved === undefined) {
      return
    }
    keys.splice(to, 0, moved)
    applyOrder(keys)
  }

  onMounted(() => {
    void nextTick(ensureSortable)
  })
  // keep-alive 重新激活/列集变化后表头行可能重建：按可见列 key 序变化重校挂载
  onActivated(() => {
    void nextTick(ensureSortable)
  })
  watch(
    () => visibleColumns.value.map((c) => c.key).join(','),
    () => {
      void nextTick(ensureSortable)
    },
  )
  onBeforeUnmount(() => {
    // 卸载前冲刷未落防抖窗内的最后一次变更（fire-and-forget），再拆 sortable
    if (debounceTimer) {
      clearTimeout(debounceTimer)
      debounceTimer = undefined
      void persist(false)
    }
    sortable?.destroy()
    sortable = undefined
  })

  return {
    columns,
    visibleColumns,
    load,
    saveNow: () => persist(true),
    saveDebounced,
    reset,
    applyOrder,
    applyWidth,
    applyPanel,
  }
}
