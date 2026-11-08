<script setup lang="ts">
/**
 * 通知管理页（契约 2026-11-08-notify-api §1-§2，菜单种子 19 /system/notify）：
 * - 模板列表：name/code 模糊 + type 精确查询；行含 isBuiltin 内置徽标（禁删可改——
 *   删除按钮置灰，后端 3049 双防线；编辑放行，契约 §1.4 内置内容可改）
 * - 行内「发送」打开 SendNotifyDialog 预选该行模板（§2.1 三档接收人 + 变量表单）
 * - 按钮 v-perms 五标识逐端点对齐契约 §5（add/edit/remove/send；list 隐含在菜单可见性）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageTemplate, removeTemplate } from '../../../api/notify'
import {
  NOTIFY_LEVEL_MAP,
  NOTIFY_LEVEL_TAG,
  NOTIFY_TYPE_MAP,
  type NotifyTemplatePageQuery,
  type NotifyTemplateVo,
} from '../../../types/api'
import TemplateEditDialog from './components/TemplateEditDialog.vue'
import type { TemplateFormMode } from './components/TemplateEditDialog.vue'
import SendNotifyDialog from './components/SendNotifyDialog.vue'

/** 组件名必须显式固定 = route.name（pathToRouteName('/system/notify')='SystemNotify'）：
 * script setup 推断名取文件名（全为 index），keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemNotify' })

/** 类型/级别下拉候选（值域固定，文案走 types 常量映射） */
const typeOptions = Object.entries(NOTIFY_TYPE_MAP).map(([value, label]) => ({ value: Number(value), label }))

/** EP 列插槽 row 固定 DefaultRow，全页断言集中此一处，模板禁散落裸 as */
function rowOf(row: unknown): NotifyTemplateVo {
  return row as NotifyTemplateVo
}

/* ============ 列表与过滤 ============ */

const loading = ref(false)
const rows = ref<NotifyTemplateVo[]>([])
const total = ref('0')
const filter = reactive({
  pageNum: 1,
  pageSize: 10,
  name: '',
  code: '',
  type: undefined as number | undefined,
})

function buildQuery(): NotifyTemplatePageQuery {
  return {
    pageNum: filter.pageNum,
    pageSize: filter.pageSize,
    name: filter.name.trim() || undefined,
    code: filter.code.trim() || undefined,
    type: filter.type,
  }
}

async function loadPage(pageNum: number = filter.pageNum): Promise<void> {
  loading.value = true
  try {
    filter.pageNum = pageNum
    const page = await pageTemplate(buildQuery())
    rows.value = page.rows
    total.value = page.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function search(): void {
  void loadPage(1)
}

function resetFilter(): void {
  filter.name = ''
  filter.code = ''
  filter.type = undefined
  void loadPage(1)
}

/* ============ 模板编辑弹窗 ============ */

const editVisible = ref(false)
const editMode = ref<TemplateFormMode>('add')
const editRow = ref<NotifyTemplateVo>()

function openAdd(): void {
  editMode.value = 'add'
  editRow.value = undefined
  editVisible.value = true
}

function openEdit(row: NotifyTemplateVo): void {
  editMode.value = 'edit'
  editRow.value = row
  editVisible.value = true
}

/* ============ 发送弹窗 ============ */

const sendVisible = ref(false)
const sendPreset = ref<NotifyTemplateVo>()

function openSend(row: NotifyTemplateVo): void {
  sendPreset.value = row
  sendVisible.value = true
}

/* ============ 删除（确认框；内置模板按钮置灰 + 后端 3049 双防线） ============ */

async function handleDelete(row: NotifyTemplateVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定删除模板「${row.name}（${row.code}）」吗？`, '删除确认', {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await removeTemplate(row.id)
    ElMessage.success('已删除')
    await loadPage()
  } catch {
    // 拦截器已统一 toast（3044 不存在 / 3049 内置禁删）
  }
}

onMounted(() => {
  void loadPage(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>通知管理</span>
        <el-button v-perms="'system:notify:add'" type="primary" @click="openAdd">新增模板</el-button>
      </div>
    </template>

    <div class="filter-bar">
      <el-input
        v-model="filter.name"
        placeholder="模板名称（模糊）"
        clearable
        class="filter-item"
        @keyup.enter="search"
      />
      <el-input
        v-model="filter.code"
        placeholder="模板编码（模糊）"
        clearable
        class="filter-item"
        @keyup.enter="search"
      />
      <el-select v-model="filter.type" placeholder="消息类型（全部）" clearable class="filter-item">
        <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-button @click="search">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <ProTable
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page-num="filter.pageNum"
      v-model:page-size="filter.pageSize"
      row-key="id"
      @page-change="loadPage()"
    >
      <el-table-column label="模板编码" min-width="160">
        <template #default="{ row }">
          <span class="mono-text">{{ rowOf(row).code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="模板名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="消息类型" width="90">
        <template #default="{ row }">
          <el-tag :type="rowOf(row).type === 1 ? 'primary' : 'success'">
            {{ NOTIFY_TYPE_MAP[rowOf(row).type] ?? rowOf(row).type }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="消息级别" width="90">
        <template #default="{ row }">
          <el-tag :type="NOTIFY_LEVEL_TAG[rowOf(row).level] ?? 'info'">
            {{ NOTIFY_LEVEL_MAP[rowOf(row).level] ?? rowOf(row).level }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标题模板" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).titleTemplate }}</template>
      </el-table-column>
      <el-table-column label="内容模板" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).contentTemplate }}</template>
      </el-table-column>
      <el-table-column label="内置" width="70">
        <template #default="{ row }">
          <el-tag v-if="rowOf(row).isBuiltin === 1" type="info" size="small">内置</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createBy" label="创建人" min-width="90" />
      <el-table-column prop="createTime" label="创建时间" min-width="160" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'system:notify:send'" link type="primary" @click="openSend(rowOf(row))">
            发送
          </el-button>
          <el-button v-perms="'system:notify:edit'" link type="primary" @click="openEdit(rowOf(row))">
            编辑
          </el-button>
          <el-button
            v-perms="'system:notify:remove'"
            link
            type="danger"
            :disabled="rowOf(row).isBuiltin === 1"
            @click="handleDelete(rowOf(row))"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </ProTable>

    <TemplateEditDialog v-model="editVisible" :mode="editMode" :template="editRow" @success="loadPage()" />
    <!-- 发送不改变模板列表（消息内容为发送时点快照），success 仅用于弹窗内关闭链路，列表无需刷新 -->
    <SendNotifyDialog v-model="sendVisible" :preset-template="sendPreset" />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 8px;
  margin-bottom: 12px;
}

.filter-item {
  width: 180px;
}

.mono-text {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
