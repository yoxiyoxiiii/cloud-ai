<script setup lang="ts">
/**
 * 知识库管理页（/rag/kb，契约 2026-10-14-ai-kb-api 2026-10-15 列表管理轮修订版）：
 * 标准表格管理页——搜索栏（§4 name 包含匹配）+ 操作条（§2 新增）+ 四列表格 + 分页（§4 id 倒序）；
 * 编辑/删除按钮权限分立 ai:kb:update/ai:kb:delete（§5/§6，种子 184/185）；
 * 文件管理平移进 FilesDrawer 抽屉（§7 文件分页 + §3 上传，能力面不增不减）；
 * 错误分支（403/1001/5003/5004）由 request.ts 拦截器统一 toast，页面 catch 留空
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteKb, pageKbs } from '../../../api/rag'
import type { KbVo } from '../../../types/api'
import KbFormDialog from './components/KbFormDialog.vue'
import type { KbFormMode } from './components/KbFormDialog.vue'
import FilesDrawer from './components/FilesDrawer.vue'

/** = pathToRouteName('/rag/kb')，keep-alive include 依赖，不可省 */
defineOptions({ name: 'RagKb' })

/** EP el-table-column 插槽 row 固定 DefaultRow，全页断言集中此一处，模板/处理函数禁止再散落裸 as */
function rowOf(row: unknown): KbVo {
  return row as KbVo
}

/** 契约 §4 name 搜索长度上限 50（搜索框 maxlength 与后端校验同值） */
const NAME_MAX = 50

const loading = ref(false)
const rows = ref<KbVo[]>([])
/** AI 域 total 为 number（契约 §1：Python 域不做 Long→String），分页组件直取无需 Number() */
const total = ref(0)
const filter = reactive({ page: 1, size: 10, name: '' })

/** 加载分页（契约 §4）：name 仅非空时携带（空串与不传同为不过滤） */
async function load(page: number = filter.page): Promise<void> {
  loading.value = true
  try {
    filter.page = page
    const result = await pageKbs({
      page: filter.page,
      size: filter.size,
      name: filter.name.trim() || undefined,
    })
    rows.value = result.rows
    total.value = result.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

/** 搜索：回第一页（新过滤条件的结果集从首页看起） */
function search(): void {
  void load(1)
}

/** 重置：清空名称回第一页全量 */
function resetFilter(): void {
  filter.name = ''
  void load(1)
}

function handleSizeChange(size: number): void {
  filter.size = size
  void load(1)
}

/* ============ 新建/编辑弹窗（KbFormDialog add/edit 双模式） ============ */

const formVisible = ref(false)
const formMode = ref<KbFormMode>('add')
const formKb = ref<KbVo>()

function openAdd(): void {
  formMode.value = 'add'
  formKb.value = undefined
  formVisible.value = true
}

function openEdit(kb: KbVo): void {
  formMode.value = 'edit'
  formKb.value = kb
  formVisible.value = true
}

/** 弹窗成功：新建回第一页（id 倒序新库居首可见），编辑刷新当前页（行位不变） */
function handleFormSuccess(): void {
  if (formMode.value === 'add') {
    void load(1)
  } else {
    void load()
  }
}

/* ============ 删除（契约 §6 三表级联软删，确认框前置——契约无 dry-run 端点） ============ */

async function handleDelete(kb: KbVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定删除知识库 "${kb.name}" 吗？其下全部文件将一并删除。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteKb(kb.id)
    ElMessage.success('删除成功')
    // 删空回退：当前页仅剩此行且非首页 → 回上一页再查（防空页滞留）；否则刷新当前页
    if (rows.value.length <= 1 && filter.page > 1) {
      await load(filter.page - 1)
    } else {
      await load()
    }
  } catch {
    // 拦截器已统一 toast（如 5004 目标不存在）
  }
}

/* ============ 文件抽屉（§7 文件分页 + §3 上传，自旧右栏平移） ============ */

const drawerVisible = ref(false)
const drawerKb = ref<KbVo>()

function openFiles(kb: KbVo): void {
  drawerKb.value = kb
  drawerVisible.value = true
}

onMounted(() => {
  void load(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>知识库管理</span>
      </div>
    </template>

    <!-- 搜索栏 + 操作条同排：左 name 搜索（§4 包含匹配）右新增（§2 权限 ai:kb:add） -->
    <div class="toolbar">
      <div class="filter-bar">
        <el-input
          v-model="filter.name"
          placeholder="名称（模糊）"
          clearable
          :maxlength="NAME_MAX"
          class="filter-input"
          @keyup.enter="search"
        />
        <el-button @click="search">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>
      <el-button v-perms="'ai:kb:add'" type="primary" @click="openAdd">新增知识库</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无知识库">
      <el-table-column label="名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).name }}</template>
      </el-table-column>
      <el-table-column label="描述" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).description ?? '暂无描述' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="170">
        <template #default="{ row }">{{ rowOf(row).create_time }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'ai:kb:update'" link type="primary" @click="openEdit(rowOf(row))"
            >编辑</el-button
          >
          <el-button v-perms="'ai:kb:delete'" link type="danger" @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
          <!-- 文件管理为查询类动作：页面可达即有菜单权限 ai:kb:list，不挂按钮级权限 -->
          <el-button link type="primary" @click="openFiles(rowOf(row))">文件管理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      v-model:current-page="filter.page"
      v-model:page-size="filter.size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next, sizes"
      @current-change="load"
      @size-change="handleSizeChange"
    />

    <KbFormDialog
      v-model="formVisible"
      :mode="formMode"
      :kb="formKb"
      @success="handleFormSuccess"
    />
    <FilesDrawer v-model="drawerVisible" :kb-id="drawerKb?.id ?? 0" :kb-name="drawerKb?.name ?? ''" />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-input {
  width: 220px;
}
.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
