<script setup lang="ts">
/**
 * 知识库文件管理抽屉（契约 §7 文件分页 + §3 上传）：代码自旧两栏页右栏平移，能力面不增不减。
 * 打开时回第一页加载；上传成功回第一页刷新（新文件 id 倒序居首）；
 * 上传与新建同权限门控 ai:kb:add（契约 §1）；错误分支拦截器统一 toast，catch 留空
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { pageKbFiles, uploadKbFile } from '../../../../api/rag'
import type { KbFileVo } from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 当前知识库 id（父组件点「文件管理」时传入；0 视为未定不加载） */
  kbId: number
  /** 抽屉标题 = 知识库名 */
  kbName: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

/** 分片类型字面量→中文（契约 §7 值域）：parent-child 父子分片 / recursion 递归分片（预留）；未知值原样展示 */
const SPLIT_TYPE_LABELS: Record<string, string> = {
  'parent-child': '父子分片',
  recursion: '递归分片',
}

function splitTypeLabel(value: string): string {
  return SPLIT_TYPE_LABELS[value] ?? value
}

/** EP el-table-column 插槽 row 固定 DefaultRow，全页断言集中此一处，模板/处理函数禁止再散落裸 as */
function rowOf(row: unknown): KbFileVo {
  return row as KbFileVo
}

const filesLoading = ref(false)
const fileRows = ref<KbFileVo[]>([])
/** AI 域 total 为 number（契约 §1：Python 域不做 Long→String），分页组件直取无需 Number() */
const total = ref(0)
const query = reactive({ page: 1, size: 10 })
const uploading = ref(false)

/** 文件分页（契约 §7）：先校验库存在（5004 拦截器已 toast），表格保持现状 */
async function loadFiles(page: number = query.page): Promise<void> {
  if (!props.kbId) {
    return
  }
  filesLoading.value = true
  try {
    query.page = page
    const result = await pageKbFiles(props.kbId, { page: query.page, size: query.size })
    fileRows.value = result.rows
    total.value = result.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    filesLoading.value = false
  }
}

/** 打开时回第一页加载（每次打开取最新列表） */
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void loadFiles(1)
    }
  },
)

function handleSizeChange(size: number): void {
  query.size = size
  void loadFiles(1)
}

/** el-upload 自定义请求（契约 §3）：multipart 交 axios，kb_id 走 query 参数；成功回第一页刷新 */
async function handleUpload(options: UploadRequestOptions): Promise<unknown> {
  if (!props.kbId) {
    return undefined
  }
  uploading.value = true
  try {
    await uploadKbFile(props.kbId, options.file)
    ElMessage.success('上传成功')
    await loadFiles(1)
  } catch {
    // 拦截器已统一 toast（如 500：不支持的文件类型）
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <!-- append-to-body：抽屉挂 body——默认渲染在 el-card 内，关闭后 display:none 残留会让
       "card 内表格行" 类选择器数到文件表（e2e 行计数翻倍实证），且 overlay 留在卡片内易被父级裁剪 -->
  <el-drawer
    :model-value="modelValue"
    :title="kbName"
    size="60%"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="drawer-toolbar">
      <!-- 上传与新建同权限门控（契约 §1：均 ai:kb:add）；仅支持 PDF/DOCX（其他类型后端 500）；
           multipart 由自定义 http-request 发 axios，timeout 120s 在 api 层放大（分片+向量写入为长操作） -->
      <el-upload
        v-perms="'ai:kb:add'"
        accept=".pdf,.docx"
        :show-file-list="false"
        :disabled="uploading"
        :http-request="handleUpload"
      >
        <el-button type="primary" :loading="uploading">上传文件</el-button>
      </el-upload>
    </div>
    <el-table v-loading="filesLoading" :data="fileRows" row-key="id">
      <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ rowOf(row).file_name }}</template>
      </el-table-column>
      <el-table-column label="分片类型" width="140">
        <template #default="{ row }">
          <el-tag>{{ splitTypeLabel(rowOf(row).file_split_type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" min-width="170">
        <template #default="{ row }">{{ rowOf(row).create_time }}</template>
      </el-table-column>
    </el-table>
    <el-pagination
      class="files-pagination"
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next, sizes"
      @current-change="loadFiles"
      @size-change="handleSizeChange"
    />
  </el-drawer>
</template>

<style scoped>
.drawer-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
.files-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
