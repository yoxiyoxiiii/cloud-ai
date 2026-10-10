<script setup lang="ts">
/**
 * 数据权限规则配置弹窗（契约 2026-10-10-data-permission-api §3.2/§3.3，设计 §8）
 * - 表单域：资源 select（listResources）→ 主体类型 radio → 主体 select（subject-options，随类型换源）
 *   → 行档位 radio 五档 → CUSTOM 档显示账号多选（filterable）→ 列配置逐列动作 select（可配列由
 *   资源注册表下发，默认可视）
 * - 编辑（行「配置」）入口：三项锁定，打开即 getRuleConfig 回显权威配置；
 *   configured=false（行数据滞后等边界）走新增默认 SELF
 * - 新增入口：资源默认取注册表第一项，档位默认 SELF，不拉 config（目标主体已有规则时保存即覆盖——upsert 语义 §3.3）
 * - 提交 saveRule 全量 payload：**非 CUSTOM 档强制不带 customAccounts**（§3.3：其余档位必须空，
 *   传值得 1002）；列动作「可视」不入 columns（无规则=可视，§1）
 * - customAccounts 值域是账号串（§3.3）：主体选项 label 为后端拼好的 `昵称(账号)`（§6.5），
 *   提取括号内账号作选项 value
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { getRuleConfig, listResources, listSubjectOptions, saveRule } from '../../../../api/dataPerm'
import type {
  ColumnRuleVo,
  DataPermResourceVo,
  DataPermRuleVo,
  SubjectOptionVo,
} from '../../../../types/api'
import {
  ACTION_HIDDEN,
  ACTION_MASKED,
  ROW_SCOPE_ALL,
  ROW_SCOPE_CUSTOM,
  ROW_SCOPE_DEPT,
  ROW_SCOPE_DEPT_AND_CHILD,
  ROW_SCOPE_SELF,
  SUBJECT_ROLE,
  SUBJECT_USER,
} from '../../../../types/api'

export type RuleConfigMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: RuleConfigMode
  /** 编辑（「配置」）时传入规则行；新增时缺省 */
  rule?: DataPermRuleVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

interface RuleFormData {
  resource: string
  subjectType: number
  subjectId: string
  rowScope: number
  customAccounts: string[]
  /** columnKey → 动作；COLUMN_ACTION_NONE=可视（不入提交） */
  columnActions: Record<string, number>
}

/** 列动作本地哨兵：可视（无规则）——仅在弹窗内表示「不配置」，永不提交（契约 action 值域仅 0/1） */
const COLUMN_ACTION_NONE = -1

const ACTION_OPTIONS = [
  { value: COLUMN_ACTION_NONE, label: '可视' },
  { value: ACTION_MASKED, label: '脱敏' },
  { value: ACTION_HIDDEN, label: '隐藏' },
]

const SCOPE_OPTIONS = [
  { value: ROW_SCOPE_SELF, label: '仅自己' },
  { value: ROW_SCOPE_DEPT, label: '本部门' },
  { value: ROW_SCOPE_DEPT_AND_CHILD, label: '本部门及以下' },
  { value: ROW_SCOPE_CUSTOM, label: '自定义集合' },
  { value: ROW_SCOPE_ALL, label: '全部' },
]

const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)
/** 资源注册表（可配列数据源） */
const resources = ref<DataPermResourceVo[]>([])
/** 主体选项（随 subjectType 换源） */
const subjectOptions = ref<SubjectOptionVo[]>([])
/** CUSTOM 档账号候选（subject-options type=用户） */
const accountOptions = ref<SubjectOptionVo[]>([])

const form = reactive<RuleFormData>({
  resource: '',
  subjectType: SUBJECT_ROLE,
  subjectId: '',
  rowScope: ROW_SCOPE_SELF,
  customAccounts: [],
  columnActions: {},
})

const rules = computed<FormRules<RuleFormData>>(() => ({
  resource: [{ required: true, message: '请选择资源', trigger: 'change' }],
  subjectId: [{ required: true, message: '请选择主体', trigger: 'change' }],
  rowScope: [{ required: true, message: '请选择行范围档位', trigger: 'change' }],
  // §3.3：rowScope=3 时必填非空（1002），逐账号存在启用由后端 3033 校验
  customAccounts: [
    {
      validator: (_rule: unknown, value: string[], callback: (err?: Error) => void) => {
        if (form.rowScope === ROW_SCOPE_CUSTOM && value.length === 0) {
          callback(new Error('自定义范围档须选择至少一个账号'))
          return
        }
        callback()
      },
      trigger: 'change',
    },
  ],
}))

const title = computed(() => (props.mode === 'add' ? '新增规则' : `配置规则${props.rule ? `（${props.rule.subjectName}）` : ''}`))

/** 当前资源的可配列清单（资源注册表下发，D11） */
const configurableColumns = computed<string[]>(() => {
  const hit = resources.value.find((r) => r.resource === form.resource)
  return hit?.columns ?? []
})

const isCustomScope = computed(() => form.rowScope === ROW_SCOPE_CUSTOM)

/** 从主体选项 label（后端拼好的 `昵称(账号)`，契约 §6.5）提取账号——customAccounts 值域是账号串（§3.3） */
function accountOfLabel(label: string): string {
  const matched = /\(([^)]+)\)$/.exec(label)
  return matched ? matched[1] : label
}

/** 列动作状态重建：默认全部可视，再叠加以有列规则（新增默认/编辑回显共用） */
function rebuildColumnActions(columns: ColumnRuleVo[]): void {
  const next: Record<string, number> = {}
  for (const columnKey of configurableColumns.value) {
    next[columnKey] = COLUMN_ACTION_NONE
  }
  for (const col of columns) {
    if (columnKeyIn(col.columnKey)) {
      next[col.columnKey] = col.action
    }
  }
  form.columnActions = next
}

function columnKeyIn(columnKey: string): boolean {
  return configurableColumns.value.includes(columnKey)
}

/** 主体类型切换：换源主体选项并清空已选主体（防跨类型残留 id） */
function handleSubjectTypeChange(): void {
  form.subjectId = ''
  formRef.value?.clearValidate(['subjectId'])
  void loadSubjectOptions()
}

async function loadSubjectOptions(): Promise<void> {
  try {
    subjectOptions.value = await listSubjectOptions(form.subjectType)
  } catch {
    // 拦截器已统一 toast；空候选不阻断表单其余字段
  }
}

/** 资源切换（新增模式）：可配列随之变化，列动作重置为全可视 */
function handleResourceChange(): void {
  formRef.value?.clearValidate(['resource'])
  rebuildColumnActions([])
}

/** 档位切换到非 CUSTOM：清空账号集合（§3.3 其余档位必须空；提交时亦强制兜底） */
function handleScopeChange(): void {
  if (form.rowScope !== ROW_SCOPE_CUSTOM) {
    form.customAccounts = []
  }
  formRef.value?.clearValidate(['customAccounts'])
}

/** 打开时初始化：编辑锁定三项并拉 config 回显；新增给默认（资源取注册表第一项 / SELF / 全可视列） */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) {
      return
    }
    formRef.value?.clearValidate()
    loading.value = true
    try {
      const [resourceList, accountList] = await Promise.all([
        resources.value.length > 0 ? Promise.resolve(resources.value) : listResources(),
        accountOptions.value.length > 0 ? Promise.resolve(accountOptions.value) : listSubjectOptions(SUBJECT_USER),
      ])
      resources.value = resourceList
      accountOptions.value = accountList

      if (props.mode === 'edit' && props.rule) {
        form.resource = props.rule.resource
        form.subjectType = props.rule.subjectType
        form.subjectId = props.rule.subjectId
        form.customAccounts = [...props.rule.customAccounts]
        const [options, config] = await Promise.all([
          listSubjectOptions(props.rule.subjectType),
          getRuleConfig({
            resource: props.rule.resource,
            subjectType: props.rule.subjectType,
            subjectId: props.rule.subjectId,
          }),
        ])
        subjectOptions.value = options
        if (config.configured && config.rowScope !== null) {
          // 权威回显（页面行数据可能滞后）
          form.rowScope = config.rowScope
          form.customAccounts = [...config.customAccounts]
          rebuildColumnActions(config.columns)
        } else {
          // configured=false：走新增默认 SELF（契约 §3.2）
          form.rowScope = ROW_SCOPE_SELF
          form.customAccounts = []
          rebuildColumnActions([])
        }
      } else {
        form.resource = resources.value[0]?.resource ?? ''
        form.subjectType = SUBJECT_ROLE
        form.subjectId = ''
        form.rowScope = ROW_SCOPE_SELF
        form.customAccounts = []
        rebuildColumnActions([])
        await loadSubjectOptions()
      }
    } catch {
      // 拦截器已统一 toast；弹窗保持可关闭
    } finally {
      loading.value = false
    }
  },
)

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || saving.value) {
    return
  }
  saving.value = true
  try {
    const columns = Object.entries(form.columnActions)
      .filter(([, action]) => action !== COLUMN_ACTION_NONE)
      .map(([columnKey, action]) => ({ columnKey, action }))
    await saveRule({
      resource: form.resource,
      subjectType: form.subjectType,
      subjectId: form.subjectId,
      rowScope: form.rowScope,
      // 非 CUSTOM 档强制不带（§3.3 其余档位必须空，传值得 1002「仅自定义范围档可配置账号集合」）
      customAccounts: form.rowScope === ROW_SCOPE_CUSTOM ? form.customAccounts : undefined,
      columns,
    })
    ElMessage.success(props.mode === 'add' ? '新增成功' : '保存成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（3032 主体无效 / 3033 无效账号 / 3034 资源或列非法 / 1002 校验）
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="560px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" v-loading="loading" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="资源" prop="resource">
        <!-- 编辑锁定：行规则的 (resource, subject) 即 upsert 身份 -->
        <el-select
          v-model="form.resource"
          placeholder="请选择资源"
          :disabled="mode === 'edit' || loading"
          class="full-width"
          @change="handleResourceChange"
        >
          <el-option v-for="r in resources" :key="r.resource" :label="r.resource" :value="r.resource" />
        </el-select>
      </el-form-item>
      <el-form-item label="主体类型" prop="subjectType">
        <!-- 编辑锁定（沿用户页 account 锁定形态）：换主体=另一条规则，应从列表行/新增入口进入 -->
        <el-radio-group
          v-model="form.subjectType"
          :disabled="mode === 'edit' || loading"
          @change="handleSubjectTypeChange"
        >
          <el-radio :value="SUBJECT_ROLE">角色</el-radio>
          <el-radio :value="SUBJECT_USER">用户</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="主体" prop="subjectId">
        <el-select
          v-model="form.subjectId"
          placeholder="请选择主体"
          filterable
          :disabled="mode === 'edit' || loading"
          class="full-width"
        >
          <el-option v-for="s in subjectOptions" :key="s.id" :label="s.label" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="行范围" prop="rowScope">
        <el-radio-group v-model="form.rowScope" :disabled="loading" @change="handleScopeChange">
          <el-radio v-for="opt in SCOPE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="isCustomScope" label="账号集合" prop="customAccounts">
        <el-select
          v-model="form.customAccounts"
          multiple
          filterable
          placeholder="请选择账号（值域为账号）"
          :disabled="loading"
          class="full-width"
        >
          <!-- 选项 value = 从 label 提取的账号（customAccounts 契约值域，§3.3） -->
          <el-option
            v-for="a in accountOptions"
            :key="a.id"
            :label="a.label"
            :value="accountOfLabel(a.label)"
          />
        </el-select>
        <!-- customAccounts 上限约 30 账号（VARCHAR(1000) 承载，契约 §3.3 设计注） -->
        <div class="field-hint">已选 {{ form.customAccounts.length }} 个账号（上限约 30）</div>
      </el-form-item>
      <el-form-item
        v-for="columnKey in configurableColumns"
        :key="columnKey"
        :label="`列·${columnKey}`"
      >
        <el-select v-model="form.columnActions[columnKey]" :disabled="loading" class="column-action-select">
          <el-option v-for="opt in ACTION_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full-width {
  width: 100%;
}

.column-action-select {
  width: 180px;
}

.field-hint {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
