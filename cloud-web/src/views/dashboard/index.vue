<script setup lang="ts">
/**
 * 工作台两卡（契约 2026-11-14-aggregate-workbench-api §8 消费约定）：单请求 GET /aggregate/workbench
 * 拉三段（我的待办 / 我的审批 / 未读通知摘要），替代旧版对 /bpmn/task/todo 与 /bpmn/approval/page 的
 * 双请求并行编排——那两个端点保留给各自列表页，页内 hasPerm/v-perms 前端权限判断一并退役，
 * 卡片显隐职责移交段状态（契约 §8.2）：
 * - 段 status=200 → 渲染数据（段内无数据走既有空态文案）
 * - 段 status=7001（无权限）→ 整卡隐藏
 * - 段 status=7002（下游失败）→ 卡片保留，空态区展示段 msg（如「cloud-bpmn 服务不可用」）
 * - notify 段随单请求到达，界面消费归顶栏铃铛（30s 轮询本轮不动，合并机会项另轮评估）
 * 视觉与交互沿旧版形态不变（契约 §8.3）：卡标题/加载态/空态文案（「暂无待办任务」「暂无审批记录」）/
 * 「查看全部」跳转（/bpmn/task、/bpmn/approval）/状态 tag 颜色映射全保留；
 * 译文降级链沿审批平台契约 §9（statusLabel → 本地映射 → 原值）；刷新策略：进页拉取（实时推送另案）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getWorkbench } from '../../api/aggregate'
import {
  APPROVAL_STATUS_MAP,
  SECTION_STATUS_FORBIDDEN,
  SECTION_STATUS_OK,
  type ApprovalVo,
  type TaskVo,
  type WorkbenchVo,
} from '../../types/api'

/** 组件名显式固定 = route.name：script setup 推断名取文件名（全为 index），
 * keep-alive include 按组件名匹配会失效（已知坑，组件名与路由名必须一致） */
defineOptions({ name: 'Dashboard' })

/** 状态→tag 颜色映射（审批单 status 字典 bpmn_approval_status，我的审批页同款）：永远按原字段 status 取值；
 * 4=发起失败在本卡不可达（我的审批卡为 bpmn 域，bpmn_approval 永不落 4——契约 2026-10-09 §2.2，仅保字典镜像完整） */
const APPROVAL_STATUS_TAG: Record<string, 'warning' | 'success' | 'danger' | 'info'> = {
  '0': 'warning', // 审批中
  '1': 'success', // 已通过
  '2': 'danger', // 已拒绝
  '3': 'info', // 已撤销
  '4': 'danger', // 发起失败（仅 system 产生，本卡不可达）
}

const router = useRouter()

/** 单请求加载态：两卡 loading 同源（进页拉取期间保持卡片占位出加载态） */
const loading = ref(false)
/** 三段聚合响应（契约 §1.2）：null=尚未拿到响应；行数据与卡片显隐全部经 computed 派生 */
const workbench = ref<WorkbenchVo | null>(null)

/** 我的待办行（todo 段 data.rows）：仅在 status=200 分支解引用（契约红线：status≠200 时 data 恒 null） */
const todoRows = computed<TaskVo[]>(() => {
  const section = workbench.value?.todo
  if (!section || section.status !== SECTION_STATUS_OK || !section.data) return []
  return section.data.rows
})

/** 我的审批行（approval 段 data.rows）：解引用收口口径同 todoRows */
const approvalRows = computed<ApprovalVo[]>(() => {
  const section = workbench.value?.approval
  if (!section || section.status !== SECTION_STATUS_OK || !section.data) return []
  return section.data.rows
})

/**
 * 卡片显隐（契约 §8.2，显隐职责在段状态）：加载中恒显示（占位出加载态，沿旧版视觉）；
 * 加载完成后段无权限（7001）或段缺失（整体失败/响应异常）→ 整卡隐藏
 */
const todoCardVisible = computed(() => {
  if (loading.value) return true
  const section = workbench.value?.todo
  return !!section && section.status !== SECTION_STATUS_FORBIDDEN
})

const approvalCardVisible = computed(() => {
  if (loading.value) return true
  const section = workbench.value?.approval
  return !!section && section.status !== SECTION_STATUS_FORBIDDEN
})

/** 空态文案（契约 §8.3：200 无数据保留「暂无待办任务」；7002 等失败态展示段 msg） */
const todoEmptyText = computed(() => {
  const section = workbench.value?.todo
  return section && section.status !== SECTION_STATUS_OK ? section.msg : '暂无待办任务'
})

const approvalEmptyText = computed(() => {
  const section = workbench.value?.approval
  return section && section.status !== SECTION_STATUS_OK ? section.msg : '暂无审批记录'
})

/** 单请求拉三段（契约 §8.1-8.2）：成功落 workbench 由 computed 派生行数据/显隐/空态；
 * 失败 catch 留白（拦截器已统一 toast，workbench 保持 null → 两卡按无段口径隐藏） */
async function loadWorkbench(): Promise<void> {
  loading.value = true
  try {
    workbench.value = await getWorkbench()
  } catch {
    // 拦截器已统一 toast；段全空，两卡按无段口径隐藏
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadWorkbench()
})
</script>

<template>
  <!-- 两卡并置（等价 el-row gutter=16 / el-col md=12 布局用 flex + 媒体查询实现——
       el-row/el-col 会把 EP 全量响应式栅格样式（~36kB）拖进主 style，违主包 ±5% 门（偏离记档） -->
  <div class="dash-grid">
    <!-- 我的待办：显隐由 todo 段 status 驱动（7001 无权限整卡隐藏，契约 §8.2） -->
    <el-card v-if="todoCardVisible" shadow="never" class="dash-card">
      <template #header>
        <span>我的待办</span>
      </template>
      <div v-loading="loading">
        <template v-if="todoRows.length">
          <div v-for="task in todoRows" :key="task.taskId" class="dash-row">
            <div class="dash-row-main">
              <span class="dash-row-title">{{ task.title }}</span>
              <!-- 业务类型名（配置表 join 必返非空，审批平台契约 §9），无降级链 -->
              <el-tag size="small" type="info">{{ task.businessTypeName }}</el-tag>
            </div>
            <div class="dash-row-meta">
              <span>{{ task.applyUserName ?? task.applyUser }}</span>
              <span>{{ task.createTime }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!loading" :description="todoEmptyText" :image-size="80" />
      </div>
      <template #footer>
        <el-button link type="primary" @click="router.push('/bpmn/task')">查看全部</el-button>
      </template>
    </el-card>

    <!-- 我的审批：显隐由 approval 段 status 驱动（7001 无权限整卡隐藏，契约 §8.2） -->
    <el-card v-if="approvalCardVisible" shadow="never" class="dash-card">
      <template #header>
        <span>我的审批</span>
      </template>
      <div v-loading="loading">
        <template v-if="approvalRows.length">
          <div v-for="approval in approvalRows" :key="approval.id" class="dash-row">
            <div class="dash-row-main">
              <span class="dash-row-title">{{ approval.title }}</span>
              <!-- tag 颜色按原字段 status（译文不含颜色语义——审批平台契约 §1 红线） -->
              <el-tag size="small" :type="APPROVAL_STATUS_TAG[approval.status] ?? 'info'">
                {{ approval.statusLabel ?? APPROVAL_STATUS_MAP[approval.status] ?? approval.status }}
              </el-tag>
            </div>
            <div class="dash-row-meta">
              <span>{{ approval.businessTypeName }}</span>
              <span>{{ approval.createTime }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!loading" :description="approvalEmptyText" :image-size="80" />
      </div>
      <template #footer>
        <el-button link type="primary" @click="router.push('/bpmn/approval')">查看全部</el-button>
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
