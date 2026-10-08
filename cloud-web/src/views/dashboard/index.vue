<script setup lang="ts">
/**
 * 工作台两卡（计划 F5 / 设计 D7，契约 2026-10-08 §6 消费声明——零新端点）：
 * - 「我的待办」GET /bpmn/task/todo 前 5 条（v1 §3.1 原样）；可见性 v-perms bpmn:task:list 整卡隐藏
 * - 「我的申请」GET /bpmn/leave/page pageNum=1&pageSize=5（v1 §2.2 原样）；可见性 v-perms bpmn:leave:list
 * - 工作台本身恒可见（静态页不在 sys_menu）——两卡是页内按权限显隐的局部
 * - 降级链沿 v1 §7（译文 → 本地映射 → 原值）；刷新策略：进页拉取（移交备忘 6，实时推送另案）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listTodoTasks, pageLeave } from '../../api/bpmn'
import { hasPerm } from '../../stores/perm'
import {
  LEAVE_STATUS_MAP,
  LEAVE_TYPE_MAP,
  type LeaveVo,
  type TaskVo,
} from '../../types/api'

/** 组件名必须显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（升级设计 D5 红字坑） */
defineOptions({ name: 'Dashboard' })

/** 状态→tag 颜色映射（契约 §2.2 状态字典，我的申请页同款）：永远按原字段 status 取值 */
const LEAVE_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
}

const router = useRouter()

const todoLoading = ref(false)
const todoRows = ref<TaskVo[]>([])
const leaveLoading = ref(false)
const leaveRows = ref<LeaveVo[]>([])

/** 两卡数据并行拉取：按快照权限决定是否发起（无权限卡已整卡隐藏，免无谓 403 toast）；
 * 单卡失败 catch 留空态（拦截器已统一 toast） */
async function loadCards(): Promise<void> {
  const jobs: Promise<void>[] = []
  if (hasPerm('bpmn:task:list')) {
    todoLoading.value = true
    jobs.push(
      listTodoTasks()
        .then((list) => {
          todoRows.value = list.slice(0, 5)
        })
        .catch(() => {
          // 拦截器已统一 toast，卡片保持空态
        })
        .finally(() => {
          todoLoading.value = false
        }),
    )
  }
  if (hasPerm('bpmn:leave:list')) {
    leaveLoading.value = true
    jobs.push(
      pageLeave({ pageNum: 1, pageSize: 5 })
        .then((page) => {
          leaveRows.value = page.rows
        })
        .catch(() => {
          // 拦截器已统一 toast，卡片保持空态
        })
        .finally(() => {
          leaveLoading.value = false
        }),
    )
  }
  await Promise.all(jobs)
}

onMounted(() => {
  void loadCards()
})
</script>

<template>
  <!-- 两卡并置（D7）：等价 el-row gutter=16 / el-col md=12 布局用 flex + 媒体查询实现——
       el-row/el-col 会把 EP 全量响应式栅格样式（~36kB）拖进主 style，违 F6 主包 ±5% 门（偏离记档） -->
  <div class="dash-grid">
    <!-- 我的待办（契约 §6：数据源 /bpmn/task/todo 前 5；无 bpmn:task:list 整卡隐藏） -->
    <el-card shadow="never" class="dash-card" v-perms="'bpmn:task:list'">
      <template #header>
        <span>我的待办</span>
      </template>
      <div v-loading="todoLoading">
        <template v-if="todoRows.length">
          <div v-for="task in todoRows" :key="task.taskId" class="dash-row">
            <div class="dash-row-main">
              <span class="dash-row-title">{{ task.leaveTitle }}</span>
              <!-- 降级链（契约 §7）：译文 → 本地映射 → 原值 -->
              <el-tag size="small" type="info">
                {{ task.leaveTypeLabel ?? LEAVE_TYPE_MAP[task.leaveType] ?? task.leaveType }}
              </el-tag>
            </div>
            <div class="dash-row-meta">
              <span>{{ task.applyUserName ?? task.applyUser }}</span>
              <span>{{ task.createTime }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!todoLoading" description="暂无待办任务" :image-size="80" />
      </div>
      <template #footer>
        <el-button link type="primary" @click="router.push('/bpmn/task')">查看全部</el-button>
      </template>
    </el-card>

    <!-- 我的申请（契约 §6：数据源 /bpmn/leave/page 前 5；无 bpmn:leave:list 整卡隐藏） -->
    <el-card shadow="never" class="dash-card" v-perms="'bpmn:leave:list'">
      <template #header>
        <span>我的申请</span>
      </template>
      <div v-loading="leaveLoading">
        <template v-if="leaveRows.length">
          <div v-for="leave in leaveRows" :key="leave.id" class="dash-row">
            <div class="dash-row-main">
              <span class="dash-row-title">{{ leave.title }}</span>
              <!-- tag 颜色按原字段 status（译文不含颜色语义——契约 §1 红线） -->
              <el-tag size="small" :type="LEAVE_STATUS_TAG[leave.status] ?? 'info'">
                {{ leave.statusLabel ?? LEAVE_STATUS_MAP[leave.status] ?? leave.status }}
              </el-tag>
            </div>
            <div class="dash-row-meta">
              <span>{{ leave.createTime }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!leaveLoading" description="暂无申请记录" :image-size="80" />
      </div>
      <template #footer>
        <el-button link type="primary" @click="router.push('/bpmn/leave')">查看全部</el-button>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
/* 两卡并置（gutter 16px 等价），992px（EP md 断点同值）以下堆叠 */
.dash-grid {
  display: flex;
  gap: 16px;
  align-items: stretch;
}

.dash-grid > .dash-card {
  flex: 1;
  min-width: 0;
}

@media (max-width: 991.98px) {
  .dash-grid {
    flex-direction: column;
  }
}

.dash-card {
  height: 100%;
}

.dash-row {
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.dash-row:last-child {
  border-bottom: none;
}

.dash-row-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dash-row-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dash-row-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
