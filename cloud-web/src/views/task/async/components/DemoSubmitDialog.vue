<script setup lang="ts">
/**
 * 提交演示任务弹窗（契约 §7，端点 /system/task-demo/submit——在 cloud-system，
 * 服务内经 Feign 转 cloud-task 的 inner 提交面；提交人/来源由服务端从登录态填充）。
 * 两参数均可选带默认值（total=100 / failPercent=0）；failPercent>0 用于注入失败，
 * 验证重试/失败/告警链路——种子场景「Java 侧演示性批量任务」的入口。
 */
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { submitDemoTask } from '../../../../api/task'

interface Props {
  modelValue: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 提交成功（列表页刷新首屏看新排队行） */
  success: []
}>()

/** 契约 §7 值域：total 1-10000 默认 100；failPercent 0-100 默认 0 */
const TOTAL_DEFAULT = 100
const TOTAL_MIN = 1
const TOTAL_MAX = 10000
const FAIL_PERCENT_DEFAULT = 0
const FAIL_PERCENT_MAX = 100

interface DemoForm {
  total: number
  failPercent: number
}

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<DemoForm>({
  total: TOTAL_DEFAULT,
  failPercent: FAIL_PERCENT_DEFAULT,
})

/** 打开时回默认值并清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    form.total = TOTAL_DEFAULT
    form.failPercent = FAIL_PERCENT_DEFAULT
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value) {
    return
  }
  loading.value = true
  try {
    const result = await submitDemoTask({ total: form.total, failPercent: form.failPercent })
    ElMessage.success(`任务已提交（ID: ${result.taskId}），已进入排队`)
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（6002 参数越界 / cloud-task 不可用时 Feign 降级 msg 透出）；弹窗保持打开
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="提交演示任务"
    width="480px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" label-width="100px">
      <el-form-item label="处理条数" prop="total">
        <el-input-number
          v-model="form.total"
          :min="TOTAL_MIN"
          :max="TOTAL_MAX"
          :step="100"
          :disabled="loading"
        />
        <span class="form-tip">模拟处理条数（1-10000，默认 100）</span>
      </el-form-item>
      <el-form-item label="失败百分比" prop="failPercent">
        <el-input-number
          v-model="form.failPercent"
          :min="FAIL_PERCENT_DEFAULT"
          :max="FAIL_PERCENT_MAX"
          :disabled="loading"
        />
        <span class="form-tip">大于 0 时任务在约该进度处失败（验证重试/告警链路）</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
