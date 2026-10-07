<script setup lang="ts">
/**
 * 我的申请页（/bpmn/leave，设计 D10）：当前登录人请假单分页 + 发起弹窗 + 详情弹窗（时间线）
 * 数据源 /bpmn/leave/page（契约 §2.2，恒按当前登录人过滤、id 倒序，无查询参数——契约现状）
 * 撤销仅审批中且本人行显示（契约 §2.4 语义；页面恒本人数据，applyUser 判断为防御性冗余）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelLeave, pageLeave } from '../../../api/bpmn'
import { useAuthStore } from '../../../stores/auth'
import { LEAVE_STATUS_MAP, LEAVE_TYPE_MAP, type LeaveVo } from '../../../types/api'
import LeaveFormDialog from './components/LeaveFormDialog.vue'
import LeaveDetailDialog from './components/LeaveDetailDialog.vue'

/** 组件名必须显式固定 = pathToRouteName('/bpmn/leave')：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'BpmnLeave' })

/** 审批中状态值（契约 §2.4：撤销仅审批中单可操作，4002 兜底终态校验） */
const STATUS_APPROVING = '0'

/**
 * 状态→tag 颜色映射（契约 §2.2 状态字典，计划 F2 指定色）：
 * tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）
 */
const LEAVE_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
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
function rowOf(row: unknown): LeaveVo {
  return row as LeaveVo
}

const authStore = useAuthStore()
const loading = ref(false)
const rows = ref<LeaveVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const formDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const detailLeave = ref<LeaveVo>()

/** 加载分页（契约 §2.2）：total 为 Long→String，分页组件需 Number() */
async function loadLeavePage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
  try {
    query.pageNum = pageNum
    const page = await pageLeave({ pageNum: query.pageNum, pageSize: query.pageSize })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  formDialogVisible.value = true
}

function openDetail(leave: LeaveVo): void {
  detailLeave.value = leave
  detailDialogVisible.value = true
}

/** 撤销按钮显隐：仅审批中且申请人本人行（契约 §2.4；后端 4002/4003 为最终防线） */
function canCancel(leave: LeaveVo): boolean {
  return leave.status === STATUS_APPROVING && leave.applyUser === authStore.account
}

async function handleCancel(leave: LeaveVo): Promise<void> {
  // 文案含标题 + 不可恢复提示（契约 §1：撤销是唯一申请人主动终态化操作）
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
    // 拦截器已统一 toast（如 4002 已终态）
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
        <span>我的申请</span>
        <el-button v-perms="'bpmn:leave:add'" type="primary" @click="openCreate">发起请假</el-button>
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
          <!-- 降级链（契约 §7）：译文 → 本地映射 → 原值 -->
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
          <el-tag :type="LEAVE_STATUS_TAG[rowOf(row).status] ?? 'info'">
            {{ rowOf(row).statusLabel ?? LEAVE_STATUS_MAP[rowOf(row).status] ?? rowOf(row).status }}
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
            v-perms="'bpmn:leave:cancel'"
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
    <LeaveDetailDialog v-model="detailDialogVisible" :leave="detailLeave" />
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
