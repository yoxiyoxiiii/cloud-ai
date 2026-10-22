<script setup lang="ts">
/**
 * 数据字典管理页（单卡全宽列表 + 字典项弹框，重构设计 D1/D3）：
 * 类型表全宽分页（契约 §2.1，id 倒序）；行内"字典项"按钮或双击行打开 DictDataDialog 弹框，
 * 项的分页与增删改全部内聚在弹框内（契约 §3，原主从双卡右栏逻辑已迁入）
 * 类型增删改仍在本页：表单弹窗 DictTypeFormDialog + 操作列 编辑/删除
 * 类型表已接入通用表格组件 ProTable（2026-10-21）：表格壳/勾选列/分页条收编组件
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDictType, pageDictType } from '../../../api/dict'
import type { SysDictTypeVo } from '../../../types/api'
import type { ProTableExposed } from '../../../components/ProTable.vue'
import DictTypeFormDialog from './components/DictTypeFormDialog.vue'
import type { DictTypeFormMode } from './components/DictTypeFormDialog.vue'
import DictDataDialog from './components/DictDataDialog.vue'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D5 红字坑） */
defineOptions({ name: 'SystemDict' })

/**
 * 状态本地映射（契约 §1：0=正常 1=停用；管理端点不过滤停用，靠 tag 区分）：
 * - tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）
 * - label 仅作 statusLabel 缺位时的降级文案（契约 2026-10-07-translation-api §8.1/§8.4 降级链；
 *   审计译文后端照给、UI 审计列不展示——Round E 取舍维持）
 */
const STATUS_MAP: Record<number, { label: string; tagType: 'success' | 'danger' }> = {
  0: { label: '正常', tagType: 'success' },
  1: { label: '停用', tagType: 'danger' },
}

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * el-table 的泛型不会流入列插槽，无法在模板内类型收窄；
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function typeRowOf(row: unknown): SysDictTypeVo {
  return row as SysDictTypeVo
}

// ---------- 类型列表（单卡全宽） ----------
const typeLoading = ref(false)
const typeRows = ref<SysDictTypeVo[]>([])
/** total 直传 ProTable（组件内归一），类型保持后端原样 string */
const typeTotal = ref<number | string>(0)
/** 分页收编进 ProTable：10/20/50 可切（2026-10-21 组件化取代 dict 轮恒 10 旧口径） */
const typeQuery = reactive({ pageNum: 1, pageSize: 10 })

/** 加载类型分页（契约 §2.1）：total 为 Long→String 字符串，ProTable 内部归一 */
async function loadTypePage(pageNum: number = typeQuery.pageNum): Promise<void> {
  typeLoading.value = true
  try {
    typeQuery.pageNum = pageNum
    const page = await pageDictType({ pageNum: typeQuery.pageNum, pageSize: typeQuery.pageSize })
    typeRows.value = page.rows
    typeTotal.value = page.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    typeLoading.value = false
  }
}

const typeDialogVisible = ref(false)
const typeDialogMode = ref<DictTypeFormMode>('add')
const typeDialogRow = ref<SysDictTypeVo>()

// ---------- ProTable 勾选与翻页 ----------
/** ProTable 实例（expose 面见 ProTableExposed：清勾选） */
const tableRef = ref<ProTableExposed>()

/** 勾选集（ProTable selection-change 上抛；工具栏暂不消费选中集，仅保证链路通） */
const selectedRows = ref<SysDictTypeVo[]>([])

/** 勾选集上抛收窄（unknown[] 经全页唯一断言函数收敛，不散落裸 as） */
function handleSelectionChange(rows: unknown[]): void {
  selectedRows.value = rows.map(typeRowOf)
}

/** 翻页/改 size：先清勾选（选中集不跨页保留），再按新页码加载 */
function handleTypePageChange(): void {
  tableRef.value?.clearSelection()
  void loadTypePage()
}

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

/** 类型保存成功：add 回第 1 页（新行 id 倒序置顶可见）；edit 留当前页原地刷新 */
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
    await loadTypePage()
    tableRef.value?.clearSelection()
  } catch {
    // 拦截器已统一 toast（3011/3008）；表格刷新后自然对齐
  }
}

// ---------- 字典项弹框 ----------
const dataDialogVisible = ref(false)
/** 弹框当前管理的类型行（标题与项数据源）；无 success 回传——父页不关心项变更 */
const dataDialogType = ref<SysDictTypeVo>()

function openDataDialog(row: SysDictTypeVo): void {
  dataDialogType.value = row
  dataDialogVisible.value = true
}

onMounted(() => {
  void loadTypePage()
})
</script>

<template>
  <el-card shadow="never" class="type-pane">
    <template #header>
      <div class="pane-header">
        <span>字典类型</span>
        <el-button v-perms="'system:dict:add'" type="primary" @click="openTypeAdd"
          >新增类型</el-button
        >
      </div>
    </template>

    <!-- row-key：无 key 时 el-table 按 index 原地 patch，新增行会瞬时残留上一行 el-tag 节点（E3 dict D2 渲染竞态实锤） -->
    <ProTable
      ref="tableRef"
      :rows="typeRows"
      :loading="typeLoading"
      :total="typeTotal"
      v-model:page-num="typeQuery.pageNum"
      v-model:page-size="typeQuery.pageSize"
      selectable
      row-key="id"
      @selection-change="handleSelectionChange"
      @row-dblclick="(row: unknown) => openDataDialog(typeRowOf(row))"
      @page-change="handleTypePageChange"
    >
      <el-table-column label="字典名称" min-width="140">
        <template #default="{ row }">
          <span>{{ typeRowOf(row).dictName }}</span>
          <!-- 内置徽标（保护契约 §7.2：内联名称格不新增列；仅展示，禁用面在操作列） -->
          <el-tag v-if="typeRowOf(row).builtin" class="builtin-badge" type="info" size="small"
            >内置</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column prop="dictKey" label="字典键" min-width="160" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[typeRowOf(row).status]?.tagType ?? 'info'">
            {{
              typeRowOf(row).statusLabel ?? STATUS_MAP[typeRowOf(row).status]?.label ?? typeRowOf(row).status
            }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <!-- 内置类型（user_status/common_status）编辑/删除禁用；「字典项」按钮放行——内置类型可进弹框管理项（保护契约 §7.2/§7.3，错误码 3015 为最终防线） -->
          <el-button
            v-perms="'system:dict:list'"
            link
            type="primary"
            @click="openDataDialog(typeRowOf(row))"
            >字典项</el-button
          >
          <el-button
            v-perms="'system:dict:edit'"
            link
            type="primary"
            :disabled="typeRowOf(row).builtin"
            @click="openTypeEdit(typeRowOf(row))"
            >编辑</el-button
          >
          <el-button
            v-perms="'system:dict:remove'"
            link
            type="danger"
            :disabled="typeRowOf(row).builtin"
            @click="handleTypeDelete(typeRowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </ProTable>

    <DictTypeFormDialog
      v-model="typeDialogVisible"
      :mode="typeDialogMode"
      :dict-type="typeDialogRow"
      @success="handleTypeSaved"
    />
    <DictDataDialog v-model="dataDialogVisible" :dict-type="dataDialogType" />
  </el-card>
</template>

<style scoped>
.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/** 内置徽标内联名称格（设计 D3 统一形态） */
.builtin-badge {
  margin-left: 8px;
}
</style>
