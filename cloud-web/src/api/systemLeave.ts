/**
 * system 请假接口（契约 2026-10-08-approval-platform-api §5，cloud-system，网关前缀 /system）
 * 自 v1 bpmn-leave-api §2 迁移（perms 换 system 域）；请假为审批平台首个接入业务
 * （发起经 bpmn /inner 通道编排，撤销双路：本端点与平台 PUT /bpmn/approval/cancel）
 * 注意：id/approvalId/total 均为字符串（Long→String）；错误码 3018-3024 归 system 请假域（契约 §6）
 */
import { request } from '../utils/request'
import type { LeaveCreatePayload, PageResult, SysLeaveDetailVo, SysLeaveVo, UserOptionVo } from '../types/api'

export interface LeavePageQuery {
  /** 页码（1 起） */
  pageNum: number
  /** 每页条数（后端分页插件 maxLimit 200；纠偏分批 ≤100 在服务端，契约 §5.2） */
  pageSize: number
}

/** 发起请假（契约 §5.1，perms system:leave:add）：system 本地事务 + Feign 平台发起编排；返回新请假单 id 字符串；日期无效得 3019、审批人无效得 3023、已存在审批得 3024、平台不可用得 3022 */
export function addLeave(payload: LeaveCreatePayload): Promise<string> {
  return request<string>({ url: '/system/leave', method: 'post', data: payload })
}

/** 我的请假分页（契约 §5.2，perms system:leave:list）：恒按当前登录人过滤（无查询参数——契约现状），id 倒序；status 经平台批量纠偏为实时值，Feign 失败降级本地快照（可能滞后记档） */
export function pageLeave(query: LeavePageQuery): Promise<PageResult<SysLeaveVo>> {
  return request<PageResult<SysLeaveVo>>({ url: '/system/leave/page', method: 'get', params: query })
}

/** 请假单详情（契约 §5.3，perms system:leave:list）：仅 leave 主体（不含时间线/图——前端按 approvalId 另调平台 §3.2/§3.4 拼装）；纠偏失败抛 3022；请假单不存在得 3018 */
export function getLeaveDetail(id: string): Promise<SysLeaveDetailVo> {
  return request<SysLeaveDetailVo>({ url: `/system/leave/${id}`, method: 'get' })
}

/** 撤销请假（契约 §5.4，perms system:leave:cancel）：仅申请人本人 + 非终态；3018 不存在 / 3021 非本人 / 3020 已终态（校验顺序 3018→3021→3020）；平台侧 4011/4012 转译同码语义 */
export function cancelLeave(id: string): Promise<null> {
  return request<null>({ url: `/system/leave/cancel/${id}`, method: 'put' })
}

/** 审批人投影（契约 §5.5，perms system:leave:add）：id/account/nickname 三字段，**仅启用账号**（v1 含停用宽松语义随迁移收紧）——发起弹窗审批人下拉数据源 */
export function getLeaveApprovers(): Promise<UserOptionVo[]> {
  return request<UserOptionVo[]>({ url: '/system/leave/approvers', method: 'get' })
}
