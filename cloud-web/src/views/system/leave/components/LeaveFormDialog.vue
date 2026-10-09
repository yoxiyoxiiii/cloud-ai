<script setup lang="ts">
/**
 * 发起请假弹窗（设计 D9，自 /bpmn/leave 迁移）：
 * - 请假类型下拉：GET /system/dict/data/type/system_leave_type（契约 §7 字典迁名；translation-api §2.1 既有消费端点）
 * - 审批人下拉：GET /system/leave/approvers（契约 §5.5 投影，**仅启用账号**——迁移收紧语义）
 * - 提交 POST /system/leave（契约 §5.1；语义修订 2026-10-09-rocketmq-tx-approval-api §2.1：
 *   MQ 事务消息，同步仍返 R<Long> 新单 id；消费端确定性失败异步转 status=4 不阻塞响应）
 * - 起止日期 el-date-picker daterange，value-format YYYY-MM-DD 对齐契约 §5.1 入参形态
 * - initial 预填入参（契约 2026-10-09-rocketmq-tx-approval-api §2.2 / 计划 F2）：status=4 单「重新发起」
 *   复制本单六字段预填（同 2026-10-08 移交备忘 6「驳回重报」预留形态）；提交=创建**新单据**，原单保留 4 不变
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { addLeave, getLeaveApprovers } from '../../../../api/systemLeave'
import { getDictItems } from '../../../../api/dict'
import type { DictItemVo, LeaveCreatePayload, UserOptionVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 重新发起预填初始值（undefined=普通发起）：字段同 LeaveCreatePayload，打开时一次性快照应用 */
  initial?: LeaveCreatePayload
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface LeaveFormData {
  title: string
  leaveType: string
  /** daterange 双值：[startDate, endDate]，提交时拆开（契约 §5.1 两独立字段） */
  dateRange: string[]
  reason: string
  approver: string
}

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<LeaveFormData>({
  title: '',
  leaveType: '',
  dateRange: [],
  reason: '',
  approver: '',
})

/** 下拉数据源：字典项（value 提交原值）与审批人投影（account 提交，account+nickname 展示） */
const leaveTypeOptions = ref<DictItemVo[]>([])
const approverOptions = ref<UserOptionVo[]>([])
const optionsLoading = ref(false)

/** daterange 空数组为 truthy，必填校验须显式判长度 */
const rules: FormRules<LeaveFormData> = {
  // title 长度兜底对齐契约 §5.1 @Size≤100（后端 1001 为最终防线）
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 100, message: '标题不超过 100 字', trigger: 'blur' },
  ],
  leaveType: [{ required: true, message: '请选择请假类型', trigger: 'change' }],
  dateRange: [
    {
      required: true,
      validator: (_rule, value: string[], callback) => {
        if (!value || value.length !== 2) {
          callback(new Error('请选择请假起止日期'))
        } else {
          callback()
        }
      },
      trigger: 'change',
    },
  ],
  // reason 可选（契约 §5.1），仅长度兜底对齐 @Size≤500
  reason: [{ max: 500, message: '事由不超过 500 字', trigger: 'blur' }],
  approver: [{ required: true, message: '请选择审批人', trigger: 'change' }],
}

/** 弹窗标题：预填入口=重新发起（提示用户提交将创建新单据），普通入口=发起请假 */
const dialogTitle = computed(() => (props.initial ? '重新发起请假' : '发起请假'))

/** 打开时重置表单（重新发起入口再应用预填快照）并拉取两下拉数据源（量级小，每次打开取最新） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    form.title = props.initial?.title ?? ''
    form.leaveType = props.initial?.leaveType ?? ''
    form.dateRange =
      props.initial?.startDate && props.initial?.endDate
        ? [props.initial.startDate, props.initial.endDate]
        : []
    form.reason = props.initial?.reason ?? ''
    form.approver = props.initial?.approver ?? ''
    void loadOptions()
  },
)

/** 拉取类型字典 + 审批人投影：失败 catch 留空（拦截器已统一 toast，下拉呈现空态） */
async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  try {
    const [types, approvers] = await Promise.all([
      getDictItems('system_leave_type'),
      getLeaveApprovers(),
    ])
    leaveTypeOptions.value = types
    approverOptions.value = approvers
  } catch {
    // 拦截器已统一 toast，下拉保持空态
  } finally {
    optionsLoading.value = false
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value) {
    return
  }
  loading.value = true
  try {
    await addLeave({
      title: form.title,
      leaveType: form.leaveType,
      startDate: form.dateRange[0],
      endDate: form.dateRange[1],
      reason: form.reason,
      approver: form.approver,
    })
    ElMessage.success('发起成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 3019 日期无效 / 3023 审批人无效 / 3025 消息服务不可用——
    // 契约 2026-10-09 §2.1/§3：3022/3024 已自本端点退役）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="520px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="标题" prop="title">
        <el-input
          v-model="form.title"
          placeholder="请输入标题"
          maxlength="100"
          show-word-limit
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="请假类型" prop="leaveType">
        <el-select
          v-model="form.leaveType"
          placeholder="请选择请假类型"
          :loading="optionsLoading"
          :disabled="loading"
          style="width: 100%"
        >
          <el-option
            v-for="item in leaveTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="起止日期" prop="dateRange">
        <el-date-picker
          v-model="form.dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :disabled="loading"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="事由" prop="reason">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
          placeholder="选填，不超过 500 字"
          maxlength="500"
          show-word-limit
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="审批人" prop="approver">
        <el-select
          v-model="form.approver"
          placeholder="请选择审批人"
          :loading="optionsLoading"
          :disabled="loading"
          style="width: 100%"
        >
          <el-option
            v-for="user in approverOptions"
            :key="user.id"
            :label="`${user.account}（${user.nickname}）`"
            :value="user.account"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>
