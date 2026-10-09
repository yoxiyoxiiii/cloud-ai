<script setup lang="ts">
/**
 * 请假申请页（/system/leave，设计 D9 / 契约 §5，自 /bpmn/leave 迁移 cloud-system）：
 * 当前登录人请假单分页 + 发起弹窗 + 详情弹窗（双源：业务 descriptions + 平台时间线/图）
 * 数据源 /system/leave/page（契约 §5.2，恒按当前登录人、id 倒序、无查询参数——契约现状）
 * 撤销仅审批中且本人行显示（契约 §5.4 语义；后端 3020/3021 为最终防线）
 * 发起失败终态（契约 2026-10-09-rocketmq-tx-approval-api §2.2）：status=4 行 danger 标签 +
 * 本人「重新发起」（预填弹窗，提交=新单据，原单保留 4）；「查看审批」入口以 approvalId 非空为
 * 渲染条件（status=4 恒 null 不渲染；发起后至事件回填前的秒级瞬态 null 同样不渲染，不设 loading 态）
 * 待办跳转落点协议（契约 §1）：识别 query.approval（=approvalId）自动开对应详情弹窗
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelLeave, pageLeave } from '../../../api/systemLeave'
import { useAuthStore } from '../../../stores/auth'
import { APPROVAL_STATUS_MAP, LEAVE_TYPE_MAP, type LeaveCreatePayload, type SysLeaveVo } from '../../../types/api'
import LeaveFormDialog from './components/LeaveFormDialog.vue'
import LeaveDetailDialog from './components/LeaveDetailDialog.vue'

/** 组件名必须显式固定 = pathToRouteName('/system/leave')：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemLeave' })

/** 审批中状态值（契约 §5.4：撤销仅审批中单可操作，3020 兜底终态校验） */
const STATUS_APPROVING = '0'

/** 发起失败终态值（契约 2026-10-09 §2.2）：仅 system 产生，approvalId 恒 null——无审批跳转/图/时间线 */
const STATUS_FAILED = '4'

/**
 * 状态→tag 颜色映射（契约 §5.2 status 字典 bpmn_approval_status；值域扩 4 见 2026-10-09 §2.2）：
 * tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）
 */
const APPROVAL_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
  '4': 'danger', // 发起失败（终态，仅 system 产生）
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
/** 重新发起预填值（undefined=普通发起；打开弹窗前设置，关闭/普通发起时复位——契约 2026-10-09 §2.2） */
const formInitial = ref<LeaveCreatePayload>()
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
  formInitial.value = undefined
  formDialogVisible.value = true
}

/** 重新发起（契约 2026-10-09 §2.2/计划 F2）：status=4 行复制本单六字段预填发起弹窗；
 * 提交=POST /system/leave 创建**新单据**，原单保留 4 不变（驳回重报同形态，2026-10-08 移交备忘 6） */
function canResubmit(leave: SysLeaveVo): boolean {
  return leave.status === STATUS_FAILED && leave.applyUser === authStore.account
}

function openResubmit(leave: SysLeaveVo): void {
  formInitial.value = {
    title: leave.title,
    leaveType: leave.leaveType,
    startDate: leave.startDate,
    endDate: leave.endDate,
    reason: leave.reason ?? undefined,
    approver: leave.approver,
  }
  formDialogVisible.value = true
}

/** 跳平台审批页并冷开对应详情（?approval= 协议，落点见 /bpmn/approval 同款 watch） */
function viewApproval(approvalId: string): void {
  void router.push({ path: '/bpmn/approval', query: { approval: approvalId } })
}

/** 列表行「查看审批」：approvalId 非空为渲染条件（v-if 闸 + 函数内防御再判） */
function handleRowViewApproval(leave: SysLeaveVo): void {
  if (leave.approvalId) {
    viewApproval(leave.approvalId)
  }
}

/** 详情弹窗「查看审批」事件：先关弹窗再跳（closed 清 query 有页内守卫，不清审批页落点） */
function handleDetailViewApproval(approvalId: string): void {
  detailDialogVisible.value = false
  viewApproval(approvalId)
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

/** 详情弹窗关闭动画后清除落点 query（防刷新重弹），并清两入口数据；
 *  限本页路径才清——「查看审批」跳走后 route 已在 /bpmn/approval?approval=x（keep-alive 下
 *  closed 迟到触发），误清会破坏审批页冷开落点（契约 2026-10-09 §2.2 跳转协议） */
function handleDetailClosed(): void {
  detailLeave.value = undefined
  detailApprovalId.value = ''
  if (route.path === '/system/leave' && typeof route.query.approval === 'string' && route.query.approval) {
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
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(rowOf(row))">详情</el-button>
          <!-- 查看审批：跳平台审批页冷开对应详情；approvalId 非空为渲染条件
               （status=4 恒 null 与发起后秒级瞬态 null 均不渲染，不设 loading 态——契约 2026-10-09 §2.2） -->
          <el-button
            v-if="rowOf(row).approvalId"
            link
            type="primary"
            @click="handleRowViewApproval(rowOf(row))"
            >查看审批</el-button
          >
          <!-- 重新发起：status=4 且本人行（v-if）+ 发起权限指令双闸；预填弹窗提交=新单据，原单保留 4 -->
          <el-button
            v-if="canResubmit(rowOf(row))"
            v-perms="'system:leave:add'"
            link
            type="warning"
            @click="openResubmit(rowOf(row))"
            >重新发起</el-button
          >
          <!-- 撤销：仅审批中且本人行（v-if）+ 权限指令（无权限移除 DOM）双闸；status=4 非审批中自然隐藏 -->
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

    <LeaveFormDialog v-model="formDialogVisible" :initial="formInitial" @success="loadLeavePage()" />
    <LeaveDetailDialog
      v-model="detailDialogVisible"
      :leave="detailLeave"
      :approval-id="detailApprovalId"
      @view-approval="handleDetailViewApproval"
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
