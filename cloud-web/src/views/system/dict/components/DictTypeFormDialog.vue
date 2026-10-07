<script setup lang="ts">
/**
 * 字典类型 新增/编辑 弹窗（对照 RoleFormDialog 范式，设计 §6）
 * 与用户页的关键差异：dictKey 编辑时可改可保存（契约 §2.3 改键触发唯一性校验排除自身，
 * roleKey 同款）——勿照抄用户页 account 锁定
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createDictType, updateDictType } from '../../../../api/dict'
import type { SysDictTypeVo } from '../../../../types/api'

export type DictTypeFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: DictTypeFormMode
  /** 编辑时传入行数据；新增时缺省 */
  dictType?: SysDictTypeVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface DictTypeFormData {
  dictName: string
  dictKey: string
  status: number
}

/** 字典类型状态（契约 §1）：0=正常 1=停用 */
const STATUS_NORMAL = 0
const STATUS_DISABLED = 1

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<DictTypeFormData>({
  dictName: '',
  dictKey: '',
  status: STATUS_NORMAL,
})

const rules: FormRules<DictTypeFormData> = {
  // dictName 后端仅非空白校验（契约 §2.2），长度 1-30 为前端兜底（DDL VARCHAR(30)）
  dictName: [
    { required: true, message: '请输入字典名称', trigger: 'blur' },
    { min: 1, max: 30, message: '字典名称长度为 1-30 位', trigger: 'blur' },
  ],
  // pattern 为前端约定兜底（契约 §6 宽松语义：后端不校验格式）——dictKey 是消费方取数锚点
  dictKey: [
    { required: true, message: '请输入字典键', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]{0,49}$/,
      message: '字母开头，仅含字母/数字/下划线，不超过 50 位',
      trigger: 'blur',
    },
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const title = computed(() => (props.mode === 'add' ? '新增字典类型' : '编辑字典类型'))

/** 打开时初始化：编辑回显三字段（dictKey 可改），新增置默认；先清校验残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.dictType) {
      form.dictName = props.dictType.dictName
      form.dictKey = props.dictType.dictKey
      form.status = props.dictType.status
    } else {
      form.dictName = ''
      form.dictKey = ''
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
      await createDictType({ dictName: form.dictName, dictKey: form.dictKey, status: form.status })
      ElMessage.success('新增成功')
    } else if (props.dictType) {
      // 契约 §2.3 部分更新语义：null 不更新，前端始终全量提交三写字段 + id
      await updateDictType({
        id: props.dictType.id,
        dictName: form.dictName,
        dictKey: form.dictKey,
        status: form.status,
      })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 3009 字典键已存在）；弹窗保持打开可改后重提
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
      <el-form-item label="字典名称" prop="dictName">
        <el-input v-model="form.dictName" placeholder="请输入字典名称，如：用户状态" :disabled="loading" />
      </el-form-item>
      <el-form-item label="字典键" prop="dictKey">
        <!-- 编辑不锁定：契约 §2.3 dictKey 可修改（与用户页 account 锁定的契约差异，roleKey 同款） -->
        <el-input v-model="form.dictKey" placeholder="字母开头，如：user_status" :disabled="loading" />
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
