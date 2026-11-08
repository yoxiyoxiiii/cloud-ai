/**
 * 通知中心接口（契约 2026-11-08-notify-api，cloud-system，网关前缀 /system）
 * 鉴权两档（契约 §0）：§1/§2 管理端逐端点 perms；§3 用户侧登录即可（无 perms）。
 * 注意：id/templateId/userIds/roleIds/messageId 均为字符串（Long→String）；
 * 错误码 3043-3049 归 notify 域（契约 §4）——3043 模板编码已存在 / 3044 模板不存在 /
 * 3045 模板变量缺失（msg 动态列缺失变量名）/ 3046 解析后超长 / 3047 接收人列表为空 /
 * 3048 收件记录不存在（含非本人，同文案）/ 3049 内置模板禁止删除
 */
import { request } from '../utils/request'
import type {
  NotifySendRequest,
  NotifySendResultVo,
  NotifySummaryVo,
  NotifyTemplateEditPayload,
  NotifyTemplatePageQuery,
  NotifyTemplateSavePayload,
  NotifyTemplateVo,
  NotifyUserMessagePageQuery,
  NotifyUserMessageVo,
  PageResult,
} from '../types/api'

/** 模板分页（契约 §1.1，perms system:notify:list）：name/code 模糊 + type 精确，全可选 */
export function pageTemplate(query: NotifyTemplatePageQuery): Promise<PageResult<NotifyTemplateVo>> {
  return request<PageResult<NotifyTemplateVo>>({ url: '/system/notify/template/page', method: 'get', params: query })
}

/** 模板详情（契约 §1.2，perms system:notify:list）：id 无效 3044 */
export function getTemplate(id: string): Promise<NotifyTemplateVo> {
  return request<NotifyTemplateVo>({ url: `/system/notify/template/${id}`, method: 'get' })
}

/** 新增模板（契约 §1.3，perms system:notify:add）：参数不合法 1001 / code 重复 3043 */
export function addTemplate(payload: NotifyTemplateSavePayload): Promise<null> {
  return request<null>({ url: '/system/notify/template', method: 'post', data: payload })
}

/** 修改模板（契约 §1.4，perms system:notify:edit）：内置模板内容可改；1001 / 3043 / 3044 */
export function editTemplate(payload: NotifyTemplateEditPayload): Promise<null> {
  return request<null>({ url: '/system/notify/template', method: 'put', data: payload })
}

/** 删除模板（契约 §1.5，perms system:notify:remove）：逻辑删；3044 不存在 / 3049 内置禁删 */
export function removeTemplate(id: string): Promise<null> {
  return request<null>({ url: `/system/notify/template/${id}`, method: 'delete' })
}

/**
 * 发送通知（契约 §2.1，perms system:notify:send）：同步事务发送，返回
 * messageId + receiverCount（实际展开收件人数）；失败 1001（入参不配套）/ 3044 /
 * 3045（变量缺失，msg 列名）/ 3046（解析后超长）/ 3047（收件人为空）
 */
export function sendNotify(payload: NotifySendRequest): Promise<NotifySendResultVo> {
  return request<NotifySendResultVo>({ url: '/system/notify/send', method: 'post', data: payload })
}

/** 未读摘要（契约 §3.1，登录即可）：unreadCount/latestId 均字符串——30s 轮询唯一接口 */
export function getNotifySummary(): Promise<NotifySummaryVo> {
  return request<NotifySummaryVo>({ url: '/system/notify/summary', method: 'get' })
}

/** 我的消息分页（契约 §3.2，登录即可）：按收件行 id 倒序；type/readFlag 可选过滤 */
export function pageMyNotify(query: NotifyUserMessagePageQuery): Promise<PageResult<NotifyUserMessageVo>> {
  return request<PageResult<NotifyUserMessageVo>>({ url: '/system/notify/page', method: 'get', params: query })
}

/** 单条已读（契约 §3.3，登录即可）：幂等（已读重复调用成功）；不存在或非本人 3048 */
export function readNotify(id: string): Promise<null> {
  return request<null>({ url: `/system/notify/read/${id}`, method: 'put' })
}

/** 全部已读（契约 §3.4，登录即可）：返回本次影响行数（toast「已读 N 条」用） */
export function readNotifyAll(): Promise<number> {
  return request<number>({ url: '/system/notify/read-all', method: 'put' })
}
