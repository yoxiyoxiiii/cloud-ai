<script setup lang="ts">
/**
 * 数据权限管理页（设计 §8 / 契约 2026-10-10-data-permission-api §3）：el-tabs 三 tab
 * ① 规则配置：资源/主体类型/主体筛选 + 规则表格 + 新增（角色管理页联动入口带 query 预筛选）
 * ② 决策留痕：账号/资源筛选 + 留痕表格（deny 行红 tag）+ 分页（explain/my-scope 不留痕，不在本表）
 * ③ 模拟解释：账号+资源 → narratives 时间线 + 命中规则表 + 列决策表
 * 规则即时生效（每请求实时求值无快照——契约 §1）；配置/删除走 RuleConfigDialog（upsert 全量覆盖 §3.3）
 */
import { reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteRule,
  explainDataPerm,
  listResources,
  listSubjectOptions,
  pageDataPermLog,
  pageRule,
} from '../../../api/dataPerm'
import type {
  ColumnRuleVo,
  DataPermExplainVo,
  DataPermLogVo,
  DataPermResourceVo,
  DataPermRuleVo,
  SubjectOptionVo,
} from '../../../types/api'
import {
  ACTION_HIDDEN,
  ACTION_MASKED,
  OP_DENY,
  OP_DETAIL,
  OP_LIST,
  ROW_SCOPE_ALL,
  ROW_SCOPE_CUSTOM,
  ROW_SCOPE_DEPT,
  ROW_SCOPE_DEPT_AND_CHILD,
  ROW_SCOPE_SELF,
  SUBJECT_ROLE,
  SUBJECT_USER,
} from '../../../types/api'
import RuleConfigDialog from './components/RuleConfigDialog.vue'
import type { RuleConfigMode } from './components/RuleConfigDialog.vue'

/** 组件名必须显式固定 = route.name（pathToRouteName('/system/data-perm')='SystemDataPerm'）：
 * script setup 推断名取文件名（全为 index），keep-alive include 按组件名匹配会失效（动态路由设计 D9 红字坑） */
defineOptions({ name: 'SystemDataPerm' })

/** 主体类型映射（契约 §1：0=角色 1=用户） */
const SUBJECT_TYPE_MAP: Record<number, { label: string; tagType: 'primary' | 'warning' }> = {
  [SUBJECT_ROLE]: { label: '角色', tagType: 'primary' },
  [SUBJECT_USER]: { label: '用户', tagType: 'warning' },
}

/** 行档位映射（契约 §1：0-4 五档；未知值 fallback info + 原值） */
const ROW_SCOPE_MAP: Record<number, { label: string; tagType: 'info' | 'primary' | 'warning' | 'success' }> = {
  [ROW_SCOPE_SELF]: { label: '仅自己', tagType: 'info' },
  [ROW_SCOPE_DEPT]: { label: '本部门', tagType: 'primary' },
  [ROW_SCOPE_DEPT_AND_CHILD]: { label: '本部门及以下', tagType: 'primary' },
  [ROW_SCOPE_CUSTOM]: { label: '自定义集合', tagType: 'warning' },
  [ROW_SCOPE_ALL]: { label: '全部', tagType: 'success' },
}

/** 留痕操作映射（契约 §6.6：list/detail/deny——deny 为详情被拒补记，红） */
const OP_MAP: Record<string, { label: string; tagType: 'primary' | 'success' | 'danger' }> = {
  [OP_LIST]: { label: '列表', tagType: 'primary' },
  [OP_DETAIL]: { label: '详情', tagType: 'success' },
  [OP_DENY]: { label: '拒绝', tagType: 'danger' },
}

/** 列动作中文（契约 §1：0 隐藏 / 1 脱敏） */
const ACTION_MAP: Record<number, string> = {
  [ACTION_HIDDEN]: '隐藏',
  [ACTION_MASKED]: '脱敏',
}

/** EP 列插槽 row 固定 DefaultRow，全页断言集中两处（规则表/留痕表），模板禁散落裸 as */
function ruleRowOf(row: unknown): DataPermRuleVo {
  return row as DataPermRuleVo
}

function logRowOf(row: unknown): DataPermLogVo {
  return row as DataPermLogVo
}

/** 从主体选项 label（后端拼好的 `昵称(账号)`，契约 §6.5）提取账号——explain 入参值域是账号串（§3.8） */
function accountOfLabel(label: string): string {
  const matched = /\(([^)]+)\)$/.exec(label)
  return matched ? matched[1] : label
}

/** 列摘要串（`reason:脱敏;title:隐藏` 形态直显；空数组 '-'）——与留痕 columnSummary 同风格 */
function columnSummaryOf(columns: ColumnRuleVo[]): string {
  if (columns.length === 0) {
    return '-'
  }
  return columns.map((c) => `${c.columnKey}:${ACTION_MAP[c.action] ?? c.action}`).join(';')
}

const route = useRoute()
const activeTab = ref('rule')

/** 资源注册表（筛选与解释 tab 数据源，契约 §3.5） */
const resources = ref<DataPermResourceVo[]>([])

async function loadResources(): Promise<void> {
  try {
    resources.value = resources.value.length > 0 ? resources.value : await listResources()
  } catch {
    // 拦截器已统一 toast；筛选/解释候选为空不阻断页面
  }
}

/* ============ Tab① 规则配置 ============ */

const ruleLoading = ref(false)
const ruleRows = ref<DataPermRuleVo[]>([])
const ruleTotal = ref(0)
/** 主体类型筛选用 number | ''（el-select clearable 清空为 ''），查询前归一 */
const ruleFilter = reactive<{
  pageNum: number
  pageSize: number
  resource: string
  subjectType: number | ''
  subjectId: string
}>({ pageNum: 1, pageSize: 10, resource: '', subjectType: '', subjectId: '' })

/** 主体筛选选项（随主体类型换源；仅类型选定后渲染主体 select） */
const filterSubjectOptions = ref<SubjectOptionVo[]>([])

const configDialogVisible = ref(false)
const configDialogMode = ref<RuleConfigMode>('add')
const configDialogRule = ref<DataPermRuleVo>()

async function loadRulePage(pageNum: number = ruleFilter.pageNum): Promise<void> {
  ruleLoading.value = true
  try {
    ruleFilter.pageNum = pageNum
    const page = await pageRule({
      pageNum: ruleFilter.pageNum,
      pageSize: ruleFilter.pageSize,
      resource: ruleFilter.resource || undefined,
      subjectType: ruleFilter.subjectType === '' ? undefined : ruleFilter.subjectType,
      subjectId: ruleFilter.subjectId || undefined,
    })
    ruleRows.value = page.rows
    ruleTotal.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    ruleLoading.value = false
  }
}

/** 主体类型筛选切换：换源选项并清空主体（防跨类型残留 id） */
async function handleFilterTypeChange(): Promise<void> {
  ruleFilter.subjectId = ''
  if (ruleFilter.subjectType === '') {
    filterSubjectOptions.value = []
    return
  }
  try {
    filterSubjectOptions.value = await listSubjectOptions(ruleFilter.subjectType)
  } catch {
    // 拦截器已统一 toast
  }
}

function searchRules(): void {
  void loadRulePage(1)
}

/** 重置筛选（query 预筛选一并清除，恢复全量） */
function resetRuleFilter(): void {
  ruleFilter.resource = ''
  ruleFilter.subjectType = ''
  ruleFilter.subjectId = ''
  filterSubjectOptions.value = []
  void loadRulePage(1)
}

function openAddRule(): void {
  configDialogMode.value = 'add'
  configDialogRule.value = undefined
  configDialogVisible.value = true
}

function openConfigRule(rule: DataPermRuleVo): void {
  configDialogMode.value = 'edit'
  configDialogRule.value = rule
  configDialogVisible.value = true
}

async function handleDeleteRule(rule: DataPermRuleVo): Promise<void> {
  // 文案含主体名 + 列规则连带提示（契约 §3.4：删除连带物理删同主体全部列规则）
  const confirmed = await ElMessageBox.confirm(
    `确定删除 ${rule.resource} 的规则（主体：${rule.subjectName}）吗？其列规则将一并删除。`,
    '删除确认',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteRule(rule.id)
    ElMessage.success('删除成功')
    await loadRulePage()
  } catch {
    // 拦截器已统一 toast（3031 不存在）
  }
}

function handleRuleSizeChange(size: number): void {
  ruleFilter.pageSize = size
  void loadRulePage(1)
}

/**
 * 角色页联动入口（设计 §8）：/system/data-perm?subjectType=0&subjectId=x 预筛选——
 * watch 而非仅 onMounted：keep-alive 缓存复用时再次跳入（同 path 不同 query）不重挂载
 */
watch(
  () => route.query,
  async (query) => {
    if (route.path !== '/system/data-perm') {
      return
    }
    const subjectTypeRaw = query.subjectType
    const subjectIdRaw = query.subjectId
    if (typeof subjectTypeRaw === 'string' && subjectTypeRaw !== '' && typeof subjectIdRaw === 'string' && subjectIdRaw !== '') {
      const subjectType = Number(subjectTypeRaw)
      if (subjectType !== SUBJECT_ROLE && subjectType !== SUBJECT_USER) {
        return
      }
      activeTab.value = 'rule'
      ruleFilter.subjectType = subjectType
      ruleFilter.subjectId = subjectIdRaw
      ruleFilter.resource = ''
      try {
        filterSubjectOptions.value = await listSubjectOptions(subjectType)
      } catch {
        // 拦截器已统一 toast；预筛选主体下拉候选为空不阻断
      }
      await loadRulePage(1)
    }
  },
  { immediate: true },
)

watch(
  activeTab,
  (tab) => {
    if (tab === 'rule') {
      void loadResources()
      void loadRulePage()
    } else if (tab === 'log') {
      void loadResources()
      void loadLogPage()
    } else {
      void loadResources()
      void loadExplainOptions()
    }
  },
  { immediate: true },
)

/* ============ Tab② 决策留痕 ============ */

const logLoading = ref(false)
const logRows = ref<DataPermLogVo[]>([])
const logTotal = ref(0)
const logFilter = reactive({ pageNum: 1, pageSize: 10, account: '', resource: '' })

async function loadLogPage(pageNum: number = logFilter.pageNum): Promise<void> {
  logLoading.value = true
  try {
    logFilter.pageNum = pageNum
    const page = await pageDataPermLog({
      pageNum: logFilter.pageNum,
      pageSize: logFilter.pageSize,
      account: logFilter.account.trim() || undefined,
      resource: logFilter.resource || undefined,
    })
    logRows.value = page.rows
    logTotal.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    logLoading.value = false
  }
}

function searchLogs(): void {
  void loadLogPage(1)
}

function resetLogFilter(): void {
  logFilter.account = ''
  logFilter.resource = ''
  void loadLogPage(1)
}

function handleLogSizeChange(size: number): void {
  logFilter.pageSize = size
  void loadLogPage(1)
}

/* ============ Tab③ 模拟解释 ============ */

const explainLoading = ref(false)
const explainAccount = ref('')
const explainResource = ref('')
const explainResult = ref<DataPermExplainVo>()
/** 被模拟账号候选（subject-options type=用户；选项 value 取 label 括号内账号——§3.8 入参值域） */
const explainUserOptions = ref<SubjectOptionVo[]>([])
const explainUserOptionsLoaded = ref(false)

async function loadExplainOptions(): Promise<void> {
  if (explainUserOptionsLoaded.value) {
    return
  }
  try {
    explainUserOptions.value = await listSubjectOptions(SUBJECT_USER)
    explainUserOptionsLoaded.value = true
  } catch {
    // 拦截器已统一 toast；候选为空不阻断
  }
}

async function runExplain(): Promise<void> {
  if (!explainAccount.value || !explainResource.value) {
    ElMessage.warning('请选择账号与资源')
    return
  }
  explainLoading.value = true
  try {
    explainResult.value = await explainDataPerm({
      account: explainAccount.value,
      resource: explainResource.value,
    })
  } catch {
    // 拦截器已统一 toast（3032 目标用户无效 / 3034 资源非法）；保留上次结果
  } finally {
    explainLoading.value = false
  }
}

/** 命中规则表 row 收窄（EP 列插槽 DefaultRow，同 ruleRowOf 说明） */
function hitRowOf(row: unknown): DataPermExplainVo['hitRules'][number] {
  return row as DataPermExplainVo['hitRules'][number]
}

function columnDecisionRowOf(row: unknown): DataPermExplainVo['columns'][number] {
  return row as DataPermExplainVo['columns'][number]
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>数据权限</span>
        <el-button v-perms="'system:dataPerm:save'" type="primary" @click="openAddRule">新增规则</el-button>
      </div>
    </template>

    <el-tabs v-model="activeTab">
      <!-- ============ ① 规则配置 ============ -->
      <el-tab-pane label="规则配置" name="rule">
        <div class="filter-bar">
          <el-select
            v-model="ruleFilter.resource"
            placeholder="资源（全部）"
            clearable
            class="filter-item"
          >
            <el-option v-for="r in resources" :key="r.resource" :label="r.resource" :value="r.resource" />
          </el-select>
          <el-select
            v-model="ruleFilter.subjectType"
            placeholder="主体类型（全部）"
            clearable
            class="filter-item"
            @change="handleFilterTypeChange"
          >
            <el-option label="角色" :value="SUBJECT_ROLE" />
            <el-option label="用户" :value="SUBJECT_USER" />
          </el-select>
          <el-select
            v-if="ruleFilter.subjectType !== ''"
            v-model="ruleFilter.subjectId"
            placeholder="主体（全部）"
            clearable
            filterable
            class="filter-item"
          >
            <el-option
              v-for="s in filterSubjectOptions"
              :key="s.id"
              :label="s.label"
              :value="s.id"
            />
          </el-select>
          <el-button @click="searchRules">查询</el-button>
          <el-button @click="resetRuleFilter">重置</el-button>
        </div>

        <el-table v-loading="ruleLoading" :data="ruleRows">
          <el-table-column prop="resource" label="资源" min-width="100" />
          <el-table-column label="主体类型" width="90">
            <template #default="{ row }">
              <el-tag :type="SUBJECT_TYPE_MAP[ruleRowOf(row).subjectType]?.tagType ?? 'info'">
                {{ SUBJECT_TYPE_MAP[ruleRowOf(row).subjectType]?.label ?? ruleRowOf(row).subjectType }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="subjectName" label="主体名称" min-width="120" />
          <el-table-column label="行范围" width="110">
            <template #default="{ row }">
              <el-tag :type="ROW_SCOPE_MAP[ruleRowOf(row).rowScope]?.tagType ?? 'info'">
                {{ ROW_SCOPE_MAP[ruleRowOf(row).rowScope]?.label ?? ruleRowOf(row).rowScope }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="自定义人数" width="110">
            <!-- 仅 CUSTOM 档有集合（契约 §6.2：其余档 []） -->
            <template #default="{ row }">
              <span>{{ ruleRowOf(row).customAccounts.length > 0 ? ruleRowOf(row).customAccounts.length : '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="列规则" min-width="140">
            <template #default="{ row }">{{ columnSummaryOf(ruleRowOf(row).columns) }}</template>
          </el-table-column>
          <el-table-column label="更新人" width="90">
            <template #default="{ row }">{{ ruleRowOf(row).updateBy ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" min-width="160">
            <template #default="{ row }">{{ ruleRowOf(row).updateTime ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                v-perms="'system:dataPerm:save'"
                link
                type="primary"
                @click="openConfigRule(ruleRowOf(row))"
                >配置</el-button
              >
              <el-button
                v-perms="'system:dataPerm:remove'"
                link
                type="danger"
                @click="handleDeleteRule(ruleRowOf(row))"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="table-pagination"
          v-model:current-page="ruleFilter.pageNum"
          v-model:page-size="ruleFilter.pageSize"
          :total="ruleTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, prev, pager, next, sizes"
          @current-change="loadRulePage"
          @size-change="handleRuleSizeChange"
        />
      </el-tab-pane>

      <!-- ============ ② 决策留痕 ============ -->
      <el-tab-pane label="决策留痕" name="log">
        <div class="filter-bar">
          <el-input
            v-model="logFilter.account"
            placeholder="账号（精确）"
            clearable
            class="filter-item filter-input"
          />
          <el-select
            v-model="logFilter.resource"
            placeholder="资源（全部）"
            clearable
            class="filter-item"
          >
            <el-option v-for="r in resources" :key="r.resource" :label="r.resource" :value="r.resource" />
          </el-select>
          <el-button @click="searchLogs">查询</el-button>
          <el-button @click="resetLogFilter">重置</el-button>
        </div>

        <el-table v-loading="logLoading" :data="logRows">
          <el-table-column prop="createTime" label="决策时间" min-width="160" />
          <el-table-column prop="account" label="账号" min-width="110" />
          <el-table-column prop="resource" label="资源" min-width="90" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-tag :type="OP_MAP[logRowOf(row).operation]?.tagType ?? 'info'">
                {{ OP_MAP[logRowOf(row).operation]?.label ?? logRowOf(row).operation }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="规则摘要" min-width="220">
            <template #default="{ row }">{{ logRowOf(row).ruleDigest ?? '（无规则，默认档）' }}</template>
          </el-table-column>
          <el-table-column prop="scopeSummary" label="范围结论" min-width="110" />
          <el-table-column label="列结论" min-width="110">
            <template #default="{ row }">{{ logRowOf(row).columnSummary ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="业务键" min-width="140">
            <template #default="{ row }">{{ logRowOf(row).businessKey ?? '-' }}</template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="table-pagination"
          v-model:current-page="logFilter.pageNum"
          v-model:page-size="logFilter.pageSize"
          :total="logTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, prev, pager, next, sizes"
          @current-change="loadLogPage"
          @size-change="handleLogSizeChange"
        />
      </el-tab-pane>

      <!-- ============ ③ 模拟解释 ============ -->
      <el-tab-pane label="模拟解释" name="explain">
        <div class="filter-bar">
          <el-select
            v-model="explainAccount"
            placeholder="请选择账号"
            filterable
            clearable
            class="filter-item"
          >
            <!-- 选项 value = 从 label 提取的账号（explain 入参值域为账号串，契约 §3.8） -->
            <el-option
              v-for="u in explainUserOptions"
              :key="u.id"
              :label="u.label"
              :value="accountOfLabel(u.label)"
            />
          </el-select>
          <el-select
            v-model="explainResource"
            placeholder="请选择资源"
            clearable
            class="filter-item"
          >
            <el-option v-for="r in resources" :key="r.resource" :label="r.resource" :value="r.resource" />
          </el-select>
          <el-button type="primary" :loading="explainLoading" @click="runExplain">解释</el-button>
        </div>

        <template v-if="explainResult">
          <div class="explain-section-title">决策过程</div>
          <el-timeline class="explain-timeline">
            <el-timeline-item v-for="(line, i) in explainResult.narratives" :key="i">
              {{ line }}
            </el-timeline-item>
          </el-timeline>

          <div class="explain-section-title">命中规则</div>
          <el-table :data="explainResult.hitRules" size="small">
            <el-table-column label="规则 id" width="90">
              <template #default="{ row }">{{ hitRowOf(row).ruleId }}</template>
            </el-table-column>
            <el-table-column label="主体" min-width="120">
              <template #default="{ row }">
                <el-tag :type="SUBJECT_TYPE_MAP[hitRowOf(row).subjectType]?.tagType ?? 'info'" size="small">
                  {{ SUBJECT_TYPE_MAP[hitRowOf(row).subjectType]?.label ?? hitRowOf(row).subjectType }}
                </el-tag>
                <span class="explain-subject-name">{{ hitRowOf(row).subjectName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="行范围" width="110">
              <template #default="{ row }">
                <el-tag :type="ROW_SCOPE_MAP[hitRowOf(row).rowScope]?.tagType ?? 'info'" size="small">
                  {{ ROW_SCOPE_MAP[hitRowOf(row).rowScope]?.label ?? hitRowOf(row).rowScope }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="展开账号数" width="100">
              <template #default="{ row }">{{ hitRowOf(row).expandedCount }}</template>
            </el-table-column>
          </el-table>

          <div class="explain-section-title">列决策</div>
          <el-table :data="explainResult.columns" size="small">
            <el-table-column label="列" min-width="120">
              <template #default="{ row }">{{ columnDecisionRowOf(row).columnKey }}</template>
            </el-table-column>
            <el-table-column label="动作" width="100">
              <template #default="{ row }">
                {{ ACTION_MAP[columnDecisionRowOf(row).action] ?? columnDecisionRowOf(row).action }}
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="选择账号与资源后点击「解释」查看决策过程" :image-size="80" />
      </el-tab-pane>
    </el-tabs>

    <RuleConfigDialog
      v-model="configDialogVisible"
      :mode="configDialogMode"
      :rule="configDialogRule"
      @success="loadRulePage()"
    />
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
  width: 200px;
}

.filter-input {
  width: 180px;
}

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.explain-section-title {
  margin: 14px 0 8px;
  font-weight: 600;
}

.explain-timeline {
  margin-top: 8px;
  padding-left: 4px;
}

.explain-subject-name {
  margin-left: 6px;
}
</style>
