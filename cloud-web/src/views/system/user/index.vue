<script setup lang="ts">
/**
 * 用户管理页：2026-10-23 表格配置轮改造——顺迁 ProTable（表格壳/分页条收编，不加勾选不加双击）
 * + 列定义数组化接入 useTableConfig（列序/显隐/列宽个人偏好，设计 D12 双页差异表）。
 * mobile/email 列级数据权限显隐收敛进合并函数（原页面散写 v-if 已删——列显隐唯一落点），
 * 权限脱敏列面板标「已脱敏」；操作列锁列模板直写不进配置。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteUser, pageUser } from '../../../api/user'
import { usePermColumns } from '../../../composables/usePermColumns'
import { useTableConfig } from '../../../composables/useTableConfig'
import type { ColumnDef } from '../../../composables/useTableConfig'
import { DATAPERM_RESOURCE_USER, TABLE_KEY_USER } from '../../../types/api'
import type { SysUserVo } from '../../../types/api'
import type { ProTableExposed } from '../../../components/ProTable.vue'
import UserFormDialog from './components/UserFormDialog.vue'
import type { UserFormMode } from './components/UserFormDialog.vue'
import ResetPwdDialog from './components/ResetPwdDialog.vue'
import AssignRoleDialog from './components/AssignRoleDialog.vue'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（升级设计 D5 红字坑） */
defineOptions({ name: 'SystemUser' })

/**
 * 用户状态本地映射（status 语义 0=正常 1=停用，契约 §3）：
 * - tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）
 * - label 仅作 statusLabel 缺位时的降级文案（契约 2026-10-07-translation-api §3.1 降级链）
 * 编辑弹窗回填/提交仍用原字段 status，不消费译文
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
function rowOf(row: unknown): SysUserVo {
  return row as SysUserVo
}

/**
 * EP @header-dragend 列上下文收窄（本页唯一断言点）：只取 property（= 列定义 key）
 */
function columnKeyOf(column: unknown): string {
  const key = (column as { property?: unknown }).property
  return typeof key === 'string' ? key : ''
}

/**
 * 列定义（锁列外，设计 D12）：key = VO 字段名（与数据权限 columnKey 同一命名空间）；
 * slot=true 富内容列（模板 v-for 内按 key 分支），简单文本列（昵称）prop 直出
 */
const COLUMN_DEFS: ColumnDef[] = [
  { key: 'account', label: '账号', minWidth: 120, slot: true },
  { key: 'nickname', label: '昵称', minWidth: 120 },
  { key: 'mobile', label: '手机号', minWidth: 120, slot: true },
  { key: 'email', label: '邮箱', minWidth: 180, slot: true },
  { key: 'status', label: '状态', width: 80, slot: true },
  { key: 'createBy', label: '创建人', minWidth: 100, slot: true },
  { key: 'createTime', label: '创建时间', minWidth: 160, slot: true },
  { key: 'updateBy', label: '更新人', minWidth: 100, slot: true },
  { key: 'updateTime', label: '更新时间', minWidth: 160, slot: true },
]

const loading = ref(false)
const rows = ref<SysUserVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

/** 列级数据权限（user 资源）：my-scope columns 驱动列集——规则变更后下一次加载即同步
 * （即时生效语义，契约 2026-10-22-dataperm-column-visibility-api §1.1） */
const permColumns = usePermColumns(DATAPERM_RESOURCE_USER)

/** ProTable 实例（表头拖拽挂载经 expose 的 getTable() 定位表头） */
const tableRef = ref<ProTableExposed>()

/** 表格列配置（设计 D7-D11）：权限过滤优先于个人偏好（权限赢）；拖拽列序/列宽自动防抖保存 */
const {
  columns: configColumns,
  visibleColumns,
  load: loadColumnConfig,
  applyWidth,
  applyPanel,
  reset: resetColumnConfig,
} = useTableConfig(TABLE_KEY_USER, {
  defs: COLUMN_DEFS,
  table: tableRef,
  isColumnHidden: permColumns.isHidden,
  isColumnMasked: permColumns.isMasked,
})

const formDialogVisible = ref(false)
const formDialogMode = ref<UserFormMode>('add')
const formDialogUser = ref<SysUserVo>()
const resetPwdVisible = ref(false)
const resetPwdUser = ref<SysUserVo>()
const assignRoleVisible = ref(false)
const assignRoleUser = ref<SysUserVo>()

/** 加载分页（契约 §3.1）：total 为 Long→String，分页组件需 Number()；
 * 列清单与分页请求并行重拉（不阻塞表格——即时生效语义），失败降级全列由 composable 内消化 */
async function loadUserPage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
  void permColumns.load()
  try {
    query.pageNum = pageNum
    const page = await pageUser({ pageNum: query.pageNum, pageSize: query.pageSize })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

/** 列宽拖拽落定：EP @header-dragend 经 ProTable $attrs 透传，column.property=列 key */
function onHeaderDragend(newWidth: number, _oldWidth: number, column: unknown): void {
  const key = columnKeyOf(column)
  if (key) {
    applyWidth(key, newWidth)
  }
}

function openAdd(): void {
  formDialogMode.value = 'add'
  formDialogUser.value = undefined
  formDialogVisible.value = true
}

function openEdit(user: SysUserVo): void {
  formDialogMode.value = 'edit'
  formDialogUser.value = user
  formDialogVisible.value = true
}

function openResetPwd(user: SysUserVo): void {
  resetPwdUser.value = user
  resetPwdVisible.value = true
}

function openAssignRole(user: SysUserVo): void {
  assignRoleUser.value = user
  assignRoleVisible.value = true
}

async function handleDelete(user: SysUserVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定删除用户 "${user.account}" 吗？`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteUser(user.id)
    ElMessage.success('删除成功')
    await loadUserPage()
  } catch {
    // 拦截器已统一 toast
  }
}

onMounted(() => {
  // 个人列配置与分页/权限清单并行拉取（互不阻塞）
  void loadUserPage()
  void loadColumnConfig()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>用户管理</span>
        <span class="table-header__actions">
          <TableConfigButton :columns="configColumns" @save="applyPanel" @reset="resetColumnConfig" />
          <el-button v-perms="'system:user:add'" type="primary" @click="openAdd">新增用户</el-button>
        </span>
      </div>
    </template>

    <!-- border 是列宽拖拽的前提：EP resize 处理仅 border 表格生效（POC 实证）；
         row-key 保持行状态与渲染稳定性 -->
    <ProTable
      ref="tableRef"
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page-num="query.pageNum"
      v-model:page-size="query.pageSize"
      row-key="id"
      border
      @page-change="loadUserPage()"
      @header-dragend="onHeaderDragend"
    >
      <el-table-column
        v-for="col in visibleColumns"
        :key="col.key"
        :prop="col.key"
        :label="col.label"
        :width="col.width ?? undefined"
        :min-width="col.width ? undefined : col.minWidth"
      >
        <template v-if="col.slot" #default="{ row }">
          <template v-if="col.key === 'account'">
            <span>{{ rowOf(row).account }}</span>
            <!-- 内置徽标（保护契约 §7.2：内联账号格不新增列；仅展示，禁用面在操作列） -->
            <el-tag v-if="rowOf(row).builtin" class="builtin-badge" type="info" size="small"
              >内置</el-tag
            >
          </template>
          <!-- 手机号/邮箱：脱敏列保留显示（值已是 ***）；null（未填或隐藏）显示 '-' -->
          <span v-else-if="col.key === 'mobile'">{{ rowOf(row).mobile ?? '-' }}</span>
          <span v-else-if="col.key === 'email'">{{ rowOf(row).email ?? '-' }}</span>
          <el-tag
            v-else-if="col.key === 'status'"
            :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'"
          >
            {{ rowOf(row).statusLabel ?? STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
          <span v-else-if="col.key === 'createBy'">{{
            rowOf(row).createByName ?? rowOf(row).createBy ?? '-'
          }}</span>
          <span v-else-if="col.key === 'createTime'">{{ rowOf(row).createTime ?? '-' }}</span>
          <span v-else-if="col.key === 'updateBy'">{{
            rowOf(row).updateByName ?? rowOf(row).updateBy ?? '-'
          }}</span>
          <span v-else-if="col.key === 'updateTime'">{{ rowOf(row).updateTime ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <!-- 内置用户（admin）仅禁 删除/分配角色；编辑/重置密码放行——用户域例外（保护契约 §7.2/§2 放行项，错误码 3017 为最终防线）；编辑弹窗内「停用」项单独禁用 -->
          <el-button v-perms="'system:user:edit'" link type="primary" @click="openEdit(rowOf(row))"
            >编辑</el-button
          >
          <el-button
            v-perms="'system:user:resetPwd'"
            link
            type="primary"
            @click="openResetPwd(rowOf(row))"
            >重置密码</el-button
          >
          <el-button
            v-perms="'system:user:assignRole'"
            link
            type="primary"
            :disabled="rowOf(row).builtin"
            @click="openAssignRole(rowOf(row))"
            >分配角色</el-button
          >
          <el-button
            v-perms="'system:user:remove'"
            link
            type="danger"
            :disabled="rowOf(row).builtin"
            @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </ProTable>

    <UserFormDialog
      v-model="formDialogVisible"
      :mode="formDialogMode"
      :user="formDialogUser"
      @success="loadUserPage()"
    />
    <ResetPwdDialog v-model="resetPwdVisible" :user="resetPwdUser" />
    <AssignRoleDialog v-model="assignRoleVisible" :user="assignRoleUser" />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-header__actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

/** 内置徽标内联账号格（设计 D3 统一形态） */
.builtin-badge {
  margin-left: 8px;
}
</style>
