<script setup lang="ts">
/**
 * 死信治理列表页（契约 2026-10-11-mq-dlq-governance-api §2-§4 / 设计 D6 状态机）：
 * - 过滤区：处置状态（字典 mq_dlq_status 驱动，默认 0 待处理）/来源组（两静态 watch 组+清空）/
 *   msgKey 模糊/死信时间范围（闭区间 yyyy-MM-dd HH:mm:ss）
 * - 表格：dlq_time 倒序分页；**代际链提示**（契约 §2：同 msgKey 多行=重投代际链，当前页
 *   数据按 msgKey 聚合计数，>1 行首列标「代际链 N 行」warning，不新增接口）
 * - 操作四按钮（契约 §4 可见性逐字）：详情恒可见（list）；重投 status∈{0,1} 且
 *   resendCount<5（=5 disabled，后端 3041 亦拦）；忽略 status=0；重置 status∈{2,3}——
 *   均叠加 v-perms 按钮级权限
 * - 三个操作均确认弹窗：重投高危警示回显 msgId/msgKey/bornTopic（契约 §3.3 强制）；
 *   忽略提示终态仅 admin reset 可解除；重置提示将清零重投计数
 */
import { computed, h, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageMqDlq, resetMqDlq, resendMqDlq, ignoreMqDlq } from '../../../api/mqDlq'
import { getDictItems } from '../../../api/dict'
import {
  MQ_DLQ_BORN_GROUP_OPTIONS,
  MQ_DLQ_RESEND_MAX,
  MQ_DLQ_STATUS_DICT_KEY,
  MQ_DLQ_STATUS_MAP,
  MQ_DLQ_STATUS_TAG,
  type DictItemVo,
  type MqDlqPageQuery,
  type MqDlqVo,
} from '../../../types/api'
import DlqDetailDialog from './components/DlqDetailDialog.vue'

/** 组件名必须显式固定 = route.name（pathToRouteName('/system/mq-dlq')='SystemMqDlq'）：
 * script setup 推断名取文件名（全为 index），keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemMqDlq' })

/* 处置状态值（契约 §2 字典 mq_dlq_status / 设计 D6 状态机）：判断/文案用原 status 字段，禁魔法串 */
const STATUS_PENDING = '0'
const STATUS_RESENT = '1'
const STATUS_IGNORED = '2'
const STATUS_ABANDONED = '3'

/** 死信时间范围缺省时刻（契约 §3.1 闭区间入参 yyyy-MM-dd HH:mm:ss：起 00:00:00 / 止 23:59:59） */
const RANGE_DEFAULT_TIME: [Date, Date] = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

/** EP 列插槽 row 固定 DefaultRow，全页断言集中此一处，模板禁散落裸 as */
function rowOf(row: unknown): MqDlqVo {
  return row as MqDlqVo
}

/* ============ 状态字典（契约 §2：标签全字典驱动） ============ */

const statusOptions = ref<DictItemVo[]>([])

async function loadStatusDict(): Promise<void> {
  try {
    statusOptions.value = await getDictItems(MQ_DLQ_STATUS_DICT_KEY)
  } catch {
    // 拦截器已统一 toast；空数组走本地镜像降级（标签/下拉两处同链）
  }
}

/** 状态标签降级链：字典译文 → 本地镜像 → 原值 */
function statusLabelOf(status: string): string {
  return statusOptions.value.find((i) => i.value === status)?.label ?? MQ_DLQ_STATUS_MAP[status] ?? status
}

/** 状态过滤下拉候选：字典驱动；未加载/失败降级本地镜像 */
const statusFilterOptions = computed<{ value: string; label: string }[]>(() =>
  statusOptions.value.length > 0
    ? statusOptions.value
    : Object.entries(MQ_DLQ_STATUS_MAP).map(([value, label]) => ({ value, label })),
)

/* ============ 列表与过滤 ============ */

const loading = ref(false)
const rows = ref<MqDlqVo[]>([])
const total = ref(0)
/** 默认 status=0（待处理）；清空状态选择 = '' → 查询全部 */
const filter = reactive({
  pageNum: 1,
  pageSize: 10,
  status: STATUS_PENDING,
  bornGroup: '',
  msgKey: '',
  dlqTimeRange: [] as string[],
})

function buildQuery(): MqDlqPageQuery {
  return {
    pageNum: filter.pageNum,
    pageSize: filter.pageSize,
    status: filter.status || undefined,
    bornGroup: filter.bornGroup || undefined,
    msgKey: filter.msgKey.trim() || undefined,
    dlqTimeBegin: filter.dlqTimeRange[0],
    dlqTimeEnd: filter.dlqTimeRange[1],
  }
}

async function loadPage(pageNum: number = filter.pageNum): Promise<void> {
  loading.value = true
  try {
    filter.pageNum = pageNum
    const page = await pageMqDlq(buildQuery())
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function search(): void {
  void loadPage(1)
}

/** 重置 = 恢复默认过滤（status 回到待处理 0） */
function resetFilter(): void {
  filter.status = STATUS_PENDING
  filter.bornGroup = ''
  filter.msgKey = ''
  filter.dlqTimeRange = []
  void loadPage(1)
}

function handleSizeChange(size: number): void {
  filter.pageSize = size
  void loadPage(1)
}

/* ============ 代际链提示（契约 §2 / 设计 D5） ============ */

/** 同 msgKey 行计数（当前页数据内聚合；重投代际链 = 同键多行证据链） */
const chainCounts = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const row of rows.value) {
    map[row.msgKey] = (map[row.msgKey] ?? 0) + 1
  }
  return map
})

function chainCountOf(msgKey: string): number {
  return chainCounts.value[msgKey] ?? 1
}

/* ============ 按钮可见性（契约 §4 逐字） ============ */

/** 重投可见：status∈{0,1}（已重投行可继续重投，设计 D6）；resendCount=5 时 disabled（后端 3041 亦拦） */
function canResend(row: MqDlqVo): boolean {
  return row.status === STATUS_PENDING || row.status === STATUS_RESENT
}

/** 忽略可见：仅待处理（已重投行不可忽略，语义互斥，设计 D6） */
function canIgnore(row: MqDlqVo): boolean {
  return row.status === STATUS_PENDING
}

/** 重置可见：终态（已忽略/已放弃）行才可解除（设计 D8：admin 解除终态） */
function canReset(row: MqDlqVo): boolean {
  return row.status === STATUS_IGNORED || row.status === STATUS_ABANDONED
}

/* ============ 详情弹窗 ============ */

const detailVisible = ref(false)
const detailRow = ref<MqDlqVo>()

function openDetail(row: MqDlqVo): void {
  detailRow.value = row
  detailVisible.value = true
}

/* ============ 三个处置操作（均确认弹窗） ============ */

/** 重投确认（契约 §3.3 前置要求）：回显 msgId/msgKey/bornTopic + 高危警示；VNode 渲染（后端原值无 HTML 注入面） */
async function handleResend(row: MqDlqVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    h('div', { class: 'resend-confirm' }, [
      h('div', `消息 ID：${row.msgId}`),
      h('div', `消息键：${row.msgKey}`),
      h('div', `原 topic：${row.bornTopic}`),
      h('div', `来源消费组：${row.bornGroup}`),
      h('div', { class: 'resend-confirm-warn' }, '重投将真实触发业务动作：消息按原文重新投递到原 topic 并由消费端真实执行，请确认故障已修复。'),
    ]),
    '重投确认（高危操作）',
    { type: 'warning', confirmButtonText: '确认重投', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await resendMqDlq(row.id)
    ElMessage.success('重投成功')
  } catch {
    // 拦截器已统一 toast（3040 终态不可重投 / 3041 上限已自动置弃 / 3039 发送失败行不动可重试）
  } finally {
    // 成功与 3041 自动置弃均改变行状态——统一重拉（契约 §3.3「前端重拉列表取新状态」）
    await loadPage()
  }
}

/** 忽略确认：终态仅 admin reset 可解除（契约 §3.4 提示要求） */
async function handleIgnore(row: MqDlqVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定忽略该死信吗（消息键：${row.msgKey}）？忽略后进入终态，仅 admin 重置可解除。`,
    '忽略确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await ignoreMqDlq(row.id)
    ElMessage.success('已忽略')
    await loadPage()
  } catch {
    // 拦截器已统一 toast（3040 非 0 态不可忽略 / 3038 记录不存在）
  }
}

/** 重置确认：回待处理且重投计数清零（契约 §3.5 语义提示） */
async function handleReset(row: MqDlqVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定解除该死信的终态吗（消息键：${row.msgKey}）？行将回到待处理，且重投计数清零。`,
    '重置确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await resetMqDlq(row.id)
    ElMessage.success('已重置')
    await loadPage()
  } catch {
    // 拦截器已统一 toast（3040 非终态不可重置 / 3038 记录不存在）
  }
}

onMounted(() => {
  void loadStatusDict()
  void loadPage(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>死信治理</span>
      </div>
    </template>

    <div class="filter-bar">
      <el-select v-model="filter.status" placeholder="处置状态（全部）" clearable class="filter-item">
        <el-option v-for="opt in statusFilterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-select v-model="filter.bornGroup" placeholder="来源组（全部）" clearable class="filter-group">
        <el-option v-for="g in MQ_DLQ_BORN_GROUP_OPTIONS" :key="g" :label="g" :value="g" />
      </el-select>
      <el-input
        v-model="filter.msgKey"
        placeholder="消息键（模糊）"
        clearable
        class="filter-item filter-input"
        @keyup.enter="search"
      />
      <el-date-picker
        v-model="filter.dlqTimeRange"
        type="datetimerange"
        value-format="YYYY-MM-DD HH:mm:ss"
        :default-time="RANGE_DEFAULT_TIME"
        range-separator="至"
        start-placeholder="死信时间起"
        end-placeholder="死信时间止"
        clearable
        class="filter-daterange"
      />
      <el-button @click="search">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id">
      <!-- 行首列：消息键 + 代际链标记（契约 §2：同 msgKey 多行为重投代际链，当前页内聚合计数） -->
      <el-table-column label="消息键（msgKey）" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="chainCountOf(rowOf(row).msgKey) > 1" type="warning" size="small" class="chain-tag">
            代际链 {{ chainCountOf(rowOf(row).msgKey) }} 行
          </el-tag>
          <div class="mono-text">{{ rowOf(row).msgKey }}</div>
        </template>
      </el-table-column>
      <el-table-column label="消息 ID" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono-text">{{ rowOf(row).msgId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="bornTopic" label="原 topic" min-width="150" />
      <el-table-column label="tag" width="120">
        <template #default="{ row }">{{ rowOf(row).tag ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="bornGroup" label="来源组" min-width="180" show-overflow-tooltip />
      <el-table-column label="body 预览" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).bodyPreview ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="reconsumeTimes" label="重试次数" width="85" />
      <el-table-column prop="dlqTime" label="进死信时间" min-width="160" />
      <el-table-column label="处置状态" width="90">
        <template #default="{ row }">
          <el-tag :type="MQ_DLQ_STATUS_TAG[rowOf(row).status] ?? 'info'">
            {{ statusLabelOf(rowOf(row).status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="resendCount" label="重投次数" width="90" />
      <el-table-column label="处置人" min-width="90">
        <template #default="{ row }">{{ rowOf(row).operateAccount ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'system:mqDlq:list'" link type="primary" @click="openDetail(rowOf(row))">
            详情
          </el-button>
          <el-button
            v-if="canResend(rowOf(row))"
            v-perms="'system:mqDlq:resend'"
            link
            type="warning"
            :disabled="rowOf(row).resendCount >= MQ_DLQ_RESEND_MAX"
            @click="handleResend(rowOf(row))"
          >
            重投
          </el-button>
          <el-button
            v-if="canIgnore(rowOf(row))"
            v-perms="'system:mqDlq:ignore'"
            link
            type="danger"
            @click="handleIgnore(rowOf(row))"
          >
            忽略
          </el-button>
          <el-button
            v-if="canReset(rowOf(row))"
            v-perms="'system:mqDlq:reset'"
            link
            type="primary"
            @click="handleReset(rowOf(row))"
          >
            重置
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      v-model:current-page="filter.pageNum"
      v-model:page-size="filter.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next, sizes"
      @current-change="loadPage"
      @size-change="handleSizeChange"
    />

    <DlqDetailDialog v-model="detailVisible" :row="detailRow" />
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
  width: 160px;
}

.filter-input {
  width: 180px;
}

.filter-group {
  width: 240px;
}

.filter-daterange {
  width: 360px;
}

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.chain-tag {
  margin-bottom: 2px;
}

.mono-text {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
