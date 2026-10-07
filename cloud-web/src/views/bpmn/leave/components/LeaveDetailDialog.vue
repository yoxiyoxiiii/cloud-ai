<script setup lang="ts">
/**
 * 请假单详情弹窗（计划 F2 / 设计 D10）：LeaveVo 字段平铺 + el-steps 竖向时间线
 * 数据源 GET /bpmn/leave/{id}（契约 §2.3）：leave 主体 + steps 按时间升序
 * （发起 apply / 审批意见 approval / 流程结束 end 三源拼装，MVP 无图——契约 §1）
 */
import { ref, watch } from 'vue'
import { getLeaveDetail } from '../../../../api/bpmn'
import {
  LEAVE_STATUS_MAP,
  LEAVE_TYPE_MAP,
  type LeaveDetailVo,
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

/** 状态→tag 颜色映射（与列表页同款，契约 §2.2 状态字典）：永远按原字段 status 取值 */
const LEAVE_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
}

const loading = ref(false)
const detail = ref<LeaveDetailVo>()

/** 打开时按 id 拉取最新详情：失败 catch 留空（拦截器已统一 toast，如 4001） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    detail.value = undefined
    if (!props.leave) {
      return
    }
    loading.value = true
    getLeaveDetail(props.leave.id)
      .then((data) => {
        detail.value = data
      })
      .catch(() => {
        // 拦截器已统一 toast，详情区保持空态
      })
      .finally(() => {
        loading.value = false
      })
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="请假单详情"
    width="640px"
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
.detail-steps {
  margin-top: 20px;
}

.step-line {
  line-height: 1.8;
  font-size: 13px;
}
</style>
