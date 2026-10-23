<script lang="ts">
/**
 * 暴露给父组件的方法面：模板 ref 消费用（ref&lt;ProTableExposed&gt;()），
 * 与 defineExpose 保持一致
 */
export interface ProTableExposed {
  /** 清空表格勾选（透传 el-table 同名方法；删除/翻页后清残留勾选） */
  clearSelection: () => void
  /** 取内层 el-table 实例（表格配置列拖拽等需 DOM 级查询的场景经 $el 定位表头；只读用途） */
  getTable: () => TableInstance | undefined
}
</script>

<script setup lang="ts">
/**
 * 通用表格组件（透传式包装）：el-table 壳 + 可选勾选列 + 分页条收编一处。
 * 目录约定：src/components 是全局通用组件目录——unplugin-vue-components 扫描自动注册，
 * 页面免 import 直接写 <ProTable>；页面私有组件仍就近放 views/<模块>/<页面>/components。
 * 取向：列由页面经默认插槽写 el-table-column（保留页面既有列写法），组件只收编表格壳、
 * 勾选列与分页联动，不做配置驱动列；border/stripe 等未列举 el-table 属性经 $attrs 透传。
 */
import { computed, ref } from 'vue'
import type { TableInstance } from 'element-plus'

interface Props {
  /** 表格数据（页面 Vo 数组直接可传；行类型收窄留在页面侧集中断言） */
  rows: unknown[]
  /** 加载态（表格 v-loading 遮罩） */
  loading?: boolean
  /** 总条数：后端 total 是 Long→String 字符串，组件内归一为数字，页面免手工转换 */
  total: number | string
  /** 当前页码（v-model:page-num） */
  pageNum: number
  /** 每页条数（v-model:page-size） */
  pageSize: number
  /** 可选每页条数 */
  pageSizes?: number[]
  /** 是否注入勾选列（列首） */
  selectable?: boolean
  /** 行 key（保持行状态与渲染稳定性，透传 el-table row-key） */
  rowKey?: string
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  pageSizes: () => [10, 20, 50],
  selectable: false,
})

const emit = defineEmits<{
  'update:pageNum': [page: number]
  'update:pageSize': [size: number]
  /** 翻页或改 size 后统一触发（页面据此重新加载）；触发前 v-model 值已同步 */
  pageChange: []
  /** 勾选集变化（透传 el-table） */
  selectionChange: [rows: unknown[]]
  /** 行双击（只传 row） */
  rowDblclick: [row: unknown]
}>()

// inheritAttrs 关闭根继承，$attrs（border/stripe/事件等未列举项）整体透传给 el-table
defineOptions({ name: 'ProTable', inheritAttrs: false })

const tableRef = ref<TableInstance>()

/** total 归一：字符串/数字统一转数字，NaN 兜底 0 */
const totalCount = computed<number>(() => Number(props.total) || 0)

/**
 * EP el-table 的 data 形参是 DefaultRow[]（Record<string, any>），本组件 API 定形 rows: unknown[]
 * ——断言集中此一处（组件内唯一断言点；页面 Vo 数组本就是 Record 子类型，收窄安全）
 */
function tableData(rows: unknown[]): Record<string, unknown>[] {
  return rows as Record<string, unknown>[]
}

function onSelectionChange(rows: unknown[]): void {
  emit('selectionChange', rows)
}

function onRowDblclick(row: unknown): void {
  emit('rowDblclick', row)
}

function onCurrentChange(page: number): void {
  emit('update:pageNum', page)
  schedulePageChange()
}

function onSizeChange(size: number): void {
  emit('update:pageSize', size)
  // size 变更先回第 1 页（旧页码可能超出新页数范围），再统一通知加载
  emit('update:pageNum', 1)
  schedulePageChange()
}

/** page-change 统一触发：改 size 可能使分页组件同时补发 current-change（页码越界自动收敛），
 * 同一轮次只通知一次，页面不重复加载 */
let pageChangePending = false
function schedulePageChange(): void {
  if (pageChangePending) {
    return
  }
  pageChangePending = true
  void Promise.resolve().then(() => {
    pageChangePending = false
    emit('pageChange')
  })
}

defineExpose({
  clearSelection: () => tableRef.value?.clearSelection(),
  getTable: () => tableRef.value,
})
</script>

<template>
  <div class="pro-table">
    <el-table
      ref="tableRef"
      v-bind="$attrs"
      v-loading="loading"
      :data="tableData(rows)"
      :row-key="rowKey"
      @selection-change="onSelectionChange"
      @row-dblclick="onRowDblclick"
    >
      <el-table-column v-if="selectable" type="selection" />
      <slot />
    </el-table>

    <el-pagination
      class="pro-table__pagination"
      :current-page="pageNum"
      :page-size="pageSize"
      :page-sizes="pageSizes"
      :total="totalCount"
      layout="total, sizes, prev, pager, next"
      @current-change="onCurrentChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<style scoped>
.pro-table__pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
