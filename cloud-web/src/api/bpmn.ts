/**
 * 请假工作流接口（契约 2026-10-07-bpmn-leave-api，cloud-bpmn，网关前缀 /bpmn）
 * 三 controller：/leave 请假单（§2）/ /task 任务（§3）/ /definition 流程定义（§4，只读）
 * 注意：id/taskId/leaveId/total 均为字符串（Long→String）；错误码 4xxx 段归 bpmn 域（§5）
 */
import { request } from '../utils/request'
import type {
  DefinitionVo,
  LeaveCreatePayload,
  LeaveDetailVo,
  LeaveVo,
  PageResult,
  TaskCompletePayload,
  TaskDoneVo,
  TaskVo,
  UserOptionVo,
} from '../types/api'

export interface LeavePageQuery {
  /** 页码（1 起） */
  pageNum: number
  /** 每页条数（后端分页插件 maxLimit 200） */
  pageSize: number
}

export interface DefinitionPageQuery {
  pageNum: number
  pageSize: number
}

/** 发起请假（契约 §2.1，perms bpmn:leave:add）：同事务建单（审批中）+ 启动流程实例；返回新请假单 id 字符串；审批人无效得 4004、end<start 得 4006 */
export function createLeave(payload: LeaveCreatePayload): Promise<string> {
  return request<string>({ url: '/bpmn/leave', method: 'post', data: payload })
}

/** 我的申请分页（契约 §2.2，perms bpmn:leave:list）：恒按当前登录人过滤（无查询参数——契约现状），id 倒序，含全部状态 */
export function pageLeave(query: LeavePageQuery): Promise<PageResult<LeaveVo>> {
  return request<PageResult<LeaveVo>>({ url: '/bpmn/leave/page', method: 'get', params: query })
}

/** 请假单详情（契约 §2.3，perms bpmn:leave:list）：leave 主体 + steps 时间线（升序）；不存在得 4001 */
export function getLeaveDetail(id: string): Promise<LeaveDetailVo> {
  return request<LeaveDetailVo>({ url: `/bpmn/leave/${id}`, method: 'get' })
}

/** 撤销请假（契约 §2.4，perms bpmn:leave:cancel）：仅申请人本人 + 审批中；4001 不存在 / 4002 已终态 / 4003 非本人（校验顺序 4001→4003→4002） */
export function cancelLeave(id: string): Promise<null> {
  return request<null>({ url: `/bpmn/leave/cancel/${id}`, method: 'put' })
}

/** 审批人投影（契约 §2.5，perms bpmn:leave:add）：id/account/nickname 三字段，含停用账号（宽松语义记档）——发起弹窗审批人下拉数据源 */
export function listApprovers(): Promise<UserOptionVo[]> {
  return request<UserOptionVo[]>({ url: '/bpmn/leave/approvers', method: 'get' })
}

/** 待办任务列表（契约 §3.1，perms bpmn:task:list）：assignee=当前登录人，任务创建时间倒序；不分页（契约现状） */
export function listTodoTasks(): Promise<TaskVo[]> {
  return request<TaskVo[]>({ url: '/bpmn/task/todo', method: 'get' })
}

/** 已办任务列表（契约 §3.2，perms bpmn:task:list）：taskAssignee=当前登录人且 finished，办理时间倒序；不分页（契约现状） */
export function listDoneTasks(): Promise<TaskDoneVo[]> {
  return request<TaskDoneVo[]>({ url: '/bpmn/task/done', method: 'get' })
}

/** 办理任务（契约 §3.3，perms bpmn:task:complete）：approve 为 "true"/"false" 字符串；任务不存在或已被办理得 4005 */
export function completeTask(payload: TaskCompletePayload): Promise<null> {
  return request<null>({ url: '/bpmn/task/complete', method: 'post', data: payload })
}

/** 流程定义分页（契约 §4.1，perms bpmn:definition:list）：latestVersion 过滤，key 升序；只读域——无任何写端点 */
export function pageDefinitions(query: DefinitionPageQuery): Promise<PageResult<DefinitionVo>> {
  return request<PageResult<DefinitionVo>>({
    url: '/bpmn/definition/page',
    method: 'get',
    params: query,
  })
}
