<script setup lang="ts">
/**
 * 请假单详情弹窗（设计 D9 双源拼装）：业务 descriptions（system）+ 平台时间线/图（bpmn approval）
 * 双入口：
 * - 列表行 leave（id 驱动）：getLeaveDetail → leave.approvalId 非空 → 平台链
 * - 待办跳转 approvalId（query 落点，契约 §1）：getApprovalDetail（反解 businessKey=请假单 id）→ getLeaveDetail → 图链
 * 平台链（契约 §3.2/§3.4 + diagram 契约 §1 两段式）：
 *   getApprovalDetail（steps 时间线）→ getApprovalDiagram → definitionId 非空则
 *   GET /bpmn/definition/{definitionId}/xml → BpmnViewer 三态高亮
 * definitionId=null 为历史实例缺失防御态 → 隐藏图区（契约 §3.4）
 * 发起失败终态（契约 2026-10-09-rocketmq-tx-approval-api §2.2）：status=4（approvalId 恒 null）
 * 仅业务主体 + danger 标签 +「发起失败，可重新发起」说明，不渲染审批跳转/图/时间线；
 * 「查看审批」入口以 approvalId 非空为渲染条件（发起后秒级瞬态 null 不渲染，不设 loading 态）
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { getApprovalDetail, getApprovalDiagram, getDefinitionXml } from '../../../../api/bpmn'
import { getLeaveDetail } from '../../../../api/systemLeave'
import {
  APPROVAL_STATUS_MAP,
  LEAVE_TYPE_MAP,
  type ApprovalDetailVo,
  type ApprovalDiagramVo,
  type ApprovalStepVo,
  type SysLeaveDetailVo,
  type SysLeaveVo,
} from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 列表行入口（提供 id；展示以弹窗内新拉的 detail 为准——行数据可能已过期） */
  leave?: SysLeaveVo
  /** 待办跳转入口（query.approval = approvalId；弹窗内经平台详情反解 businessKey） */
  approvalId?: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 「查看审批」：approvalId 非空时由 footer 入口发出，父页关弹窗并跳平台审批页（?approval= 协议） */
  viewApproval: [approvalId: string]
  /** 关闭动画结束（父页清落点 query 与入口数据） */
  closed: []
}>()

/** 公共 Viewer 按需分包（设计 D9 分包铁律）：defineAsyncComponent 引入，bpmn-js 及其 CSS 一并入 async chunk */
const BpmnViewer = defineAsyncComponent(() => import('../../../../components/bpmn/BpmnViewer.vue'))

/** 发起失败终态值（契约 2026-10-09 §2.2）：仅 system 产生，approvalId 恒 null */
const STATUS_FAILED = '4'

/** 状态→tag 颜色映射（与列表页同款，契约 §5.2 status 字典 bpmn_approval_status；值域扩 4）：永远按原字段 status 取值 */
const APPROVAL_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
  '4': 'danger', // 发起失败（终态，仅 system 产生）
}

const loading = ref(false)
/** 业务主体（system 域，契约 §5.3：仅 leave，不含时间线/图） */
const detail = ref<SysLeaveDetailVo>()
/** 平台审批单主体与时间线（bpmn 域，契约 §3.2） */
const platformDetail = ref<ApprovalDetailVo>()
const steps = ref<ApprovalStepVo[]>([])
/** 平台链整体失败（详情弹窗对 3022 诚实报错语义的展示态：业务主体仍展示，进度区失败占位） */
const platformFailed = ref(false)

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
async function loadDiagram(approvalId: string, seq: number): Promise<void> {
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
 * 双源主加载链（seq 防串）：
 * 1. 入口解析 →（平台详情可预取）→ 业务详情 getLeaveDetail
 * 2. leave.approvalId 非空 → getApprovalDetail（时间线；跳转入口已取则复用）→ 图链
 * 各段失败分治：业务失败详情区空态；平台链失败进度区失败占位、图区不发起
 */
async function loadDetail(seq: number): Promise<void> {
  let leaveId: string | undefined
  let approvalDetail: ApprovalDetailVo | undefined
  if (props.leave) {
    leaveId = props.leave.id
  } else if (props.approvalId) {
    try {
      approvalDetail = await getApprovalDetail(props.approvalId)
    } catch {
      if (seq === openSeq) {
        loading.value = false
        platformFailed.value = true
      }
      return
    }
    if (seq !== openSeq) {
      return
    }
    platformDetail.value = approvalDetail
    steps.value = approvalDetail.steps
    leaveId = approvalDetail.approval.businessKey
  }
  if (!leaveId) {
    return
  }
  try {
    const data = await getLeaveDetail(leaveId)
    if (seq !== openSeq) {
      return
    }
    detail.value = data
  } catch {
    // 拦截器已统一 toast（如 3018 不存在 / 3022 平台不可用诚实报错），详情区保持空态
    return
  }
  const approvalId = detail.value?.leave.approvalId
  if (!approvalId) {
    // 发起失败无审批单（契约 §5.2 approvalId 可 null）：仅业务主体，无进度/图
    return
  }
  if (!approvalDetail) {
    try {
      approvalDetail = await getApprovalDetail(approvalId)
    } catch {
      if (seq === openSeq) {
        platformFailed.value = true
      }
      return
    }
    if (seq !== openSeq) {
      return
    }
    platformDetail.value = approvalDetail
    steps.value = approvalDetail.steps
  }
  void loadDiagram(approvalId, seq)
}

/**
 * 打开时按入口拉取（失败 catch 留空——拦截器已统一 toast）。
 * immediate 必须有：query.approval 冷进入时父页在 setup 期（子弹窗挂载前）就把
 * modelValue 置 true——子组件首挂载即处于打开态，无 false→true 变更可侦听，
 * 缺 immediate 将零请求空白（F9 走查 A3）
 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    const seq = ++openSeq
    detail.value = undefined
    platformDetail.value = undefined
    steps.value = []
    platformFailed.value = false
    diagram.value = undefined
    diagramXml.value = ''
    diagramFailed.value = false
    if (!props.leave && !props.approvalId) {
      return
    }
    loading.value = true
    void loadDetail(seq).finally(() => {
      if (seq === openSeq) {
        loading.value = false
      }
    })
  },
  { immediate: true },
)

/** 「查看审批」：跳平台审批页冷开对应详情（事件交父页导航）；approvalId 非空才可达（v-if 闸+防御再判） */
function handleViewApproval(): void {
  const approvalId = detail.value?.leave.approvalId
  if (approvalId) {
    emit('viewApproval', approvalId)
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="请假单详情"
    width="860px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="emit('closed')"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.leave.title }}</el-descriptions-item>
          <el-descriptions-item label="请假类型">
            <!-- 降级链（契约 §9）：译文 → 本地映射 → 原值 -->
            {{ detail.leave.leaveTypeLabel ?? LEAVE_TYPE_MAP[detail.leave.leaveType] ?? detail.leave.leaveType }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="APPROVAL_STATUS_TAG[detail.leave.status] ?? 'info'">
              {{ detail.leave.statusLabel ?? APPROVAL_STATUS_MAP[detail.leave.status] ?? detail.leave.status }}
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
          <el-descriptions-item label="审批单号">
            <!-- 双源关联锚点（契约 §5.2 approvalId：撤销后仍在；发起失败无） -->
            {{ detail.leave.approvalId ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="事由" :span="2">{{ detail.leave.reason ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 发起失败终态说明（契约 2026-10-09 §2.2）：无审批单（approvalId 恒 null）——
             平台链不发起（下方图/时间线区块均不渲染），重新发起入口在请假列表行 -->
        <el-alert
          v-if="detail.leave.status === STATUS_FAILED"
          class="leave-failed-tip"
          type="warning"
          :closable="false"
          show-icon
          title="发起失败，可重新发起"
          description="该单据未生成审批单（无审批进度与流程图）。可在请假列表对本单「重新发起」，以本单内容创建新的申请。"
        />

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

        <!-- 时间线（平台 §3.2 steps 按时间升序；出现的步骤均已发生，active=长度即全完成态）；
             result 仅 end 步骤有值 -->
        <el-steps
          v-if="steps.length"
          class="detail-steps"
          direction="vertical"
          :active="steps.length"
          :space="90"
        >
          <el-step v-for="step in steps" :key="step.stepKey" :title="step.title">
            <template #description>
              <div class="step-line">{{ step.operatorName ?? step.operator ?? '-' }}</div>
              <div v-if="step.comment" class="step-line">意见：{{ step.comment }}</div>
              <div v-if="step.time" class="step-line">{{ step.time }}</div>
              <div v-if="step.result" class="step-line">结果：{{ step.result }}</div>
            </template>
          </el-step>
        </el-steps>
        <!-- 平台链失败占位（getApprovalDetail 失败，如 3022）：业务主体仍展示，进度区诚实报错 -->
        <div v-else-if="platformFailed" class="steps-failed">审批进度加载失败，请稍后重试</div>
      </template>
      <el-empty v-else-if="!loading" description="暂无数据" />
    </div>
    <template #footer>
      <!-- 查看审批：跳平台审批页冷开对应详情；approvalId 非空为渲染条件
           （status=4 恒 null 与发起后秒级瞬态 null 均不渲染——契约 2026-10-09 §2.2） -->
      <el-button v-if="detail?.leave.approvalId" type="primary" @click="handleViewApproval">查看审批</el-button>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 图区与时间线拉开间距（BpmnViewer 自带 360px 高与边框） */
.diagram-block {
  margin-top: 20px;
}

/* 发起失败终态说明（契约 2026-10-09 §2.2）：与业务主体/图区拉开间距 */
.leave-failed-tip {
  margin-top: 16px;
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

/* 平台链失败占位（契约 §5.3 详情纠偏 3022 诚实报错的展示态） */
.steps-failed {
  margin-top: 20px;
  padding: 16px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
