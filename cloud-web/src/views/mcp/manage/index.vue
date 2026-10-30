<script setup lang="ts">
/**
 * MCP 服务管理页（/mcp/manage，契约 2026-10-30-mcp-server-mgmt-api §2 管理面九端点）：
 * 标准表格管理页——搜索栏（§2.1 name 包含匹配）+ 操作条（§2.2 新增）+ ProTable 列表
 * （名称/描述/地址/传输/内置标记/工具计数摘要/状态开关/创建时间/操作）；
 * 状态开关走 §2.4 全量编辑（先拉 §2.3 详情合并提交——列表行无 headers/凭据/超时，
 * PUT 全量覆盖语义不能只发 status）；操作列四类动作分立（perms §0：update/sync/delete
 * 按钮级控制，「工具」为查询类动作不挂按钮级权限）；删除对内置行禁用按钮（后端 5022 拦）；
 * 同步报告（§2.7）toast 展示新增/更新/失效计数，conflicts 非空以告警框列出；
 * 错误分支（403/1001/5021-5026）拦截器统一 toast，页面 catch 留空
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteMcpServer, getMcpServer, pageMcpServers, syncMcpServer, testMcpConfig, updateMcpServer } from '../../../api/mcp'
import type { CreateMcpServerPayload, McpServerSummaryVo } from '../../../types/api'
import { hasPerm } from '../../../stores/perm'
import ServerEditDialog from './components/ServerEditDialog.vue'
import type { ServerEditMode } from './components/ServerEditDialog.vue'
import ToolReviewDrawer from './components/ToolReviewDrawer.vue'

/** = pathToRouteName('/mcp/manage')，keep-alive include 依赖，不可省 */
defineOptions({ name: 'McpManage' })

/** MCP 服务状态（契约 §1）：0=启用 1=停用 */
const STATUS_ENABLED = 0
const STATUS_DISABLED = 1

/** 契约 §2.1 name 搜索长度上限 30（搜索框 maxlength 与后端校验同值） */
const NAME_MAX = 30

/**
 * EP el-table-column 插槽 row 固定 DefaultRow，全页断言集中此一处，
 * 模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): McpServerSummaryVo {
  return row as McpServerSummaryVo
}

const loading = ref(false)
const rows = ref<McpServerSummaryVo[]>([])
/** AI 域 total 为 number（契约沿 agent 契约 §0），ProTable 内部归一直取 */
const total = ref(0)
const query = reactive({ page: 1, size: 10, name: '' })

/** 状态列形态：有编辑权限显示开关（操作入口），无权限只读 tag（v-perms 是移除语义，开关场景需二态渲染） */
const canUpdate = computed(() => hasPerm('ai:mcp:update'))

/** 加载分页（契约 §2.1）：name 仅非空时携带（空串与不传同为不过滤） */
async function load(page: number = query.page): Promise<void> {
  loading.value = true
  try {
    query.page = page
    const result = await pageMcpServers({
      page: query.page,
      size: query.size,
      name: query.name.trim() || undefined,
    })
    rows.value = result.rows
    total.value = result.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function search(): void {
  void load(1)
}

function resetFilter(): void {
  query.name = ''
  void load(1)
}

function handlePageChange(): void {
  void load()
}

/* ============ 新建/编辑弹窗 ============ */

const formVisible = ref(false)
const formMode = ref<ServerEditMode>('add')
const formServer = ref<McpServerSummaryVo>()

function openAdd(): void {
  formMode.value = 'add'
  formServer.value = undefined
  formVisible.value = true
}

function openEdit(row: McpServerSummaryVo): void {
  formMode.value = 'edit'
  formServer.value = row
  formVisible.value = true
}

/** 弹窗成功：新建回第一页（id 倒序新行居首可见），编辑刷新当前页（行位不变） */
function handleFormSuccess(): void {
  if (formMode.value === 'add') {
    void load(1)
  } else {
    void load()
  }
}

/* ============ 状态开关（契约 §2.4 全量编辑：详情合并提交） ============ */

/**
 * 行内启用/停用：PUT 全量覆盖语义要求带全部字段——先拉 §2.3 详情再合并新状态提交；
 * :model-value 由行数据派生，失败不落本地态（开关自动回弹），成功写回行数据
 */
async function handleStatusChange(row: McpServerSummaryVo, enabled: boolean): Promise<void> {
  const next = enabled ? STATUS_ENABLED : STATUS_DISABLED
  try {
    const detail = await getMcpServer(row.id)
    const payload: CreateMcpServerPayload = {
      name: detail.name,
      url: detail.url,
      description: detail.description ?? undefined,
      transport: detail.transport,
      headers: Object.keys(detail.headers).length > 0 ? detail.headers : undefined,
      credential_headers: Object.keys(detail.credential_headers).length > 0 ? detail.credential_headers : undefined,
      timeout_ms: detail.timeout_ms,
    }
    await updateMcpServer(row.id, { ...payload, status: next })
    row.status = next
    ElMessage.success(next === STATUS_ENABLED ? '已启用' : '已停用')
  } catch {
    // 拦截器已统一 toast；行数据未写回，开关派生值自动回弹
  }
}

/* ============ 行内测试连接（契约 §2.6，按行配置直连远端） ============ */

/** 测试行所在服务：详情端点取完整配置（列表行无 headers/凭据/超时）再发起测试 */
async function handleTest(row: McpServerSummaryVo): Promise<void> {
  try {
    const detail = await getMcpServer(row.id)
    const result = await testMcpConfig({
      name: detail.name,
      url: detail.url,
      description: detail.description ?? undefined,
      transport: detail.transport,
      headers: Object.keys(detail.headers).length > 0 ? detail.headers : undefined,
      credential_headers: Object.keys(detail.credential_headers).length > 0 ? detail.credential_headers : undefined,
      timeout_ms: detail.timeout_ms,
    })
    ElMessage.success(
      result.tool_count > 0
        ? `连接成功：发现 ${result.tool_count} 个工具（${result.sample_tools.join('、')}）`
        : '连接成功：远端未列出工具',
    )
  } catch {
    // 拦截器已统一 toast（5025 连接失败/5026 凭据未配置）
  }
}

/* ============ 工具同步（契约 §2.7：拉远端清单 upsert 草稿/失效状态机） ============ */

/** 同步后行数据刷新（tool_counts 摘要实时聚合） */
async function handleSync(row: McpServerSummaryVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定同步服务 "${row.name}" 的工具清单吗？将按远端 tools/list 更新草稿并置失效缺失项。`,
    '同步确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    const report = await syncMcpServer(row.id)
    ElMessage.success(`同步完成：新增 ${report.added} / 更新 ${report.updated} / 失效 ${report.missing}`)
    if (report.conflicts.length > 0) {
      // 跨 server 撞 tool_key 被跳过的工具（契约 §2.7 conflicts）：告警框列出供管理员处置
      await ElMessageBox.alert(`以下工具与其它服务撞名，本次同步已跳过：${report.conflicts.join('、')}`, '同步冲突', {
        type: 'warning',
      }).catch(() => undefined)
    }
    await load()
  } catch {
    // 拦截器已统一 toast（5021/5024 未启用/5025 连接失败/5026 凭据未配置，库内零变更）
  }
}

/* ============ 删除（契约 §2.5 软删：工具行保留，对 agent 运行不可见） ============ */

async function handleDelete(row: McpServerSummaryVo): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定删除 MCP 服务 "${row.name}" 吗？其名下工具行将保留但对智能体运行不可见。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteMcpServer(row.id)
    ElMessage.success('删除成功')
    // 删空回退：当前页仅剩此行且非首页 → 回上一页再查（防空页滞留）
    if (rows.value.length <= 1 && query.page > 1) {
      await load(query.page - 1)
    } else {
      await load()
    }
  } catch {
    // 拦截器已统一 toast（如 5022 内置服务不可删——前端已对内置行禁用按钮，此为兜底）
  }
}

/* ============ 工具审核抽屉（契约 §2.8/§2.9） ============ */

const drawerVisible = ref(false)
const drawerServer = ref<McpServerSummaryVo>()

function openTools(row: McpServerSummaryVo): void {
  drawerServer.value = row
  drawerVisible.value = true
}

/** 审核成功后刷新列表（tool_counts 摘要变化） */
function handleReviewed(): void {
  void load()
}

onMounted(() => {
  void load(1)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>MCP 服务管理</span>
      </div>
    </template>

    <!-- 搜索栏 + 操作条同排：左 name 搜索（§2.1 包含匹配）右新增（§2.2 权限 ai:mcp:add） -->
    <div class="toolbar">
      <div class="filter-bar">
        <el-input
          v-model="query.name"
          placeholder="服务名（模糊）"
          clearable
          :maxlength="NAME_MAX"
          class="filter-input"
          @keyup.enter="search"
        />
        <el-button @click="search">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>
      <el-button v-perms="'ai:mcp:add'" type="primary" @click="openAdd">新增服务</el-button>
    </div>

    <!-- row-key：新增行 id 倒序居首，避免按 index 原地 patch 残留旧节点；border 为列宽可读性 -->
    <ProTable
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page-num="query.page"
      v-model:page-size="query.size"
      row-key="id"
      border
      empty-text="暂无 MCP 服务"
      @page-change="handlePageChange"
    >
      <el-table-column label="名称" min-width="140">
        <template #default="{ row }">
          {{ rowOf(row).name }}
          <!-- 内置平台服务标记（删除/改名受限） -->
          <el-tag v-if="rowOf(row).is_builtin === 1" size="small" type="warning" class="builtin-tag">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).description ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="端点地址" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).url }}</template>
      </el-table-column>
      <el-table-column label="传输" width="80">
        <template #default="{ row }">{{ rowOf(row).transport }}</template>
      </el-table-column>
      <el-table-column label="工具(草/发/失)" width="120" align="center">
        <template #default="{ row }">{{ rowOf(row).tool_counts }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <!-- 开关是状态编辑入口（需 ai:mcp:update）；只读用户降级 tag 展示 -->
          <!-- :model-value 受控 + change 全量提交：失败不写回行数据，开关视觉自动回弹旧态 -->
          <el-switch
            v-if="canUpdate"
            :model-value="rowOf(row).status === STATUS_ENABLED"
            @change="(enabled: string | number | boolean) => handleStatusChange(rowOf(row), Boolean(enabled))"
          />
          <el-tag v-else :type="rowOf(row).status === STATUS_ENABLED ? 'success' : 'danger'">
            {{ rowOf(row).status === STATUS_ENABLED ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).create_time ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button v-perms="'ai:mcp:update'" link type="primary" @click="openEdit(rowOf(row))">编辑</el-button>
          <!-- 保存后测试与同步同挂 ai:mcp:sync（契约 §0 分段表） -->
          <el-button v-perms="'ai:mcp:sync'" link type="primary" @click="handleTest(rowOf(row))">测试</el-button>
          <el-button v-perms="'ai:mcp:sync'" link type="primary" @click="handleSync(rowOf(row))">同步</el-button>
          <!-- 工具清单/审核入口为查询类动作：页面可达即有 ai:mcp:list，不挂按钮级权限 -->
          <el-button link type="primary" @click="openTools(rowOf(row))">工具</el-button>
          <el-button
            v-perms="'ai:mcp:delete'"
            link
            type="danger"
            :disabled="rowOf(row).is_builtin === 1"
            @click="handleDelete(rowOf(row))"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </ProTable>

    <ServerEditDialog v-model="formVisible" :mode="formMode" :server="formServer" @success="handleFormSuccess" />
    <ToolReviewDrawer v-model="drawerVisible" :server="drawerServer" @reviewed="handleReviewed" />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-input {
  width: 220px;
}
.builtin-tag {
  margin-left: 4px;
}
</style>
