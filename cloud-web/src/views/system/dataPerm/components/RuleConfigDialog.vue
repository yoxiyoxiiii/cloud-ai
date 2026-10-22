<script setup lang="ts">
/**
 * 数据权限规则配置弹窗（契约 2026-10-10-data-permission-api §3.2/§3.3，设计 §8；
 * 谓词增量契约 2026-10-11-dataperm-predicate-api §2.2/§2.3/§2.4——行级条件段；
 * 操作符扩展契约 2026-10-11-dataperm-operators-api §1.1/§8（R7 合并视图）——九操作符矩阵 + 字典下拉）
 * - 表单域：资源 select（listResources）→ 主体类型 radio → 主体 select（subject-options，随类型换源）
 *   → 行档位 radio 五档 → CUSTOM 档显示账号多选（filterable）→ **行级条件段**（档位下方，条件三元组
 *   行列表：列 select（注册表 filterColumns，§2.4）→ 操作符 select（operatorsForType 按列 type 过滤，
 *   操作符契约 §1.1 矩阵镜像 v2）→ 值输入器三驱动：字典列（dictKey 非空）切 el-select 字典下拉
 *   （getDictItems 数据源，EQ/NE/GTE/GT/LTE 单选、IN 多选、BETWEEN 双选；filterable+allow-create
 *   兜底存量原值回显——§8/设计 D45）/ 非字典列沿类型驱动（NUMBER 数值/DATE·DATETIME 日期选择器/
 *   STRING 短文本）/ PREFIX（非字典 STRING 列）单行文本提交 trimEnd（§1.1 软规范））
 *   → 列配置逐列动作 select（可配列由资源注册表下发，默认可视）
 * - 条件语义（谓词契约 §1.1）：条件组内 AND、与档位行内 AND——可见行 = 档位账号集合 ∧ 条件组全满足；
 *   **空条件列表 = 无条件（随档位保存）**，upsert 全量覆盖语义下显式提交 [] 即清空条件（§2.3）
 * - 前端校验兜底（必填完整/同列禁重复/上限/介于有序/NUMBER 格式）——后端 3035-3037+1002 为权威，
 *   前端拦截仅给友好提示；列/操作符切换即清空已选操作符与值（防类型不符的脏数据提交）
 * - 编辑（行「配置」）入口：三项锁定，打开即 getRuleConfig 回显权威配置（含 predicates 条件段回显）；
 *   configured=false（行数据滞后等边界）走新增默认 SELF + 空条件
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
import { getDictItems } from '../../../../api/dict'
import type {
  ColumnRuleVo,
  DataPermResourceVo,
  DataPermRuleVo,
  DictItemVo,
  FilterColumnVo,
  FilterColumnType,
  PredicateItem,
  PredicateOperator,
  SubjectOptionVo,
} from '../../../../types/api'
import {
  ACTION_HIDDEN,
  ACTION_MASKED,
  FILTER_TYPE_DATE,
  FILTER_TYPE_NUMBER,
  FILTER_TYPE_STRING,
  IN_VALUE_MAX,
  OPERATOR_BETWEEN,
  OPERATOR_EQ,
  OPERATOR_GT,
  OPERATOR_GTE,
  OPERATOR_IN,
  OPERATOR_LT,
  OPERATOR_LTE,
  OPERATOR_NE,
  OPERATOR_PREFIX,
  PREDICATE_MAX,
  ROW_SCOPE_ALL,
  ROW_SCOPE_CUSTOM,
  ROW_SCOPE_DEPT,
  ROW_SCOPE_DEPT_AND_CHILD,
  ROW_SCOPE_SELF,
  STRING_VALUE_MAX,
  SUBJECT_ROLE,
  SUBJECT_USER,
  operatorsForType,
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

/**
 * 当前资源是否仅列级权限（契约 2026-10-22-dataperm-column-visibility-api §2/§3.1）：
 * true 时行档位与谓词不参与求值——行档区禁用、条件段隐藏并提示；rowScope 固定 SELF 照存
 * （存库值不参与求值）。防御旧后端缺 columnOnly 字段按 false 消费（既有资源形态零变化）
 */
const isColumnOnly = computed<boolean>(() => {
  const hit = resources.value.find((r) => r.resource === form.resource)
  return hit?.columnOnly ?? false
})

/** columnOnly 资源行档固定 SELF（契约 §3.1：rowScope 照收照存但不参与求值——默认/回显/切换统一归位） */
function applyColumnOnlyScope(): void {
  if (isColumnOnly.value && form.rowScope !== ROW_SCOPE_SELF) {
    form.rowScope = ROW_SCOPE_SELF
    form.customAccounts = []
  }
}

/* ============ 行级条件段（谓词契约 2026-10-11-dataperm-predicate-api §2.2-§2.4） ============ */

/**
 * 条件行本地模型：值离散承载（valueA 单值/介于起、valueB 介于止、valueList 属于多值），
 * 提交时按 operator 组装为 PredicateItem.values 统一字符串数组（§5.1）。
 * 注意：date-picker 清空时 EP 运行时会向 v-model 写回 null（类型之外）——值消费一律经 textOf 归一
 */
interface PredicateRow {
  /** 行本地自增 key（v-for 稳定 key——splice 中间行防输入状态错位） */
  uid: number
  field: string
  operator: PredicateOperator | ''
  valueA: string
  valueB: string
  /** 属于（IN）多值 tag 列表 */
  valueList: string[]
  /** 属于（IN）输入缓冲（回车/「添加」按钮/date-picker change 共用） */
  valueInput: string
}

let predicateRowSeq = 0

function emptyPredicateRow(): PredicateRow {
  predicateRowSeq += 1
  return { uid: predicateRowSeq, field: '', operator: '', valueA: '', valueB: '', valueList: [], valueInput: '' }
}

/** 条件行列表（≤PREDICATE_MAX；空列表=无条件随档位保存） */
const predicateRows = ref<PredicateRow[]>([])

/** NUMBER 值格式正则（契约 §1.3：^-?\d+(\.\d+)?$，整数/小数/负数可，禁科学计数） */
const NUMBER_VALUE_PATTERN = /^-?\d+(\.\d+)?$/

/** date-picker 清空会写回 null——统一归一为字符串再消费 */
function textOf(value: string | null): string {
  return value ?? ''
}

/** 当前资源的可过滤列清单（注册表下发，契约 §2.4；防御旧后端未带时空清单） */
const filterColumns = computed<FilterColumnVo[]>(() => {
  const hit = resources.value.find((r) => r.resource === form.resource)
  return hit?.filterColumns ?? []
})

/** 列 field → 可过滤列（type/label/dictKey 查询锚点） */
function filterColumnOf(field: string): FilterColumnVo | undefined {
  return filterColumns.value.find((c) => c.field === field)
}

/* ---- 字典列值输入器（操作符契约 §5.2/§8 + 设计 D45：dictKey 非空列切字典下拉） ---- */

/** 字典选项缓存（dictKey → 启用项，契约 2026-10-07-translation-api §2.1）：弹窗生命周期内复用，失败留空走 allow-create 手输兜底 */
const dictOptionsMap = ref<Record<string, DictItemVo[]>>({})

/**
 * 行值输入器是否走字典下拉（契约 §8）：列 dictKey 非空且操作符非 PREFIX——
 * PREFIX 恒走前缀文本输入器（前缀语义面向任意文本，字典枚举无「前缀」意义）
 */
function useDictInput(row: PredicateRow): boolean {
  const col = filterColumnOf(row.field)
  return col !== undefined && col.dictKey !== null && col.dictKey !== '' && row.operator !== OPERATOR_PREFIX
}

/** 行字典列选项：非字典列/未加载返回 []（下拉空态 + allow-create 仍可手输原值） */
function dictOptionsOf(row: PredicateRow): DictItemVo[] {
  const key = filterColumnOf(row.field)?.dictKey
  if (key !== null && key !== undefined && key !== '') {
    return dictOptionsMap.value[key] ?? []
  }
  return []
}

/** 预取当前资源全部字典列选项（去重；已缓存跳过）：未知 dictKey/停用类型后端返回空数组（表单容错语义，非错误） */
async function loadDictOptions(): Promise<void> {
  const keys = [
    ...new Set(filterColumns.value.map((c) => c.dictKey).filter((k): k is string => k !== null && k !== '')),
  ]
  await Promise.all(
    keys.map(async (key) => {
      if (dictOptionsMap.value[key]) {
        return
      }
      try {
        dictOptionsMap.value[key] = await getDictItems(key)
      } catch {
        // 拦截器已统一 toast；该键保持空态，allow-create 手输原值兜底
      }
    }),
  )
}

/** 行当前列类型（未选列给 NUMBER 兜底——未选列时值输入器不渲染，兜底值不参与提交） */
function rowColumnType(row: PredicateRow): FilterColumnType {
  return filterColumnOf(row.field)?.type ?? FILTER_TYPE_NUMBER
}

/** 单值操作符（EQ/GTE/LTE/GT/LT/NE，操作符契约 §1.1 值数=1）：值形态为一个输入器；
 * PREFIX 亦单值但独立前缀文本输入器（提交 trimEnd 软规范，§1.1——LIKE 非 PAD SPACE 尾空格是字面窄化） */
function isSingleOp(operator: PredicateOperator | ''): boolean {
  return (
    operator === OPERATOR_EQ ||
    operator === OPERATOR_GTE ||
    operator === OPERATOR_LTE ||
    operator === OPERATOR_GT ||
    operator === OPERATOR_LT ||
    operator === OPERATOR_NE
  )
}

/** 新增条件行（上限 PREDICATE_MAX——后端 §2.3 校验链 3037 权威，UI 同值拦截） */
function addPredicateRow(): void {
  if (predicateRows.value.length >= PREDICATE_MAX) {
    ElMessage.warning(`条件数上限 ${PREDICATE_MAX} 条`)
    return
  }
  predicateRows.value.push(emptyPredicateRow())
}

function removePredicateRow(index: number): void {
  predicateRows.value.splice(index, 1)
}

/** 列切换：清空已选操作符与全部值（列 type 变化则操作符矩阵/输入器随之变，防脏数据提交） */
function handlePredicateFieldChange(row: PredicateRow): void {
  row.operator = ''
  clearPredicateValues(row)
}

/** 操作符切换：清空值（值形态随操作符变：单值/双值/多值互不复用） */
function handlePredicateOperatorChange(row: PredicateRow): void {
  clearPredicateValues(row)
}

function clearPredicateValues(row: PredicateRow): void {
  row.valueA = ''
  row.valueB = ''
  row.valueList = []
  row.valueInput = ''
}

/** 单值格式校验（契约 §1.3 值格式：后端 3037 权威，前端友好提示；空值必填在完整校验做） */
function valueFormatError(type: FilterColumnType, value: string): string | null {
  if (value === '') {
    return null
  }
  if (type === FILTER_TYPE_NUMBER && !NUMBER_VALUE_PATTERN.test(value)) {
    return '须为数值（整数或小数，负数可，禁科学计数）'
  }
  if (type === FILTER_TYPE_STRING && value.length > STRING_VALUE_MAX) {
    return `长度上限 ${STRING_VALUE_MAX} 字符`
  }
  return null
}

/** 介于有序校验（契约 §1.3：v0 ≤ v1 同型比较——NUMBER 数值比较，DATE/DATETIME 定长格式字典序即时间序） */
function orderedValues(type: FilterColumnType, a: string, b: string): boolean {
  if (type === FILTER_TYPE_NUMBER) {
    return Number(a) <= Number(b)
  }
  return a <= b
}

/** 属于（IN）追加值（输入框回车/「添加」按钮、date-picker change 共用）：trim → 格式校验 → 去重 → 上限 */
function appendInValue(row: PredicateRow): void {
  const value = textOf(row.valueInput).trim()
  if (value === '') {
    return
  }
  const col = filterColumnOf(row.field)
  if (col) {
    const err = valueFormatError(col.type, value)
    if (err) {
      ElMessage.warning(`值「${value}」${err}`)
      return
    }
  }
  if (row.valueList.includes(value)) {
    // 重复值静默忽略（IN 语义去重无害）
    row.valueInput = ''
    return
  }
  if (row.valueList.length >= IN_VALUE_MAX) {
    ElMessage.warning(`属于的值上限 ${IN_VALUE_MAX} 个`)
    return
  }
  row.valueList.push(value)
  row.valueInput = ''
}

function removeInValue(row: PredicateRow, index: number): void {
  row.valueList.splice(index, 1)
}

/**
 * 条件行 → 提交三元组（契约 §5.1）：校验失败 ElMessage.warning 并返回 null；
 * 空行列表返回 []（=无条件随档位保存，upsert 不传即清空——前端显式提交 []，§2.3）
 */
function buildPredicates(): PredicateItem[] | null {
  const result: PredicateItem[] = []
  const seenFields = new Set<string>()
  for (let i = 0; i < predicateRows.value.length; i++) {
    const row = predicateRows.value[i]
    const no = i + 1
    if (row.field === '' || row.operator === '') {
      ElMessage.warning(`条件 ${no}：请选择列与操作符`)
      return null
    }
    const col = filterColumnOf(row.field)
    if (!col) {
      ElMessage.warning(`条件 ${no}：列不在当前资源的可过滤列清单`)
      return null
    }
    if (seenFields.has(row.field)) {
      ElMessage.warning(`条件 ${no}：列「${col.label}」重复（同列至多一条条件）`)
      return null
    }
    seenFields.add(row.field)
    let values: string[]
    if (row.operator === OPERATOR_BETWEEN) {
      const start = textOf(row.valueA).trim()
      const end = textOf(row.valueB).trim()
      if (start === '' || end === '') {
        ElMessage.warning(`条件 ${no}（${col.label}）：请完整填写介于的起止值`)
        return null
      }
      values = [start, end]
    } else if (row.operator === OPERATOR_IN) {
      if (row.valueList.length === 0) {
        ElMessage.warning(`条件 ${no}（${col.label}）：属于至少填写一个值`)
        return null
      }
      if (row.valueList.length > IN_VALUE_MAX) {
        // 字典多选下拉不经 appendInValue 的入口上限，提交口径统一兜底（后端 3037 权威）
        ElMessage.warning(`条件 ${no}（${col.label}）：属于的值上限 ${IN_VALUE_MAX} 个`)
        return null
      }
      values = [...row.valueList]
    } else {
      const raw = textOf(row.valueA)
      // PREFIX 仅裁尾随空格（操作符契约 §1.1 软规范——值首空格是字面、尾空格几乎必是手滑）；其余单值两侧 trim
      const single = row.operator === OPERATOR_PREFIX ? raw.trimEnd() : raw.trim()
      if (single === '') {
        ElMessage.warning(`条件 ${no}（${col.label}）：请填写条件值`)
        return null
      }
      values = [single]
    }
    // 格式校验先于有序校验（非法格式直接报格式错，不让 Number(NaN) 误导成「无序」）
    for (const value of values) {
      const err = valueFormatError(col.type, value)
      if (err) {
        ElMessage.warning(`条件 ${no}（${col.label}）的值「${value}」${err}`)
        return null
      }
    }
    if (row.operator === OPERATOR_BETWEEN && !orderedValues(col.type, values[0], values[1])) {
      ElMessage.warning(`条件 ${no}（${col.label}）：介于的起值须不大于止值`)
      return null
    }
    result.push({ field: row.field, operator: row.operator, values })
  }
  return result
}

/**
 * config.predicates → 条件行回显（契约 §2.2；configured=false 调用方传空数组）：
 * BETWEEN 双值/IN 多值/其余（EQ/GTE/LTE/GT/LT/NE/PREFIX 单值）三分支承载九操作符；
 * 字典列值按配置原值回显（wire 恒原值，操作符契约 §1.5——el-select 值不在字典项时显示原值，
 * allow-create 保证停用项/存量原值可回显可续编）
 */
function rebuildPredicates(predicates: PredicateItem[]): void {
  predicateRows.value = predicates.map((item) => {
    const row = emptyPredicateRow()
    row.field = item.field
    row.operator = item.operator
    if (item.operator === OPERATOR_BETWEEN) {
      row.valueA = item.values[0] ?? ''
      row.valueB = item.values[1] ?? ''
    } else if (item.operator === OPERATOR_IN) {
      row.valueList = [...item.values]
    } else {
      row.valueA = item.values[0] ?? ''
    }
    return row
  })
}

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

/** 资源切换（新增模式）：可配列/可过滤列随之变化，列动作重置为全可视、条件行清空、
 * columnOnly 资源行档归 SELF、字典列选项预取 */
function handleResourceChange(): void {
  formRef.value?.clearValidate(['resource'])
  rebuildColumnActions([])
  predicateRows.value = []
  applyColumnOnlyScope()
  void loadDictOptions()
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
          // 条件段回显（谓词契约 §2.2；predicates 必返，?? [] 防御旧后端缺字段）
          rebuildPredicates(config.predicates ?? [])
          // columnOnly 资源回显归位 SELF（契约 §3.1：历史/API 直调可能存非 SELF，存库值不参与求值）
          applyColumnOnlyScope()
          if (isColumnOnly.value) {
            predicateRows.value = []
          }
        } else {
          // configured=false：走新增默认 SELF（契约 §3.2）+ 空条件
          form.rowScope = ROW_SCOPE_SELF
          form.customAccounts = []
          rebuildColumnActions([])
          predicateRows.value = []
        }
      } else {
        form.resource = resources.value[0]?.resource ?? ''
        form.subjectType = SUBJECT_ROLE
        form.subjectId = ''
        form.rowScope = ROW_SCOPE_SELF
        form.customAccounts = []
        rebuildColumnActions([])
        predicateRows.value = []
        applyColumnOnlyScope()
        await loadSubjectOptions()
      }
      // 当前资源字典列选项预取（dictKey 非空列值输入器数据源；缓存幂等）
      void loadDictOptions()
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
  // 条件行先行校验/组装（后端 3035-3037/1002 权威，前端拦截给友好提示；null=已提示拦截）；
  // columnOnly 资源谓词恒空提交（契约 §3.1：条件段不可配，非空后端 3035 拒绝——固定 [] 防切换残留）
  const predicates = isColumnOnly.value ? [] : buildPredicates()
  if (predicates === null) {
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
      // 显式提交（含空数组=清空条件随档位保存——upsert 全量覆盖语义，谓词契约 §2.3）
      predicates,
    })
    ElMessage.success(props.mode === 'add' ? '新增成功' : '保存成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（3032 主体无效 / 3033 无效账号 / 3034 资源或列非法 /
    // 谓词链：3035 条件列非法 / 3036 操作符×类型 / 3037 值形态 / 1002 操作符无效·条件列重复）
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
        <el-radio-group v-model="form.rowScope" :disabled="loading || isColumnOnly" @change="handleScopeChange">
          <el-radio v-for="opt in SCOPE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <!-- columnOnly 资源提示（契约 §2/§3.1）：行档区禁用 + 条件段隐藏的同时说明原因 -->
      <el-alert
        v-if="isColumnOnly"
        type="info"
        :closable="false"
        show-icon
        class="column-only-alert"
        title="该资源仅列级权限生效，行范围配置不参与求值"
      />
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
      <!-- ============ 行级条件段（谓词契约 2026-10-11-dataperm-predicate-api §1.1/§2.2-§2.4）：
        组内多条件 AND、与档位行内 AND；空列表=无条件（保存即随档位清空条件）；
        columnOnly 资源整块隐藏（契约 2026-10-22-dataperm-column-visibility-api §3.1：谓词不可配） ============ -->
      <template v-if="!isColumnOnly">
      <el-divider content-position="left" class="predicate-divider">行级条件（与档位同时生效）</el-divider>
      <div class="predicate-section">
        <div v-if="predicateRows.length === 0" class="predicate-empty">未配置条件——仅按上方档位过滤行范围</div>
        <div v-for="(row, i) in predicateRows" :key="row.uid" class="predicate-row">
          <div class="predicate-line">
            <!-- 列：资源注册表 filterColumns（§2.4），显示中文 label、提交 field -->
            <el-select
              v-model="row.field"
              placeholder="选择列"
              filterable
              :disabled="loading"
              class="predicate-field-select"
              @change="handlePredicateFieldChange(row)"
            >
              <el-option v-for="c in filterColumns" :key="c.field" :label="c.label" :value="c.field" />
            </el-select>
            <!-- 操作符：operatorsForType 按列 type 过滤（操作符契约 §1.1 矩阵镜像 v2——
                STRING 仅 等于/属于/不等于/前缀；其余类型八操作符无 前缀） -->
            <el-select
              v-model="row.operator"
              placeholder="操作符"
              :disabled="loading || row.field === ''"
              class="predicate-op-select"
              @change="handlePredicateOperatorChange(row)"
            >
              <el-option
                v-for="opt in operatorsForType(rowColumnType(row))"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-button link type="danger" :disabled="loading" @click="removePredicateRow(i)">删除</el-button>
          </div>
          <!-- 值输入器三驱动（操作符契约 §8/设计 D45）：字典列（dictKey 非空，PREFIX 除外）→ 字典下拉；
               非字典列 → 类型驱动 × 操作符（单值/双值/多值；PREFIX 前缀文本提交 trimEnd） -->
          <div v-if="row.field !== '' && row.operator !== ''" class="predicate-values">
            <!-- 字典列：el-select 字典下拉（getDictItems 数据源，EQ/NE/GTE/GT/LTE 单选、IN 多选、BETWEEN 双选）；
                 filterable+allow-create 兜底存量原值回显与手输原值（值域软约束不校验，设计 D38） -->
            <template v-if="useDictInput(row)">
              <!-- 属于（IN）：字典多选 -->
              <el-select
                v-if="row.operator === OPERATOR_IN"
                v-model="row.valueList"
                multiple
                filterable
                allow-create
                placeholder="选择或输入值（可多选）"
                :disabled="loading"
                class="predicate-value-input"
              >
                <el-option v-for="d in dictOptionsOf(row)" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
              <!-- 介于（BETWEEN）：字典双选有序（起≤止提交前校验；仅 NUMBER 字典列可达） -->
              <div v-else-if="row.operator === OPERATOR_BETWEEN" class="predicate-between">
                <el-select
                  v-model="row.valueA"
                  filterable
                  allow-create
                  clearable
                  placeholder="起值"
                  :disabled="loading"
                  class="predicate-between-input"
                >
                  <el-option v-for="d in dictOptionsOf(row)" :key="d.value" :label="d.label" :value="d.value" />
                </el-select>
                <span class="predicate-sep">~</span>
                <el-select
                  v-model="row.valueB"
                  filterable
                  allow-create
                  clearable
                  placeholder="止值"
                  :disabled="loading"
                  class="predicate-between-input"
                >
                  <el-option v-for="d in dictOptionsOf(row)" :key="d.value" :label="d.label" :value="d.value" />
                </el-select>
              </div>
              <!-- 单值（等于/不等于/大于等于/大于/小于等于/小于）：字典单选 -->
              <el-select
                v-else
                v-model="row.valueA"
                filterable
                allow-create
                clearable
                placeholder="选择或输入值"
                :disabled="loading"
                class="predicate-value-input"
              >
                <el-option v-for="d in dictOptionsOf(row)" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
              <div class="field-hint">字典列：下拉选择；亦可直接输入原值（如已停用项）</div>
            </template>
            <!-- 非字典列：既有类型驱动输入器 -->
            <template v-else>
              <!-- 单值（等于/大于等于/小于等于/大于/小于/不等于——GT/LT/NE 为操作符契约 §1.1 增量） -->
              <el-input
                v-if="isSingleOp(row.operator) && rowColumnType(row) === FILTER_TYPE_NUMBER"
                v-model="row.valueA"
                placeholder="数值（整数/小数，负数可）"
                clearable
                :disabled="loading"
                class="predicate-value-input"
              />
              <el-input
                v-else-if="isSingleOp(row.operator) && rowColumnType(row) === FILTER_TYPE_STRING"
                v-model="row.valueA"
                :placeholder="`文本（精确值，≤${STRING_VALUE_MAX} 字）`"
                clearable
                :maxlength="STRING_VALUE_MAX"
                :disabled="loading"
                class="predicate-value-input"
              />
              <el-date-picker
                v-else-if="isSingleOp(row.operator) && rowColumnType(row) === FILTER_TYPE_DATE"
                v-model="row.valueA"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择日期"
                :disabled="loading"
                class="predicate-value-input"
              />
              <el-date-picker
                v-else-if="isSingleOp(row.operator)"
                v-model="row.valueA"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="选择日期时间"
                :disabled="loading"
                class="predicate-value-input"
              />
              <!-- 前缀（PREFIX，矩阵 STRING 专属、非字典列）：单行文本，提交仅裁尾随空格（契约 §1.1 软规范——
                   值内 %/_/| 字面合法，SQL 渲染层转义消解；尾随空格是字面窄化条件，裁剪防手滑） -->
              <el-input
                v-else-if="row.operator === OPERATOR_PREFIX"
                v-model="row.valueA"
                :placeholder="`前缀文本（右模糊，≤${STRING_VALUE_MAX} 字；尾随空格提交时裁剪）`"
                clearable
                :maxlength="STRING_VALUE_MAX"
                :disabled="loading"
                class="predicate-value-input"
              />
              <!-- 介于：双值起/止（有序闭区间，提交前校验 起≤止；STRING 无介于——操作符矩阵已过滤） -->
              <div v-else-if="row.operator === OPERATOR_BETWEEN" class="predicate-between">
                <template v-if="rowColumnType(row) === FILTER_TYPE_NUMBER">
                  <el-input v-model="row.valueA" placeholder="起值" clearable :disabled="loading" class="predicate-between-input" />
                  <span class="predicate-sep">~</span>
                  <el-input v-model="row.valueB" placeholder="止值" clearable :disabled="loading" class="predicate-between-input" />
                </template>
                <template v-else-if="rowColumnType(row) === FILTER_TYPE_DATE">
                  <el-date-picker
                    v-model="row.valueA"
                    type="date"
                    value-format="YYYY-MM-DD"
                    placeholder="起日期"
                    :disabled="loading"
                    class="predicate-between-input"
                  />
                  <span class="predicate-sep">~</span>
                  <el-date-picker
                    v-model="row.valueB"
                    type="date"
                    value-format="YYYY-MM-DD"
                    placeholder="止日期"
                    :disabled="loading"
                    class="predicate-between-input"
                  />
                </template>
                <template v-else>
                  <el-date-picker
                    v-model="row.valueA"
                    type="datetime"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    placeholder="起时间"
                    :disabled="loading"
                    class="predicate-between-input"
                  />
                  <span class="predicate-sep">~</span>
                  <el-date-picker
                    v-model="row.valueB"
                    type="datetime"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    placeholder="止时间"
                    :disabled="loading"
                    class="predicate-between-input"
                  />
                </template>
              </div>
              <!-- 属于：多值 tag 追加（≤IN_VALUE_MAX，重复忽略；DATE/DATETIME 选择即添加） -->
              <div v-else class="predicate-in">
                <div
                  v-if="rowColumnType(row) === FILTER_TYPE_NUMBER || rowColumnType(row) === FILTER_TYPE_STRING"
                  class="predicate-in-line"
                >
                  <el-input
                    v-model="row.valueInput"
                    :placeholder="rowColumnType(row) === FILTER_TYPE_STRING ? `文本（回车添加）` : '数值（回车添加）'"
                    clearable
                    :maxlength="rowColumnType(row) === FILTER_TYPE_STRING ? STRING_VALUE_MAX : undefined"
                    :disabled="loading"
                    class="predicate-in-input"
                    @keyup.enter="appendInValue(row)"
                  />
                  <el-button :disabled="loading" @click="appendInValue(row)">添加</el-button>
                </div>
                <el-date-picker
                  v-else-if="rowColumnType(row) === FILTER_TYPE_DATE"
                  v-model="row.valueInput"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择即添加"
                  :disabled="loading"
                  class="predicate-value-input"
                  @change="appendInValue(row)"
                />
                <el-date-picker
                  v-else
                  v-model="row.valueInput"
                  type="datetime"
                  value-format="YYYY-MM-DD HH:mm:ss"
                  placeholder="选择即添加"
                  :disabled="loading"
                  class="predicate-value-input"
                  @change="appendInValue(row)"
                />
                <div v-if="row.valueList.length > 0" class="predicate-tags">
                  <el-tag
                    v-for="(v, vi) in row.valueList"
                    :key="`${v}-${vi}`"
                    closable
                    :disable-transitions="true"
                    @close="removeInValue(row, vi)"
                    >{{ v }}</el-tag
                  >
                </div>
                <div class="field-hint">已 {{ row.valueList.length }}/{{ IN_VALUE_MAX }} 个值（重复自动忽略）</div>
              </div>
            </template>
          </div>
        </div>
        <div class="predicate-footer">
          <el-button
            v-if="predicateRows.length < PREDICATE_MAX"
            link
            type="primary"
            :disabled="loading"
            @click="addPredicateRow"
            >+ 添加条件</el-button
          >
          <span v-else class="field-hint">已达条件数上限 {{ PREDICATE_MAX }} 条</span>
          <span class="field-hint">多条件同时满足（AND）并与档位叠加；同列至多一条</span>
        </div>
      </div>
      </template>
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

/* columnOnly 资源行级不适用提示（契约 2026-10-22-dataperm-column-visibility-api §2）：与表单内容区对齐 */
.column-only-alert {
  margin: 0 0 12px;
  padding-left: 90px;
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

/* ============ 行级条件段 ============ */

.predicate-divider {
  margin: 4px 0 12px;
}

/* 与 el-form label-width=90px 的内容区对齐 */
.predicate-section {
  padding-left: 90px;
}

.predicate-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.predicate-row {
  margin-bottom: 12px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.predicate-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.predicate-field-select {
  width: 180px;
}

.predicate-op-select {
  width: 130px;
}

.predicate-values {
  margin-top: 8px;
}

.predicate-value-input {
  width: 240px;
}

.predicate-between {
  display: flex;
  align-items: center;
  gap: 8px;
}

.predicate-between-input {
  width: 170px;
}

.predicate-sep {
  color: var(--el-text-color-secondary);
}

.predicate-in-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.predicate-in-input {
  width: 200px;
}

.predicate-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 4px;
  margin-top: 8px;
}

.predicate-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}
</style>
