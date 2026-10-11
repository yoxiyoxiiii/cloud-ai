/**
 * MQ 死信治理接口（契约 2026-10-11-mq-dlq-governance-api §3，cloud-system，网关前缀 /system）
 * 注意：id 均为字符串（Long→String）；错误码 3038-3041 归死信域（契约 §6）——
 * 3038 死信记录不存在 / 3039 消息重投发送失败（行不动可重试）/ 3040 死信状态不允许该操作 /
 * 3041 重投次数已达上限（行已自动置 3 已放弃，前端重拉）
 */
import { request } from '../utils/request'
import type { MqDlqDetailVo, MqDlqPageQuery, MqDlqVo, PageResult } from '../types/api'

/** 死信台账分页（契约 §3.1，perms system:mqDlq:list）：dlq_time 倒序；列表不返 body（行多体大，详情取） */
export function pageMqDlq(query: MqDlqPageQuery): Promise<PageResult<MqDlqVo>> {
  return request<PageResult<MqDlqVo>>({ url: '/system/mq-dlq/page', method: 'get', params: query })
}

/** 死信详情（契约 §3.2，perms system:mqDlq:list）：MqDlqVo 全字段 + body 原文/bodyTruncated/createTime/updateTime；id 无效 3038 */
export function getMqDlqDetail(id: string): Promise<MqDlqDetailVo> {
  return request<MqDlqDetailVo>({ url: `/system/mq-dlq/${id}`, method: 'get' })
}

/**
 * 人工重投（契约 §3.3，perms system:mqDlq:resend，高危）：清 dedup 残留 → syncSend 原 topic
 * （恢复 tag/KEYS/TX_NO/body）→ 行置已重投（resendCount+1）——**前端必须二次确认弹窗**
 * （回显 msgId/msgKey/bornTopic，警示「重投将真实触发业务动作」）；
 * 终态 3040 / 上限 3041（自动置弃后拒，重拉）/ MQ 不可达 3039（行状态不动，可重试）
 */
export function resendMqDlq(id: string): Promise<null> {
  return request<null>({ url: `/system/mq-dlq/${id}/resend`, method: 'post' })
}

/** 人工忽略（契约 §3.4，perms system:mqDlq:ignore）：仅待处理可忽略（0→2 终态，仅 admin reset 可解除）；status≠0 得 3040 */
export function ignoreMqDlq(id: string): Promise<null> {
  return request<null>({ url: `/system/mq-dlq/${id}/ignore`, method: 'post' })
}

/** 解除终态（契约 §3.5，perms system:mqDlq:reset，admin）：status∈{2,3}→0 且 resendCount 清零；非终态得 3040 */
export function resetMqDlq(id: string): Promise<null> {
  return request<null>({ url: `/system/mq-dlq/${id}/reset`, method: 'post' })
}
