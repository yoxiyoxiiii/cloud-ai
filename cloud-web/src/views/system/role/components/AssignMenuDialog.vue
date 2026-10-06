<script setup lang="ts">
/**
 * 分配菜单权限 弹窗（布局 2026-10-06 重构：M 目录=组头 / C 菜单=行 / F 按钮=行内横排，替代 el-tree）
 *
 * 勾选语义与原 el-tree 方案（设计 D2）逐位等价——状态唯一数据源是"选中叶子集合"：
 * - 叶子域 = F 节点 + 无子节点的 C 节点（checkedIds 只收这两类 id）
 * - 回显：bound（listRoleMenuIds）中的 id 仅当属于叶子域才入 checkedIds——
 *   父 id（M / 有 F 子的 C）不直接设置勾选，其三态纯由子孙推导
 *   （防 admin 等存量"绑定了父目录"的数据误勾整树，D2 核心教训）
 * - 交互：点 M 全选/全清其下全部叶子；点 C 全选/全清其 F（无 F 子仅自身）；点 F 仅自身翻转
 * - 提交：include(F)=选中；include(无F子C)=选中；include(有F子C)=任一其F选中；
 *   include(M)=其子树任一叶子选中 —— 等价原 getCheckedKeys ∪ getHalfCheckedKeys
 *   （验证锚点：勾"用户新增 111 + 用户删除 113" → menuIds 必含 111/113/11/10）
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { assignRoleMenus, listRoleMenuIds } from '../../../../api/role'
import { menuTree } from '../../../../api/menu'
import type { MenuTreeNode, SysRoleVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 目标角色行数据 */
  role?: SysRoleVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

/** 三态勾选态：checked 全选 / indeterminate 半选 */
interface TriState {
  checked: boolean
  indeterminate: boolean
}

const loading = ref(false)
const saving = ref(false)
const treeData = ref<MenuTreeNode[]>([])

/** 选中叶子集合（F 与无子 C 的 id）——勾选状态唯一数据源，M/C 三态与提交集合全部由它推导 */
const checkedIds = ref<Set<string>>(new Set())

const title = computed(() => `分配权限${props.role ? `（${props.role.name}）` : ''}`)

/** C 节点下属按钮（F）子节点——行内横排渲染与提交规则的取值域 */
function funcChildren(c: MenuTreeNode): MenuTreeNode[] {
  return c.children.filter((n) => n.type === 'F')
}

/**
 * 节点三态：叶子（F / 无子节点）直接看选中集；父节点由子孙聚合——
 * 全选=子孙全部 checked；半选=部分 checked 或 indeterminate
 */
function triStateOf(node: MenuTreeNode): TriState {
  if (node.children.length === 0) {
    return { checked: checkedIds.value.has(node.id), indeterminate: false }
  }
  const states = node.children.map((child) => triStateOf(child))
  const allChecked = states.every((s) => s.checked && !s.indeterminate)
  const someActive = states.some((s) => s.checked || s.indeterminate)
  return { checked: allChecked, indeterminate: someActive && !allChecked }
}

/** 收集子树内全部叶子域 id（F 与无子 C）——回显过滤 / 批量勾选与清空的取值域 */
function collectLeafDomainIds(nodes: MenuTreeNode[], acc: Set<string> = new Set()): Set<string> {
  for (const node of nodes) {
    if (node.children.length === 0) {
      acc.add(node.id)
    } else {
      collectLeafDomainIds(node.children, acc)
    }
  }
  return acc
}

/** 点 F：仅自身翻转 */
function toggleF(f: MenuTreeNode): void {
  if (checkedIds.value.has(f.id)) {
    checkedIds.value.delete(f.id)
  } else {
    checkedIds.value.add(f.id)
  }
}

/** 点 C：已全选则清空其全部 F；未选/半选则全选其 F；无 F 子仅自身翻转 */
function toggleC(c: MenuTreeNode): void {
  const funcs = funcChildren(c)
  if (funcs.length === 0) {
    toggleF(c)
    return
  }
  const clearing = triStateOf(c).checked
  for (const f of funcs) {
    if (clearing) {
      checkedIds.value.delete(f.id)
    } else {
      checkedIds.value.add(f.id)
    }
  }
}

/** 点 M：已全选则清空其下全部叶子；未选/半选则全选 */
function toggleM(m: MenuTreeNode): void {
  const leafIds = collectLeafDomainIds(m.children)
  const clearing = triStateOf(m).checked
  for (const id of leafIds) {
    if (clearing) {
      checkedIds.value.delete(id)
    } else {
      checkedIds.value.add(id)
    }
  }
}

/** C 是否入提交集合：无 F 子看自身选中；有 F 子=任一其 F 选中 */
function cIncluded(c: MenuTreeNode): boolean {
  const funcs = funcChildren(c)
  if (funcs.length === 0) {
    return checkedIds.value.has(c.id)
  }
  return funcs.some((f) => checkedIds.value.has(f.id))
}

/** M 是否入提交集合：其子树内任一叶子选中（"任一其 C included"的递归展开，语义等价） */
function mIncluded(m: MenuTreeNode): boolean {
  const leafIds = collectLeafDomainIds(m.children)
  for (const id of leafIds) {
    if (checkedIds.value.has(id)) {
      return true
    }
  }
  return false
}

/** 提交集合（契约 §4.6 全量覆盖）：M → C → F 遍历收集，父节点有任一选中后代即入库（半选父语义） */
function submissionIds(): string[] {
  const ids: string[] = []
  for (const m of treeData.value) {
    if (mIncluded(m)) {
      ids.push(m.id)
    }
    for (const c of m.children) {
      if (cIncluded(c)) {
        ids.push(c.id)
      }
      for (const f of funcChildren(c)) {
        if (checkedIds.value.has(f.id)) {
          ids.push(f.id)
        }
      }
    }
  }
  return ids
}

/** 打开时并行拉取菜单树与角色已绑菜单（契约 §5.1 / §4.7），回显按叶子域过滤 */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible || !props.role) {
      return
    }
    loading.value = true
    try {
      const [tree, boundIds] = await Promise.all([menuTree(), listRoleMenuIds(props.role.id)])
      treeData.value = tree
      // 只认叶子域 id：父 id 不直接设勾选，其三态由子孙推导（D2——防存量父目录绑定误勾整棵子树）
      const leafDomain = collectLeafDomainIds(tree)
      const next = new Set<string>()
      for (const id of boundIds) {
        if (leafDomain.has(id)) {
          next.add(id)
        }
      }
      checkedIds.value = next
    } catch {
      // 拦截器已统一 toast；保持弹窗可关闭
    } finally {
      loading.value = false
    }
  },
)

async function handleSubmit(): Promise<void> {
  if (!props.role || saving.value) {
    return
  }
  saving.value = true
  try {
    const menuIds = submissionIds()
    await assignRoleMenus({ roleId: props.role.id, menuIds })
    ElMessage.success('分配成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="800px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading" class="perm-panel">
      <div v-for="m in treeData" :key="m.id" class="perm-group">
        <div class="perm-group-header">
          <el-checkbox
            class="perm-group-check"
            :model-value="triStateOf(m).checked"
            :indeterminate="triStateOf(m).indeterminate"
            @change="toggleM(m)"
          >
            {{ m.name }}
          </el-checkbox>
        </div>
        <div v-for="c in m.children" :key="c.id" class="perm-menu-row">
          <el-checkbox
            class="perm-menu-check"
            :model-value="triStateOf(c).checked"
            :indeterminate="triStateOf(c).indeterminate"
            @change="toggleC(c)"
          >
            {{ c.name }}
          </el-checkbox>
          <div class="perm-func-list">
            <el-checkbox
              v-for="f in funcChildren(c)"
              :key="f.id"
              class="perm-func"
              :model-value="checkedIds.has(f.id)"
              @change="toggleF(f)"
            >
              {{ f.name }}
              <!-- 按钮节点追加权限标识灰字（分配对象的核心信息） -->
              <span class="perm-func-perms">{{ f.perms }}</span>
            </el-checkbox>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && treeData.length === 0" description="暂无菜单" :image-size="60" />
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="handleSubmit">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.perm-panel {
  max-height: 360px;
  overflow: auto;
}

/* M 目录 = 组头：视觉分组（底色块 + 分隔线 + 组名加粗） */
.perm-group {
  margin-bottom: 12px;
  padding: 10px 16px 6px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}

.perm-group:last-child {
  margin-bottom: 0;
}

.perm-group-header {
  padding-bottom: 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.perm-group-header :deep(.el-checkbox__label) {
  font-weight: 600;
}

/* C 菜单 = 行：行首三态 checkbox + 菜单名（定宽对齐），右侧横排其 F 按钮 */
.perm-menu-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  column-gap: 16px;
  padding: 7px 0;
}

.perm-menu-row + .perm-menu-row {
  border-top: 1px dashed var(--el-border-color-extra-light);
}

.perm-menu-check {
  flex: 0 0 auto;
  width: 112px;
}

/* F 按钮 = 行内横排，超出换行 */
.perm-func-list {
  display: flex;
  flex: 1 1 auto;
  flex-wrap: wrap;
  gap: 6px 16px;
}

.perm-func-perms {
  margin-left: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
