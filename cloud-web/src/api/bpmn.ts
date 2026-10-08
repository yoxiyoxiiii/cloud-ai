/**
 * 请假工作流接口（契约 2026-10-07-bpmn-leave-api，cloud-bpmn，网关前缀 /bpmn）
 * 三 controller：/leave 请假单（§2）/ /task 任务（§3）/ /definition 流程定义（§4）
 * 注意：id/taskId/leaveId/total 均为字符串（Long→String）；错误码 4xxx 段归 bpmn 域（§5）
 * 增量契约 2026-10-08-bpmn-diagram-designer-api（additive）：/leave/{id}/diagram（§3）、
 * /definition/{id}/xml（§2.1）、/definition/deploy（§2.2，multipart）——定义域不再只读（§0.1）
 */
import { request } from '../utils/request'
import type {
  DefinitionVo,
  DefinitionXmlVo,
  DeployResultVo,
  LeaveCreatePayload,
  LeaveDetailVo,
  LeaveDiagramVo,
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

/** 流程定义分页（契约 §4.1，perms bpmn:definition:list）：latestVersion 过滤，key 升序（v1 只读语义；写端点见下方增量契约） */
export function pageDefinitions(query: DefinitionPageQuery): Promise<PageResult<DefinitionVo>> {
  return request<PageResult<DefinitionVo>>({
    url: '/bpmn/definition/page',
    method: 'get',
    params: query,
  })
}

/**
 * 流程定义 XML（增量契约 2026-10-08-bpmn-diagram-designer-api §2.1，perms bpmn:definition:list）：
 * 返回部署时原始资源字符串；definitionId 冒号为合法路径字符无需编码；定义不存在得 4008
 */
export function getDefinitionXml(id: string): Promise<DefinitionXmlVo> {
  return request<DefinitionXmlVo>({ url: `/bpmn/definition/${id}/xml`, method: 'get' })
}

/**
 * 部署流程（增量契约 §2.2，perms bpmn:definition:deploy）：
 * multipart/form-data 非 JSON body——data 传 FormData，axios 对 FormData 自动设
 * Content-Type 与 boundary（request.ts 拦截器未强设 Content-Type，已核对）；
 * 空文件/任何尺寸超限（业务 2MB 与解析层 ≥3MB 三段式归口）/解析失败统一 4009；
 * 同 key 部署 version+1；无权限得 HTTP 200 + body 403
 */
export function deployDefinition(file: File): Promise<DeployResultVo> {
  const formData = new FormData()
  formData.append('file', file)
  return request<DeployResultVo>({ url: '/bpmn/definition/deploy', method: 'post', data: formData })
}

/**
 * 请假单图数据（增量契约 §3，perms bpmn:leave:list）：definitionId + 三态高亮数据
 * （activeActivityIds/completedActivityIds/endActivityId，三态矩阵见类型注释）；
 * 历史实例缺失为防御态 definitionId=null（前端隐藏图区）；请假单不存在得 4001
 */
export function getLeaveDiagram(id: string): Promise<LeaveDiagramVo> {
  return request<LeaveDiagramVo>({ url: `/bpmn/leave/${id}/diagram`, method: 'get' })
}
