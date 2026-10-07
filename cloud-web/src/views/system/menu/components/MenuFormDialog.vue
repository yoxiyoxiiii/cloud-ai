<script setup lang="ts">
/**
 * 菜单 新增/编辑 弹窗（设计 D3 联动核心）
 * type 单选驱动上级选择器形态与 perms 规则（层级规则为前端约定，契约 §4 后端不校验父级类型）：
 * - M 目录：不显示选择器，固定只读"根目录"（提交 parentId='0'）；perms 隐藏提交 ''
 *   （目录不嵌套——AssignMenuDialog 是 M/C/F 三层布局，嵌套 M 会使其渲染失真）
 * - C 菜单：el-tree-select 候选 = 全部 M 节点（仅 M 分支的 M 子孙），必选；perms 选填
 * - F 按钮：el-tree-select 候选 = 全部 C 节点（剥子级），必选；perms 必填
 * 编辑时 type 锁定（改 type 造成层级语义漂移）；环防御结构性满足：
 * C 候选是 M、F 候选是 C，自身不在候选集内，后端 3007 环校验继续兜底直连 API 路径
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createMenu, updateMenu } from '../../../../api/menu'
import type { MenuTreeNode } from '../../../../types/api'

export type MenuFormMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: MenuFormMode
  /** 编辑时传入行数据；新增时缺省 */
  menu?: MenuTreeNode
  /** 父级候选数据源（页面持有 treeData 传入，弹窗不发请求——设计 §4） */
  treeData: MenuTreeNode[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface MenuFormData {
  type: 'M' | 'C' | 'F'
  parentId: string
  name: string
  /** 路由路径（契约 §5.2）：仅 C 采编（必填 / 开头）；M/F 提交空串 */
  path: string
  /** 图标名（契约 §5.2）：M/C 选填白名单名（ICON_MAP 兜底）；F 提交空串 */
  icon: string
  perms: string
  sort: number
  status: number
}

/** 菜单类型（契约 §1）：M 目录 / C 菜单 / F 按钮 */
const TYPE_DIR = 'M'
const TYPE_MENU = 'C'
const TYPE_FUNC = 'F'
/** 根节点 parentId 约定值（契约 §2.2："0" 或 null = 根级） */
const ROOT_PARENT_ID = '0'

/** 菜单状态（契约 §1）：0=正常 1=停用 */
const STATUS_NORMAL = 0
const STATUS_DISABLED = 1

/** perms 格式（前端约定兜底，契约 §4 后端无格式校验；DDL VARCHAR(50)） */
const PERMS_PATTERN = /^[a-zA-Z][a-zA-Z0-9:_-]{0,49}$/

/**
 * 路由路径格式（前端约定，契约 §5.2 后端不校验非空与格式）：
 * / 开头 + 字母起始，仅字母/数字/中横线/下划线/斜杠（DDL VARCHAR(100) 对齐 maxlength）
 */
const PATH_PATTERN = /^\/[a-zA-Z][\w/-]*$/

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<MenuFormData>({
  type: TYPE_DIR,
  parentId: ROOT_PARENT_ID,
  name: '',
  path: '',
  icon: '',
  perms: '',
  sort: 0,
  status: STATUS_NORMAL,
})

/**
 * 上级校验随 type 联动：C/F 必选（"请选择上级"）；M 固定根目录无需选择
 * （pattern 类规则对空串同样触发，自定义 validator 统一控制必填与格式的分流）
 */
function validateParent(_rule: unknown, value: string, callback: (err?: Error) => void): void {
  if (form.type !== TYPE_DIR && !value) {
    callback(new Error('请选择上级'))
    return
  }
  callback()
}

/** perms 校验随 type 联动：F 必填；C 选填（空可提交）；非空校验格式 */
function validatePerms(_rule: unknown, value: string, callback: (err?: Error) => void): void {
  if (!value) {
    if (form.type === TYPE_FUNC) {
      callback(new Error('请输入权限标识'))
      return
    }
    callback()
    return
  }
  if (!PERMS_PATTERN.test(value)) {
    callback(new Error('字母开头，仅含字母/数字/冒号/下划线/中横线，不超过 50 位'))
    return
  }
  callback()
}

/** path 校验随 type 联动（契约 §5.2 前端约定）：仅 C 必填；非空校验格式 */
function validatePath(_rule: unknown, value: string, callback: (err?: Error) => void): void {
  if (form.type !== TYPE_MENU) {
    callback()
    return
  }
  if (!value) {
    callback(new Error('请输入路由路径'))
    return
  }
  if (!PATH_PATTERN.test(value)) {
    callback(new Error('以 / 开头，仅字母/数字/中横线/下划线/斜杠'))
    return
  }
  callback()
}

const rules: FormRules<MenuFormData> = {
  name: [
    // name 后端仅非空白校验（契约 §2.2），长度为前端约定兜底（DDL VARCHAR(30)）
    { required: true, message: '请输入菜单名称', trigger: 'blur' },
    { min: 1, max: 30, message: '菜单名称长度为 1-30 位', trigger: 'blur' },
  ],
  parentId: [{ validator: validateParent, trigger: 'change' }],
  path: [{ validator: validatePath, trigger: 'blur' }],
  perms: [{ validator: validatePerms, trigger: 'blur' }],
  sort: [{ required: true, message: '请输入排序号', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

const title = computed(() => (props.mode === 'add' ? '新增菜单' : '编辑菜单'))

/** C 的上级候选：仅 M 分支递归保留 M 子孙（目录不嵌套约定下实际为根级 M 列表） */
function mCandidates(nodes: MenuTreeNode[]): MenuTreeNode[] {
  const out: MenuTreeNode[] = []
  for (const node of nodes) {
    if (node.type !== TYPE_DIR) {
      continue
    }
    out.push({ ...node, children: mCandidates(node.children) })
  }
  return out
}

/** F 的上级候选：全部 C 节点剥子级（递归收集，兼容直连 API 产生的畸形层级） */
function cCandidates(nodes: MenuTreeNode[]): MenuTreeNode[] {
  const out: MenuTreeNode[] = []
  for (const node of nodes) {
    if (node.type === TYPE_MENU) {
      out.push({ ...node, children: [] })
    }
    if (node.children.length > 0) {
      out.push(...cCandidates(node.children))
    }
  }
  return out
}

/** 上级候选随 type 切换（候选构建为组件内纯函数，不发请求） */
const parentCandidates = computed<MenuTreeNode[]>(() => {
  if (form.type === TYPE_MENU) {
    return mCandidates(props.treeData)
  }
  if (form.type === TYPE_FUNC) {
    return cCandidates(props.treeData)
  }
  return []
})

/** type 切换：上级语义随类型变化（M=根 / C 挂 M / F 挂 C），重置选择与路由字段并清除残留校验 */
function handleTypeChange(): void {
  form.parentId = form.type === TYPE_DIR ? ROOT_PARENT_ID : ''
  form.path = ''
  form.icon = ''
  formRef.value?.clearValidate()
}

/**
 * 用户在上级下拉选中后定点清除该字段校验错误——
 * tree-select 已关闭 validateEvent（程序化赋值/挂载会异步触发 change 校验，
 * 与切换类型时的 clearValidate 存在时序竞争），改由此处手动清理
 */
function handleParentSelected(): void {
  formRef.value?.clearValidate(['parentId'])
}

/** 打开时初始化：编辑回显八字段（type 锁定，parentId 取行值；path/icon 回显 ?? '' 防旧后端缺字段）；
 *  新增给默认（M/根目录/空名/0/0） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    if (props.mode === 'edit' && props.menu) {
      form.type = props.menu.type
      form.parentId = props.menu.parentId
      form.name = props.menu.name
      form.path = props.menu.path ?? ''
      form.icon = props.menu.icon ?? ''
      form.perms = props.menu.perms
      form.sort = props.menu.sort
      form.status = props.menu.status
    } else {
      form.type = TYPE_DIR
      form.parentId = ROOT_PARENT_ID
      form.name = ''
      form.path = ''
      form.icon = ''
      form.perms = ''
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
    // perms/path/icon 语义随类型（契约 §5.2 前端约定）：目录 perms 恒空串；
    // path 仅 C 采编（M/F 空串）；icon 对 M/C 采编（F 空串）；上级 M 固定根
    const parentId = form.type === TYPE_DIR ? ROOT_PARENT_ID : form.parentId
    const perms = form.type === TYPE_DIR ? '' : form.perms.trim()
    const path = form.type === TYPE_MENU ? form.path.trim() : ''
    const icon = form.type === TYPE_FUNC ? '' : form.icon.trim()
    if (props.mode === 'add') {
      await createMenu({
        parentId,
        name: form.name.trim(),
        perms,
        type: form.type,
        path,
        icon,
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('新增成功')
    } else if (props.menu) {
      // 契约 §2.3 部分更新语义（null 不更新）：始终全量提交八写字段 + id 规避歧义
      await updateMenu({
        id: props.menu.id,
        parentId,
        name: form.name.trim(),
        perms,
        type: form.type,
        path,
        icon,
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（3007 父菜单非法 / 1002 名称空白等）；弹窗保持打开可改后重提
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="560px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="类型" prop="type">
        <!-- 编辑锁定（D3）：改 type 造成层级语义漂移（C 改 F 后其 F 子级成孤儿层级），后端可改但前端约定锁定 -->
        <el-radio-group
          v-model="form.type"
          :disabled="mode === 'edit' || loading"
          @change="handleTypeChange"
        >
          <el-radio :value="TYPE_DIR">目录</el-radio>
          <el-radio :value="TYPE_MENU">菜单</el-radio>
          <el-radio :value="TYPE_FUNC">按钮</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="上级" prop="parentId">
        <!-- M 目录固定根级（目录不嵌套，D3）；C/F 为 el-tree-select 类型化候选 -->
        <span v-if="form.type === TYPE_DIR" class="root-parent">根目录</span>
        <el-tree-select
          v-else
          v-model="form.parentId"
          :data="parentCandidates"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          check-strictly
          default-expand-all
          :validate-event="false"
          :placeholder="form.type === TYPE_MENU ? '请选择上级目录' : '请选择上级菜单'"
          :disabled="loading"
          class="parent-select"
          @change="handleParentSelected"
        />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入菜单名称" :disabled="loading" maxlength="30" />
      </el-form-item>
      <!-- 路由路径（设计 D11 + 主控裁定 R2）：仅 C 型显示（M 恒空串、F 提交空串——
           侧边导航/动态路由只认 C 的 path）；必填 + 格式校验见 validatePath -->
      <el-form-item v-if="form.type === TYPE_MENU" label="路由路径" prop="path">
        <el-input
          v-model="form.path"
          placeholder="必填，如 /system/xxx"
          :disabled="loading"
          maxlength="100"
        />
      </el-form-item>
      <!-- 图标名（主控裁定 R2）：M/C 型显示（目录图标种子 10/20 已配，须可维护）、F 隐藏提交空串；
           自由文本白名单名（ICON_MAP 兜底，icon picker 移交备忘），存在性为前端约定 -->
      <el-form-item v-if="form.type !== TYPE_FUNC" label="图标" prop="icon">
        <el-input
          v-model="form.icon"
          placeholder="选填，如 User"
          :disabled="loading"
          maxlength="50"
        />
      </el-form-item>
      <el-form-item v-if="form.type !== TYPE_DIR" label="权限标识" prop="perms">
        <el-input
          v-model="form.perms"
          :placeholder="form.type === TYPE_MENU ? '选填，如 system:xxx:list' : '必填，如 system:xxx:add'"
          :disabled="loading"
        />
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
.root-parent {
  color: var(--el-text-color-regular);
}

.parent-select {
  width: 100%;
}
</style>
