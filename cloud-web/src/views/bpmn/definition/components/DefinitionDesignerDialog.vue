<script setup lang="ts">
/**
 * 流程定义在线设计器弹窗（计划 F4 / 设计 D5）：
 * - 双 tab：「流程图」Modeler + properties-panel（v5 接入法：additionalModules 两 Module +
 *   构造项 propertiesPanel.parent 指定容器——v5 已无 v1 的 attachTo API，等价实现）
 *   /「XML 源码」只读 pre（契约 §2.1 消费位之一；切 tab 时 saveXML 同步）
 * - 打开态：新建 = 空模板画布（palette 可直接拖入）；编辑 = getDefinitionXml 回填
 * - 保存部署：saveXML(format) → File('process.bpmn20.xml') → deployDefinition（multipart，契约 §2.2）
 *   → toast「部署成功 {key} v{version}」→ emit success 刷新列表 + 关弹窗
 * - 分包铁律（D1）：Modeler/properties-panel JS 全部 dynamic import；下方 CSS 静态 import
 *   随本组件 async chunk（本组件在被消费处 defineAsyncComponent 引入）
 * - 关闭即销毁 Modeler 防泄漏；bootSeq 防快速开关的异步竞态
 */
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { deployDefinition, getDefinitionXml } from '../../../../api/bpmn'
import type Canvas from 'diagram-js/lib/core/Canvas'
// bpmn-js Modeler 基础样式（djs 容器/建模交互/图标字体）+ 属性面板样式——随本组件 async chunk 分包
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import '@bpmn-io/properties-panel/dist/assets/properties-panel.css'

interface Props {
  modelValue: boolean
  /** 编辑态 definitionId；缺省 = 新建（计划 F4 两种打开态） */
  definitionId?: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 部署成功：父组件刷新定义列表 */
  success: []
}>()

/** 新建态初始画布：空 process + 空 plane（palette 拖入即建根元素——bpmn.io「new diagram」样板形态） */
const EMPTY_DIAGRAM_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="Definitions_New" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_New" isExecutable="true"></bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_New">
    <bpmndi:BPMNPlane id="BPMNPlane_New" bpmnElement="Process_New"></bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`

/** Modeler 实例（纯类型引用，编译期擦除——不构成静态依赖） */
let modeler: import('bpmn-js/lib/Modeler').default | undefined

const canvasRef = ref<HTMLElement>()
const panelRef = ref<HTMLElement>()
const fileInputRef = ref<HTMLInputElement>()

const booting = ref(false)
const bootFailed = ref(false)
const deploying = ref(false)
const activeTab = ref<'diagram' | 'xml'>('diagram')
const sourceXml = ref('')

/** 打开序号：快速关闭重开时在途异步结果作废 */
let bootSeq = 0

/** 从 Modeler 同步当前 XML 到源码态（saveXML format:true——两态同一出口的同步源） */
async function syncSourceXml(): Promise<void> {
  if (!modeler) {
    return
  }
  const { xml } = await modeler.saveXML({ format: true })
  if (xml) {
    sourceXml.value = xml
  }
}

/** 打开态初始化：编辑=契约 §2.1 取 XML 回填；新建=空模板 */
async function boot(): Promise<void> {
  const seq = ++bootSeq
  activeTab.value = 'diagram'
  sourceXml.value = ''
  bootFailed.value = false
  booting.value = true
  try {
    let initialXml = EMPTY_DIAGRAM_XML
    if (props.definitionId) {
      const vo = await getDefinitionXml(props.definitionId)
      if (seq !== bootSeq) {
        return
      }
      initialXml = vo.xml
    }
    // el-dialog 首开懒渲染：等容器进 DOM
    await nextTick()
    if (seq !== bootSeq || !canvasRef.value || !panelRef.value) {
      return
    }
    // 分包铁律：Modeler 与 properties-panel 动态加载（独立 async chunk）
    const [{ default: Modeler }, panelModules] = await Promise.all([
      import('bpmn-js/lib/Modeler'),
      import('bpmn-js-properties-panel'),
    ])
    if (seq !== bootSeq || !canvasRef.value || !panelRef.value) {
      return
    }
    modeler = new Modeler({
      container: canvasRef.value,
      propertiesPanel: { parent: panelRef.value },
      additionalModules: [
        panelModules.BpmnPropertiesPanelModule,
        panelModules.BpmnPropertiesProviderModule,
      ],
    })
    await modeler.importXML(initialXml)
    if (seq !== bootSeq) {
      return
    }
    modeler.get<Canvas>('canvas').zoom('fit-viewport')
    await syncSourceXml()
  } catch {
    if (seq === bootSeq) {
      bootFailed.value = true
    }
  } finally {
    if (seq === bootSeq) {
      booting.value = false
    }
  }
}

/** 销毁 Modeler 与面板残留 DOM（关闭/卸载两路径共用） */
function destroyModeler(): void {
  bootSeq++
  modeler?.destroy()
  modeler = undefined
  if (canvasRef.value) {
    canvasRef.value.innerHTML = ''
  }
  if (panelRef.value) {
    panelRef.value.innerHTML = ''
  }
  sourceXml.value = ''
  bootFailed.value = false
  booting.value = false
  deploying.value = false
}

function triggerImportFile(): void {
  fileInputRef.value?.click()
}

/** 导入本地 .bpmn/.xml（计划 F4）：文本读入 → importXML 回填；解析失败提示（前端自防，未到后端） */
async function handleFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 复位以支持重复选择同一文件
  if (!file || !modeler) {
    return
  }
  try {
    const text = await file.text()
    await modeler.importXML(text)
    modeler.get<Canvas>('canvas').zoom('fit-viewport')
    await syncSourceXml()
    ElMessage.success('导入成功')
  } catch {
    ElMessage.error('文件解析失败：不是有效的 BPMN 2.0 XML')
  }
}

function fitCanvas(): void {
  modeler?.get<Canvas>('canvas').zoom('fit-viewport')
}

/** 切到 XML 源码 tab 时同步最新画布内容（画布为唯一事实源，源码只读） */
async function handleTabChange(tab: string | number): Promise<void> {
  if (tab === 'xml') {
    try {
      await syncSourceXml()
    } catch {
      // 保持已同步内容（拦截器不涉——saveXML 为本地序列化）
    }
  }
}

/** 保存部署（契约 §2.2 multipart）：当前画布 saveXML → File → deployDefinition */
async function handleDeploy(): Promise<void> {
  if (!modeler || deploying.value) {
    return
  }
  deploying.value = true
  try {
    const { xml } = await modeler.saveXML({ format: true })
    if (!xml) {
      ElMessage.error('无法生成流程 XML')
      return
    }
    sourceXml.value = xml
    const file = new File([xml], 'process.bpmn20.xml', { type: 'application/xml' })
    const result = await deployDefinition(file)
    // definitions 为本次部署产生的定义（不做 latestVersion 过滤），逐个拼接「key vN」
    const summary = result.definitions.map((d) => `${d.key} v${d.version}`).join('、')
    ElMessage.success(summary ? `部署成功 ${summary}` : '部署成功')
    emit('success')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已统一 toast（4009 文件无效/超限等）
  } finally {
    deploying.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void boot()
    } else {
      destroyModeler()
    }
  },
)

onUnmounted(destroyModeler)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="definitionId ? '编辑流程' : '新建流程'"
    width="1100px"
    top="5vh"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <!-- 版本语义防呆（设计 D5，常驻）：引擎原生在途实例走发起时版本 -->
    <el-alert
      class="designer-alert"
      type="info"
      show-icon
      :closable="false"
      title="新版本仅对新发起的流程生效，在途流程继续走原版本"
    />

    <!-- 画布容器常驻渲染（bugfix F7）：canvasRef/panelRef 不得置于 v-if 分支内——
         boot() 在 booting=true 期间取 ref 挂 Modeler，若容器随 v-if=v-booting 消失则永远空画布 -->
    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <el-tab-pane label="流程图" name="diagram">
        <div class="designer-toolbar">
          <el-button size="small" @click="triggerImportFile">导入本地文件</el-button>
          <el-button size="small" @click="fitCanvas">适应画布</el-button>
          <input
            ref="fileInputRef"
            class="designer-file-input"
            type="file"
            accept=".bpmn,.xml"
            @change="handleFileChange"
          />
        </div>
        <div class="designer-body" v-loading="booting">
          <div v-if="bootFailed" class="designer-failed">设计器加载失败</div>
          <template v-else>
            <div ref="canvasRef" class="designer-canvas"></div>
            <div ref="panelRef" class="designer-panel"></div>
          </template>
        </div>
      </el-tab-pane>
      <el-tab-pane label="XML 源码" name="xml">
        <pre class="designer-xml">{{ sourceXml || '暂无内容' }}</pre>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button
        v-perms="'bpmn:definition:deploy'"
        type="primary"
        :loading="deploying"
        @click="handleDeploy"
      >
        保存部署
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.designer-alert {
  margin-bottom: 12px;
}

/* 失败态：铺满画布区居中（bugfix F7——随容器常驻结构内呈现） */
.designer-failed {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.designer-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

/* 原生 input 隐藏，由「导入本地文件」按钮触发点击 */
.designer-file-input {
  display: none;
}

/* 画布 + 属性面板并置（D5：左 canvas 右 properties-panel） */
.designer-body {
  display: flex;
  height: 560px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
}

.designer-canvas {
  flex: 1;
  min-width: 0;
  height: 100%;
}

.designer-panel {
  width: 300px;
  height: 100%;
  border-left: 1px solid var(--el-border-color-lighter);
  overflow-y: auto;
}

.designer-xml {
  height: 560px;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
