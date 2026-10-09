<script setup lang="ts">
/**
 * 请假申请页（/system/leave，设计 D9 / 契约 §5，自 /bpmn/leave 迁移 cloud-system）：
 * 当前登录人请假单分页 + 发起弹窗 + 详情弹窗（双源：业务 descriptions + 平台时间线/图）
 * 数据源 /system/leave/page（契约 §5.2，恒按当前登录人、id 倒序、无查询参数——契约现状）
 * 撤销仅审批中且本人行显示（契约 §5.4 语义；后端 3020/3021 为最终防线）
 * 待办跳转落点协议（契约 §1）：识别 query.approval（=approvalId）自动开对应详情弹窗
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelLeave, pageLeave } from '../../../api/systemLeave'
import { useAuthStore } from '../../../stores/auth'
import { APPROVAL_STATUS_MAP, LEAVE_TYPE_MAP, type SysLeaveVo } from '../../../types/api'
import LeaveFormDialog from './components/LeaveFormDialog.vue'
import LeaveDetailDialog from './components/LeaveDetailDialog.vue'

/** 组件名必须显式固定 = pathToRouteName('/system/leave')：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemLeave' })

/** 审批中状态值（契约 §5.4：撤销仅审批中单可操作，3020 兜底终态校验） */
const STATUS_APPROVING = '0'

/**
 * 状态→tag 颜色映射（契约 §5.2 status 字典 bpmn_approval_status）：
 * tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）
 */
const APPROVAL_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
}

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * el-table 的泛型不会流入列插槽，无法在模板内类型收窄；
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): SysLeaveVo {
  return row as SysLeaveVo
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)
const rows = ref<SysLeaveVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const formDialogVisible = ref(false)
const detailDialogVisible = ref(false)
/** 列表行入口（提供 id；展示以弹窗内新拉的 detail 为准） */
const detailLeave = ref<SysLeaveVo>()
/** 待办跳转入口（query.approval=approvalId，弹窗内经平台详情反解 businessKey） */
const detailApprovalId = ref('')

/** 加载分页（契约 §5.2）：total 为 Long→String，分页组件需 Number() */
async function loadLeavePage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
  try {
    query.pageNum = pageNum
    const page = await pageLeave({ pageNum: query.pageNum, pageSize: query.pageSize })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状（Feign 失败后端已降级本地快照，契约 §5.2）
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  formDialogVisible.value = true
}

/** 列表行详情入口 */
function openDetail(leave: SysLeaveVo): void {
  detailLeave.value = leave
  detailApprovalId.value = ''
  detailDialogVisible.value = true
}

/**
 * 待办跳转落点（契约 §1）：query.approval = approvalId（detailPath 渲染值）。
 * watch 而非 onMounted——keep-alive 缓存复用时再次跳入（同 path 不同 query）不重挂载；
 * 弹窗独立加载数据（审批人视角列表无该行，不依赖行数据）
 */
watch(
  () => route.query.approval,
  (value) => {
    if (typeof value === 'string' && value) {
      detailApprovalId.value = value
      detailLeave.value = undefined
      detailDialogVisible.value = true
    }
  },
  { immediate: true },
)

/** 详情弹窗关闭动画后清除落点 query（防刷新重弹），并清两入口数据 */
function handleDetailClosed(): void {
  detailLeave.value = undefined
  detailApprovalId.value = ''
  if (typeof route.query.approval === 'string' && route.query.approval) {
    void router.replace({ query: { ...route.query, approval: undefined } })
  }
}

/** 撤销按钮显隐：仅审批中且申请人本人行（契约 §5.4；后端 3020/3021 为最终防线） */
function canCancel(leave: SysLeaveVo): boolean {
  return leave.status === STATUS_APPROVING && leave.applyUser === authStore.account
}

async function handleCancel(leave: SysLeaveVo): Promise<void> {
  // 文案含标题 + 不可恢复提示（撤销是唯一申请人主动终态化操作）
  const confirmed = await ElMessageBox.confirm(
    `确定撤销请假单 "${leave.title}" 吗？撤销后不可恢复。`,
    '撤销确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await cancelLeave(leave.id)
    ElMessage.success('撤销成功')
    await loadLeavePage()
  } catch {
    // 拦截器已统一 toast（如 3020 已终态 / 3022 平台不可用）
  }
}

function handleSizeChange(size: number): void {
  query.pageSize = size
  void loadLeavePage(1)
}

onMounted(() => {
  void loadLeavePage()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>请假申请</span>
        <el-button v-perms="'system:leave:add'" type="primary" @click="openCreate">发起请假</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" row-key="id">
      <el-table-column label="标题" min-width="180">
        <template #default="{ row }">
          <span>{{ rowOf(row).title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="请假类型" width="100">
        <template #default="{ row }">
          <!-- 降级链（契约 §9）：译文 → 本地映射 → 原值 -->
          <span>{{ rowOf(row).leaveTypeLabel ?? LEAVE_TYPE_MAP[rowOf(row).leaveType] ?? rowOf(row).leaveType }}</span>
        </template>
      </el-table-column>
      <el-table-column label="起止日期" min-width="200">
        <template #default="{ row }">
          <span>{{ rowOf(row).startDate }} ~ {{ rowOf(row).endDate }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="APPROVAL_STATUS_TAG[rowOf(row).status] ?? 'info'">
            {{ rowOf(row).statusLabel ?? APPROVAL_STATUS_MAP[rowOf(row).status] ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="申请人" min-width="100">
        <template #default="{ row }">
          <span>{{ rowOf(row).applyUserName ?? rowOf(row).applyUser }}</span>
        </template>
      </el-table-column>
      <el-table-column label="审批人" min-width="100">
        <template #default="{ row }">
          <span>{{ rowOf(row).approverName ?? rowOf(row).approver }}</span>
        </template>
      </el-table-column>
      <el-table-column label="发起时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).createTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(rowOf(row))">详情</el-button>
          <!-- 撤销：仅审批中且本人行（v-if）+ 权限指令（无权限移除 DOM）双闸 -->
          <el-button
            v-if="canCancel(rowOf(row))"
            v-perms="'system:leave:cancel'"
            link
            type="danger"
            @click="handleCancel(rowOf(row))"
            >撤销</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next, sizes"
      @current-change="loadLeavePage"
      @size-change="handleSizeChange"
    />

    <LeaveFormDialog v-model="formDialogVisible" @success="loadLeavePage()" />
    <LeaveDetailDialog
      v-model="detailDialogVisible"
      :leave="detailLeave"
      :approval-id="detailApprovalId"
      @closed="handleDetailClosed"
    />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
