<script setup lang="ts">
/**
 * 表格列配置按钮（设计 2026-10-23-table-config-design.md D10）：工具栏齿轮入口 + 列配置面板。
 * 目录约定同 ProTable：src/components 全局通用组件，unplugin-vue-components 自动注册，页面免 import。
 * 面板职责：列显隐勾选、面板内拖拽排序（sortablejs 同依赖）、脱敏列「已脱敏」标注、
 * 恢复默认（confirm）。**确定才落库**（emit save 由页面转发 composable 即时保存），
 * 取消/关闭丢弃编辑；恢复默认 emit reset（页面调 composable 的 reset）。
 * 面板只列权限允许的列（父页传合并后清单，权限隐藏列已被合并函数过滤）；锁列不出现。
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { nextTick } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Rank, Setting } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import type { SortableEvent } from 'sortablejs'
import type { PanelSaveItem } from '../composables/useTableConfig'

/** 面板列项（父页传合并后清单 MergedColumn[] 的结构子集即可） */
interface PanelColumn {
  key: string
  label: string
  visible: boolean
  masked?: boolean
}

const props = defineProps<{ columns: PanelColumn[] }>()

const emit = defineEmits<{
  /** 确定保存：面板内拖拽后的全量有序列 + 勾选显隐 */
  save: [items: PanelSaveItem[]]
  /** 恢复默认（confirm 已通过） */
  reset: []
}>()

/** 面板内编辑态（打开时从 props 拷贝，取消不回写） */
interface PanelItem {
  key: string
  label: string
  visible: boolean
  masked: boolean
}

const open = ref(false)
const items = ref<PanelItem[]>([])
/** 面板列表容器（sortable 挂载点；el-dialog 懒渲染，打开后 nextTick 才有值） */
const dragListRef = ref<HTMLDivElement>()

function syncFromProps(): void {
  items.value = props.columns.map((c) => ({
    key: c.key,
    label: c.label,
    visible: c.visible,
    masked: c.masked ?? false,
  }))
}

let panelSortable: Sortable | undefined

/** 幂等挂载面板拖拽（容器未变复用旧实例） */
function ensurePanelSortable(): void {
  const list = dragListRef.value
  if (!list) {
    return
  }
  if (panelSortable && panelSortable.el === list) {
    return
  }
  panelSortable?.destroy()
  panelSortable = Sortable.create(list, {
    animation: 150,
    handle: '.config-drag-handle',
    onEnd: (evt) => onPanelDragEnd(evt),
  })
}

/** onEnd 先还原 DOM 再改数据源（与表头拖拽同口径，Vue v-for keyed 重排接管） */
function onPanelDragEnd(evt: SortableEvent): void {
  const { oldIndex, newIndex, item } = evt
  const list = item.parentElement
  if (!list || oldIndex === undefined || newIndex === undefined || oldIndex === newIndex) {
    return
  }
  list.removeChild(item)
  list.insertBefore(item, list.children[oldIndex] ?? null)
  const moved = items.value.splice(oldIndex, 1)[0]
  if (moved === undefined) {
    return
  }
  items.value.splice(newIndex, 0, moved)
}

watch(open, (visible) => {
  if (visible) {
    syncFromProps()
    void nextTick(ensurePanelSortable)
  }
})

function onConfirm(): void {
  emit(
    'save',
    items.value.map((i) => ({ key: i.key, visible: i.visible })),
  )
  open.value = false
}

async function onRestoreDefault(): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    '确定恢复默认列配置吗？将清除本页的个人列偏好（列序/显隐/列宽）。',
    '恢复默认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  emit('reset')
  open.value = false
}

onBeforeUnmount(() => {
  panelSortable?.destroy()
  panelSortable = undefined
})
</script>

<template>
  <el-button
    class="table-config-trigger"
    :icon="Setting"
    circle
    title="列配置"
    @click="open = true"
  />
  <el-dialog v-model="open" title="列配置" width="420px">
    <div v-if="items.length === 0" class="config-empty">暂无可配置列</div>
    <div v-else ref="dragListRef" class="config-list">
      <div v-for="item in items" :key="item.key" class="config-row" :data-key="item.key">
        <el-icon class="config-drag-handle"><Rank /></el-icon>
        <el-checkbox v-model="item.visible" class="config-checkbox">{{ item.label }}</el-checkbox>
        <el-tag v-if="item.masked" size="small" type="info">已脱敏</el-tag>
      </div>
    </div>
    <template #footer>
      <div class="config-footer">
        <el-button link type="warning" @click="onRestoreDefault">恢复默认</el-button>
        <span class="config-footer__spacer" />
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" @click="onConfirm">确定</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.config-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 4px;
}

.config-row:hover {
  background-color: var(--el-fill-color-light);
}

.config-drag-handle {
  cursor: move;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.config-checkbox {
  flex: 1;
}

.config-empty {
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 16px 0;
}

.config-footer {
  display: flex;
  align-items: center;
}

.config-footer__spacer {
  flex: 1;
}
</style>
