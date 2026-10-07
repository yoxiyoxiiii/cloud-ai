<script setup lang="ts">
/**
 * 角色管理页（设计 §3）：分页列表 / 新增 / 编辑（名称·标识·状态）/ 删除 / 分配菜单权限
 * 数据源 /system/role/page（契约 §4.2，含停用角色、id 倒序，无搜索参数——契约现状）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteRole, pageRole } from '../../../api/role'
import type { SysRoleVo } from '../../../types/api'
import RoleFormDialog from './components/RoleFormDialog.vue'
import type { RoleFormMode } from './components/RoleFormDialog.vue'
import AssignMenuDialog from './components/AssignMenuDialog.vue'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（升级设计 D5 红字坑） */
defineOptions({ name: 'SystemRole' })

/** 角色状态展示映射（契约 §4：0=正常 1=停用；未知值 fallback info + 原值） */
const STATUS_MAP: Record<number, { label: string; tagType: 'success' | 'danger' }> = {
  0: { label: '正常', tagType: 'success' },
  1: { label: '停用', tagType: 'danger' },
}

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * el-table 的泛型不会流入列插槽，无法在模板内类型收窄；
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): SysRoleVo {
  return row as SysRoleVo
}

const loading = ref(false)
const rows = ref<SysRoleVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const formDialogVisible = ref(false)
const formDialogMode = ref<RoleFormMode>('add')
const formDialogRole = ref<SysRoleVo>()
const assignMenuVisible = ref(false)
const assignMenuRole = ref<SysRoleVo>()

/** 加载分页（契约 §4.2）：total 为 Long→String，分页组件需 Number() */
async function loadRolePage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
  try {
    query.pageNum = pageNum
    const page = await pageRole({ pageNum: query.pageNum, pageSize: query.pageSize })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function openAdd(): void {
  formDialogMode.value = 'add'
  formDialogRole.value = undefined
  formDialogVisible.value = true
}

function openEdit(role: SysRoleVo): void {
  formDialogMode.value = 'edit'
  formDialogRole.value = role
  formDialogVisible.value = true
}

function openAssignMenu(role: SysRoleVo): void {
  assignMenuRole.value = role
  assignMenuVisible.value = true
}

async function handleDelete(role: SysRoleVo): Promise<void> {
  // 文案含角色名 + 解绑提示（契约 §4.5：删除事务内解除该角色与用户、菜单的绑定）
  const confirmed = await ElMessageBox.confirm(
    `确定删除角色 "${role.name}" 吗？删除后将解除该角色与用户、菜单的绑定。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteRole(role.id)
    ElMessage.success('删除成功')
    await loadRolePage()
  } catch {
    // 拦截器已统一 toast
  }
}

function handleSizeChange(size: number): void {
  query.pageSize = size
  void loadRolePage(1)
}

onMounted(() => {
  void loadRolePage()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>角色管理</span>
        <el-button v-perms="'system:role:add'" type="primary" @click="openAdd">新增角色</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows">
      <el-table-column prop="name" label="角色名称" min-width="120" />
      <el-table-column prop="roleKey" label="权限标识" min-width="120" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'">
            {{ STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建人" min-width="100">
        <template #default="{ row }">{{ rowOf(row).createBy ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).createTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="更新人" min-width="100">
        <template #default="{ row }">{{ rowOf(row).updateBy ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).updateTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'system:role:edit'" link type="primary" @click="openEdit(rowOf(row))"
            >编辑</el-button
          >
          <el-button
            v-perms="'system:role:assignMenu'"
            link
            type="primary"
            @click="openAssignMenu(rowOf(row))"
            >分配权限</el-button
          >
          <el-button
            v-perms="'system:role:remove'"
            link
            type="danger"
            @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next, sizes"
      @current-change="loadRolePage"
      @size-change="handleSizeChange"
    />

    <RoleFormDialog
      v-model="formDialogVisible"
      :mode="formDialogMode"
      :role="formDialogRole"
      @success="loadRolePage()"
    />
    <AssignMenuDialog v-model="assignMenuVisible" :role="assignMenuRole" @success="loadRolePage()" />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
