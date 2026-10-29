<script setup lang="ts">
/**
 * 智能体管理页（/agent/manage，契约 2026-10-29-agent-platform-api §2 管理面七端点）：
 * 标准表格管理页——搜索栏（§2.1 name 包含匹配）+ 操作条（§2.2 新增）+ ProTable 列表
 * （名称/工具/温度/状态开关/创建时间/操作）；状态开关走 §2.4 全量编辑（先拉 §2.3 详情
 * 合并提交——列表行无提示词与工具绑定，PUT 全量覆盖语义不能只发 status）；
 * 编辑/删除/运行历史/试跑四入口分立（perms §1：update/delete/run 按钮级控制）；
 * 错误分支（403/1001/5015/5016/5017）拦截器统一 toast，页面 catch 留空
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteAgent, getAgent, pageAgents, updateAgent } from '../../../api/agent'
import type { AgentSummaryVo } from '../../../types/api'
import { hasPerm } from '../../../stores/perm'
import AgentEditDialog from './components/AgentEditDialog.vue'
import type { AgentEditMode } from './components/AgentEditDialog.vue'
import AgentRunDrawer from './components/AgentRunDrawer.vue'
import AgentTryDialog from './components/AgentTryDialog.vue'

/** = pathToRouteName('/agent/manage')，keep-alive include 依赖，不可省 */
defineOptions({ name: 'AgentManage' })

/** 智能体状态（契约 §2.1）：0=启用 1=停用 */
const STATUS_ENABLED = 0
const STATUS_DISABLED = 1

/** 契约 §2.1 name 搜索长度上限 50（搜索框 maxlength 与后端校验同值） */
const NAME_MAX = 50

/**
 * EP el-table-column 插槽 row 固定 DefaultRow，全页断言集中此一处，
 * 模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): AgentSummaryVo {
  return row as AgentSummaryVo
}

const loading = ref(false)
const rows = ref<AgentSummaryVo[]>([])
/** AI 域 total 为 number（契约 §0），ProTable 内部归一直取 */
const total = ref(0)
const query = reactive({ page: 1, size: 10, name: '' })

/** 状态列形态：有编辑权限显示开关（操作入口），无权限只读 tag（v-perms 是移除语义，开关场景需二态渲染） */
const canUpdate = computed(() => hasPerm('ai:agent:update'))

/** 加载分页（契约 §2.1）：name 仅非空时携带（空串与不传同为不过滤） */
async function load(page: number = query.page): Promise<void> {
  loading.value = true
  try {
    query.page = page
    const result = await pageAgents({
      page: query.page,
      size: query.size,
      name: query.name.trim() || undefined,
    })
    rows.value = result.rows
    total.value = result.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function search(): void {
  void load(1)
}

function resetFilter(): void {
  query.name = ''
  void load(1)
}

function handlePageChange(): void {
  void load()
}

/* ============ 新建/编辑弹窗 ============ */

const formVisible = ref(false)
const formMode = ref<AgentEditMode>('add')
const formAgent = ref<AgentSummaryVo>()

function openAdd(): void {
  formMode.value = 'add'
  formAgent.value = undefined
  formVisible.value = true
}

function openEdit(row: AgentSummaryVo): void {
  formMode.value = 'edit'
  formAgent.value = row
  formVisible.value = true
}

/** 弹窗成功：新建回第一页（id 倒序新行居首可见），编辑刷新当前页（行位不变） */
function handleFormSuccess(): void {
  if (formMode.value === 'add') {
    void load(1)
  } else {
    void load()
  }
}

/* ============ 状态开关（契约 §2.4 全量编辑：详情合并提交） ============ */

/**
 * 行内启用/停用：PUT 全量覆盖语义要求带全部字段——先拉 §2.3 详情再合并新状态提交；
 * :model-value 由行数据派生，失败不落本地态（开关自动回弹），成功写回行数据
 */
async function handleStatusChange(row: AgentSummaryVo, enabled: boolean): Promise<void> {
  const next = enabled ? STATUS_ENABLED : STATUS_DISABLED
  try {
    const detail = await getAgent(row.id)
    await updateAgent(row.id, {
      name: detail.name,
      system_prompt: detail.system_prompt,
      description: detail.description ?? undefined,
      temperature: detail.temperature,
      tool_ids: detail.tool_ids,
      status: next,
    })
    row.status = next
    ElMessage.success(next === STATUS_ENABLED ? '已启用' : '已停用')
  } catch {
    // 拦截器已统一 toast；行数据未写回，开关派生值自动回弹
  }
}

/* ============ 删除（契约 §2.5 软删：会话与审计保留） ============ */

async function handleDelete(row: AgentSummaryVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定删除智能体 "${row.name}" 吗？其会话与运行历史将保留（历史中名称降级显示）。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteAgent(row.id)
    ElMessage.success('删除成功')
    // 删空回退：当前页仅剩此行且非首页 → 回上一页再查（防空页滞留）
    if (rows.value.length <= 1 && query.page > 1) {
      await load(query.page - 1)
    } else {
      await load()
    }
  } catch {
    // 拦截器已统一 toast（如 5015 目标不存在）
  }
}

/* ============ 运行历史抽屉 / 试跑弹窗 ============ */

const drawerVisible = ref(false)
const drawerAgent = ref<AgentSummaryVo>()

function openRuns(row: AgentSummaryVo): void {
  drawerAgent.value = row
  drawerVisible.value = true
}

const tryVisible = ref(false)
const tryAgent = ref<AgentSummaryVo>()

function openTry(row: AgentSummaryVo): void {
  tryAgent.value = row
  tryVisible.value = true
}

onMounted(() => {
  void load(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>智能体管理</span>
      </div>
    </template>

    <!-- 搜索栏 + 操作条同排：左 name 搜索（§2.1 包含匹配）右新增（§2.2 权限 ai:agent:add） -->
    <div class="toolbar">
      <div class="filter-bar">
        <el-input
          v-model="query.name"
          placeholder="名称（模糊）"
          clearable
          :maxlength="NAME_MAX"
          class="filter-input"
          @keyup.enter="search"
        />
        <el-button @click="search">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>
      <el-button v-perms="'ai:agent:add'" type="primary" @click="openAdd">新增智能体</el-button>
    </div>

    <!-- row-key：新增行 id 倒序居首，避免按 index 原地 patch 残留旧节点；border 为列宽可读性 -->
    <ProTable
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page-num="query.page"
      v-model:page-size="query.size"
      row-key="id"
      border
      empty-text="暂无智能体"
      @page-change="handlePageChange"
    >
      <el-table-column label="名称" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).name }}</template>
      </el-table-column>
      <el-table-column label="工具" min-width="200">
        <template #default="{ row }">
          <!-- 已绑定工具显示名 tags；空数组 = 纯对话智能体（契约 §2.1） -->
          <template v-if="rowOf(row).tool_names.length > 0">
            <el-tag v-for="t in rowOf(row).tool_names" :key="t" size="small" class="tool-tag">{{ t }}</el-tag>
          </template>
          <span v-else class="plain-mark">纯对话</span>
        </template>
      </el-table-column>
      <el-table-column label="温度" width="80">
        <template #default="{ row }">{{ rowOf(row).temperature }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <!-- 开关是状态编辑入口（需 ai:agent:update）；只读用户降级 tag 展示 -->
          <!-- :model-value 受控 + change 全量提交：失败不写回行数据，开关视觉自动回弹旧态 -->
          <el-switch
            v-if="canUpdate"
            :model-value="rowOf(row).status === STATUS_ENABLED"
            @change="(enabled: string | number | boolean) => handleStatusChange(rowOf(row), Boolean(enabled))"
          />
          <el-tag v-else :type="rowOf(row).status === STATUS_ENABLED ? 'success' : 'danger'">
            {{ rowOf(row).status === STATUS_ENABLED ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).create_time ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'ai:agent:update'" link type="primary" @click="openEdit(rowOf(row))">编辑</el-button>
          <!-- 试跑走运行面：管理与使用分离，按钮随 ai:agent:run 显隐 -->
          <el-button v-perms="'ai:agent:run'" link type="primary" @click="openTry(rowOf(row))">试跑</el-button>
          <!-- 运行历史为查询类动作：页面可达即有 ai:agent:list，不挂按钮级权限 -->
          <el-button link type="primary" @click="openRuns(rowOf(row))">历史</el-button>
          <el-button v-perms="'ai:agent:delete'" link type="danger" @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </ProTable>

    <AgentEditDialog
      v-model="formVisible"
      :mode="formMode"
      :agent="formAgent"
      @success="handleFormSuccess"
    />
    <AgentRunDrawer v-model="drawerVisible" :agent="drawerAgent" />
    <AgentTryDialog v-model="tryVisible" :agent="tryAgent" />
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
.tool-tag {
  margin-right: 4px;
}
.tool-tag:last-child {
  margin-right: 0;
}
.plain-mark {
  color: #909399;
}
</style>
