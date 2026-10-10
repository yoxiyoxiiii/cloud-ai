<script setup lang="ts">
/**
 * 部门 新增/编辑 弹窗（沿 MenuFormDialog 范式，设计 §8）
 * - 上级部门 el-tree-select：候选 = 合成根节点（id="0"「根部门」）+ 页面传入部门森林；
 *   新增模式初始值由入口注入（表头按钮=根级 "0" / 行内「新增子级」=该行 id），可改选；
 *   **编辑模式禁用只读**——契约 §2.3 MVP 禁改上级（传入不同值得 1002「暂不支持修改上级部门」，
 *   不传放行），编辑提交恒不含 parentId（DeptUpdatePayload 无此字段，结构性规避）
 * - 名称/排序/状态可编辑；同层重名 3028 由拦截器 toast
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createDept, updateDept } from '../../../../api/dept'
import type { SysDeptTreeNode } from '../../../../types/api'

export type DeptFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: DeptFormMode
  /** 编辑时传入行数据；新增时缺省 */
  dept?: SysDeptTreeNode
  /** 新增时入口注入的父部门 id（表头=根级 "0" / 行内「新增子级」=该行 id） */
  parentId?: string
  /** 父级候选数据源（页面持有 treeData 传入，弹窗不发请求——沿 MenuFormDialog 设计） */
  treeData: SysDeptTreeNode[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface DeptFormData {
  parentId: string
  name: string
  sort: number
  status: number
}

/** 根级 parentId 约定值（契约 §2.1：parent_id=0 为根） */
const ROOT_PARENT_ID = '0'

/** 部门状态（契约 §6.1）：0=正常 1=停用 */
const STATUS_NORMAL = 0
const STATUS_DISABLED = 1

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<DeptFormData>({
  parentId: ROOT_PARENT_ID,
  name: '',
  sort: 0,
  status: STATUS_NORMAL,
})

const rules: FormRules<DeptFormData> = {
  parentId: [{ required: true, message: '请选择上级部门', trigger: 'change' }],
  // name 后端非空白校验（契约 §2.2），长度 1-30 为前端兜底（DDL VARCHAR(30)）
  name: [
    { required: true, message: '请输入部门名称', trigger: 'blur' },
    { min: 1, max: 30, message: '部门名称长度为 1-30 位', trigger: 'blur' },
  ],
  sort: [{ required: true, message: '请输入排序号', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const title = computed(() => (props.mode === 'add' ? '新增部门' : '编辑部门'))

/** 上级候选：合成根节点「根部门」（id="0"）挂整棵部门森林——任一级部门均可作上级（含根级） */
const parentCandidates = computed<SysDeptTreeNode[]>(() => [
  {
    id: ROOT_PARENT_ID,
    parentId: '-',
    name: '根部门',
    sort: 0,
    status: STATUS_NORMAL,
    builtin: false,
    createTime: null,
    children: props.treeData,
  },
])

/**
 * 上级选择后定点清除该字段校验残留（沿 MenuFormDialog：tree-select 程序化赋值/回显
 * 与 clearValidate 存在时序竞争，validate-event 关闭改手动清理）
 */
function handleParentSelected(): void {
  formRef.value?.clearValidate(['parentId'])
}

/** 打开时初始化：编辑回显行数据（上级只读展示）；新增取入口注入的 parentId 作初始值 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.dept) {
      form.parentId = props.dept.parentId
      form.name = props.dept.name
      form.sort = props.dept.sort
      form.status = props.dept.status
    } else {
      form.parentId = props.parentId ?? ROOT_PARENT_ID
      form.name = ''
      form.sort = 0
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
      await createDept({
        parentId: form.parentId,
        name: form.name.trim(),
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('新增成功')
    } else if (props.dept) {
      // 契约 §2.3：上级禁改不提交（null 不更新语义下前端仅提交可编辑三字段 + id）
      await updateDept({
        id: props.dept.id,
        name: form.name.trim(),
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（3027 父部门无效 / 3028 同层重名 / 1002 名称空白）；弹窗保持打开可改后重提
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
      <el-form-item label="上级部门" prop="parentId">
        <!-- 编辑禁用只读：契约 §2.3 MVP 禁改上级（改值得 1002，不传放行）；新增可改选（初始值入口注入） -->
        <el-tree-select
          v-model="form.parentId"
          :data="parentCandidates"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          check-strictly
          default-expand-all
          :validate-event="false"
          placeholder="请选择上级部门"
          :disabled="mode === 'edit' || loading"
          class="parent-select"
          @change="handleParentSelected"
        />
      </el-form-item>
      <el-form-item label="部门名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入部门名称" :disabled="loading" maxlength="30" />
      </el-form-item>
      <el-form-item label="排序" prop="sort">
        <el-input-number v-model="form.sort" :min="0" :max="999" :disabled="loading" />
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

<style scoped>
.parent-select {
  width: 100%;
}
</style>
