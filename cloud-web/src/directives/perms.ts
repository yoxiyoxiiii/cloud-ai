/**
 * v-perms 按钮级权限指令（契约 perms-api §4 / 设计 D2/D4）：
 * - 值类型 string | string[]：单值 = 快照含该 perms 显示；数组 = 任一命中（some）显示
 * - 隐藏 = DOM 移除（v-if 语义，非 disabled）：快照已保证"可见即可操作"，disabled 暴露
 *   功能存在性且诱导点击得 403；EP 相邻按钮间距用 `.el-button + .el-button` 兄弟选择器，
 *   display:none 的元素仍占选择器位会留间距残迹，移除则布局自然收拢
 * - 未加载 fail-closed：perm store 未 loaded 恒判无权限移除（与后端执法同向的保守隐藏；
 *   守卫原子门下组件挂载前恒已 loaded，该分支为纯防御，覆盖未来 public 页误用等时序）
 * - mounted + updated 双钩子复检：perms 会话内不变（快照语义），updated 实为防御性冗余，
 *   覆盖 el-table 行数据刷新 / keep-alive 复活等同元素重渲染路径，时序边界零假设
 * - 仅用于原生元素/单根组件（如 el-button，指令作用其根元素）：多根组件上自定义指令
 *   会被 Vue 忽略并告警（开发期可见）
 * - removeChild 绕过 Vue patch 的说明：业界标准实现（vue-element-admin v-perms 同款）
 *   多年验证 + updated 复检兜底；Vue 对已 detach 元素的 patch 无插回 DOM 的副作用路径
 */
import type { Directive } from 'vue'
import { hasPerm, type PermValue } from '../stores/perm'

export const vPerms: Directive<HTMLElement, PermValue> = {
  mounted(el, binding) {
    if (!hasPerm(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
  updated(el, binding) {
    if (!hasPerm(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}
