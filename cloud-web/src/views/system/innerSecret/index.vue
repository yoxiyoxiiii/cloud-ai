<script setup lang="ts">
/**
 * inner 密钥管理页（契约 2026-11-09-inner-secret-admin-api §1-§4，菜单种子 22 /system/inner-secret）：
 * - 状态卡（§2）只读回显 Nacos 轮换参数（拍板口径：无编辑端点）；redisConsistent=false 提示
 *   「等待自动重发」——发布落后由轮换任务一分钟内自愈
 * - 密钥流水（§1）追加式全量可见（含已过期/已作废行），status 过滤 + 分页；
 *   页面无密钥明文展示位（契约红线——仅 secretMask 掩码列）
 * - 立即轮换（§3）与作废（§4，≤30 秒生效的非瞬时语义）的 3050-3053 msg 由拦截器统一 toast；
 *   变更后列表与状态卡都重查（3052 软失败下变更已落库，重查对齐真态）
 * - 按钮 v-perms 两标识（rotate/revoke；list 隐含在菜单可见性）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getInnerSecretStatus, pageInnerSecret, revokeInnerSecret, rotateInnerSecret } from '../../../api/innerSecret'
import {
  INNER_SECRET_SOURCE_MAP,
  INNER_SECRET_STATUS_MAP,
  INNER_SECRET_STATUS_TAG,
  type InnerSecretPageQuery,
  type InnerSecretStatusVo,
  type InnerSecretVo,
} from '../../../types/api'

/** 组件名必须显式固定 = route.name（pathToRouteName('/system/inner-secret')='SystemInnerSecret'）：
 * script setup 推断名取文件名（全为 index），keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemInnerSecret' })

/** 状态下拉候选（值域固定，文案走 types 常量映射） */
const statusOptions = Object.entries(INNER_SECRET_STATUS_MAP).map(([value, label]) => ({ value: Number(value), label }))

/** EP 列插槽 row 固定 DefaultRow，全页断言集中此一处，模板禁散落裸 as */
function rowOf(row: unknown): InnerSecretVo {
  return row as InnerSecretVo
}

/** 可作废状态集（契约 §4）：仅活跃集内行可作废——3/4 为终态（UI 面不给出按钮，后端 3050 兜底） */
const REVOKABLE_STATUS: readonly number[] = [1, 2]

/* ============ 状态卡（§2 只读） ============ */

const status = ref<InnerSecretStatusVo>()

async function loadStatus(): Promise<void> {
  try {
    status.value = await getInnerSecretStatus()
  } catch {
    // 拦截器已统一 toast，状态卡保持现状
  }
}

/* ============ 密钥流水列表与过滤 ============ */

const loading = ref(false)
const rows = ref<InnerSecretVo[]>([])
const total = ref('0')
const filter = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
})

function buildQuery(): InnerSecretPageQuery {
  return {
    pageNum: filter.pageNum,
    pageSize: filter.pageSize,
    status: filter.status,
  }
}

async function loadPage(pageNum: number = filter.pageNum): Promise<void> {
  loading.value = true
  try {
    filter.pageNum = pageNum
    const page = await pageInnerSecret(buildQuery())
    rows.value = page.rows
    total.value = page.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function search(): void {
  void loadPage(1)
}

function resetFilter(): void {
  filter.status = undefined
  void loadPage(1)
}

/* ============ 立即轮换（§3）与作废（§4） ============ */

const rotating = ref(false)

async function handleRotate(): Promise<void> {
  rotating.value = true
  try {
    const row = await rotateInnerSecret()
    ElMessage.success(`已轮换至第 ${row.seq} 代`)
  } catch {
    // 拦截器已统一 toast（3053 频控剩余秒数 / 3052 发布失败——新钥已落库，重查对齐真态）
  } finally {
    rotating.value = false
    await Promise.all([loadPage(1), loadStatus()])
  }
}

async function handleRevoke(row: InnerSecretVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定作废第 ${row.seq} 代密钥（${row.secretMask}）吗？将立即断开重叠窗内的在途服务间调用，最迟约 30 秒内生效。作废即终态，不可恢复。`,
    '作废确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await revokeInnerSecret({ id: row.id })
    ElMessage.success(`第 ${row.seq} 代已作废，最迟约 30 秒内生效`)
  } catch {
    // 拦截器已统一 toast（3050 已退场 / 3051 最后一把生效密钥 / 3052 作废已落库发布自愈中）
  } finally {
    await Promise.all([loadPage(), loadStatus()])
  }
}

onMounted(() => {
  void loadPage(1)
  void loadStatus()
})
</script>

<template>
  <div class="inner-secret-page">
    <el-card shadow="never" class="status-card">
      <template #header>
        <div class="table-header">
          <span>轮换状态</span>
          <el-button v-perms="'system:innerSecret:rotate'" type="primary" :loading="rotating" @click="handleRotate">
            立即轮换
          </el-button>
        </div>
      </template>

      <el-descriptions v-if="status" :column="4" border size="small">
        <el-descriptions-item label="密钥有效期">{{ status.validityHours }} 小时</el-descriptions-item>
        <el-descriptions-item label="重叠窗">{{ status.overlapSeconds }} 秒</el-descriptions-item>
        <el-descriptions-item label="提前轮换预算">{{ status.leadHours }} 小时</el-descriptions-item>
        <el-descriptions-item label="当前生效代数">第 {{ status.currentSeq }} 代</el-descriptions-item>
        <el-descriptions-item label="活跃密钥数">{{ status.activeCount }} 把</el-descriptions-item>
        <el-descriptions-item label="预计下次轮换">
          {{ status.nextRotationTime ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="分发状态">
          <el-tag v-if="status.redisConsistent" type="success">分发正常</el-tag>
          <el-tag v-else type="warning">等待自动重发</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-else :image-size="48" description="状态加载中" />
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span>密钥流水</span>
      </template>

      <div class="filter-bar">
        <el-select v-model="filter.status" placeholder="状态（全部）" clearable class="filter-item">
          <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button @click="search">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>

      <ProTable
        :rows="rows"
        :loading="loading"
        :total="total"
        v-model:page-num="filter.pageNum"
        v-model:page-size="filter.pageSize"
        row-key="id"
        @page-change="loadPage()"
      >
        <el-table-column prop="seq" label="代数" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="INNER_SECRET_STATUS_TAG[rowOf(row).status] ?? 'info'">
              {{ INNER_SECRET_STATUS_MAP[rowOf(row).status] ?? rowOf(row).status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密钥掩码" width="120">
          <template #default="{ row }">
            <span class="mono-text">{{ rowOf(row).secretMask }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            {{ INNER_SECRET_SOURCE_MAP[rowOf(row).source] ?? rowOf(row).source }}
          </template>
        </el-table-column>
        <el-table-column prop="activateTime" label="生效时间" min-width="160" />
        <el-table-column prop="expireTime" label="过期时间" min-width="160" />
        <el-table-column label="退场时间" min-width="160">
          <template #default="{ row }">
            {{ rowOf(row).retireTime ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="创建人" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="REVOKABLE_STATUS.includes(rowOf(row).status)"
              v-perms="'system:innerSecret:revoke'"
              link
              type="danger"
              @click="handleRevoke(rowOf(row))"
            >
              作废
            </el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>
  </div>
</template>

<style scoped>
.inner-secret-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 8px;
  margin-bottom: 12px;
}

.filter-item {
  width: 180px;
}

.mono-text {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
