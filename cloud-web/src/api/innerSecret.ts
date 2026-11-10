/**
 * inner 面共享密钥管理接口（契约 2026-11-09-inner-secret-admin-api，cloud-system，网关前缀 /system）
 * perms 三枚（契约 §0）：list / rotate / revoke 均为 system:innerSecret:*。
 * 错误码 3050-3053（契约 §5）：3050 记录不存在或已退场 / 3051 禁止作废最后一把生效密钥（请先轮换）/
 * 3052 活跃集发布失败（变更已落库，定时任务一分钟内自愈重发）/ 3053 轮换请求过于频繁
 * （上一轮重叠窗口未结束，msg 带剩余秒数）——均由拦截器统一 toast msg 原文。
 * 注意：id 为字符串（Long→String）；任何出参不含密钥明文，仅 secretMask 掩码（契约红线）。
 */
import { request } from '../utils/request'
import type {
  InnerSecretPageQuery,
  InnerSecretStatusVo,
  InnerSecretVo,
  PageResult,
  RevokePayload,
} from '../types/api'

/** 密钥分页（契约 §1，perms system:innerSecret:list）：id 倒序追加式流水，status 可选精确过滤 */
export function pageInnerSecret(query: InnerSecretPageQuery): Promise<PageResult<InnerSecretVo>> {
  return request<PageResult<InnerSecretVo>>({ url: '/system/inner-secret/page', method: 'get', params: query })
}

/** 轮换状态卡（契约 §2，perms system:innerSecret:list）：Nacos 参数只读回显 + 活跃集一致性 */
export function getInnerSecretStatus(): Promise<InnerSecretStatusVo> {
  return request<InnerSecretStatusVo>({ url: '/system/inner-secret/status', method: 'get' })
}

/** 立即轮换（契约 §3，perms system:innerSecret:rotate）：返回新密钥行；3053 频控 / 3052 发布失败（新钥已落库生效） */
export function rotateInnerSecret(): Promise<InnerSecretVo> {
  return request<InnerSecretVo>({ url: '/system/inner-secret/rotate', method: 'post' })
}

/** 作废密钥（契约 §4，perms system:innerSecret:revoke）：立即移出活跃集，生效时延 ≤30 秒（非瞬时语义）；3050/3051/3052 */
export function revokeInnerSecret(payload: RevokePayload): Promise<null> {
  return request<null>({ url: '/system/inner-secret/revoke', method: 'put', data: payload })
}
