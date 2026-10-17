<script setup lang="ts">
/**
 * 重新向量化参数弹窗（契约 §8 POST /rag/kb/reembedding）：沿 KbFormDialog 弹窗模式。
 * 打开时预填行数据快照值（契约 §7 两 additive 列 file_chunk_size/file_chunk_overlap），
 * 提交恒带两参——发快照原值与「不传=沿用快照」语义等价，弹窗无需「留空沿用」通道；
 * 关系校验（重叠 < 分片）前端对表单值整体判定（预填后两字段必填恒有值，即生效值，
 * 契约 §8「只传其一」组合在弹窗形态下不存在），后端 service 对生效值兜底（违例 1001）；
 * 业务错误（5005/5006/5007/5008）拦截器统一 toast，catch 留空且不关弹窗——
 * 5008/500 原样重试同一请求即收敛（契约 §8），弹窗保留可改参重试
 */
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { reembedKbFile } from '../../../../api/rag'
import type { KbFileVo } from '../../../../types/api'

defineOptions({ name: 'ReembedDialog' })

interface Props {
  modelValue: boolean
  /** 目标文件行（file_id 与两参快照预填的数据源）；null 时不允许提交 */
  file: KbFileVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface ReembedFormData {
  chunk_size: number | null
  chunk_overlap: number | null
}

/** 契约 §8 值域：chunk_size 整数 1..499（父块固定 500、子块须更小）；overlap 整数 ≥0 且 < 生效 size */
const SIZE_MIN = 1
const SIZE_MAX = 499
const OVERLAP_MIN = 0

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<ReembedFormData>({ chunk_size: null, chunk_overlap: null })

const rules: FormRules<ReembedFormData> = {
  chunk_size: [{ required: true, message: '请输入子块分片大小', trigger: 'blur' }],
  chunk_overlap: [
    { required: true, message: '请输入子块重叠大小', trigger: 'blur' },
    {
      // 关系校验为后端 service 生效值判定的前端同值镜像，先行拦截换内联提示
      validator: (_rule, value: number | null, callback) => {
        if (value != null && form.chunk_size != null && value >= form.chunk_size) {
          callback(new Error('重叠大小须小于分片大小'))
        } else {
          callback()
        }
      },
      trigger: ['blur', 'change'],
    },
  ],
}

/** 打开时预填行快照值并清校验残留（弹窗关闭再开不留上次痕迹） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    form.chunk_size = props.file?.file_chunk_size ?? null
    form.chunk_overlap = props.file?.file_chunk_overlap ?? null
    formRef.value?.clearValidate()
  },
)

/** 分片大小变更后联动重校验重叠字段：重叠的关系校验以 size 为参照，参照变了其错误提示须刷新 */
function handleSizeChange(): void {
  if (form.chunk_overlap != null) {
    void formRef.value?.validateField('chunk_overlap').catch(() => false)
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  const size = form.chunk_size
  const overlap = form.chunk_overlap
  if (!valid || loading.value || !props.file || size == null || overlap == null) {
    return
  }
  loading.value = true
  try {
    await reembedKbFile({ file_id: props.file.id, chunk_size: size, chunk_overlap: overlap })
    ElMessage.success('重新向量化完成')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 5006 文件无原件）；弹窗保持打开，改参或原样重试
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <!-- append-to-body：本弹窗从抽屉内打开（嵌套弹层），挂 body 避免受抽屉定位/裁剪影响 -->
  <el-dialog
    :model-value="modelValue"
    title="重新向量化"
    width="520px"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="文件名">
        <el-input :model-value="file?.file_name ?? ''" disabled />
      </el-form-item>
      <el-form-item label="分片大小" prop="chunk_size">
        <el-input-number
          v-model="form.chunk_size"
          :min="SIZE_MIN"
          :max="SIZE_MAX"
          :precision="0"
          :disabled="loading"
          @change="handleSizeChange"
        />
      </el-form-item>
      <el-form-item label="重叠大小" prop="chunk_overlap">
        <el-input-number
          v-model="form.chunk_overlap"
          :min="OVERLAP_MIN"
          :precision="0"
          :disabled="loading"
        />
      </el-form-item>
      <p class="reembed-tip">参数按子块字符数计；父块分片大小固定 500，子块须更小（1-499）。提交后以新参数重做分片与向量化，并回写为该文件的快照值。</p>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">重新向量化</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reembed-tip {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
