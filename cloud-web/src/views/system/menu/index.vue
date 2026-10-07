<script setup lang="ts">
/**
 * 菜单管理页（设计 2026-10-06 §3 / D2）：树表展示 / 新增 / 编辑 / 删除
 * 数据源 /system/menu/tree（契约 §2.1，全量含停用、同级 sort 升序）——
 * 菜单量级小（RBAC 常态 <100 行）不分页，el-table 原生树形全展开一屏呈现
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteMenu, menuTree } from '../../../api/menu'
import type { MenuTreeNode } from '../../../types/api'
import MenuFormDialog from './components/MenuFormDialog.vue'
import type { MenuFormMode } from './components/MenuFormDialog.vue'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（升级设计 D5 红字坑） */
defineOptions({ name: 'SystemMenu' })

/** 类型展示映射（契约 §1：M 目录 / C 菜单 / F 按钮；未知值 fallback info + 原值） */
const TYPE_MAP: Record<string, { label: string; tagType: 'primary' | 'success' | 'warning' }> = {
  M: { label: '目录', tagType: 'primary' },
  C: { label: '菜单', tagType: 'success' },
  F: { label: '按钮', tagType: 'warning' },
}

/** 状态展示映射（契约 §1：0=正常 1=停用；tree 不过滤停用菜单，靠此列区分） */
const STATUS_MAP: Record<number, { label: string; tagType: 'success' | 'danger' }> = {
  0: { label: '正常', tagType: 'success' },
  1: { label: '停用', tagType: 'danger' },
}

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * el-table 的泛型不会流入列插槽，无法在模板内类型收窄；
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): MenuTreeNode {
  return row as MenuTreeNode
}

const loading = ref(false)
const treeData = ref<MenuTreeNode[]>([])

const formDialogVisible = ref(false)
const formDialogMode = ref<MenuFormMode>('add')
const formDialogMenu = ref<MenuTreeNode>()

/** 加载全量菜单树（契约 §2.1）：弹窗 success / 删除成功后整树刷新（展开态重置为全展开，可接受） */
async function loadTree(): Promise<void> {
  loading.value = true
  try {
    treeData.value = await menuTree()
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function openAdd(): void {
  formDialogMode.value = 'add'
  formDialogMenu.value = undefined
  formDialogVisible.value = true
}

function openEdit(menu: MenuTreeNode): void {
  formDialogMode.value = 'edit'
  formDialogMenu.value = menu
  formDialogVisible.value = true
}

async function handleDelete(menu: MenuTreeNode): Promise<void> {
  // 文案含菜单名 + 解绑提示（契约 §2.4：删除单事务内物理删除该菜单的全部角色绑定）
  const confirmed = await ElMessageBox.confirm(
    `确定删除菜单 "${menu.name}" 吗？删除后将解除该菜单与角色的绑定。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteMenu(menu.id)
    ElMessage.success('删除成功')
    await loadTree()
  } catch {
    // 拦截器已统一 toast（3005 存在子菜单先删子级 / 3006 不存在）；行保留，用户按树自底向上删
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
        <span>菜单管理</span>
        <el-button v-perms="'system:menu:add'" type="primary" @click="openAdd">新增菜单</el-button>
      </div>
    </template>

    <!-- 树形表：契约已核实叶子 children 恒为空数组 []，EP 判空即不渲染展开箭头 -->
    <el-table
      v-loading="loading"
      :data="treeData"
      row-key="id"
      :tree-props="{ children: 'children' }"
      default-expand-all
    >
      <el-table-column prop="name" label="名称" min-width="240" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="TYPE_MAP[rowOf(row).type]?.tagType ?? 'info'">
            {{ TYPE_MAP[rowOf(row).type]?.label ?? rowOf(row).type }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限标识" min-width="160">
        <!-- 目录（M）perms 恒空串，显示 -（契约 §3） -->
        <template #default="{ row }">{{ rowOf(row).perms || '-' }}</template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'">
            {{ STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建人" width="100">
        <template #default="{ row }">{{ rowOf(row).createBy ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ rowOf(row).createTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="更新人" width="100">
        <template #default="{ row }">{{ rowOf(row).updateBy ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ rowOf(row).updateTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'system:menu:edit'" link type="primary" @click="openEdit(rowOf(row))"
            >编辑</el-button
          >
          <el-button
            v-perms="'system:menu:remove'"
            link
            type="danger"
            @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <MenuFormDialog
      v-model="formDialogVisible"
      :mode="formDialogMode"
      :menu="formDialogMenu"
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
</style>
