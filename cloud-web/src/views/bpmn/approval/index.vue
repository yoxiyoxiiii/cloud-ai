<script setup lang="ts">
/**
 * 我的审批页（/bpmn/approval，设计 D9 / 契约 §3，新）：跨业务审批单中心
 * 数据源 /bpmn/approval/page（契约 §3.1，恒按当前登录人 applyUser、id 倒序、含全部状态与业务类型）
 * 撤销仅审批中且本人行可点（契约 §3.3 语义；终态行按钮禁用置灰，后端 4011/4012 为最终防线）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelApproval, getApprovalPage } from '../../../api/bpmn'
import { useAuthStore } from '../../../stores/auth'
import { APPROVAL_STATUS_MAP, type ApprovalVo } from '../../../types/api'
import ApprovalDetailDialog from './components/ApprovalDetailDialog.vue'

/** 组件名必须显式固定 = pathToRouteName('/bpmn/approval')：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'BpmnApproval' })

/** 审批中状态值（契约 §3.3：撤销仅审批中单可操作，4011 兜底终态校验） */
const STATUS_APPROVING = '0'

/**
 * 状态→tag 颜色映射（契约 §3.1 status 字典 bpmn_approval_status）：
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
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): ApprovalVo {
  return row as ApprovalVo
}

const authStore = useAuthStore()
const loading = ref(false)
const rows = ref<ApprovalVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const detailDialogVisible = ref(false)
const detailApproval = ref<ApprovalVo>()

/** 加载分页（契约 §3.1）：total 为 Long→String，分页组件需 Number() */
async function loadApprovalPage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
  try {
    query.pageNum = pageNum
    const page = await getApprovalPage({ pageNum: query.pageNum, pageSize: query.pageSize })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function openDetail(approval: ApprovalVo): void {
  detailApproval.value = approval
  detailDialogVisible.value = true
}

/** 撤销可点：仅审批中且申请人本人行（契约 §3.3；页面恒本人数据，applyUser 判断为防御性冗余）；终态行按钮禁用置灰 */
function canCancel(approval: ApprovalVo): boolean {
  return approval.status === STATUS_APPROVING && approval.applyUser === authStore.account
}

async function handleCancel(approval: ApprovalVo): Promise<void> {
  // 文案含标题 + 不可恢复提示（撤销是唯一申请人主动终态化操作）
  const confirmed = await ElMessageBox.confirm(
    `确定撤销审批单 "${approval.title}" 吗？撤销后不可恢复。`,
    '撤销确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await cancelApproval(approval.id)
    ElMessage.success('撤销成功')
    await loadApprovalPage()
  } catch {
    // 拦截器已统一 toast（如 4011 已终态 / 4012 非本人）
  }
}

function handleSizeChange(size: number): void {
  query.pageSize = size
  void loadApprovalPage(1)
}

onMounted(() => {
  void loadApprovalPage()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>我的审批</span>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" row-key="id">
      <el-table-column label="标题" min-width="200">
        <template #default="{ row }">
          <span>{{ rowOf(row).title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="业务类型" width="110">
        <template #default="{ row }">
          <!-- 配置表 join 必返非空（契约 §9），无降级链 -->
          <span>{{ rowOf(row).businessTypeName }}</span>
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
      <el-table-column label="最近变更" min-width="160">
        <template #default="{ row }">{{ rowOf(row).updateTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(rowOf(row))">详情</el-button>
          <!-- 撤销：审批中且本人行可点（终态禁用置灰）+ 权限指令（无权限移除 DOM）双闸 -->
          <el-button
            v-perms="'bpmn:approval:cancel'"
            link
            type="danger"
            :disabled="!canCancel(rowOf(row))"
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
      @current-change="loadApprovalPage"
      @size-change="handleSizeChange"
    />

    <ApprovalDetailDialog v-model="detailDialogVisible" :approval="detailApproval" />
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
