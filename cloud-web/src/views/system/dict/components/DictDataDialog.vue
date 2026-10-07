<script setup lang="ts">
/**
 * 字典项管理弹框（列表弹框，重构设计 D2）：
 * 由类型行"字典项"按钮打开，项分页/增删改全部内聚在本组件——父页仅传 dictType 与 v-model
 * 项表 5 列精简（标签/值/排序/状态/操作）：审计 4 列不再展示（弹框聚焦管理本身；
 * 契约 §3 VO 字段不变，仅 UI 不消费）；增/编辑复用 DictDataFormDialog（append-to-body 二层弹框）
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDictData, pageDictData } from '../../../../api/dict'
import type { SysDictDataVo, SysDictTypeVo } from '../../../../types/api'
import DictDataFormDialog from './DictDataFormDialog.vue'
import type { DictDataFormMode } from './DictDataFormDialog.vue'

interface Props {
  modelValue: boolean
  /** 当前管理的字典类型（标题与数据源）；无 success emit——父页不关心项变更 */
  dictType?: SysDictTypeVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

/**
 * 状态本地映射（契约 §1：0=正常 1=停用；管理端点不过滤停用，靠 tag 区分）：
 * - tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）
 * - label 仅作 statusLabel 缺位时的降级文案（契约 2026-10-07-translation-api §8.1/§8.4 降级链）
 */
const STATUS_MAP: Record<number, { label: string; tagType: 'success' | 'danger' }> = {
  0: { label: '正常', tagType: 'success' },
  1: { label: '停用', tagType: 'danger' },
}

/** 标题沿用原右栏格式：字典项：{dictName}（{dictKey}） */
const title = computed(() =>
  props.dictType ? `字典项：${props.dictType.dictName}（${props.dictType.dictKey}）` : '字典项',
)

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（泛型不流入列插槽），
 * 全组件断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): SysDictDataVo {
  return row as SysDictDataVo
}

const loading = ref(false)
const rows = ref<SysDictDataVo[]>([])
const total = ref(0)
/** 弹框内分页：pageSize 恒 10、省 sizes（字典项量少，契约只有分页端点，保留分页控件最稳） */
const query = reactive({ pageNum: 1, pageSize: 10 })

/** 加载字典项分页（契约 §3.1）：typeId 必传；total 为 Long→String，分页组件需 Number() */
async function loadPage(pageNum: number = query.pageNum): Promise<void> {
  const type = props.dictType
  if (!type) {
    return
  }
  loading.value = true
  try {
    query.pageNum = pageNum
    const page = await pageDictData({
      typeId: type.id,
      pageNum: query.pageNum,
      pageSize: query.pageSize,
    })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast（如 3008 类型在他处被删）；表格保持现状
  } finally {
    loading.value = false
  }
}

/** 弹框打开（或开着时切换类型）→ 加载第 1 页 */
watch(
  () => [props.modelValue, props.dictType] as const,
  ([visible]) => {
    if (visible && props.dictType) {
      void loadPage(1)
    }
  },
)

const formVisible = ref(false)
const formMode = ref<DictDataFormMode>('add')
const formRow = ref<SysDictDataVo>()

function openAdd(): void {
  formMode.value = 'add'
  formRow.value = undefined
  formVisible.value = true
}

function openEdit(row: SysDictDataVo): void {
  formMode.value = 'edit'
  formRow.value = row
  formVisible.value = true
}

/** 保存成功（add/edit）：原地刷新当前页 */
function handleSaved(): void {
  void loadPage()
}

async function handleDelete(row: SysDictDataVo): Promise<void> {
  // 文案含标签（契约 §3.4 前端消费）
  const confirmed = await ElMessageBox.confirm(
    `确定删除字典项 "${row.label}" 吗？`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteDictData(row.id)
    ElMessage.success('删除成功')
    await loadPage()
  } catch {
    // 拦截器已统一 toast（3010）；表格刷新后自然对齐
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="860px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="dialog-toolbar">
      <el-button v-perms="'system:dict:add'" type="primary" @click="openAdd"
        >新增字典项</el-button
      >
    </div>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="标签" min-width="120">
        <template #default="{ row }">
          <span>{{ rowOf(row).label }}</span>
          <!-- 内置徽标（保护契约 §7.2：内联标签格不新增列；仅展示，禁用面在行内操作） -->
          <el-tag v-if="rowOf(row).builtin" class="builtin-badge" type="info" size="small"
            >内置</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column prop="value" label="值" min-width="120" />
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'">
            {{ rowOf(row).statusLabel ?? STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <!-- 内置项（种子项）行内编辑/删除禁用；「新增」按钮放行——内置类型可追加项（保护契约 §7.2/§7.3，错误码 3016 为最终防线） -->
          <el-button
            v-perms="'system:dict:edit'"
            link
            type="primary"
            :disabled="rowOf(row).builtin"
            @click="openEdit(rowOf(row))"
            >编辑</el-button
          >
          <el-button
            v-perms="'system:dict:remove'"
            link
            type="danger"
            :disabled="rowOf(row).builtin"
            @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无字典项" :image-size="80" />
      </template>
    </el-table>

    <el-pagination
      class="dialog-pagination"
      v-model:current-page="query.pageNum"
      :page-size="query.pageSize"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="loadPage"
    />

    <DictDataFormDialog
      v-model="formVisible"
      append-to-body
      :mode="formMode"
      :dict-data="formRow"
      :type-id="dictType?.id ?? ''"
      @success="handleSaved"
    />
  </el-dialog>
</template>

<style scoped>
.dialog-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.dialog-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

/** 内置徽标内联标签格（设计 D3 统一形态） */
.builtin-badge {
  margin-left: 8px;
}
</style>
