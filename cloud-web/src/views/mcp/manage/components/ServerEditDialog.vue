<script setup lang="ts">
/**
 * MCP 服务新建/编辑弹窗（契约 2026-10-30-mcp-server-mgmt-api §1 配置 schema + §2.2 创建 +
 * §2.4 全量编辑 + §2.6 保存前连通测试）：
 * - JSON 导入区：粘贴 canonical mcpServers 条目（或整档 {"mcpServers":{...}}，取第一条并
 *   提示逐条导入）；契约 §1 键位转换口径——canonical 键位惯例小写/任意，导入自动将键名
 *   大写化填入 name（github→GITHUB），大写化后非法或与既有服务重名给表单校验提示由用户改，
 *   不静默改写
 * - headers/credential_headers 动态键值行：credential_headers 值只收 .env 键名
 *   （^CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>$ 命名空间护栏，违例前端拦截——后端 1001 兜底）
 * - 编辑回显走 §2.3 详情（列表行不含 headers/credential_headers/timeout_ms）；内置行
 *   （is_builtin=1）name 只读（后端改名 5022）
 * - 保存前「测试连接」走 §2.6（无 DB 写），结果 el-alert 展示 tool_count/sample_tools；
 *   失败走 R 错误分支（5025/5026/1001）拦截器 toast，内联 alert 留档原因
 * - 编辑提交恒带 status：取列表行当前值（状态变更由列表行开关负责，全量覆盖语义不能漏）
 * 业务错误（5023 重名/5022 stdio 或内置改名/1001）拦截器统一 toast，弹窗保持打开可改可重试
 */
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { createMcpServer, getMcpServer, pageMcpServers, testMcpConfig, updateMcpServer } from '../../../../api/mcp'
import type { CreateMcpServerPayload, McpServerSummaryVo, McpTestResultVo, R } from '../../../../types/api'

export type ServerEditMode = 'add' | 'edit'

interface Props {
  modelValue: boolean
  mode: ServerEditMode
  /** 编辑时传入列表行（取 id/is_builtin 与当前 status）；新增时缺省 */
  server?: McpServerSummaryVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

/** 动态键值行（headers/credential_headers 表单形态；空行提交时忽略） */
interface KvRow {
  key: string
  value: string
}

interface ServerFormData {
  name: string
  description: string
  url: string
  /** '' = 省略（按 url 推断，提交不带该键） */
  transport: string
  headers: KvRow[]
  credentialHeaders: KvRow[]
  timeout_ms: number
}

/** 契约 §1 长度/格式约束与默认值 */
const NAME_PATTERN = /^[A-Z][A-Z0-9_]{1,29}$/
const URL_PATTERN = /^https?:\/\/\S+$/
/** 凭据 .env 键名命名空间护栏（契约 §1；真值只存 .env，此处只收键名） */
const CREDENTIAL_KEY_PATTERN = /^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$/
const NAME_MAX = 30
const DESC_MAX = 512
const URL_MAX = 512
const TIMEOUT_DEFAULT = 30000
const TIMEOUT_MIN = 1000
const TIMEOUT_MAX = 120000
const TIMEOUT_STEP = 1000

const formRef = ref<FormInstance>()
const loading = ref(false)
/** 编辑回显拉取详情中（列表行缺 headers/凭据/超时，回显前表单禁提交） */
const detailLoading = ref(false)
/** 既有服务名全集（重名校验数据源：弹窗打开时拉 size 200 一页——管理面量级十位数内） */
const existingNames = ref<string[]>([])

const form = reactive<ServerFormData>({
  name: '',
  description: '',
  url: '',
  transport: '',
  headers: [],
  credentialHeaders: [],
  timeout_ms: TIMEOUT_DEFAULT,
})

const title = computed(() => (props.mode === 'add' ? '新增 MCP 服务' : `编辑 MCP 服务${props.server ? ` · ${props.server.name}` : ''}`))

/** 内置行 name 不可改（契约 §2.4：改名 5022），编辑态只读展示 */
const nameDisabled = computed(() => props.mode === 'edit' && props.server?.is_builtin === 1)

/** 服务名重名校验（契约 §1 导入口径：重名给表单提示由用户改；后端 5023 兜底） */
function validateNameUnique(_rule: unknown, value: string, callback: (error?: Error) => void): void {
  const name = value.trim()
  if (!name) {
    callback()
    return
  }
  if (props.mode === 'edit' && props.server && name === props.server.name) {
    callback()
    return
  }
  if (existingNames.value.includes(name)) {
    callback(new Error(`服务名 ${name} 已存在，请修改`))
    return
  }
  callback()
}

const rules: FormRules<ServerFormData> = {
  name: [
    { required: true, message: '请输入服务名', trigger: 'blur' },
    { pattern: NAME_PATTERN, message: '大写字母开头，仅含大写字母/数字/下划线，2~30 位（如 GITHUB）', trigger: 'blur' },
    { validator: validateNameUnique, trigger: 'blur' },
  ],
  url: [
    { required: true, message: '请输入 MCP 端点地址', trigger: 'blur' },
    { pattern: URL_PATTERN, message: '须为 http(s) URL', trigger: 'blur' },
    { max: URL_MAX, message: `地址不超过 ${URL_MAX} 个字符`, trigger: 'blur' },
  ],
  description: [{ max: DESC_MAX, message: `描述不超过 ${DESC_MAX} 个字符`, trigger: 'blur' }],
}

/* ============ JSON 导入（契约 §1 canonical 格式） ============ */

const jsonText = ref('')
const importError = ref('')
const importNote = ref('')

/** JSON.parse 结果收窄：仅认普通对象（数组/标量不收）——全页禁 any 的收窄点 */
function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** 对象转键值行：string 直收，number/boolean 宽松转字符串，对象/数组值不收（契约键值均 string） */
function objectToRows(v: unknown): KvRow[] {
  if (!isRecord(v)) {
    return []
  }
  const rows: KvRow[] = []
  for (const [k, val] of Object.entries(v)) {
    if (typeof val === 'string') {
      rows.push({ key: k, value: val })
    } else if (typeof val === 'number' || typeof val === 'boolean') {
      rows.push({ key: k, value: String(val) })
    }
  }
  return rows
}

/**
 * 导入粘贴的 JSON：单条 canonical 条目（无 name 键则 name 留空由用户补填）或整档
 * {"mcpServers":{...}}（取第一条，键名大写化填入 name——契约 §1 转换口径，
 * 非法/重名不静默改写，交给表单校验提示）
 */
function handleImport(): void {
  importError.value = ''
  importNote.value = ''
  const text = jsonText.value.trim()
  if (!text) {
    importError.value = '请先粘贴 JSON 配置'
    return
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (e) {
    importError.value = `JSON 解析失败：${e instanceof Error ? e.message : String(e)}`
    return
  }
  if (!isRecord(parsed)) {
    importError.value = '仅接受 JSON 对象（单条服务配置或 {"mcpServers":{...}} 整档）'
    return
  }
  let entry: Record<string, unknown>
  let name = ''
  let note = ''
  const doc = parsed['mcpServers']
  if (isRecord(doc)) {
    const keys = Object.keys(doc)
    if (keys.length === 0) {
      importError.value = 'mcpServers 整档为空，无可导入条目'
      return
    }
    const first = doc[keys[0]]
    if (!isRecord(first)) {
      importError.value = `mcpServers["${keys[0]}"] 不是对象，无法导入`
      return
    }
    entry = first
    name = keys[0].toUpperCase()
    note =
      keys.length > 1
        ? `整档含 ${keys.length} 个服务，已导入第 1 条「${keys[0]}」（服务名大写化为 ${name}），其余请逐条粘贴导入`
        : `已导入整档第 1 条「${keys[0]}」（服务名大写化为 ${name}）`
  } else {
    entry = parsed
    const n = parsed['name']
    if (typeof n === 'string' && n.trim()) {
      name = n.trim()
      note = '已导入单条配置'
    } else {
      note = '已导入单条配置（未含 name 字段，请补填服务名）'
    }
  }
  if (typeof entry['url'] === 'string') {
    form.url = entry['url']
  }
  if (typeof entry['description'] === 'string') {
    form.description = entry['description']
  }
  if (entry['transport'] === 'http' || entry['transport'] === 'sse') {
    form.transport = entry['transport']
  }
  if (typeof entry['timeout_ms'] === 'number') {
    form.timeout_ms = Math.min(TIMEOUT_MAX, Math.max(TIMEOUT_MIN, Math.round(entry['timeout_ms'])))
  }
  form.headers = objectToRows(entry['headers'])
  form.credentialHeaders = objectToRows(entry['credential_headers'])
  if (name) {
    form.name = name
  }
  importNote.value = note
  // 导入后立即跑一轮校验：大写化后非法/重名即刻在 name 字段提示（契约口径，不静默改写）
  void formRef.value?.validate().catch(() => false)
}

/* ============ 键值行动态编辑 ============ */

function addHeaderRow(): void {
  form.headers.push({ key: '', value: '' })
}

function removeHeaderRow(index: number): void {
  form.headers.splice(index, 1)
}

function addCredentialRow(): void {
  form.credentialHeaders.push({ key: '', value: '' })
}

function removeCredentialRow(index: number): void {
  form.credentialHeaders.splice(index, 1)
}

/** 键值行成对校验：header 名与值须成对填写（空行忽略）；返回错误文案或 null */
function kvRowError(rows: KvRow[], label: string): string | null {
  for (const r of rows) {
    const key = r.key.trim()
    const value = r.value.trim()
    if (!key && !value) {
      continue
    }
    if (!key || !value) {
      return `${label}的 header 名与值须成对填写`
    }
  }
  return null
}

/** 凭据键名命名空间护栏（契约 §1：违例后端 1001，前端先拦给可读提示） */
function credentialKeyError(): string | null {
  for (const r of form.credentialHeaders) {
    const key = r.key.trim()
    const value = r.value.trim()
    if (!key && !value) {
      continue
    }
    if (!CREDENTIAL_KEY_PATTERN.test(value)) {
      return `凭据键名 "${value}" 须匹配 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>（大写字母/数字/下划线）`
    }
  }
  return null
}

/** 键值行 → 对象（空行忽略；无有效行返回 undefined = 提交不带该键） */
function kvToObject(rows: KvRow[]): Record<string, string> | undefined {
  const out: Record<string, string> = {}
  for (const r of rows) {
    if (r.key.trim() && r.value.trim()) {
      out[r.key.trim()] = r.value.trim()
    }
  }
  return Object.keys(out).length > 0 ? out : undefined
}

/** 提交互转：出参即契约 §1 对象（transport 空串/空对象字段不传，对齐全量覆盖语义） */
function buildPayload(): CreateMcpServerPayload {
  return {
    name: form.name.trim(),
    url: form.url.trim(),
    description: form.description.trim() || undefined,
    transport: form.transport || undefined,
    headers: kvToObject(form.headers),
    credential_headers: kvToObject(form.credentialHeaders),
    timeout_ms: form.timeout_ms,
  }
}

/** 表单+键值行+凭据护栏全量校验（保存与测试共用）；失败 toast 具体原因 */
async function validateAll(): Promise<boolean> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return false
  }
  const err = kvRowError(form.headers, '静态请求头') ?? kvRowError(form.credentialHeaders, '凭据请求头') ?? credentialKeyError()
  if (err) {
    ElMessage.error(err)
    return false
  }
  return true
}

/* ============ 保存前连通测试（契约 §2.6，无 DB 写） ============ */

const testing = ref(false)
const testResult = ref<McpTestResultVo | null>(null)
const testError = ref('')

/** 拦截器 reject 的 R 业务体收窄（失败原因内联展示用，无裸 as） */
function isRBody(v: unknown): v is R {
  return typeof v === 'object' && v !== null && 'code' in v && 'msg' in v
}

/** 按当前表单配置直连远端测试：成功 el-alert 展示工具数与样例；失败内联留档原因 */
async function handleTest(): Promise<void> {
  if (testing.value || loading.value || detailLoading.value) {
    return
  }
  if (!(await validateAll())) {
    ElMessage.error('请先补全并修正表单后再测试连接')
    return
  }
  testing.value = true
  testResult.value = null
  testError.value = ''
  try {
    testResult.value = await testMcpConfig(buildPayload())
  } catch (e) {
    // 拦截器已统一 toast（5025 连接失败/5026 凭据未配置/1001）；内联留档供截图与复查
    testError.value = (isRBody(e) && e.msg) || '测试失败'
  } finally {
    testing.value = false
  }
}

/* ============ 回显与提交 ============ */

/** 拉既有服务名全集（add/edit 共用：重名校验与 JSON 导入提示的数据源） */
async function loadExistingNames(): Promise<void> {
  try {
    const result = await pageMcpServers({ page: 1, size: 200 })
    existingNames.value = result.rows.map((r) => r.name)
  } catch {
    // 拦截器已 toast；名集按空集处理（重名兜底交给后端 5023）
  }
}

/** 编辑回显：详情端点取 headers/凭据/超时（列表行无此三字段） */
async function loadDetail(id: number): Promise<void> {
  detailLoading.value = true
  try {
    const detail = await getMcpServer(id)
    form.name = detail.name
    form.description = detail.description ?? ''
    form.url = detail.url
    form.transport = detail.transport === 'http' || detail.transport === 'sse' ? detail.transport : ''
    form.headers = objectToRows(detail.headers)
    form.credentialHeaders = objectToRows(detail.credential_headers)
    form.timeout_ms = detail.timeout_ms
  } catch {
    // 拦截器已 toast；回显失败保持空表单，提交按钮由 detailLoading 收口防误覆盖
  } finally {
    detailLoading.value = false
  }
}

/** 打开时初始化：拉名集；edit 拉详情回显、add 置默认值；并清校验与导入残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    void loadExistingNames()
    if (props.mode === 'edit' && props.server) {
      void loadDetail(props.server.id)
    } else {
      form.name = ''
      form.description = ''
      form.url = ''
      form.transport = ''
      form.headers = []
      form.credentialHeaders = []
      form.timeout_ms = TIMEOUT_DEFAULT
    }
    jsonText.value = ''
    importError.value = ''
    importNote.value = ''
    testResult.value = null
    testError.value = ''
    formRef.value?.clearValidate()
  },
)

async function handleSubmit(): Promise<void> {
  if (loading.value || detailLoading.value) {
    return
  }
  if (!(await validateAll())) {
    return
  }
  loading.value = true
  try {
    if (props.mode === 'add') {
      await createMcpServer(buildPayload())
      ElMessage.success('新建成功')
    } else if (props.server) {
      // 状态取列表行当前值（行内开关是状态的编辑入口）；详情回显失败时禁止提交已由 detailLoading 拦
      await updateMcpServer(props.server.id, { ...buildPayload(), status: props.server.status })
      ElMessage.success('保存成功')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（如 5023 名称已存在）；弹窗保持打开供改名重试
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="760px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <!-- JSON 导入区：粘贴 canonical 条目或整档，解析填充下方表单（只做导入不直接提交） -->
      <el-divider content-position="left">JSON 导入（可选）</el-divider>
      <el-form-item label="粘贴配置">
        <div class="import-area">
          <el-input
            v-model="jsonText"
            type="textarea"
            :rows="4"
            placeholder='粘贴单条 MCP 服务配置 JSON，或整档 {"mcpServers":{"github":{"url":"https://.../mcp"}}}（取第一条导入，服务名大写化）'
            :disabled="loading"
          />
          <div class="import-actions">
            <el-button @click="handleImport">解析导入</el-button>
            <span class="import-hint">导入仅填充下方表单，保存前仍可修改</span>
          </div>
          <el-alert v-if="importError" :title="importError" type="error" :closable="false" show-icon />
          <el-alert v-if="importNote" :title="importNote" type="success" :closable="false" show-icon />
        </div>
      </el-form-item>

      <el-divider content-position="left">基本配置</el-divider>
      <el-form-item label="服务名" prop="name">
        <el-input
          v-model="form.name"
          :maxlength="NAME_MAX"
          placeholder="大写字母开头，仅大写字母/数字/下划线（如 GITHUB）"
          :disabled="loading || nameDisabled"
        />
        <span v-if="nameDisabled" class="field-hint">内置服务不可改名</span>
      </el-form-item>
      <el-form-item label="端点地址" prop="url">
        <el-input v-model="form.url" :maxlength="URL_MAX" placeholder="https://mcp.example.com/mcp" :disabled="loading" />
      </el-form-item>
      <el-form-item label="传输类型" prop="transport">
        <el-select v-model="form.transport" clearable placeholder="省略 = 按 url 推断" class="full-width" :disabled="loading">
          <el-option label="http（Streamable HTTP）" value="http" />
          <el-option label="sse（SSE 事件流）" value="sse" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          :maxlength="DESC_MAX"
          show-word-limit
          placeholder="管理备注（可选）"
          :disabled="loading"
        />
      </el-form-item>

      <el-divider content-position="left">请求头（可选）</el-divider>
      <el-form-item label="静态请求头">
        <div class="kv-rows">
          <div v-for="(row, i) in form.headers" :key="`h-${i}`" class="kv-row">
            <el-input v-model="row.key" placeholder="header 名（如 X-Org-Id）" :disabled="loading" />
            <el-input v-model="row.value" placeholder="字面值（非敏感）" :disabled="loading" />
            <el-button link type="danger" :disabled="loading" @click="removeHeaderRow(i)">删除</el-button>
          </div>
          <el-button :disabled="loading" @click="addHeaderRow">添加请求头</el-button>
          <div class="kv-note">非敏感静态字面值入库；secret/token 一律走下方凭据请求头（引用 .env 键名）</div>
        </div>
      </el-form-item>
      <el-form-item label="凭据请求头">
        <div class="kv-rows">
          <div v-for="(row, i) in form.credentialHeaders" :key="`c-${i}`" class="kv-row">
            <el-input v-model="row.key" placeholder="header 名（如 Authorization）" :disabled="loading" />
            <el-input
              v-model="row.value"
              placeholder=".env 键名（CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>）"
              :disabled="loading"
            />
            <el-button link type="danger" :disabled="loading" @click="removeCredentialRow(i)">删除</el-button>
          </div>
          <el-button :disabled="loading" @click="addCredentialRow">添加凭据头</el-button>
          <div class="kv-note">
            只存 .env 键名（真值在服务端 .env，不入库不回显）；键名须匹配
            CLOUD_BASE_AI_MCP__SERVERS__&lt;NAME&gt;__&lt;KEY&gt;
          </div>
        </div>
      </el-form-item>

      <el-form-item label="超时(ms)" prop="timeout_ms">
        <el-input-number v-model="form.timeout_ms" :min="TIMEOUT_MIN" :max="TIMEOUT_MAX" :step="TIMEOUT_STEP" :disabled="loading" />
        <span class="field-hint">单次调用超时，{{ TIMEOUT_MIN }}~{{ TIMEOUT_MAX }}，默认 {{ TIMEOUT_DEFAULT }}</span>
      </el-form-item>

      <!-- 保存前连通测试结果（契约 §2.6）：成功展示工具数与样例；失败留档原因 -->
      <el-alert
        v-if="testResult"
        :title="`连接成功：发现 ${testResult.tool_count} 个工具`"
        type="success"
        :closable="false"
        show-icon
      >
        <div v-if="testResult.sample_tools.length > 0" class="sample-tools">
          <el-tag v-for="t in testResult.sample_tools" :key="t" size="small" class="sample-tag">{{ t }}</el-tag>
        </div>
        <div v-else class="sample-tools">远端未列出工具（清单为空是合法结果）</div>
      </el-alert>
      <el-alert v-if="testError" :title="`连接失败：${testError}`" type="error" :closable="false" show-icon />
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button v-perms="'ai:mcp:sync'" :loading="testing" :disabled="loading || detailLoading" @click="handleTest">
        测试连接
      </el-button>
      <el-button type="primary" :loading="loading || detailLoading" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-area {
  width: 100%;
}
.import-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
}
.import-hint {
  color: #909399;
  font-size: 12px;
}
.import-area .el-alert {
  margin-top: 4px;
}
.full-width {
  width: 100%;
}
.kv-rows {
  width: 100%;
}
.kv-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.kv-note {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}
.field-hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
.sample-tools {
  margin-top: 4px;
}
.sample-tag {
  margin-right: 4px;
}
.sample-tag:last-child {
  margin-right: 0;
}
</style>
