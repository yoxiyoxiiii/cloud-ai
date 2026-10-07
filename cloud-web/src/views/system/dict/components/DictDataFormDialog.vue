<script setup lang="ts">
/**
 * 字典项 新增/编辑 弹窗（对照 RoleFormDialog 范式，设计 §6）
 * typeId 不渲染控件：由页面以 props 注入（add 提交时带；edit 全量提交五写字段 + id 亦带，
 * 契约 §3.3——规避 PUT 部分更新歧义）
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createDictData, updateDictData } from '../../../../api/dict'
import type { SysDictDataVo } from '../../../../types/api'

export type DictDataFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: DictDataFormMode
  /** 编辑时传入行数据；新增时缺省 */
  dictData?: SysDictDataVo
  /** 归属类型 id：页面当前选中类型（契约 §3.2/§3.3） */
  typeId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface DictDataFormData {
  label: string
  value: string
  sort: number
  status: number
}

/** 字典项状态（契约 §1）：0=正常 1=停用（与类型各自独立） */
const STATUS_NORMAL = 0
const STATUS_DISABLED = 1
/** 排序取值范围（前端约定）：0-999 */
const SORT_MIN = 0
const SORT_MAX = 999

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<DictDataFormData>({
  label: '',
  value: '',
  sort: SORT_MIN,
  status: STATUS_NORMAL,
})

const rules: FormRules<DictDataFormData> = {
  // label 后端仅非空白校验（契约 §3.2），长度 1-50 前端兜底（DDL VARCHAR(50)）；类型内不唯一（契约 §1）
  label: [
    { required: true, message: '请输入标签', trigger: 'blur' },
    { min: 1, max: 50, message: '标签长度为 1-50 位', trigger: 'blur' },
  ],
  // pattern 为前端约定（契约 §6：后端不校验格式）；value 同类型内唯一，重复得 3012
  value: [
    { required: true, message: '请输入字典值', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]{1,50}$/,
      message: '仅含字母/数字/下划线/点/中横线，不超过 50 位',
      trigger: 'blur',
    },
  ],
  sort: [{ required: true, message: '请输入排序号', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const title = computed(() => (props.mode === 'add' ? '新增字典项' : '编辑字典项'))

/** 打开时初始化：编辑回显四字段，新增置默认；先清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.dictData) {
      form.label = props.dictData.label
      form.value = props.dictData.value
      form.sort = props.dictData.sort
      form.status = props.dictData.status
    } else {
      form.label = ''
      form.value = ''
      form.sort = SORT_MIN
      form.status = STATUS_NORMAL
    }
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || loading.value) {
    return
  }
  loading.value = true
  try {
    if (props.mode === 'add') {
      await createDictData({
        typeId: props.typeId,
        label: form.label,
        value: form.value,
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('新增成功')
    } else if (props.dictData) {
      // 契约 §3.3 部分更新语义：前端全量提交五写字段 + id（typeId 亦提交）
      await updateDictData({
        id: props.dictData.id,
        typeId: props.typeId,
        label: form.label,
        value: form.value,
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 3012 该类型下字典项值已存在）；弹窗保持打开可改后重提
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="480px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="标签" prop="label">
        <el-input v-model="form.label" placeholder="请输入展示标签，如：启用" :disabled="loading" />
      </el-form-item>
      <el-form-item label="字典值" prop="value">
        <el-input v-model="form.value" placeholder="存库值，如：0" :disabled="loading" />
      </el-form-item>
      <el-form-item label="排序" prop="sort">
        <el-input-number
          v-model="form.sort"
          :min="SORT_MIN"
          :max="SORT_MAX"
          :precision="0"
          :disabled="loading"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="form.status" :disabled="loading">
          <el-radio :value="STATUS_NORMAL">正常</el-radio>
          <el-radio :value="STATUS_DISABLED">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>
