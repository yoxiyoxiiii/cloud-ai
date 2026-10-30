<script setup lang="ts">
/**
 * 工具审核抽屉（契约 2026-10-30-mcp-server-mgmt-api §2.8 工具清单 + §2.9 工具审核）：
 * 列表行「工具」操作打开，按 server_id 过展该服务名下工具（含草稿/失效）；
 * 草稿/已发布行带「审核」按钮（v-perms ai:mcp:update，失效行无审核入口——复活须先同步回草稿）；
 * 展开行显示 description 与 input_schema（el-collapse 展开查 JSON）；
 * 审核小弹窗：required_perms 多选自由输入（格式 svc:entity:action）+ 可选显示名，
 * 提交 PUT review 置已发布；已发布工具重复审核=改 perms/显示名。
 * 5027 工具不存在 / 5028 schema 不可构建（停留草稿）拦截器统一 toast
 */
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { listMcpTools, reviewMcpTool } from '../../../../api/mcp'
import type { McpServerSummaryVo, McpToolManageVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 目标服务行（取 id 与名称）；可缺省——标题插值空表下兜底 */
  server?: McpServerSummaryVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 审核成功（父页面刷新 tool_counts 摘要） */
  reviewed: []
}>()

/** 工具状态（契约 §2.8）：0=草稿 1=已发布 2=已失效 */
const TOOL_STATUS_DRAFT = 0
const TOOL_STATUS_PUBLISHED = 1
const TOOL_STATUS_MISSING = 2

/** 契约 §2.9 perms 项格式与限额：≤5 项、每项 ≤100 字符、^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$ */
const PERM_ITEM_PATTERN = /^[a-z]+:[a-zA-Z]+:[a-zA-Z]+$/
const PERM_MAX_ITEMS = 5
const PERM_ITEM_MAX = 100
const TOOL_NAME_MAX = 50

const STATUS_MAP: Record<number, { label: string; tagType: 'info' | 'success' | 'danger' }> = {
  [TOOL_STATUS_DRAFT]: { label: '草稿', tagType: 'info' },
  [TOOL_STATUS_PUBLISHED]: { label: '已发布', tagType: 'success' },
  [TOOL_STATUS_MISSING]: { label: '已失效', tagType: 'danger' },
}

/** EP el-table-column 插槽 row 固定 DefaultRow，全页断言集中此一处，模板/处理函数禁止再散落裸 as */
function rowOf(row: unknown): McpToolManageVo {
  return row as McpToolManageVo
}

const loading = ref(false)
const rows = ref<McpToolManageVo[]>([])

/** 工具清单（契约 §2.8，perms ai:mcp:list）：按 server 过展，不分页；失败拦截器已 toast */
async function load(): Promise<void> {
  if (!props.server) {
    return
  }
  loading.value = true
  try {
    rows.value = await listMcpTools({ server_id: props.server.id })
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

/** 打开时加载（每次打开取最新清单） */
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void load()
    }
  },
)

/* ============ 审核小弹窗（契约 §2.9） ============ */

const reviewVisible = ref(false)
const reviewLoading = ref(false)
const reviewFormRef = ref<FormInstance>()
const reviewTarget = ref<McpToolManageVo>()
const reviewForm = reactive({
  /** 空数组=登录即可（契约合法态，非必填一项） */
  required_perms: [] as string[],
  name: '',
})

/** 打开审核：回显当前 perms 与显示名 */
function openReview(tool: McpToolManageVo): void {
  reviewTarget.value = tool
  reviewForm.required_perms = [...tool.required_perms]
  reviewForm.name = tool.name
  reviewVisible.value = true
  reviewFormRef.value?.clearValidate()
}

/** perms 项校验（契约 §2.9 约束，el-select allow-create 自由输入须提交前拦）：返回错误文案或 null */
function permsError(): string | null {
  if (reviewForm.required_perms.length > PERM_MAX_ITEMS) {
    return `权限标识最多 ${PERM_MAX_ITEMS} 项`
  }
  for (const p of reviewForm.required_perms) {
    if (!p.trim()) {
      return '权限标识不能为空'
    }
    if (p.length > PERM_ITEM_MAX) {
      return `权限标识 "${p}" 超过 ${PERM_ITEM_MAX} 字符`
    }
    if (!PERM_ITEM_PATTERN.test(p)) {
      return `权限标识 "${p}" 格式须为 svc:entity:action（如 system:user:list）`
    }
  }
  return null
}

/** 提交审核：校验 → PUT review（发布/改配置）→ 关弹窗刷新清单 */
async function handleReviewSubmit(): Promise<void> {
  if (reviewLoading.value || !reviewTarget.value) {
    return
  }
  const err = permsError()
  if (err) {
    ElMessage.error(err)
    return
  }
  const displayName = reviewForm.name.trim()
  if (displayName.length > TOOL_NAME_MAX) {
    ElMessage.error(`显示名不超过 ${TOOL_NAME_MAX} 个字符`)
    return
  }
  reviewLoading.value = true
  try {
    await reviewMcpTool(reviewTarget.value.id, {
      required_perms: reviewForm.required_perms.map((p) => p.trim()),
      name: displayName || undefined,
    })
    ElMessage.success('审核完成，工具已发布')
    reviewVisible.value = false
    await load()
    emit('reviewed')
  } catch {
    // 拦截器已统一 toast（5027/5028 停留草稿可重试）；弹窗保持打开
  } finally {
    reviewLoading.value = false
  }
}

/** input_schema 对象转格式化 JSON（展开行展示用；序列化失败降级 String） */
function schemaText(schema: object): string {
  try {
    return JSON.stringify(schema, null, 2)
  } catch {
    return String(schema)
  }
}
</script>

<template>
  <!-- append-to-body：抽屉挂 body，避免叠加在管理页卡片内被裁剪（沿 AgentRunDrawer 先例） -->
  <el-drawer
    :model-value="modelValue"
    :title="props.server ? `工具清单 · ${props.server.name}` : '工具清单'"
    size="62%"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-table v-loading="loading" :data="rows" row-key="id" border empty-text="暂无工具（同步后产生草稿）">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="tool-detail">
            <el-collapse>
              <el-collapse-item title="工具描述" name="desc">
                <div class="desc-text">{{ rowOf(row).description || '-' }}</div>
              </el-collapse-item>
              <el-collapse-item title="入参 Schema（JSON）" name="schema">
                <pre class="schema-pre">{{ schemaText(rowOf(row).input_schema) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="工具标识" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).tool_key }}</template>
      </el-table-column>
      <el-table-column label="显示名" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).name }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'">
            {{ STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="所需权限" min-width="180">
        <template #default="{ row }">
          <!-- 空数组=登录即可（契约 §2.8 合法态） -->
          <template v-if="rowOf(row).required_perms.length > 0">
            <el-tag v-for="p in rowOf(row).required_perms" :key="p" size="small" type="warning" class="perm-tag">
              {{ p }}
            </el-tag>
          </template>
          <span v-else class="plain-mark">登录即可</span>
        </template>
      </el-table-column>
      <el-table-column label="最近同步/审核" min-width="160">
        <template #default="{ row }">{{ rowOf(row).update_time ?? rowOf(row).create_time ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <!-- 草稿/已发布可审核（已发布重复审核=改配置）；失效行无入口（复活走同步回草稿）；
               按钮显隐另由 v-perms ai:mcp:update 控制 -->
          <el-button
            v-if="rowOf(row).status === TOOL_STATUS_DRAFT || rowOf(row).status === TOOL_STATUS_PUBLISHED"
            v-perms="'ai:mcp:update'"
            link
            type="primary"
            @click="openReview(rowOf(row))"
          >
            审核
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 审核小弹窗：perms 多选自由输入 + 可选显示名（append-to-body 脱离抽屉层叠） -->
    <el-dialog
      v-model="reviewVisible"
      :title="reviewTarget ? `审核工具 · ${reviewTarget.tool_key}` : '审核工具'"
      width="560px"
      append-to-body
    >
      <el-form ref="reviewFormRef" :model="reviewForm" label-width="90px">
        <el-form-item label="所需权限">
          <el-select
            v-model="reviewForm.required_perms"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入权限标识后回车添加（如 system:user:list）；留空 = 登录即可"
            class="full-width"
          >
            <!-- 自由输入为主，预置常用项降低手输成本 -->
            <el-option v-for="p in ['ai:mcp:list', 'ai:agent:list', 'system:user:list']" :key="p" :value="p" :label="p" />
          </el-select>
          <div class="field-hint-block">
            格式 svc:entity:action，最多 {{ PERM_MAX_ITEMS }} 项；审核通过后工具对 agent 可见
          </div>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="reviewForm.name" :maxlength="TOOL_NAME_MAX" placeholder="可选，默认为工具标识" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="reviewLoading" @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewLoading" @click="handleReviewSubmit">发布</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.tool-detail {
  padding: 4px 12px;
}
.desc-text {
  color: #606266;
  font-size: 13px;
  word-break: break-all;
}
.schema-pre {
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.perm-tag {
  margin-right: 4px;
}
.perm-tag:last-child {
  margin-right: 0;
}
.plain-mark {
  color: #909399;
  font-size: 12px;
}
.full-width {
  width: 100%;
}
.field-hint-block {
  width: 100%;
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
}
</style>
