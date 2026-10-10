<script setup lang="ts">
/**
 * 部门管理页（设计 §8 / 契约 2026-10-10-data-permission-api §2）：树表展示 / 新增（根级·子级）/ 编辑 / 删除
 * 数据源 /system/dept/tree（契约 §2.1，全量含停用、sort ASC id ASC）——
 * 部门量级小不分页，el-table 原生树形全展开（沿 menu 页树形态）
 * 删除前置校验在后端（3029 内置 / 3030 有子部门或在职用户，拦截器 toast 后刷新）
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDept, treeDept } from '../../../api/dept'
import type { SysDeptTreeNode } from '../../../types/api'
import DeptFormDialog from './components/DeptFormDialog.vue'
import type { DeptFormMode } from './components/DeptFormDialog.vue'

/** 组件名必须显式固定 = route.name（pathToRouteName('/system/dept')）：
 * script setup 推断名取文件名（全为 index），keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemDept' })

/** 根级 parentId 约定值（契约 §2.1） */
const ROOT_PARENT_ID = '0'

/**
 * 部门状态本地映射（契约 §6.1：0=正常 1=停用；tree 含停用部门靠此列区分）：
 * tagType 永远按原字段 status 取值；label 仅作缺位降级文案（部门域无翻译译文，直接本地映射）
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
function rowOf(row: unknown): SysDeptTreeNode {
  return row as SysDeptTreeNode
}

const loading = ref(false)
const treeData = ref<SysDeptTreeNode[]>([])

const formDialogVisible = ref(false)
const formDialogMode = ref<DeptFormMode>('add')
const formDialogDept = ref<SysDeptTreeNode>()
/** 新增模式的入口注入父 id：表头=根级 "0" / 行内「新增子级」=该行 id */
const formDialogParentId = ref<string>(ROOT_PARENT_ID)

/** 加载全量部门树（契约 §2.1）：弹窗 success / 删除成功后整树刷新 */
async function loadTree(): Promise<void> {
  loading.value = true
  try {
    treeData.value = await treeDept()
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function openAddRoot(): void {
  formDialogMode.value = 'add'
  formDialogDept.value = undefined
  formDialogParentId.value = ROOT_PARENT_ID
  formDialogVisible.value = true
}

function openAddChild(dept: SysDeptTreeNode): void {
  formDialogMode.value = 'add'
  formDialogDept.value = undefined
  formDialogParentId.value = dept.id
  formDialogVisible.value = true
}

function openEdit(dept: SysDeptTreeNode): void {
  formDialogMode.value = 'edit'
  formDialogDept.value = dept
  formDialogVisible.value = true
}

async function handleDelete(dept: SysDeptTreeNode): Promise<void> {
  // 文案含部门名 + 前置校验提示（契约 §2.4：3030 子部门/在职用户由后端拦截并 toast）
  const confirmed = await ElMessageBox.confirm(
    `确定删除部门 "${dept.name}" 吗？删除前须确保其下无子部门且无在职用户。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteDept(dept.id)
    ElMessage.success('删除成功')
    await loadTree()
  } catch {
    // 拦截器已统一 toast（3029 内置 / 3030 有子部门或在职用户 / 3027 不存在）；树保持现状
  }
}

onMounted(() => {
  void loadTree()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>部门管理</span>
        <el-button v-perms="'system:dept:add'" type="primary" @click="openAddRoot">新增部门</el-button>
      </div>
    </template>

    <!-- 树形表：契约 §6.1 叶子 children 恒为空数组 []，EP 判空即不渲染展开箭头 -->
    <el-table
      v-loading="loading"
      :data="treeData"
      row-key="id"
      :tree-props="{ children: 'children' }"
      default-expand-all
    >
      <el-table-column label="部门名称" min-width="240">
        <template #default="{ row }">
          <span>{{ rowOf(row).name }}</span>
          <!-- 内置徽标（契约 §6.1 builtin）：内联名称格；删除按钮禁用面在操作列（3029 为最终防线） -->
          <el-tag v-if="rowOf(row).builtin" class="builtin-badge" type="info" size="small"
            >内置</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'">
            {{ STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).createTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button
            v-perms="'system:dept:add'"
            link
            type="primary"
            @click="openAddChild(rowOf(row))"
            >新增子级</el-button
          >
          <el-button
            v-perms="'system:dept:edit'"
            link
            type="primary"
            @click="openEdit(rowOf(row))"
            >编辑</el-button
          >
          <!-- 内置根部门（id=1 总公司）删除禁用（契约 §2.4：3029 为最终防线） -->
          <el-button
            v-perms="'system:dept:remove'"
            link
            type="danger"
            :disabled="rowOf(row).builtin"
            @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <DeptFormDialog
      v-model="formDialogVisible"
      :mode="formDialogMode"
      :dept="formDialogDept"
      :parent-id="formDialogParentId"
      :tree-data="treeData"
      @success="loadTree()"
    />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/** 内置徽标内联名称格（沿菜单页统一形态） */
.builtin-badge {
  margin-left: 8px;
}
</style>
