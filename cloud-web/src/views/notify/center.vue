<script setup lang="ts">
/**
 * 消息中心页（契约 2026-11-08-notify-api §3.2-§3.4，用户侧——登录即可，不挂 sys_menu）：
 * 静态路由 /notify/center（契约 §5：全员功能不能靠按角色绑定的菜单树分发），
 * 入口=Navbar 铃铛下拉「查看全部」。
 * - 列表：readFlag tab 式（全部/未读/已读）+ type 下拉过滤；行操作=已读（仅未读行，
 *   后端幂等）+ 跳转（linkUrl 有值行；点击即标已读再 router.push——与弹框点击同语义）
 * - 「全部已读」走 notify store（成功 toast「已读 N 条」+ 未读数同步归零，Navbar 角标联动）
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { pageMyNotify } from '../../api/notify'
import { useNotifyStore } from '../../stores/notify'
import {
  NOTIFY_LEVEL_MAP,
  NOTIFY_LEVEL_TAG,
  NOTIFY_TYPE_MAP,
  type NotifyUserMessagePageQuery,
  type NotifyUserMessageVo,
} from '../../types/api'

defineOptions({ name: 'NotifyCenter' })

const router = useRouter()
const notifyStore = useNotifyStore()

/** 已读标记（契约 §3.2）：0=未读 1=已读 */
const READ_UNREAD = 0
const READ_READ = 1

/** readFlag tab 哨兵：-1=全部（本地态，入参转 undefined——契约可选过滤） */
const READ_TAB_ALL = -1

/** type 下拉候选（值域固定） */
const typeOptions = Object.entries(NOTIFY_TYPE_MAP).map(([value, label]) => ({ value: Number(value), label }))

/** EP 列插槽 row 固定 DefaultRow，全页断言集中此一处，模板禁散落裸 as */
function rowOf(row: unknown): NotifyUserMessageVo {
  return row as NotifyUserMessageVo
}

/* ============ 列表与过滤 ============ */

const loading = ref(false)
const rows = ref<NotifyUserMessageVo[]>([])
const total = ref('0')
const filter = reactive({
  pageNum: 1,
  pageSize: 10,
  readTab: READ_TAB_ALL as number,
  type: undefined as number | undefined,
})

function buildQuery(): NotifyUserMessagePageQuery {
  return {
    pageNum: filter.pageNum,
    pageSize: filter.pageSize,
    readFlag: filter.readTab === READ_TAB_ALL ? undefined : filter.readTab,
    type: filter.type,
  }
}

async function loadPage(pageNum: number = filter.pageNum): Promise<void> {
  loading.value = true
  try {
    filter.pageNum = pageNum
    const page = await pageMyNotify(buildQuery())
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
  filter.readTab = READ_TAB_ALL
  filter.type = undefined
  void loadPage(1)
}

/* ============ 行操作 ============ */

/** 单条已读（幂等——重复调用后端成功不报错）；完成后重拉当前过滤视图（未读 tab 下行离开集合） */
async function handleRead(row: NotifyUserMessageVo): Promise<void> {
  try {
    await notifyStore.markRead(row.id)
    await loadPage()
  } catch {
    // store markRead 失败静默（行按钮链路的拦截器 toast 已由 readNotify 统一弹出）
  }
}

/** 跳转（与弹框点击同语义）：先标已读（失败静默）再进目标路由（模板 v-if 已保证 linkUrl 非空） */
function handleJump(row: NotifyUserMessageVo): void {
  void notifyStore.markRead(row.id)
  if (row.linkUrl) {
    void router.push(row.linkUrl)
  }
}

/** 全部已读（契约 §3.4）：toast「已读 N 条」+ 未读数归零（store 内）+ 当前视图重拉 */
async function handleReadAll(): Promise<void> {
  try {
    const count = await notifyStore.markReadAll()
    ElMessage.success(`已读 ${count} 条`)
    await loadPage()
  } catch {
    // 拦截器已统一 toast
  }
}

onMounted(() => {
  void loadPage(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>消息中心</span>
        <el-button
          type="primary"
          :disabled="notifyStore.unreadCount === 0 || loading"
          @click="handleReadAll"
        >
          全部已读
        </el-button>
      </div>
    </template>

    <div class="filter-bar">
      <el-radio-group v-model="filter.readTab" @change="search">
        <el-radio-button :value="READ_TAB_ALL">全部</el-radio-button>
        <el-radio-button :value="READ_UNREAD">未读</el-radio-button>
        <el-radio-button :value="READ_READ">已读</el-radio-button>
      </el-radio-group>
      <el-select v-model="filter.type" placeholder="消息类型（全部）" clearable class="filter-item">
        <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
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
      <el-table-column label="标题" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ 'notify-unread-title': rowOf(row).readFlag === READ_UNREAD }">
            {{ rowOf(row).title }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="消息类型" width="90">
        <template #default="{ row }">
          <el-tag :type="rowOf(row).type === 1 ? 'primary' : 'success'">
            {{ NOTIFY_TYPE_MAP[rowOf(row).type] ?? rowOf(row).type }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="NOTIFY_LEVEL_TAG[rowOf(row).level] ?? 'info'">
            {{ NOTIFY_LEVEL_MAP[rowOf(row).level] ?? rowOf(row).level }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="已读" width="80">
        <template #default="{ row }">
          <el-tag :type="rowOf(row).readFlag === READ_READ ? 'info' : 'danger'">
            {{ rowOf(row).readFlag === READ_READ ? '已读' : '未读' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sendTime" label="发送时间" min-width="160" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="rowOf(row).readFlag === READ_UNREAD"
            link
            type="primary"
            @click="handleRead(rowOf(row))"
          >
            已读
          </el-button>
          <el-button v-if="rowOf(row).linkUrl" link type="primary" @click="handleJump(rowOf(row))">
            跳转
          </el-button>
        </template>
      </el-table-column>
    </ProTable>
  </el-card>
</template>

<style scoped>
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
  width: 160px;
}

.notify-unread-title {
  font-weight: 600;
}
</style>
