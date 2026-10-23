/**
 * 表格列配置接口（契约 2026-10-23-table-config-api，cloud-system，网关前缀 /system）
 * 个人表格视图偏好（列序/显隐/列宽）三端点：服务端盲存只校验格式，登录即可，
 * 不收前端传 userId（服务端登录态取，契约 §3 冻结点）
 */
import { request } from '../utils/request'
import type { TableConfigSavePayload, TableConfigVo } from '../types/api'

/** 查询本人配置（契约 §2.1）：无配置时 resolve null（页面按默认列序渲染） */
export function getTableConfig(tableKey: string): Promise<TableConfigVo | null> {
  return request<TableConfigVo | null>({
    url: `/system/table-config/${tableKey}`,
    method: 'get',
  })
}

/** 保存本人配置（契约 §2.2）：整体覆盖（upsert）；columns 顺序即列序 */
export function saveTableConfig(
  tableKey: string,
  payload: TableConfigSavePayload,
): Promise<null> {
  return request<null>({
    url: `/system/table-config/${tableKey}`,
    method: 'put',
    data: payload,
  })
}

/** 恢复默认（契约 §2.3）：物理删本人配置行；行不存在同样成功（幂等） */
export function resetTableConfig(tableKey: string): Promise<null> {
  return request<null>({
    url: `/system/table-config/${tableKey}`,
    method: 'delete',
  })
}
