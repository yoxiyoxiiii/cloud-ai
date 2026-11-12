<script setup lang="ts">
/**
 * 异步任务列表页（契约 2026-11-10-task-center-api §3-§8，菜单种子 401 /task/async）：
 * - 筛选：status 精确下拉 + taskType / submitBy 文本（契约 §3 三可选项全量暴露）
 * - 列：taskId / 类型(+幂等键) / 状态 tag / 进度（仅执行中行渲染 el-progress+阶段文本，
 *   其余纯文本——避免终态行大量进度条占屏）/ 重试次数 / 提交人 / 来源 / 提交时间
 * - 操作：详情（列表权限隐含）/ 取消（仅排队+执行中行，perms task:asyncTask:cancel；
 *   执行中为协作式取消，确认文案必须明示「将在任务下一检查点生效」）/ 重试（仅失败+已取消行，
 *   perms task:asyncTask:retry）；行级 loading 防重复点击
 * - 自动轮询：当前页存在 status∈{排队,执行中} 行时 2 秒静默刷新（不出 loading 遮罩），
 *   无活动行即停，卸载必清 interval——进度是低频状态流，契约 §8 定稿为轮询而非 SSE
 * - 6004/6005/6006 等业务错误由拦截器统一 toast 后端 msg，页面 catch 留空
 */
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelTask, pageTask, retryTask } from '../../../api/task'
import {
  TASK_STATUS_CANCELED,
  TASK_STATUS_FAILED,
  TASK_STATUS_MAP,
  TASK_STATUS_QUEUED,
  TASK_STATUS_RUNNING,
  TASK_STATUS_TAG,
  type TaskPageQuery,
  type TaskVo,
} from '../../../types/api'
import TaskDetailDialog from './components/TaskDetailDialog.vue'
import DemoSubmitDialog from './components/DemoSubmitDialog.vue'

/** 组件名必须显式固定 = route.name（pathToRouteName('/task/async')='TaskAsync'）：
 * script setup 推断名取文件名（全为 index），keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'TaskAsync' })

/** EP 列插槽 row 固定 DefaultRow，全页断言集中此一处，模板禁散落裸 as */
function rowOf(row: unknown): TaskVo {
  return row as TaskVo
}

/** 活动态集合（契约 §8：轮询只服务未到终态的行） */
const ACTIVE_STATUSES: readonly number[] = [TASK_STATUS_QUEUED, TASK_STATUS_RUNNING]
/** 可取消集合（契约 §5：终态取消得 6004，按钮面先行收敛） */
const CANCELABLE_STATUSES: readonly number[] = [TASK_STATUS_QUEUED, TASK_STATUS_RUNNING]
/** 可重试集合（契约 §6：仅失败/已取消，成功与活动态得 6005） */
const RETRYABLE_STATUSES: readonly number[] = [TASK_STATUS_FAILED, TASK_STATUS_CANCELED]

/** 状态下拉候选（值域固定，文案走 types 常量映射） */
const statusOptions = Object.entries(TASK_STATUS_MAP).map(([value, label]) => ({ value: Number(value), label }))

/* ============ 列表与过滤 ============ */

const loading = ref(false)
const rows = ref<TaskVo[]>([])
const total = ref('0')
const filter = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  taskType: '',
  submitBy: '',
})

function buildQuery(): TaskPageQuery {
  return {
    pageNum: filter.pageNum,
    pageSize: filter.pageSize,
    status: filter.status,
    taskType: filter.taskType.trim() || undefined,
    submitBy: filter.submitBy.trim() || undefined,
  }
}

/**
 * 加载当前页。silent=true 供轮询静默刷新（不落 loading 遮罩——2 秒一次的遮罩闪烁
 * 会让表格不可交互）；失败 catch 留空保住既有行
 */
async function loadPage(pageNum: number = filter.pageNum, silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    filter.pageNum = pageNum
    const page = await pageTask(buildQuery())
    rows.value = page.rows
    total.value = page.total
    syncPolling()
  } catch {
    // 拦截器已统一 toast（6006 等业务码 msg 透出），表格保持现状
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

function search(): void {
  void loadPage(1)
}

function resetFilter(): void {
  filter.status = undefined
  filter.taskType = ''
  filter.submitBy = ''
  void loadPage(1)
}

/* ============ 2 秒条件轮询（存在活动行才轮询，卸载必清） ============ */

const POLL_INTERVAL_MS = 2000
let pollTimer: ReturnType<typeof setInterval> | undefined

/** 按当前行集启停轮询：有 status∈{0,1} 行且未启动 → 启动；无活动行 → 停止 */
function syncPolling(): void {
  const hasActive = rows.value.some((row) => ACTIVE_STATUSES.includes(row.status))
  if (hasActive && pollTimer === undefined) {
    pollTimer = setInterval(() => {
      void loadPage(filter.pageNum, true)
    }, POLL_INTERVAL_MS)
  } else if (!hasActive && pollTimer !== undefined) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

onBeforeUnmount(() => {
  // 卸载必清：keep-alive 场景下页面仍可能被卸载（路由清理），interval 残留会持续打 /task/page
  if (pollTimer !== undefined) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
})

/* ============ 取消 / 重试（行级 loading 防重复点击） ============ */

const actingTaskId = ref<string>()

async function handleCancel(row: TaskVo): Promise<void> {
  // 执行中是协作式取消：确认文案必须明示非瞬时语义（契约 §5，用户需知生效前状态不变）
  const message =
    row.status === TASK_STATUS_RUNNING
      ? `任务 ${row.taskId} 正在执行中，取消请求将在任务下一检查点生效（生效前状态仍显示执行中）。确定请求取消吗？`
      : `确定取消排队中的任务 ${row.taskId}（${row.taskType}）吗？取消立即生效。`
  const confirmed = await ElMessageBox.confirm(message, '取消确认', { type: 'warning' }).catch(() => false)
  if (!confirmed) {
    return
  }
  actingTaskId.value = row.taskId
  try {
    await cancelTask(row.taskId)
    ElMessage.success(
      row.status === TASK_STATUS_RUNNING ? '已请求取消，将在任务下一检查点生效' : '已取消',
    )
  } catch {
    // 拦截器已统一 toast（6004 终态不可取消 / 6006 不存在）
  } finally {
    actingTaskId.value = undefined
    await loadPage()
  }
}

async function handleRetry(row: TaskVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定重试任务 ${row.taskId}（${row.taskType}）吗？状态将重置回排队并重新执行（重试次数 +1）。`,
    '重试确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  actingTaskId.value = row.taskId
  try {
    await retryTask(row.taskId)
    ElMessage.success('已重新排队')
  } catch {
    // 拦截器已统一 toast（6005 当前状态不可重试 / 6006 不存在）
  } finally {
    actingTaskId.value = undefined
    await loadPage()
  }
}

/* ============ 弹窗：详情 / 提交演示任务 ============ */

const detailVisible = ref(false)
const detailRow = ref<TaskVo>()

function openDetail(row: TaskVo): void {
  detailRow.value = row
  detailVisible.value = true
}

const demoVisible = ref(false)

onMounted(() => {
  void loadPage(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>异步任务</span>
        <el-button v-perms="'task:demo:submit'" type="primary" @click="demoVisible = true">
          提交演示任务
        </el-button>
      </div>
    </template>

    <div class="filter-bar">
      <el-select v-model="filter.status" placeholder="状态（全部）" clearable class="filter-item">
        <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-input
        v-model="filter.taskType"
        placeholder="任务类型（如 demo-batch）"
        clearable
        class="filter-item"
        @keyup.enter="search"
      />
      <el-input
        v-model="filter.submitBy"
        placeholder="提交人"
        clearable
        class="filter-item"
        @keyup.enter="search"
      />
      <el-button @click="search">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <ProTable
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page-num="filter.pageNum"
      v-model:page-size="filter.pageSize"
      row-key="taskId"
      @page-change="loadPage()"
    >
      <el-table-column label="任务ID" min-width="160">
        <template #default="{ row }">
          <span class="mono-text">{{ rowOf(row).taskId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" min-width="120">
        <template #default="{ row }">
          <div>{{ rowOf(row).taskType }}</div>
          <div v-if="rowOf(row).bizKey" class="mono-text sub-line">键: {{ rowOf(row).bizKey }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <!-- 失败行原生 title 悬浮展示原因（error_msg 审计线索进列表面，无需开详情） -->
          <span :title="rowOf(row).errorMsg ?? undefined">
            <el-tag :type="TASK_STATUS_TAG[rowOf(row).status] ?? 'info'">
              {{ TASK_STATUS_MAP[rowOf(row).status] ?? rowOf(row).status }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <!-- 列宽总预算 ~1130（1440 视口 − 200 侧边栏 − 内边距 ≈ 1168 可用）：
           总宽超出容器时固定右列会覆盖提交时间尾部，故各列从紧 -->
      <el-table-column label="进度" min-width="185">
        <template #default="{ row }">
          <!-- 仅执行中行渲染进度条（活动态才有推进语义）；阶段文本随行展示 -->
          <div v-if="rowOf(row).status === TASK_STATUS_RUNNING" class="progress-cell">
            <el-progress :percentage="rowOf(row).progress" :stroke-width="8" />
            <div v-if="rowOf(row).phase" class="sub-line">{{ rowOf(row).phase }}</div>
          </div>
          <span v-else>{{ rowOf(row).progress }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="重试" width="70">
        <template #default="{ row }">
          {{ rowOf(row).retryCount }}/{{ rowOf(row).maxRetries }}
        </template>
      </el-table-column>
      <el-table-column prop="submitBy" label="提交人" min-width="80" />
      <el-table-column prop="submitSource" label="来源" min-width="125" show-overflow-tooltip />
      <el-table-column prop="createTime" label="提交时间" min-width="160" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(rowOf(row))">详情</el-button>
          <el-button
            v-if="CANCELABLE_STATUSES.includes(rowOf(row).status)"
            v-perms="'task:asyncTask:cancel'"
            link
            type="danger"
            :loading="actingTaskId === rowOf(row).taskId"
            :disabled="actingTaskId !== undefined && actingTaskId !== rowOf(row).taskId"
            @click="handleCancel(rowOf(row))"
          >
            取消
          </el-button>
          <el-button
            v-if="RETRYABLE_STATUSES.includes(rowOf(row).status)"
            v-perms="'task:asyncTask:retry'"
            link
            type="warning"
            :loading="actingTaskId === rowOf(row).taskId"
            :disabled="actingTaskId !== undefined && actingTaskId !== rowOf(row).taskId"
            @click="handleRetry(rowOf(row))"
          >
            重试
          </el-button>
        </template>
      </el-table-column>
    </ProTable>

    <!-- changed=弹窗内取消/重试已生效，列表重查对齐真态 -->
    <TaskDetailDialog v-model="detailVisible" :task="detailRow" @changed="loadPage()" />
    <DemoSubmitDialog v-model="demoVisible" @success="loadPage(1)" />
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

.sub-line {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.progress-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
