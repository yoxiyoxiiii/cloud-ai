<template>
  <div class="kb-manage">
    <!-- 左：知识库列表（全量不分页，契约 §4 现状） -->
    <aside class="kb-aside">
      <div class="aside-head">
        <span class="aside-title">知识库</span>
        <el-button v-perms="'ai:kb:add'" type="primary" @click="createVisible = true">
          新建知识库
        </el-button>
      </div>
      <div v-loading="kbLoading" class="kb-list">
        <div v-if="!kbLoading && kbs.length === 0" class="aside-empty">暂无知识库</div>
        <div
          v-for="kb in kbs"
          :key="kb.id"
          :class="['kb-item', { active: kb.id === activeKbId }]"
          @click="selectKb(kb.id)"
        >
          <div class="kb-name">{{ kb.name }}</div>
          <div class="kb-desc">{{ kb.description ?? '暂无描述' }}</div>
        </div>
      </div>
    </aside>

    <!-- 右：所选库文件表格 -->
    <section class="kb-main">
      <div v-if="!activeKb" class="empty">请选择左侧知识库</div>
      <template v-else>
        <div class="main-head">
          <span class="main-title">{{ activeKb.name }}</span>
          <!-- 上传与新建同权限门控（契约 §1：均 ai:kb:add）；未选库时整栏本就不渲染——
               上传区仅存在于已选库分支；multipart 由自定义 http-request 发 axios -->
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
      </template>
    </section>

    <CreateKbDialog v-model="createVisible" @success="handleCreated" />
  </div>
</template>

<script setup lang="ts">
/**
 * 知识库管理页（/rag/kb，契约 2026-10-14-ai-kb-api）：左栏知识库全量列表（§4 不分页）+
 * 右栏所选库文件分页表格（§5）；新建/上传权限 ai:kb:add、查询 ai:kb:list（契约 §1 拆分）；
 * 错误分支（403/5003/5004/1001）由 request.ts 拦截器统一 toast，页面不重复处理
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { listKbs, pageKbFiles, uploadKbFile } from '../../../api/rag'
import type { KbFileVo, KbVo } from '../../../types/api'
import CreateKbDialog from './components/CreateKbDialog.vue'

/** = pathToRouteName('/rag/kb')，keep-alive include 依赖，不可省 */
defineOptions({ name: 'RagKb' })

/** 分片类型字面量→中文（契约 §5 值域）：parent-child 父子分片 / recursion 递归分片（预留）；未知值原样展示 */
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

// 左栏：知识库列表
const kbs = ref<KbVo[]>([])
const kbLoading = ref(false)
const activeKbId = ref(0)
const activeKb = computed(() => kbs.value.find((kb) => kb.id === activeKbId.value))

// 右栏：文件分页
const filesLoading = ref(false)
const fileRows = ref<KbFileVo[]>([])
/** AI 域 total 为 number（契约 §1：Python 域不做 Long→String），无需 Number() 转换 */
const total = ref(0)
const query = reactive({ page: 1, size: 10 })

const createVisible = ref(false)
const uploading = ref(false)

/** 左栏加载（契约 §4）：全量活记录 id 升序 */
async function loadKbs(): Promise<void> {
  kbLoading.value = true
  try {
    kbs.value = await listKbs()
  } catch {
    // 拦截器已统一 toast，列表保持现状
  } finally {
    kbLoading.value = false
  }
}

/** 右栏文件分页（契约 §5）：先校验库存在（5004 拦截器已 toast），表格保持现状 */
async function loadFiles(page: number = query.page): Promise<void> {
  if (!activeKbId.value) {
    return
  }
  filesLoading.value = true
  try {
    query.page = page
    const result = await pageKbFiles(activeKbId.value, { page: query.page, size: query.size })
    fileRows.value = result.rows
    total.value = result.total
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    filesLoading.value = false
  }
}

/** 选中知识库：切换右栏数据源并回第一页 */
function selectKb(kbId: number): void {
  activeKbId.value = kbId
  void loadFiles(1)
}

function handleSizeChange(size: number): void {
  query.size = size
  void loadFiles(1)
}

/** el-upload 自定义请求（契约 §3）：multipart 交 axios，kb_id 走 query 参数；成功回第一页刷新（新文件 id 倒序居首） */
async function handleUpload(options: UploadRequestOptions): Promise<unknown> {
  const kbId = activeKbId.value
  if (!kbId) {
    return undefined // 未选库防御（上传区仅存在于已选库分支，双保险）
  }
  uploading.value = true
  try {
    await uploadKbFile(kbId, options.file)
    ElMessage.success('上传成功')
    await loadFiles(1)
  } catch {
    // 拦截器已统一 toast（如 500：不支持的文件类型）
  } finally {
    uploading.value = false
  }
}

/** 新建成功（契约 §2 返回新库 id）：刷新左栏并按 id 自动选中新库 */
async function handleCreated(kbId: number): Promise<void> {
  await loadKbs()
  selectKb(kbId)
}

onMounted(() => {
  void loadKbs()
})
</script>

<style scoped>
/* 两栏布局沿 /rag/chat 页 aside+main 先例 */
.kb-manage {
  display: flex;
  height: 100%;
  gap: 12px;
}
.kb-aside {
  width: 250px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}
.aside-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.aside-title {
  font-weight: 600;
}
.kb-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.aside-empty {
  padding: 16px 0;
  text-align: center;
  color: #909399;
}
.kb-item {
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
}
.kb-item:hover {
  background: #f5f7fa;
}
.kb-item.active {
  background: #ecf5ff;
  color: #409eff;
}
.kb-item .kb-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-item .kb-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.main-title {
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}
.files-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
