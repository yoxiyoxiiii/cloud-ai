<script setup lang="ts">
/**
 * 任务详情弹窗（契约 §3/§4）：打开时按行 taskId 单次拉取详情，之后若任务仍处
 * 活动态（排队/执行中）2 秒轮询刷新（关闭即清 interval，启停口径同列表页）。
 * params/result 为提交方/执行器的 JSON 原文——能解析则格式化缩进展示，解析失败兜底原文。
 * 底部取消/重试按钮显隐与列表页同口径（取消=排队/执行中、重试=失败/已取消）；
 * 弹窗内动作成功后重拉详情并 emit('changed') 让列表页对齐真态。
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelTask, getTaskDetail, retryTask } from '../../../../api/task'
import {
  TASK_STATUS_CANCELED,
  TASK_STATUS_FAILED,
  TASK_STATUS_MAP,
  TASK_STATUS_QUEUED,
  TASK_STATUS_RUNNING,
  TASK_STATUS_TAG,
  type TaskDetailVo,
  type TaskVo,
} from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 列表行数据（打开时取其 taskId 拉详情；列表行字段仅作拉取前的即时回显基底） */
  task?: TaskVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 弹窗内取消/重试已生效（列表页据此重查） */
  changed: []
}>()

/** 活动态集合（轮询口径同列表页，契约 §8） */
const ACTIVE_STATUSES: readonly number[] = [TASK_STATUS_QUEUED, TASK_STATUS_RUNNING]
/** 可取消/可重试集合（契约 §5/§6，同列表口径） */
const CANCELABLE_STATUSES: readonly number[] = [TASK_STATUS_QUEUED, TASK_STATUS_RUNNING]
const RETRYABLE_STATUSES: readonly number[] = [TASK_STATUS_FAILED, TASK_STATUS_CANCELED]

const loading = ref(false)
const detail = ref<TaskDetailVo>()
/** 行级动作 loading（取消/重试共用，防重复点击） */
const acting = ref(false)

/** JSON 原文展示：可解析则缩进格式化，失败兜底原文（提交方契约只承诺 JSON 对象文本，防御展示） */
function prettyJson(text: string | null): string {
  if (text === null || text === '') {
    return '-'
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

async function fetchDetail(): Promise<void> {
  if (!props.task) {
    return
  }
  loading.value = true
  try {
    detail.value = await getTaskDetail(props.task.taskId)
  } catch {
    // 拦截器已统一 toast（6006 任务不存在——可能已被保留期清理），保持既有回显
  } finally {
    loading.value = false
    syncPolling()
  }
}

/* ============ 活动态 2 秒轮询（关闭即清，卸载必清） ============ */

const POLL_INTERVAL_MS = 2000
let pollTimer: ReturnType<typeof setInterval> | undefined

function stopPolling(): void {
  if (pollTimer !== undefined) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function syncPolling(): void {
  const active = detail.value !== undefined && ACTIVE_STATUSES.includes(detail.value.status)
  if (active && props.modelValue && pollTimer === undefined) {
    pollTimer = setInterval(() => {
      void fetchDetail()
    }, POLL_INTERVAL_MS)
  } else if (!active && pollTimer !== undefined) {
    stopPolling()
  }
}

onBeforeUnmount(stopPolling)

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void fetchDetail()
    } else {
      stopPolling()
      detail.value = undefined
    }
  },
)

/* ============ 弹窗内取消/重试（文案口径同列表页） ============ */

async function handleCancel(): Promise<void> {
  if (!detail.value) {
    return
  }
  const row = detail.value
  const message =
    row.status === TASK_STATUS_RUNNING
      ? `任务 ${row.taskId} 正在执行中，取消请求将在任务下一检查点生效（生效前状态仍显示执行中）。确定请求取消吗？`
      : `确定取消排队中的任务 ${row.taskId}（${row.taskType}）吗？取消立即生效。`
  const confirmed = await ElMessageBox.confirm(message, '取消确认', { type: 'warning' }).catch(() => false)
  if (!confirmed) {
    return
  }
  acting.value = true
  try {
    await cancelTask(row.taskId)
    ElMessage.success(
      row.status === TASK_STATUS_RUNNING ? '已请求取消，将在任务下一检查点生效' : '已取消',
    )
  } catch {
    // 拦截器已统一 toast（6004 / 6006）
  } finally {
    acting.value = false
    await fetchDetail()
    emit('changed')
  }
}

async function handleRetry(): Promise<void> {
  if (!detail.value) {
    return
  }
  const row = detail.value
  const confirmed = await ElMessageBox.confirm(
    `确定重试任务 ${row.taskId}（${row.taskType}）吗？状态将重置回排队并重新执行（重试次数 +1）。`,
    '重试确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  acting.value = true
  try {
    await retryTask(row.taskId)
    ElMessage.success('已重新排队')
  } catch {
    // 拦截器已统一 toast（6005 / 6006）
  } finally {
    acting.value = false
    await fetchDetail()
    emit('changed')
  }
}

function close(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="任务详情"
    width="720px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading">
      <el-descriptions v-if="detail" :column="2" border size="small">
        <el-descriptions-item label="任务ID">
          <span class="mono-text">{{ detail.taskId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="任务类型">{{ detail.taskType }}</el-descriptions-item>
        <el-descriptions-item label="业务幂等键">{{ detail.bizKey ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="TASK_STATUS_TAG[detail.status] ?? 'info'">
            {{ TASK_STATUS_MAP[detail.status] ?? detail.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="进度">{{ detail.progress }}%</el-descriptions-item>
        <el-descriptions-item label="阶段">{{ detail.phase ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ detail.retryCount }}/{{ detail.maxRetries }}</el-descriptions-item>
        <el-descriptions-item label="超时配置">{{ detail.timeoutSeconds }} 秒</el-descriptions-item>
        <el-descriptions-item label="提交人">{{ detail.submitBy }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.submitSource }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ detail.createTime }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ detail.startTime ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ detail.finishTime ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="执行器">{{ detail.executorId ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="取消请求">
          <!-- 协作式取消标志（契约 §4）：已请求但未生效期间状态仍为执行中 -->
          {{ detail.cancelRequested === 1 ? '已请求（等待下一检查点生效）' : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">
          <span class="error-text">{{ detail.errorMsg ?? '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="提交参数" :span="2">
          <pre class="json-pre">{{ prettyJson(detail.params) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="执行结果" :span="2">
          <pre class="json-pre">{{ prettyJson(detail.result) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-else :image-size="64" description="详情加载中" />
    </div>
    <template #footer>
      <el-button
        v-if="detail && CANCELABLE_STATUSES.includes(detail.status)"
        v-perms="'task:asyncTask:cancel'"
        type="danger"
        :loading="acting"
        @click="handleCancel"
      >
        取消任务
      </el-button>
      <el-button
        v-if="detail && RETRYABLE_STATUSES.includes(detail.status)"
        v-perms="'task:asyncTask:retry'"
        type="warning"
        :loading="acting"
        @click="handleRetry"
      >
        重试任务
      </el-button>
      <el-button :disabled="acting" @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mono-text {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  word-break: break-all;
}

.error-text {
  word-break: break-all;
  white-space: pre-wrap;
}

.json-pre {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
