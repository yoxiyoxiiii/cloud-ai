<script setup lang="ts">
/**
 * 角色 新增/编辑 弹窗（对照 UserFormDialog，设计 §4.1）
 * 与用户页的关键差异：roleKey 编辑时可改可保存（契约 §4.4 后端支持，
 * 修改触发唯一性校验排除自身）——勿照抄用户页 account 锁定
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createRole, updateRole } from '../../../../api/role'
import type { SysRoleVo } from '../../../../types/api'

export type RoleFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: RoleFormMode
  /** 编辑时传入行数据；新增时缺省 */
  role?: SysRoleVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface RoleFormData {
  name: string
  roleKey: string
  status: number
}

/** 角色状态（契约 §4）：0=正常 1=停用 */
const STATUS_NORMAL = 0
const STATUS_DISABLED = 1

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<RoleFormData>({
  name: '',
  roleKey: '',
  status: STATUS_NORMAL,
})

const rules: FormRules<RoleFormData> = {
  // name 后端无校验（契约 §7.6），前端必填+长度兜底（DDL VARCHAR(30)）
  name: [
    { required: true, message: '请输入角色名称', trigger: 'blur' },
    { min: 1, max: 30, message: '角色名称长度为 1-30 位', trigger: 'blur' },
  ],
  // pattern 为前端约定兜底：roleKey 是系统引用锚点，脏值后患大（后端仅校验非空白，契约 §4.3）
  roleKey: [
    { required: true, message: '请输入角色标识', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_-]{0,29}$/,
      message: '字母开头，仅含字母/数字/下划线/中横线，不超过 30 位',
      trigger: 'blur',
    },
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const title = computed(() => (props.mode === 'add' ? '新增角色' : '编辑角色'))

/** 打开时初始化：编辑回显行数据（三字段全部可编辑），新增给默认值 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.role) {
      form.name = props.role.name
      form.roleKey = props.role.roleKey
      form.status = props.role.status
    } else {
      form.name = ''
      form.roleKey = ''
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
      await createRole({ name: form.name, roleKey: form.roleKey, status: form.status })
      ElMessage.success('新增成功')
    } else if (props.role) {
      // 契约 §4.4 部分更新语义：null 不更新，前端始终全量提交三写字段
      await updateRole({
        id: props.role.id,
        name: form.name,
        roleKey: form.roleKey,
        status: form.status,
      })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 3003 角色标识已存在）；弹窗保持打开可改后重提
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
      <el-form-item label="角色名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入角色名称" :disabled="loading" />
      </el-form-item>
      <el-form-item label="角色标识" prop="roleKey">
        <!-- 编辑不锁定：契约 §4.4 roleKey 可修改（与用户页 account 锁定的契约差异） -->
        <el-input v-model="form.roleKey" placeholder="字母开头，如 ops" :disabled="loading" />
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
