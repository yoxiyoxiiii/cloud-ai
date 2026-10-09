<script setup lang="ts">
/**
 * 审批单详情弹窗（设计 D9，新）：平台单源拼装——approval descriptions + 时间线 + 流程图
 * 双入口（契约 2026-10-09-rocketmq-tx-approval-api §2.2「查看审批」跳转协议，同请假页模式）：
 * - 列表行 approval（id 驱动）
 * - 直达 approvalId（query.approval 落点）：仅 id，主体以弹窗内新拉为准
 * 数据链（契约 §3.2/§3.4 + diagram 契约 §1 两段式）：
 *   GET /bpmn/approval/{id}（approval 主体 + steps 时间线）
 *   → GET /bpmn/approval/{id}/diagram → definitionId 非空则 GET /bpmn/definition/{definitionId}/xml
 *   → BpmnViewer 三态高亮；definitionId=null 为防御态 → 隐藏图区
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { getApprovalDetail, getApprovalDiagram, getDefinitionXml } from '../../../../api/bpmn'
import {
  APPROVAL_STATUS_MAP,
  type ApprovalDetailVo,
  type ApprovalDiagramVo,
  type ApprovalVo,
} from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 列表行数据（提供 id；展示以弹窗内新拉的 detail 为准——行数据可能已过期） */
  approval?: ApprovalVo
  /** 直达入口（query.approval = 审批单 id）：与 approval 二选一，行入口优先 */
  approvalId?: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 关闭动画结束（父页清落点 query 与入口数据） */
  closed: []
}>()

/** 双入口归一：列表行 id 优先，其次直达 approvalId（均缺=不打开发据链） */
function resolveApprovalId(): string | undefined {
  return props.approval?.id ?? props.approvalId
}

/** 公共 Viewer 按需分包（设计 D9 分包铁律）：defineAsyncComponent 引入，bpmn-js 及其 CSS 一并入 async chunk */
const BpmnViewer = defineAsyncComponent(() => import('../../../../components/bpmn/BpmnViewer.vue'))

/** 状态→tag 颜色映射（与列表页同款，契约 §3.1 status 字典）：永远按原字段 status 取值 */
const APPROVAL_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
}

const loading = ref(false)
const detail = ref<ApprovalDetailVo>()

const diagramLoading = ref(false)
/** 图数据与 XML 分开持有：definitionId=null（防御态）时两者皆空 → 图区整体隐藏 */
const diagram = ref<ApprovalDiagramVo>()
const diagramXml = ref('')
const diagramFailed = ref(false)

/** 打开序号：快速关闭重开时在途异步结果作废（防串单） */
let openSeq = 0

/**
 * 主高亮 id 集（契约 §3.4 三态矩阵）：activeActivityIds + 终态 endActivityId 并入——
 * 审批中 end=null 只高亮当前节点；已通过/已拒绝高亮 end 节点；已撤销 end=null 无主高亮
 */
const activeHighlightIds = computed<string[]>(() => {
  const dg = diagram.value
  if (!dg) {
    return []
  }
  return dg.endActivityId ? [...dg.activeActivityIds, dg.endActivityId] : [...dg.activeActivityIds]
})

/** 图区数据链（契约 diagram §1 两段式）：diagram → definitionId 非空再取 xml；失败留图区空态 */
async function loadDiagram(seq: number): Promise<void> {
  const approvalId = resolveApprovalId()
  if (!approvalId) {
    return
  }
  diagram.value = undefined
  diagramXml.value = ''
  diagramFailed.value = false
  diagramLoading.value = true
  try {
    const dg = await getApprovalDiagram(approvalId)
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

/**
 * 打开时按 id 拉取最新详情，就绪后拉图：失败 catch 留空（拦截器已统一 toast，如 4010）。
 * immediate 必须有：同 LeaveDetailDialog（F9 走查 A3）——若调用方在挂载前已置
 * modelValue=true（如 query 直达入口 /bpmn/approval?approval={id}），无 false→true
 * 变更可侦听将零请求空白
 */
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
    const approvalId = resolveApprovalId()
    if (!approvalId) {
      return
    }
    loading.value = true
    getApprovalDetail(approvalId)
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
  { immediate: true },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="审批单详情"
    width="860px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="emit('closed')"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.approval.title }}</el-descriptions-item>
          <el-descriptions-item label="业务类型">
            <!-- 配置表 join 必返非空（契约 §9），无降级链 -->
            {{ detail.approval.businessTypeName }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="APPROVAL_STATUS_TAG[detail.approval.status] ?? 'info'">
              {{ detail.approval.statusLabel ?? APPROVAL_STATUS_MAP[detail.approval.status] ?? detail.approval.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申请人">
            {{ detail.approval.applyUserName ?? detail.approval.applyUser }}
          </el-descriptions-item>
          <el-descriptions-item label="审批人">
            {{ detail.approval.approverName ?? detail.approval.approver }}
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">
            {{ detail.approval.createTime ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="最近变更">
            {{ detail.approval.updateTime ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="事由" :span="2">
            <!-- 业务单据回指（契约 §1）：businessType + business_key 回指业务单据（如 leave + 请假单 id） -->
            业务单据：{{ detail.approval.businessTypeName }}（{{ detail.approval.businessKey }}）
          </el-descriptions-item>
        </el-descriptions>

        <!-- 流程图区块（契约 §3.4 两段式）：防御态（definitionId=null）与加载失败分治 -->
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
             result 仅 end 步骤有值（契约 §3.2） -->
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
