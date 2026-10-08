<script setup lang="ts">
/**
 * 流程定义页（/bpmn/definition，设计 D10→F4 扩容）：分页列表 + 查看图弹窗 + 在线设计器
 * 数据源 /bpmn/definition/page（契约 §4.1，latestVersion 过滤、key 升序）
 * 写面（增量契约 2026-10-08 §2）：查看图（perms list）/ 设计与新建（v-perm bpmn:definition:deploy）/
 * 保存部署（multipart）——删除/挂起/激活永不做（拍板口径）
 */
import { defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import { pageDefinitions } from '../../../api/bpmn'
import type { DefinitionVo } from '../../../types/api'

/** 组件名必须显式固定 = pathToRouteName('/bpmn/definition')（动态路由设计 D9 keep-alive 契约） */
defineOptions({ name: 'BpmnDefinition' })

/** 两弹窗按需分包（设计 D1 铁律）：defineAsyncComponent 引入（设计器含 Modeler/properties-panel 链） */
const DefinitionDiagramDialog = defineAsyncComponent(
  () => import('./components/DefinitionDiagramDialog.vue'),
)
const DefinitionDesignerDialog = defineAsyncComponent(
  () => import('./components/DefinitionDesignerDialog.vue'),
)

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

const diagramDialogVisible = ref(false)
const diagramDefinition = ref<DefinitionVo>()

const designerDialogVisible = ref(false)
/** 编辑态 definitionId；undefined = 新建（计划 F4 两种打开态） */
const designerDefinitionId = ref<string>()

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

function openDiagram(row: DefinitionVo): void {
  diagramDefinition.value = row
  diagramDialogVisible.value = true
}

function openCreate(): void {
  designerDefinitionId.value = undefined
  designerDialogVisible.value = true
}

function openDesigner(row: DefinitionVo): void {
  designerDefinitionId.value = row.id
  designerDialogVisible.value = true
}

onMounted(() => {
  void loadDefinitionPage()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="table-header">
        <span>流程定义</span>
        <el-button v-perms="'bpmn:definition:deploy'" type="primary" @click="openCreate">
          新建流程
        </el-button>
      </div>
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
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <!-- 查看图：页面本身即 bpmn:definition:list 域（菜单路由闸），不再加指令 -->
          <el-button link type="primary" @click="openDiagram(rowOf(row))">查看图</el-button>
          <!-- 设计：编辑现有定义并部署新版本（契约 §2.2 高权限操作，perms 种子仅绑 admin） -->
          <el-button
            v-perms="'bpmn:definition:deploy'"
            link
            type="primary"
            @click="openDesigner(rowOf(row))"
            >设计</el-button
          >
        </template>
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

    <DefinitionDiagramDialog
      v-model="diagramDialogVisible"
      :definition-id="diagramDefinition?.id"
      :name="diagramDefinition?.name"
    />
    <DefinitionDesignerDialog
      v-model="designerDialogVisible"
      :definition-id="designerDefinitionId"
      @success="loadDefinitionPage()"
    />
  </el-card>
</template>

<style scoped>
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
