<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteUser, pageUser } from '../../../api/user'
import type { SysUserVo } from '../../../types/api'
import UserFormDialog from './components/UserFormDialog.vue'
import type { UserFormMode } from './components/UserFormDialog.vue'
import ResetPwdDialog from './components/ResetPwdDialog.vue'
import AssignRoleDialog from './components/AssignRoleDialog.vue'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（升级设计 D5 红字坑） */
defineOptions({ name: 'SystemUser' })

/** 用户状态展示映射（契约 §3：0=正常 1=停用） */
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

const loading = ref(false)
const rows = ref<SysUserVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const formDialogVisible = ref(false)
const formDialogMode = ref<UserFormMode>('add')
const formDialogUser = ref<SysUserVo>()
const resetPwdVisible = ref(false)
const resetPwdUser = ref<SysUserVo>()
const assignRoleVisible = ref(false)
const assignRoleUser = ref<SysUserVo>()

/** 加载分页（契约 §3.1）：total 为 Long→String，分页组件需 Number() */
async function loadUserPage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
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

function handleSizeChange(size: number): void {
  query.pageSize = size
  void loadUserPage(1)
}

onMounted(() => {
  void loadUserPage()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>用户管理</span>
        <el-button v-perms="'system:user:add'" type="primary" @click="openAdd">新增用户</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows">
      <el-table-column prop="account" label="账号" min-width="120" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
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
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
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
            @click="openAssignRole(rowOf(row))"
            >分配角色</el-button
          >
          <el-button v-perms="'system:user:remove'" link type="danger" @click="handleDelete(rowOf(row))"
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
      @current-change="loadUserPage"
      @size-change="handleSizeChange"
    />

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

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
