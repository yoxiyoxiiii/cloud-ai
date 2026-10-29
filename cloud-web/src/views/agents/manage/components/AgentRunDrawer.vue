<script setup lang="ts">
/**
 * 运行历史抽屉（契约 §2.7 运行历史分页，perms ai:agent:list）：
 * 每次打开回第一页加载（时间倒序）；行展开显示 tool_calls 明细（工具标识/成败/失败原因/入参）。
 * 会话与审计行随智能体删除保留（软删不清审计），抽屉对已删智能体同样可查
 */
import { reactive, ref, watch } from 'vue'
import { pageAgentRuns } from '../../../../api/agent'
import type { AgentSummaryVo, RunLogVo, RunToolCallVo } from '../../../../types/api'
import type { ProTableExposed } from '../../../../components/ProTable.vue'

interface Props {
  modelValue: boolean
  /** 目标智能体行（取 id 与名称）；可缺省——标题插值随组件渲染即求值，空表下兜底必入 undefined */
  agent?: AgentSummaryVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

/** 运行状态（契约 §2.7）：0=成功 1=失败（失败=异常终止；模型对工具错误的正常转述属成功） */
const RUN_STATUS_SUCCESS = 0
const RUN_STATUS_FAILED = 1

const STATUS_MAP: Record<number, { label: string; tagType: 'success' | 'danger' }> = {
  [RUN_STATUS_SUCCESS]: { label: '成功', tagType: 'success' },
  [RUN_STATUS_FAILED]: { label: '失败', tagType: 'danger' },
}

/** EP el-table-column 插槽 row 固定 DefaultRow，全页断言集中此一处，模板/处理函数禁止再散落裸 as */
function rowOf(row: unknown): RunLogVo {
  return row as RunLogVo
}

/** 展开行 tool_calls 项收窄（展开作用域插槽同为 DefaultRow 语境） */
function callOf(c: unknown): RunToolCallVo {
  return c as RunToolCallVo
}

const tableRef = ref<ProTableExposed>()
const loading = ref(false)
const rows = ref<RunLogVo[]>([])
/** AI 域 total 为 number（契约 §0：Python 域不做 Long→String），ProTable 内部归一直取 */
const total = ref(0)
const query = reactive({ page: 1, size: 10 })

/** 运行历史分页（契约 §2.7）：失败拦截器已 toast，表格保持现状 */
async function load(page: number = query.page): Promise<void> {
  if (!props.agent) {
    return
  }
  loading.value = true
  try {
    query.page = page
    const result = await pageAgentRuns(props.agent.id, { page: query.page, size: query.size })
    rows.value = result.rows
    total.value = result.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

/** 打开时回第一页加载（每次打开取最新历史） */
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void load(1)
    }
  },
)

function handlePageChange(): void {
  void load()
}

/** 工具入参对象转紧凑 JSON（展开行展示用；序列化失败降级 String） */
function argsText(args: object): string {
  try {
    return JSON.stringify(args)
  } catch {
    return String(args)
  }
}
</script>

<template>
  <!-- append-to-body：抽屉挂 body，避免叠加在管理页卡片内被裁剪（沿 FilesDrawer 先例） -->
  <el-drawer
    :model-value="modelValue"
    :title="props.agent ? `运行历史 · ${props.agent.name}` : '运行历史'"
    size="62%"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <ProTable
      ref="tableRef"
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page-num="query.page"
      v-model:page-size="query.size"
      row-key="id"
      border
      empty-text="暂无运行记录"
      @page-change="handlePageChange"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <!-- 展开行：工具调用明细（无工具运行为空数组 → 显示占位文案） -->
          <div class="calls">
            <template v-if="rowOf(row).tool_calls.length > 0">
              <div v-for="(c, i) in rowOf(row).tool_calls" :key="i" class="call-item">
                <div class="call-head">
                  <el-tag size="small" type="info">{{ callOf(c).tool_key }}</el-tag>
                  <el-tag size="small" :type="callOf(c).ok ? 'success' : 'danger'">
                    {{ callOf(c).ok ? '成功' : '失败' }}
                  </el-tag>
                </div>
                <div v-if="callOf(c).error" class="call-error">{{ callOf(c).error }}</div>
                <div class="call-args">{{ argsText(callOf(c).arguments) }}</div>
              </div>
            </template>
            <div v-else class="calls-empty">本次运行未调用工具</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="日志ID" prop="id" width="90" />
      <el-table-column label="会话ID" prop="session_id" width="90" />
      <el-table-column label="运行用户" width="100">
        <template #default="{ row }">{{ rowOf(row).user_id }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'">
            {{ STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="工具调用" width="90">
        <template #default="{ row }">{{ rowOf(row).tool_calls.length }} 次</template>
      </el-table-column>
      <el-table-column label="失败摘要" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).error_msg ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ rowOf(row).duration_ms }} ms</template>
      </el-table-column>
      <el-table-column label="结束时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).create_time }}</template>
      </el-table-column>
    </ProTable>
  </el-drawer>
</template>

<style scoped>
.calls {
  padding: 4px 12px;
}
.call-item {
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
}
.call-item:last-child {
  border-bottom: none;
}
.call-head {
  display: flex;
  gap: 8px;
  align-items: center;
}
.call-error {
  margin-top: 4px;
  color: #f56c6c;
  font-size: 12px;
}
.call-args {
  margin-top: 4px;
  color: #606266;
  font-size: 12px;
  word-break: break-all;
}
.calls-empty {
  color: #909399;
  font-size: 12px;
  padding: 4px 0;
}
</style>
