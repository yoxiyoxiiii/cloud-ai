<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { assignUserRoles, listUserRoleIds } from '../../../../api/user'
import { listRoles } from '../../../../api/role'
import type { SysRoleVo, SysUserVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  user?: SysUserVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const loading = ref(false)
const saving = ref(false)
const roleOptions = ref<SysRoleVo[]>([])
/** 勾选的角色 id（全为字符串，与回显 id 类型一致，契约 Long→String） */
const checkedRoleIds = ref<string[]>([])

const title = computed(() => `分配角色${props.user ? `（${props.user.account}）` : ''}`)

/** 打开时并行拉取角色候选与用户已有角色（契约 §4.1 / §3.8） */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible || !props.user) {
      return
    }
    loading.value = true
    checkedRoleIds.value = []
    try {
      const [roles, roleIds] = await Promise.all([listRoles(), listUserRoleIds(props.user.id)])
      roleOptions.value = roles
      checkedRoleIds.value = roleIds
    } catch {
      // 拦截器已统一 toast；保持弹窗可关闭
    } finally {
      loading.value = false
    }
  },
)

async function handleSubmit(): Promise<void> {
  if (!props.user || saving.value) {
    return
  }
  saving.value = true
  try {
    // 全量覆盖语义：空选即清空角色（契约 §3.7）
    await assignUserRoles({ userId: props.user.id, roleIds: checkedRoleIds.value })
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
    width="480px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading">
      <el-checkbox-group v-model="checkedRoleIds" :disabled="loading || saving">
        <el-checkbox v-for="role in roleOptions" :key="role.id" :value="role.id">
          {{ role.name }}（{{ role.roleKey }}）
        </el-checkbox>
      </el-checkbox-group>
      <el-empty v-if="!loading && roleOptions.length === 0" description="暂无角色" :image-size="60" />
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="handleSubmit">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>
