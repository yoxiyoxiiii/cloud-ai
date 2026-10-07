<script setup lang="ts">
/**
 * 数据字典管理页（主从式单页，设计 D2/§5）：
 * 左栏字典类型分页（契约 §2.1，id 倒序）⇄ 右栏选中类型的字典项分页（契约 §3.1，sort/id 升序）
 * 联动机制：左表 row-key + highlight-current-row——数据刷新后 EP 按 id 重对齐选中行
 * （current-change 带新行对象、id 不变不重查右栏）；选中行消失（被删/翻页离开）自动清空右栏回空态
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDictData, deleteDictType, pageDictData, pageDictType } from '../../../api/dict'
import type { SysDictDataVo, SysDictTypeVo } from '../../../types/api'
import DictTypeFormDialog from './components/DictTypeFormDialog.vue'
import type { DictTypeFormMode } from './components/DictTypeFormDialog.vue'
import DictDataFormDialog from './components/DictDataFormDialog.vue'
import type { DictDataFormMode } from './components/DictDataFormDialog.vue'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D5 红字坑） */
defineOptions({ name: 'SystemDict' })

/** 状态展示映射（契约 §1：两级各自 0=正常 1=停用；管理端点不过滤停用，靠 tag 区分） */
const STATUS_MAP: Record<number, { label: string; tagType: 'success' | 'danger' }> = {
  0: { label: '正常', tagType: 'success' },
  1: { label: '停用', tagType: 'danger' },
}

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * el-table 的泛型不会流入列插槽，无法在模板内类型收窄；
 * 左右两表各一个收窄函数，全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function typeRowOf(row: unknown): SysDictTypeVo {
  return row as SysDictTypeVo
}

function dataRowOf(row: unknown): SysDictDataVo {
  return row as SysDictDataVo
}

// ---------- 左栏：字典类型 ----------
const typeLoading = ref(false)
const typeRows = ref<SysDictTypeVo[]>([])
const typeTotal = ref(0)
/** 窄面板分页：pageSize 恒 10、省 sizes（设计 §5） */
const typeQuery = reactive({ pageNum: 1, pageSize: 10 })
/** 当前选中类型（右栏数据源与 header 文案来源）；undefined = 右栏空态 */
const selectedType = ref<SysDictTypeVo>()

/** 加载类型分页（契约 §2.1）：total 为 Long→String，分页组件需 Number() */
async function loadTypePage(pageNum: number = typeQuery.pageNum): Promise<void> {
  typeLoading.value = true
  try {
    typeQuery.pageNum = pageNum
    const page = await pageDictType({ pageNum: typeQuery.pageNum, pageSize: typeQuery.pageSize })
    typeRows.value = page.rows
    typeTotal.value = Number(page.total)
    // 选中行重对齐由 row-key 自动完成（EP 按 id 重指 currentRow 并触发 current-change）：
    // id 仍在 → current-change(新行对象) → header 随 dictName 更新；id 已不在 → current-change(null) → 右栏清空
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    typeLoading.value = false
  }
}

/** 左表行选中联动：id 变化才加载右栏第 1 页（数据刷新重对齐也走到这里，id 不变不重查） */
function handleTypeCurrentChange(row: SysDictTypeVo | null): void {
  const prevId = selectedType.value?.id
  if (row) {
    selectedType.value = row
    if (row.id !== prevId) {
      void loadDataPage(1)
    }
    return
  }
  // 选中行消失（被删/翻页离开当前页）：清空右栏回空态
  selectedType.value = undefined
  dataRows.value = []
  dataTotal.value = 0
  dataQuery.pageNum = 1
}

const typeDialogVisible = ref(false)
const typeDialogMode = ref<DictTypeFormMode>('add')
const typeDialogRow = ref<SysDictTypeVo>()

function openTypeAdd(): void {
  typeDialogMode.value = 'add'
  typeDialogRow.value = undefined
  typeDialogVisible.value = true
}

function openTypeEdit(row: SysDictTypeVo): void {
  typeDialogMode.value = 'edit'
  typeDialogRow.value = row
  typeDialogVisible.value = true
}

/** 类型保存成功：add 回第 1 页（新行 id 倒序置顶可见，设计 §8）；edit 留当前页按 id 重对齐 */
function handleTypeSaved(): void {
  void loadTypePage(typeDialogMode.value === 'add' ? 1 : typeQuery.pageNum)
}

async function handleTypeDelete(row: SysDictTypeVo): Promise<void> {
  // 文案含类型名（契约 §2.4 前端消费）；有未删项得 3011 由拦截器 toast，行保持
  const confirmed = await ElMessageBox.confirm(
    `确定删除字典类型 "${row.dictName}" 吗？`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteDictType(row.id)
    ElMessage.success('删除成功')
    // 删除的若是选中类型：刷新后 row-key 找不到该 id → current-change(null) 自动清空右栏
    await loadTypePage()
  } catch {
    // 拦截器已统一 toast（3011/3008）；表格刷新后自然对齐
  }
}

// ---------- 右栏：字典项 ----------
const dataLoading = ref(false)
const dataRows = ref<SysDictDataVo[]>([])
const dataTotal = ref(0)
const dataQuery = reactive({ pageNum: 1, pageSize: 10 })

/** 加载字典项分页（契约 §3.1）：typeId 必传；选中类型在请求期间被更换/清除时丢弃过期响应 */
async function loadDataPage(pageNum: number = dataQuery.pageNum): Promise<void> {
  const type = selectedType.value
  if (!type) {
    return
  }
  dataLoading.value = true
  try {
    dataQuery.pageNum = pageNum
    const page = await pageDictData({
      typeId: type.id,
      pageNum: dataQuery.pageNum,
      pageSize: dataQuery.pageSize,
    })
    if (selectedType.value?.id !== type.id) {
      return
    }
    dataRows.value = page.rows
    dataTotal.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast（如 3008 类型在他处被删）；表格保持现状，左表刷新后自然对齐
  } finally {
    dataLoading.value = false
  }
}

function handleDataSizeChange(size: number): void {
  dataQuery.pageSize = size
  void loadDataPage(1)
}

const dataDialogVisible = ref(false)
const dataDialogMode = ref<DictDataFormMode>('add')
const dataDialogRow = ref<SysDictDataVo>()

function openDataAdd(): void {
  if (!selectedType.value) {
    return
  }
  dataDialogMode.value = 'add'
  dataDialogRow.value = undefined
  dataDialogVisible.value = true
}

function openDataEdit(row: SysDictDataVo): void {
  dataDialogMode.value = 'edit'
  dataDialogRow.value = row
  dataDialogVisible.value = true
}

async function handleDataDelete(row: SysDictDataVo): Promise<void> {
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
    await loadDataPage()
  } catch {
    // 拦截器已统一 toast（3010）；表格刷新后自然对齐
  }
}

onMounted(() => {
  void loadTypePage()
})
</script>

<template>
  <div class="dict-layout">
    <el-card shadow="never" class="type-pane">
      <template #header>
        <div class="pane-header">
          <span>字典类型</span>
          <el-button v-perms="'system:dict:add'" type="primary" @click="openTypeAdd"
            >新增类型</el-button
          >
        </div>
      </template>

      <el-table
        v-loading="typeLoading"
        :data="typeRows"
        row-key="id"
        highlight-current-row
        @current-change="handleTypeCurrentChange"
      >
        <el-table-column prop="dictName" label="字典名称" min-width="110" />
        <el-table-column prop="dictKey" label="字典键" min-width="120" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="STATUS_MAP[typeRowOf(row).status]?.tagType ?? 'info'">
              {{ STATUS_MAP[typeRowOf(row).status]?.label ?? typeRowOf(row).status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-perms="'system:dict:edit'"
              link
              type="primary"
              @click="openTypeEdit(typeRowOf(row))"
              >编辑</el-button
            >
            <el-button
              v-perms="'system:dict:remove'"
              link
              type="danger"
              @click="handleTypeDelete(typeRowOf(row))"
              >删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="table-pagination"
        v-model:current-page="typeQuery.pageNum"
        :page-size="typeQuery.pageSize"
        :total="typeTotal"
        layout="total, prev, pager, next"
        @current-change="loadTypePage"
      />
    </el-card>

    <el-card shadow="never" class="data-pane">
      <template #header>
        <div class="pane-header">
          <span>
            字典项{{
              selectedType ? `：${selectedType.dictName}（${selectedType.dictKey}）` : ''
            }}</span
          >
          <el-button
            v-perms="'system:dict:add'"
            type="primary"
            :disabled="!selectedType"
            @click="openDataAdd"
            >新增字典项</el-button
          >
        </div>
      </template>

      <el-table v-loading="dataLoading" :data="dataRows">
        <el-table-column prop="label" label="标签" min-width="110" />
        <el-table-column prop="value" label="值" min-width="110" />
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="STATUS_MAP[dataRowOf(row).status]?.tagType ?? 'info'">
              {{ STATUS_MAP[dataRowOf(row).status]?.label ?? dataRowOf(row).status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建人" min-width="100">
          <template #default="{ row }">{{ dataRowOf(row).createBy ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ dataRowOf(row).createTime ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="更新人" min-width="100">
          <template #default="{ row }">{{ dataRowOf(row).updateBy ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ dataRowOf(row).updateTime ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-perms="'system:dict:edit'"
              link
              type="primary"
              @click="openDataEdit(dataRowOf(row))"
              >编辑</el-button
            >
            <el-button
              v-perms="'system:dict:remove'"
              link
              type="danger"
              @click="handleDataDelete(dataRowOf(row))"
              >删除</el-button
            >
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="selectedType ? '暂无字典项' : '请在左侧选择字典类型'"
            :image-size="80"
          />
        </template>
      </el-table>

      <el-pagination
        class="table-pagination"
        v-model:current-page="dataQuery.pageNum"
        v-model:page-size="dataQuery.pageSize"
        :total="dataTotal"
        :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next, sizes"
        @current-change="loadDataPage"
        @size-change="handleDataSizeChange"
      />
    </el-card>

    <DictTypeFormDialog
      v-model="typeDialogVisible"
      :mode="typeDialogMode"
      :dict-type="typeDialogRow"
      @success="handleTypeSaved"
    />
    <DictDataFormDialog
      v-model="dataDialogVisible"
      :mode="dataDialogMode"
      :dict-data="dataDialogRow"
      :type-id="selectedType?.id ?? ''"
      @success="loadDataPage()"
    />
  </div>
</template>

<style scoped>
.dict-layout {
  display: flex;
  align-items: stretch;
}

.type-pane {
  width: 380px;
  flex-shrink: 0;
}

.data-pane {
  flex: 1;
  margin-left: 16px;
  min-width: 0;
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
