/**
 * bpmn-js-properties-panel@5 类型补齐（F1 安装核对结论）：
 * 官方包未随 dist 发布任何 .d.ts（已核对 node_modules），按 v5 实际导出面做最小声明——
 * v5 已移除 v1 的 attachTo API，属性面板经 Modeler additionalModules 接入，
 * 容器由构造项 propertiesPanel: { parent } 指定（设计 D5 所述形态的 v5 等价实现）。
 * 导出面来源：dist/index.esm.js 末行 export 清单（2026-10-08 安装时核对）。
 */
declare module 'bpmn-js-properties-panel' {
  /** 属性面板渲染模块（挂 propertiesPanel.parent 容器，随选中元素联动重渲染） */
  export const BpmnPropertiesPanelModule: import('didi').ModuleDeclaration
  /** BPMN 通用属性分组 provider（MVP 默认面板——设计 D5，不定制自定义分组） */
  export const BpmnPropertiesProviderModule: import('didi').ModuleDeclaration
}
