<script setup lang="ts">
/**
 * 分配菜单权限 弹窗（对照 AssignRoleDialog 的树形版，设计 §4.2 + D2）
 *
 * D2 勾选语义（回显与提交的不对称，最易踩的坑）：
 * - 提交：getCheckedKeys()（全选，含因子全选而全选的父）∪ getHalfCheckedKeys()（半选父）
 *   合并全量提交（契约 §4.6 先清后插）——半选父也入库，保证存量"角色绑定了目录节点"的数据自洽
 * - 回显：只把叶子节点 id 传给 setCheckedKeys——传父 id 会连带勾选全部子孙，
 *   admin 等存量绑定父目录的数据会被误勾整树；叶子过滤后父节点勾选态由 EP 联动计算
 */
import { computed, nextTick, ref, watch } from 'vue'
import type { TreeInstance } from 'element-plus'
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

const loading = ref(false)
const saving = ref(false)
const treeData = ref<MenuTreeNode[]>([])

const treeRef = ref<TreeInstance>()

const title = computed(() => `分配权限${props.role ? `（${props.role.name}）` : ''}`)

/**
 * EP el-tree 默认节点插槽的 data 类型不含泛型流入，
 * 全组件断言集中此一处（同列表页 rowOf 模式）
 */
function nodeOf(data: unknown): MenuTreeNode {
  return data as MenuTreeNode
}

/** 收集叶子节点 id 集合：回显只传叶子，父节点勾选态由 EP 联动计算（设计 D2） */
function collectLeafIds(nodes: MenuTreeNode[], acc: Set<string> = new Set()): Set<string> {
  for (const node of nodes) {
    // 契约 §5.1：叶子 children 恒为空数组 []（非 null），length 判断安全
    if (node.children.length === 0) {
      acc.add(node.id)
    } else {
      collectLeafIds(node.children, acc)
    }
  }
  return acc
}

/** 打开时并行拉取菜单树与角色已绑菜单（契约 §5.1 / §4.7），回显按叶子过滤 */
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
      // 等树渲染完成再设勾选；只传叶子 id，防存量父目录绑定误勾整棵子树（设计 D2）
      const leafIds = collectLeafIds(tree)
      await nextTick()
      treeRef.value?.setCheckedKeys(boundIds.filter((id) => leafIds.has(id)))
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
    // 全选 ∪ 半选父 合并提交（契约 §4.6 全量覆盖语义；getCheckedKeys 返回 TreeKey 联合类型，map(String) 收敛）
    const checked = treeRef.value?.getCheckedKeys() ?? []
    const half = treeRef.value?.getHalfCheckedKeys() ?? []
    const menuIds = [...checked, ...half].map(String)
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
    width="560px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading" class="menu-tree-wrap">
      <el-tree
        ref="treeRef"
        :data="treeData"
        node-key="id"
        show-checkbox
        default-expand-all
        :props="{ label: 'name', children: 'children' }"
      >
        <template #default="{ data }">
          <span class="menu-tree-node">
            <span>{{ nodeOf(data).name }}</span>
            <!-- 按钮节点追加权限标识灰字（分配对象的核心信息） -->
            <span v-if="nodeOf(data).type === 'F'" class="menu-tree-perms">
              {{ nodeOf(data).perms }}
            </span>
          </span>
        </template>
      </el-tree>
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
.menu-tree-wrap {
  max-height: 360px;
  overflow: auto;
}

.menu-tree-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.menu-tree-perms {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
