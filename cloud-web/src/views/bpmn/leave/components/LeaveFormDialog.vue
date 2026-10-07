<script setup lang="ts">
/**
 * 发起请假弹窗（计划 F2 / 设计 D10）：
 * - 请假类型下拉：GET /system/dict/data/type/bpmn_leave_type（translation-api §2.1 既有消费端点）
 * - 审批人下拉：GET /bpmn/leave/approvers（契约 §2.5 投影，含停用账号——宽松语义）
 * - 起止日期 el-date-picker daterange，value-format YYYY-MM-DD 对齐契约 §2.1 入参形态
 */
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createLeave, listApprovers } from '../../../../api/bpmn'
import { getDictItems } from '../../../../api/dict'
import type { DictItemVo, UserOptionVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface LeaveFormData {
  title: string
  leaveType: string
  /** daterange 双值：[startDate, endDate]，提交时拆开（契约 §2.1 两独立字段） */
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
  // title 长度兜底对齐契约 §2.1 @Size≤100（后端 1001 为最终防线）
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
  // reason 可选（契约 §2.1），仅长度兜底对齐 @Size≤500
  reason: [{ max: 500, message: '事由不超过 500 字', trigger: 'blur' }],
  approver: [{ required: true, message: '请选择审批人', trigger: 'change' }],
}

/** 打开时重置表单并拉取两下拉数据源（量级小，每次打开取最新） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    form.title = ''
    form.leaveType = ''
    form.dateRange = []
    form.reason = ''
    form.approver = ''
    void loadOptions()
  },
)

/** 拉取类型字典 + 审批人投影：失败 catch 留空（拦截器已统一 toast，下拉呈现空态） */
async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  try {
    const [types, approvers] = await Promise.all([
      getDictItems('bpmn_leave_type'),
      listApprovers(),
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
    await createLeave({
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
    // 拦截器已统一 toast（如 4004 审批人无效 / 4006 日期无效）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="发起请假"
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
