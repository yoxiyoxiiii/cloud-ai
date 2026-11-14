/**
 * 聚合服务接口（契约 2026-11-14-aggregate-workbench-api，cloud-aggregate :9205，网关前缀 /aggregate）
 * 注意：段数据为下游外部面出参纯透传（契约 §3 零加工）——id/total 等均为字符串（Long→String 全局口径）；
 * 段级状态语义见契约 §2：前端判断只认 section.status，msg 仅供展示不得做逻辑判断
 */
import { request } from '../utils/request'
import type { WorkbenchVo } from '../types/api'

/**
 * 工作台三段聚合（契约 §1，登录即可无 perms）：我的待办 top5 + 我的审批 top5 + 未读通知摘要
 * 一次拉全，单请求替代工作台页对 bpmn 两端点的直调；段间完全隔离——任一段失败不影响
 * 其它段与整体 code=200（契约 §2），段级失败由消费页按段 status 分流渲染
 */
export function getWorkbench(): Promise<WorkbenchVo> {
  return request<WorkbenchVo>({ url: '/aggregate/workbench', method: 'get' })
}
