<script setup lang="ts">
/**
 * 我的审批页（/bpmn/approval，设计 D9 / 契约 §3，新）：跨业务审批单中心
 * 数据源 /bpmn/approval/page（契约 2026-10-10-dataperm-component-api §3.1：行集按当前登录人数据
 * 权限规则求值——无规则=仅自己（旧版「恒按申请人」行为兼容）、admin 种子=全部、部门档=部门成员发起
 * 的单；id 倒序、含全部状态与业务类型）；title 可能被列规则隐藏（null）/脱敏（***）——后端已处理，
 * 前端零转换原样展示
 * 撤销仅审批中且本人行可点（契约 §3.3 语义；终态行按钮禁用置灰，后端 4011/4012 为最终防线）
 * 直达落点（契约 2026-10-09-rocketmq-tx-approval-api §2.2「查看审批」跳转协议）：识别
 * query.approval（=审批单 id，请假页「查看审批」等业务侧入口跳入）自动开对应详情弹窗
 * 数据权限增量（契约 2026-10-10-dataperm-component-api §4）：顶部 my-scope 提示条自查范围
 * （资源 bpmn_approval）；详情/图数据越权 4018 走拦截器统一 toast（与 leave 页 3026 同链零新代码）
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelApproval, getApprovalPage } from '../../../api/bpmn'
import { getMyScope } from '../../../api/dataPerm'
import { useAuthStore } from '../../../stores/auth'
import { APPROVAL_STATUS_MAP, DATAPERM_RESOURCE_APPROVAL, type ApprovalVo } from '../../../types/api'
import ApprovalDetailDialog from './components/ApprovalDetailDialog.vue'

/** 组件名必须显式固定 = pathToRouteName('/bpmn/approval')：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'BpmnApproval' })

/** 审批中状态值（契约 §3.3：撤销仅审批中单可操作，4011 兜底终态校验） */
const STATUS_APPROVING = '0'

/**
 * 状态→tag 颜色映射（契约 §3.1 status 字典 bpmn_approval_status）：
 * tagType 是颜色映射本体，永远按原字段 status 取值（译文不含颜色语义——契约 §1 红线）；
 * 4=发起失败在本域不可达（bpmn_approval 永不落 4——契约 2026-10-09 §2.2，仅保字典镜像完整）
 */
const APPROVAL_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
  '4': 'danger', // 发起失败（仅 system 产生，本域不可达）
}

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): ApprovalVo {
  return row as ApprovalVo
}

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const rows = ref<ApprovalVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const detailDialogVisible = ref(false)
/** 列表行入口（提供 id；展示以弹窗内新拉的 detail 为准） */
const detailApproval = ref<ApprovalVo>()
/** 直达入口（query.approval=审批单 id，请假页「查看审批」等跳入；仅 id 驱动弹窗自取数据） */
const detailApprovalId = ref('')

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
  detailApprovalId.value = ''
  detailDialogVisible.value = true
}

/**
 * 直达落点（契约 2026-10-09 §2.2）：query.approval = 审批单 id。
 * watch 而非 onMounted——keep-alive 缓存复用时再次跳入（同 path 不同 query）不重挂载；
 * 弹窗独立加载数据（与列表行数据无关）
 */
watch(
  () => route.query.approval,
  (value) => {
    if (typeof value === 'string' && value) {
      detailApproval.value = undefined
      detailApprovalId.value = value
      detailDialogVisible.value = true
    }
  },
  { immediate: true },
)

/** 详情弹窗关闭动画后清除落点 query（防刷新重弹），并清两入口数据 */
function handleDetailClosed(): void {
  detailApproval.value = undefined
  detailApprovalId.value = ''
  if (route.path === '/bpmn/approval' && typeof route.query.approval === 'string' && route.query.approval) {
    void router.replace({ query: { ...route.query, approval: undefined } })
  }
}

/** 撤销可点：仅审批中且申请人本人行（契约 §3.3；行集经数据权限可含他人单——契约 §3.1，
 * applyUser 判定为非本人行的置灰闸，后端 4012 为最终防线）；终态行按钮禁用置灰 */
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

/* ---- 我的数据范围提示条（契约 2026-10-10-dataperm-component-api §4，my-scope 免权限注解登录即可；不留痕） ---- */
/** 提示条渲染条件：仅成功取回后渲染——失败静默降级不渲染（后端未就绪/接口异常不阻塞列表） */
const myScopeLabel = ref('')
const myScopeColumnSummary = ref<string | null>(null)

async function loadMyScope(): Promise<void> {
  try {
    const scope = await getMyScope(DATAPERM_RESOURCE_APPROVAL)
    myScopeLabel.value = scope.scopeLabel
    myScopeColumnSummary.value = scope.columnSummary
  } catch {
    // 静默降级：提示条不渲染（拦截器可能已 toast 业务错误，页面主功能不受影响）
  }
}

onMounted(() => {
  void loadApprovalPage()
  void loadMyScope()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>我的审批</span>
      </div>
    </template>

    <!-- 我的数据范围提示条（契约 2026-10-10-dataperm-component-api §4 my-scope，资源 bpmn_approval）：
         成功取回才渲染（失败静默降级不渲染）；列动作有值时括注（如 title:脱敏） -->
    <el-alert
      v-if="myScopeLabel"
      class="scope-alert"
      :title="`当前数据范围：${myScopeLabel}${myScopeColumnSummary ? `（${myScopeColumnSummary}）` : ''}`"
      type="info"
      :closable="false"
      show-icon
    />

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

    <ApprovalDetailDialog
      v-model="detailDialogVisible"
      :approval="detailApproval"
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

.scope-alert {
  margin-bottom: 12px;
}
</style>
