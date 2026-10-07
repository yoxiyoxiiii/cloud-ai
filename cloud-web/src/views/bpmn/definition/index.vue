<script setup lang="ts">
/**
 * 流程定义页（/bpmn/definition，设计 D10）：只读分页列表
 * 数据源 /bpmn/definition/page（契约 §4.1，latestVersion 过滤、key 升序）
 * 无任何写按钮（契约 §4 只读语义——无部署/删除/挂起端点）
 */
import { onMounted, reactive, ref } from 'vue'
import { pageDefinitions } from '../../../api/bpmn'
import type { DefinitionVo } from '../../../types/api'

/** 组件名必须显式固定 = pathToRouteName('/bpmn/definition')（动态路由设计 D9 keep-alive 契约） */
defineOptions({ name: 'BpmnDefinition' })

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * 全页断言集中此一处，模板/处理函数禁止再散落裸 as
 */
function rowOf(row: unknown): DefinitionVo {
  return row as DefinitionVo
}

const loading = ref(false)
const rows = ref<DefinitionVo[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

/** 加载分页（契约 §4.1）：total 为 Long→String，分页组件需 Number() */
async function loadDefinitionPage(pageNum: number = query.pageNum): Promise<void> {
  loading.value = true
  try {
    query.pageNum = pageNum
    const page = await pageDefinitions({ pageNum: query.pageNum, pageSize: query.pageSize })
    rows.value = page.rows
    total.value = Number(page.total)
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    loading.value = false
  }
}

function handleSizeChange(size: number): void {
  query.pageSize = size
  void loadDefinitionPage(1)
}

onMounted(() => {
  void loadDefinitionPage()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <span>流程定义</span>
    </template>

    <el-table v-loading="loading" :data="rows" row-key="id">
      <el-table-column label="定义标识" min-width="180">
        <template #default="{ row }">
          <span>{{ rowOf(row).key }}</span>
        </template>
      </el-table-column>
      <el-table-column label="定义名称" min-width="140">
        <template #default="{ row }">{{ rowOf(row).name ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="版本" width="80">
        <template #default="{ row }">{{ rowOf(row).version }}</template>
      </el-table-column>
      <el-table-column label="部署时间" min-width="160">
        <template #default="{ row }">{{ rowOf(row).deploymentTime ?? '-' }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next, sizes"
      @current-change="loadDefinitionPage"
      @size-change="handleSizeChange"
    />
  </el-card>
</template>

<style scoped>
.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
