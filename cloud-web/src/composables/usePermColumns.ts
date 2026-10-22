/**
 * 列级数据权限动态列 composable（契约 2026-10-22-dataperm-column-visibility-api §1.1 前端消费口径；
 * 设计 D65——非契约面，工程内部封装）
 *
 * 拉取时机约定：与列表分页请求并行重拉（页面 load 函数内 `void load()` 不阻塞表格）——
 * 对齐数据权限「每请求实时求值」语义，规则变更后下一次翻页/刷新即同步列集合。
 *
 * 降级语义：load 失败（含出参缺 columns 字段的运行时异常）catch 后置空两个集合不抛——
 * 按全列渲染，值层防线在后端（隐藏=null/脱敏=***），降级不泄露明文（fail-open 安全论证）。
 */
import { ref } from 'vue'
import { getMyScope } from '../api/dataPerm'
import { ACTION_HIDDEN, ACTION_MASKED } from '../types/api'

/**
 * 按资源消费列级数据权限：hidden 集合内的列页面整列不渲染（v-if），masked 集合仅作状态查询
 * （脱敏列保留显示——值已是 ***，由后端值层处理，模板无需据此隐藏）
 *
 * @param resource 资源标识（唯一声明点常量，如 DATAPERM_RESOURCE_USER）
 */
export function usePermColumns(resource: string) {
  /** action=0（隐藏）的 columnKey 集合——列 v-if 驱动源 */
  const hiddenColumns = ref(new Set<string>())
  /** action=1（脱敏）的 columnKey 集合——列保留显示，仅状态查询用 */
  const maskedColumns = ref(new Set<string>())

  /** 拉取 my-scope 并按动作分组（无动作/失败均归空集=全列渲染） */
  async function load(): Promise<void> {
    try {
      const scope = await getMyScope(resource)
      const hidden = new Set<string>()
      const masked = new Set<string>()
      for (const col of scope.columns) {
        if (col.action === ACTION_HIDDEN) {
          hidden.add(col.columnKey)
        } else if (col.action === ACTION_MASKED) {
          masked.add(col.columnKey)
        }
      }
      hiddenColumns.value = hidden
      maskedColumns.value = masked
    } catch {
      // 降级全列（见文件头降级语义）；拦截器已统一 toast，不再抛出
      hiddenColumns.value = new Set()
      maskedColumns.value = new Set()
    }
  }

  /** 该列是否被隐藏（true → 模板 v-if 不渲染整列） */
  function isHidden(columnKey: string): boolean {
    return hiddenColumns.value.has(columnKey)
  }

  /** 该列是否被脱敏（true → 列保留显示，值已是 ***） */
  function isMasked(columnKey: string): boolean {
    return maskedColumns.value.has(columnKey)
  }

  return { load, isHidden, isMasked }
}
