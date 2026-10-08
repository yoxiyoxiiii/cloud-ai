<script setup lang="ts">
/**
 * 流程定义查看图弹窗（计划 F4，契约 §2.1 消费位）：getDefinitionXml → BpmnViewer（无高亮）
 * definitionId 为 key:version:generated 全形态（冒号合法路径字符，无需编码——契约 §1）
 */
import { defineAsyncComponent, ref, watch } from 'vue'
import { getDefinitionXml } from '../../../../api/bpmn'

interface Props {
  modelValue: boolean
  /** 行 definitionId */
  definitionId?: string
  /** 定义名（弹窗标题语境，可空） */
  name?: string | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

/** 公共 Viewer 按需分包（设计 D1 铁律）：defineAsyncComponent 引入 */
const BpmnViewer = defineAsyncComponent(() => import('../../components/BpmnViewer.vue'))

const loading = ref(false)
const xml = ref('')
const failed = ref(false)

/** 打开时拉取定义 XML：失败 catch 留空态（拦截器已统一 toast，如 4008） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    xml.value = ''
    failed.value = false
    if (!props.definitionId) {
      return
    }
    loading.value = true
    getDefinitionXml(props.definitionId)
      .then((vo) => {
        xml.value = vo.xml
      })
      .catch(() => {
        failed.value = true
      })
      .finally(() => {
        loading.value = false
      })
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="name ? `流程图 - ${name}` : '流程图'"
    width="860px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-if="loading" class="diagram-placeholder" v-loading="true"></div>
    <BpmnViewer v-else-if="xml" :xml="xml" />
    <div v-else-if="failed" class="diagram-placeholder diagram-failed">流程图加载失败</div>
    <el-empty v-else description="暂无数据" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 占位/失败态与 Viewer 容器同高同边框（对齐 BpmnViewer 样式，视觉无跳变） */
.diagram-placeholder {
  height: 360px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.diagram-failed {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
