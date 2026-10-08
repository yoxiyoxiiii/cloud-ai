<script setup lang="ts">
/**
 * 请假单详情弹窗（计划 F2→F3 / 设计 D10）：LeaveVo 字段平铺 + 流程图区块（F3 增）+ el-steps 竖向时间线
 * 数据链（契约 2026-10-08-bpmn-diagram-designer-api §1 两段式）：
 *   GET /bpmn/leave/{id}（契约 §2.3，既有）→ GET /bpmn/leave/{id}/diagram（契约 §3）
 *   → definitionId 非空则 GET /bpmn/definition/{definitionId}/xml（契约 §2.1）→ BpmnViewer 渲染高亮
 * definitionId=null 为历史实例缺失防御态 → 隐藏图区（契约 §3）；时间线保留（图与时间线互补）
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { getDefinitionXml, getLeaveDetail, getLeaveDiagram } from '../../../../api/bpmn'
import {
  LEAVE_STATUS_MAP,
  LEAVE_TYPE_MAP,
  type LeaveDetailVo,
  type LeaveDiagramVo,
  type LeaveVo,
} from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 列表行数据（提供 id；展示以弹窗内新拉的 detail 为准——行数据可能已过期） */
  leave?: LeaveVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

/** 公共 Viewer 按需分包（设计 D1 铁律）：defineAsyncComponent 引入，bpmn-js 及其 CSS 一并入 async chunk */
const BpmnViewer = defineAsyncComponent(() => import('../../components/BpmnViewer.vue'))

/** 状态→tag 颜色映射（与列表页同款，契约 §2.2 状态字典）：永远按原字段 status 取值 */
const LEAVE_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
}

const loading = ref(false)
const detail = ref<LeaveDetailVo>()

const diagramLoading = ref(false)
/** 图数据与 XML 分开持有：definitionId=null（防御态）时两者皆空 → 图区整体隐藏 */
const diagram = ref<LeaveDiagramVo>()
const diagramXml = ref('')
const diagramFailed = ref(false)

/** 打开序号：快速关闭重开时在途异步结果作废（防串单） */
let openSeq = 0

/**
 * 主高亮 id 集（契约 §3 三态矩阵）：activeActivityIds + 终态 endActivityId 并入——
 * 审批中 end=null 只高亮当前节点；已通过/已拒绝高亮 end 节点；已撤销 end=null 无主高亮
 */
const activeHighlightIds = computed<string[]>(() => {
  const dg = diagram.value
  if (!dg) {
    return []
  }
  return dg.endActivityId ? [...dg.activeActivityIds, dg.endActivityId] : [...dg.activeActivityIds]
})

/** 图区数据链（契约 §1 两段式）：diagram → definitionId 非空再取 xml；失败留图区空态 */
async function loadDiagram(seq: number): Promise<void> {
  if (!props.leave) {
    return
  }
  diagram.value = undefined
  diagramXml.value = ''
  diagramFailed.value = false
  diagramLoading.value = true
  try {
    const dg = await getLeaveDiagram(props.leave.id)
    if (seq !== openSeq) {
      return
    }
    diagram.value = dg
    if (dg.definitionId) {
      const xmlVo = await getDefinitionXml(dg.definitionId)
      if (seq !== openSeq) {
        return
      }
      diagramXml.value = xmlVo.xml
    }
  } catch {
    if (seq === openSeq) {
      diagramFailed.value = true
    }
  } finally {
    if (seq === openSeq) {
      diagramLoading.value = false
    }
  }
}

/** 打开时按 id 拉取最新详情，就绪后串行拉图（计划 F3）：失败 catch 留空（拦截器已统一 toast，如 4001） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    const seq = ++openSeq
    detail.value = undefined
    diagram.value = undefined
    diagramXml.value = ''
    diagramFailed.value = false
    if (!props.leave) {
      return
    }
    loading.value = true
    getLeaveDetail(props.leave.id)
      .then((data) => {
        if (seq !== openSeq) {
          return
        }
        detail.value = data
        void loadDiagram(seq)
      })
      .catch(() => {
        // 拦截器已统一 toast，详情区保持空态
      })
      .finally(() => {
        if (seq === openSeq) {
          loading.value = false
        }
      })
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="请假单详情"
    width="860px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.leave.title }}</el-descriptions-item>
          <el-descriptions-item label="请假类型">
            <!-- 降级链（契约 §7）：译文 → 本地映射 → 原值 -->
            {{ detail.leave.leaveTypeLabel ?? LEAVE_TYPE_MAP[detail.leave.leaveType] ?? detail.leave.leaveType }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="LEAVE_STATUS_TAG[detail.leave.status] ?? 'info'">
              {{ detail.leave.statusLabel ?? LEAVE_STATUS_MAP[detail.leave.status] ?? detail.leave.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="起止日期">
            {{ detail.leave.startDate }} ~ {{ detail.leave.endDate }}
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ detail.leave.createTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请人">
            {{ detail.leave.applyUserName ?? detail.leave.applyUser }}
          </el-descriptions-item>
          <el-descriptions-item label="审批人">
            {{ detail.leave.approverName ?? detail.leave.approver }}
          </el-descriptions-item>
          <el-descriptions-item label="事由" :span="2">{{ detail.leave.reason ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 流程图区块（计划 F3，契约 §1 两段式）：防御态（definitionId=null）与加载失败分治 -->
        <div v-if="diagramLoading" class="diagram-placeholder" v-loading="true"></div>
        <BpmnViewer
          v-else-if="diagramXml"
          class="diagram-block"
          :xml="diagramXml"
          :active-ids="activeHighlightIds"
          :completed-ids="diagram?.completedActivityIds ?? []"
        />
        <div v-else-if="diagramFailed" class="diagram-placeholder diagram-failed">流程图加载失败</div>

        <!-- 时间线：steps 按时间升序（出现的步骤均已发生，active=长度即全完成态）；
             result 仅 end 步骤有值（契约 §2.3） -->
        <el-steps class="detail-steps" direction="vertical" :active="detail.steps.length" :space="90">
          <el-step v-for="step in detail.steps" :key="step.stepKey" :title="step.title">
            <template #description>
              <div class="step-line">{{ step.operatorName ?? step.operator ?? '-' }}</div>
              <div v-if="step.comment" class="step-line">意见：{{ step.comment }}</div>
              <div v-if="step.time" class="step-line">{{ step.time }}</div>
              <div v-if="step.result" class="step-line">结果：{{ step.result }}</div>
            </template>
          </el-step>
        </el-steps>
      </template>
      <el-empty v-else-if="!loading" description="暂无数据" />
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 图区与时间线拉开间距（BpmnViewer 自带 360px 高与边框） */
.diagram-block {
  margin-top: 20px;
}

/* 加载占位/失败空态：与 Viewer 容器同高同边框，视觉无跳变 */
.diagram-placeholder {
  margin-top: 20px;
  height: 360px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.diagram-failed {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.detail-steps {
  margin-top: 20px;
}

.step-line {
  line-height: 1.8;
  font-size: 13px;
}
</style>
