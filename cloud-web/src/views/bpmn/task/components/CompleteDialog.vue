<script setup lang="ts">
/**
 * 办理任务弹窗（设计 D9 通用化改造）：radio 同意/拒绝 + 意见 textarea
 * 提交 POST /bpmn/task/complete（契约 §2.3）：approve 为 "true"/"false" 字符串；
 * comment 同意/拒绝均可空（宽松语义记档——契约原文），≤200 长度兜底对齐 @Size≤200
 * 单据上下文展示通用字段（title + businessTypeName，契约 §2.1）
 */
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { completeTask } from '../../../../api/bpmn'
import type { TaskVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 待办行数据（taskId 提交锚点 + title/businessTypeName 上下文展示） */
  task?: TaskVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface CompleteFormData {
  /** 契约 §3.3 形态：字符串 "true"/"false"（radio 绑定字符串字面量） */
  approve: string
  comment: string
}

const APPROVE_TRUE = 'true'
const APPROVE_FALSE = 'false'

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<CompleteFormData>({
  approve: APPROVE_TRUE,
  comment: '',
})

const rules: FormRules<CompleteFormData> = {
  approve: [{ required: true, message: '请选择办理结果', trigger: 'change' }],
  // comment 可选（契约 §3.3 宽松语义），仅长度兜底
  comment: [{ max: 200, message: '意见不超过 200 字', trigger: 'blur' }],
}

/** 打开时重置：默认同意 + 清空意见（每单独立办理，不沿用上一单输入） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    form.approve = APPROVE_TRUE
    form.comment = ''
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value || !props.task) {
    return
  }
  loading.value = true
  try {
    await completeTask({
      taskId: props.task.taskId,
      approve: form.approve,
      comment: form.comment,
    })
    ElMessage.success('办理成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 4016 任务已被办理）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="办理任务"
    width="480px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="申请单">
        <span>{{ task?.title ?? '-' }}</span>
        <!-- 配置表 join 必返非空（契约 §9），无降级链 -->
        <el-tag v-if="task" class="task-type-tag" size="small" type="info">{{ task.businessTypeName }}</el-tag>
      </el-form-item>
      <el-form-item label="申请人">
        <span>{{ task ? task.applyUserName ?? task.applyUser : '-' }}</span>
      </el-form-item>
      <el-form-item label="办理结果" prop="approve">
        <el-radio-group v-model="form.approve" :disabled="loading">
          <el-radio :value="APPROVE_TRUE">同意</el-radio>
          <el-radio :value="APPROVE_FALSE">拒绝</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="审批意见" prop="comment">
        <el-input
          v-model="form.comment"
          type="textarea"
          :rows="3"
          placeholder="选填，不超过 200 字"
          maxlength="200"
          show-word-limit
          :disabled="loading"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 单据标题与业务类型 tag 并置间距 */
.task-type-tag {
  margin-left: 8px;
}
</style>
