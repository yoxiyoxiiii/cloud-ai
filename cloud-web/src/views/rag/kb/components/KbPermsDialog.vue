<script setup lang="ts">
/**
 * 知识库权限分配弹窗（kb-perm 契约 2026-10-18-kb-permission-api §2/§3，设计 D9）：
 * - 三块选择器用 el-divider 分段（沿 RuleConfigDialog 分段先例；弃 el-tabs——三块数据量小，
 *   同屏平铺少一次点击且配置全局感更强）：角色/用户 = el-select multiple filterable
 *   （数据源 system 域 subject-options）；部门 = el-tree show-checkbox check-strictly
 *   （数据源 dept/tree）。**两端点权限已 additive 放宽含 ai:kb:update（契约 §7）——
 *   KB 管理员无任何 system:* 权限也能取数，此为刻意设计非越权**
 * - id 类型换轨（契约 §1 前端对接重点）：数据源是 system 域（id 为 string，Java
 *   Long→String），授权两端点是 AI 域（number[]）——弹窗内统一：回显 number→String(id)
 *   进勾选态，提交前逐个 Number(id) 转换（自增 id 值域内安全，非雪花）
 * - 已失效主体（弱引用自愈，契约 §1/设计 D10）：granted id 不在选择器选项中（主体停用/删除）
 *   → 以「id:N（已失效）」占位呈现（select 注入禁选占位选项 / 部门树下方 closable 占位 tag），
 *   默认随选中集保留提交（打开不改直接保存授权不变——最小惊讶）；点 x 移除即随本次保存清理
 * - 部门树 check-strictly（父子独立勾选）：契约 §2 语义为「授权部门固定含其全部子部门树，
 *   求值在服务端，本字段只存被勾选节点」——勾一个部门即授权整棵子树；若用默认父子联动，
 *   勾父会把全部子孙 id 落库（求值等价但存冗余行，回显还会联动勾满误导配置者）
 * - 提交：三集合 Set 去重 → Number 转换 → saveKbPerms 全量覆盖（契约 §3）；每数组上限
 *   100 前端友好提示（后端 1001 权威）；成功 toast + 关闭；失败（403/1001/5004）拦截器
 *   统一 toast 且弹窗不关（沿 KbFormDialog 形态，可调整重试）
 */
import { computed, nextTick, ref, watch } from 'vue'
import type { TreeInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { getKbPerms, saveKbPerms } from '../../../../api/rag'
import { listSubjectOptions } from '../../../../api/dataPerm'
import { treeDept } from '../../../../api/dept'
import type { KbVo, SubjectOptionVo, SysDeptTreeNode } from '../../../../types/api'
import { SUBJECT_ROLE, SUBJECT_USER } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 目标知识库行数据（标题显示库名；打开时按 id 拉授权回显） */
  kb?: KbVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

/** 每数组上限（契约 §3 约束列；后端 1001 权威，此处前端友好提示同值） */
const PERMS_ARRAY_MAX = 100

const loading = ref(false)
const saving = ref(false)
const treeRef = ref<TreeInstance>()

/* 弹窗生命周期内缓存三源（主体低频变化，复用减少重复请求，沿 RuleConfigDialog 缓存先例） */
const roleOptions = ref<SubjectOptionVo[]>([])
const userOptions = ref<SubjectOptionVo[]>([])
const deptTreeData = ref<SysDeptTreeNode[]>([])

/** select 选项本地扩展：已失效主体占位项（禁选——仅随选中集保留/移除，不可主动加选） */
interface SubjectSelectOption extends SubjectOptionVo {
  invalid?: boolean
}

/** 已失效占位（每次打开按 granted 与当前选项差集重建） */
const invalidRoleOptions = ref<SubjectSelectOption[]>([])
const invalidUserOptions = ref<SubjectSelectOption[]>([])
/** 已失效部门 id（不在树中，树无法承载 → 独立 tag 呈现，closable 移除即清理） */
const invalidDeptIds = ref<string[]>([])

/** select 选中集（string id——与选项 value 同轨；含已失效占位 id） */
const selectedRoleIds = ref<string[]>([])
const selectedUserIds = ref<string[]>([])

const title = computed(() => `权限分配${props.kb ? `（${props.kb.name}）` : ''}`)

/** select 选项 = 正常选项 + 已失效占位（拼接顺序：正常在前） */
const roleSelectOptions = computed<SubjectSelectOption[]>(() => [
  ...roleOptions.value,
  ...invalidRoleOptions.value,
])
const userSelectOptions = computed<SubjectSelectOption[]>(() => [
  ...userOptions.value,
  ...invalidUserOptions.value,
])

/** 递归收集部门树全部节点 id（granted 与树求差集的取值域） */
function collectTreeIds(nodes: SysDeptTreeNode[], acc: Set<string> = new Set()): Set<string> {
  for (const node of nodes) {
    acc.add(node.id)
    if (node.children.length > 0) {
      collectTreeIds(node.children, acc)
    }
  }
  return acc
}

/** EP TreeInstance.getCheckedKeys 出参为 TreeKey[]（string|number），集中收窄此一处 */
function checkedDeptKeys(): string[] {
  return (treeRef.value?.getCheckedKeys(false) ?? []) as string[]
}

/** string id 集合 → number[]（契约 §1 换轨：system 域 string → AI 域 number），Set 去重 */
function toNumberIds(ids: string[]): number[] {
  return [...new Set(ids)].map((id) => Number(id))
}

/** 打开时并行拉三源 + 授权回显（契约 §2）；回显 number → string 统一进勾选态 */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible || !props.kb) {
      return
    }
    loading.value = true
    try {
      const [roles, users, tree, perms] = await Promise.all([
        roleOptions.value.length > 0 ? Promise.resolve(roleOptions.value) : listSubjectOptions(SUBJECT_ROLE),
        userOptions.value.length > 0 ? Promise.resolve(userOptions.value) : listSubjectOptions(SUBJECT_USER),
        deptTreeData.value.length > 0 ? Promise.resolve(deptTreeData.value) : treeDept(),
        getKbPerms(props.kb.id),
      ])
      roleOptions.value = roles
      userOptions.value = users
      deptTreeData.value = tree

      // 角色/用户回显：去重转 string；不在选项中的 id 生成「已失效」占位选项（tag 呈现 + 随集保留）
      const roleIds = [...new Set(perms.role_ids.map(String))]
      const userIds = [...new Set(perms.user_ids.map(String))]
      selectedRoleIds.value = roleIds
      selectedUserIds.value = userIds
      const roleIdSet = new Set(roles.map((o) => o.id))
      const userIdSet = new Set(users.map((o) => o.id))
      invalidRoleOptions.value = roleIds
        .filter((id) => !roleIdSet.has(id))
        .map((id) => ({ id, label: `id:${id}（已失效）`, invalid: true }))
      invalidUserOptions.value = userIds
        .filter((id) => !userIdSet.has(id))
        .map((id) => ({ id, label: `id:${id}（已失效）`, invalid: true }))

      // 部门回显：树内 key 勾选（strict 模式无父子联动）；树外 key 独立占位 tag
      const treeKeySet = collectTreeIds(tree)
      const deptIds = [...new Set(perms.dept_ids.map(String))]
      invalidDeptIds.value = deptIds.filter((id) => !treeKeySet.has(id))
      await nextTick()
      treeRef.value?.setCheckedKeys(deptIds.filter((id) => treeKeySet.has(id)), false)
    } catch {
      // 拦截器已统一 toast；弹窗保持可关闭
    } finally {
      loading.value = false
    }
  },
)

function removeInvalidDept(id: string): void {
  invalidDeptIds.value = invalidDeptIds.value.filter((v) => v !== id)
}

async function handleSubmit(): Promise<void> {
  if (!props.kb || saving.value) {
    return
  }
  // 部门提交集 = 树勾选 ∪ 已失效占位（保留语义，见头注释）；三集合各自去重换轨
  const roleIds = toNumberIds(selectedRoleIds.value)
  const deptIds = toNumberIds([...checkedDeptKeys(), ...invalidDeptIds.value])
  const userIds = toNumberIds(selectedUserIds.value)
  if (
    roleIds.length > PERMS_ARRAY_MAX ||
    deptIds.length > PERMS_ARRAY_MAX ||
    userIds.length > PERMS_ARRAY_MAX
  ) {
    ElMessage.warning(`每类主体授权上限 ${PERMS_ARRAY_MAX} 个`)
    return
  }
  saving.value = true
  try {
    await saveKbPerms(props.kb.id, { role_ids: roleIds, dept_ids: deptIds, user_ids: userIds })
    ElMessage.success('保存成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（403 无权/1001 越界/5004 库不存在）；弹窗保持打开供调整重试
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="620px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading">
      <el-divider content-position="left">角色授权</el-divider>
      <el-select
        v-model="selectedRoleIds"
        multiple
        filterable
        placeholder="选择角色（可搜索）"
        :disabled="loading || saving"
        class="full-width"
      >
        <el-option
          v-for="o in roleSelectOptions"
          :key="o.id"
          :label="o.label"
          :value="o.id"
          :disabled="o.invalid === true"
        />
      </el-select>

      <el-divider content-position="left">部门授权（勾选即授权该部门及其全部子部门）</el-divider>
      <el-tree
        ref="treeRef"
        :data="deptTreeData"
        show-checkbox
        check-strictly
        node-key="id"
        :props="{ label: 'name', children: 'children' }"
        default-expand-all
        class="dept-tree"
      />
      <div v-if="invalidDeptIds.length > 0" class="invalid-tags">
        <span class="invalid-label">已失效：</span>
        <el-tag
          v-for="id in invalidDeptIds"
          :key="id"
          closable
          type="info"
          :disable-transitions="true"
          @close="removeInvalidDept(id)"
          >id:{{ id }}（已失效）</el-tag
        >
      </div>

      <el-divider content-position="left">用户授权</el-divider>
      <el-select
        v-model="selectedUserIds"
        multiple
        filterable
        placeholder="选择用户（可搜索，昵称(账号)）"
        :disabled="loading || saving"
        class="full-width"
      >
        <el-option
          v-for="o in userSelectOptions"
          :key="o.id"
          :label="o.label"
          :value="o.id"
          :disabled="o.invalid === true"
        />
      </el-select>

      <div class="field-hint">
        被授权者（任一途径命中）对该库获得只读可见权：列表行可见 + 文件可浏览；
        上传/编辑/删除仍由功能权限独立裁决。每类主体上限 {{ PERMS_ARRAY_MAX }} 个。
      </div>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full-width {
  width: 100%;
}

.dept-tree {
  max-height: 220px;
  overflow: auto;
  padding: 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.invalid-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
}

.invalid-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.field-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
