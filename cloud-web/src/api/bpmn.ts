/**
 * 审批平台接口（契约 2026-10-08-approval-platform-api，cloud-bpmn，网关前缀 /bpmn）
 * 三 controller：/task 平台任务（§2）/ /approval 审批单（§3）/ /definition 流程定义（v1 §4 保留 + diagram 契约增量）
 * 注意：id/taskId/approvalId/total 均为字符串（Long→String）；错误码 4xxx 段归 bpmn 域（§6：
 * 4010-4017 新增，4008/4009 继续现行，4001-4007 已废弃随 leave 迁 system 承接）
 * leave 业务面已迁 cloud-system（/system/leave，api/systemLeave.ts）——本模块零 leave 函数
 */
import { request } from '../utils/request'
import type {
  ApprovalDetailVo,
  ApprovalDiagramVo,
  ApprovalVo,
  DefinitionVo,
  DefinitionXmlVo,
  DeployResultVo,
  PageResult,
  TaskCompletePayload,
  TaskDoneVo,
  TaskVo,
} from '../types/api'

export interface ApprovalPageQuery {
  /** 页码（1 起） */
  pageNum: number
  /** 每页条数（后端分页插件 maxLimit 200） */
  pageSize: number
}

export interface DefinitionPageQuery {
  pageNum: number
  pageSize: number
}

/** 待办任务列表（契约 §2.1，perms bpmn:task:list）：assignee=当前登录人，任务创建时间倒序；不分页（契约现状）；数据源 bpmn_approval 快照零业务回查 */
export function listTodoTasks(): Promise<TaskVo[]> {
  return request<TaskVo[]>({ url: '/bpmn/task/todo', method: 'get' })
}

/** 已办任务列表（契约 §2.2，perms bpmn:task:list）：taskAssignee=当前登录人且 finished，办理时间倒序；不分页（契约现状） */
export function listDoneTasks(): Promise<TaskDoneVo[]> {
  return request<TaskDoneVo[]>({ url: '/bpmn/task/done', method: 'get' })
}

/** 办理任务（契约 §2.3，perms bpmn:task:complete）：approve 为 "true"/"false" 字符串；任务不存在或已被办理得 4016 */
export function completeTask(payload: TaskCompletePayload): Promise<null> {
  return request<null>({ url: '/bpmn/task/complete', method: 'post', data: payload })
}

/** 我的审批分页（契约 §3.1，perms bpmn:approval:list）：恒按当前登录人 applyUser，id 倒序，含全部状态与业务类型（无查询参数——契约现状） */
export function getApprovalPage(query: ApprovalPageQuery): Promise<PageResult<ApprovalVo>> {
  return request<PageResult<ApprovalVo>>({ url: '/bpmn/approval/page', method: 'get', params: query })
}

/** 审批单详情（契约 §3.2，perms bpmn:approval:list）：approval 主体 + steps 时间线（升序）；审批单不存在得 4010 */
export function getApprovalDetail(id: string): Promise<ApprovalDetailVo> {
  return request<ApprovalDetailVo>({ url: `/bpmn/approval/${id}`, method: 'get' })
}

/** 撤销审批（契约 §3.3，perms bpmn:approval:cancel）：仅申请人本人 + 审批中；4010 不存在 / 4012 非本人 / 4011 已终态（校验顺序 4010→4012→4011） */
export function cancelApproval(id: string): Promise<null> {
  return request<null>({ url: `/bpmn/approval/cancel/${id}`, method: 'put' })
}

/**
 * 审批单图数据（契约 §3.4，perms bpmn:approval:list）：definitionId + 三态高亮数据
 * （activeActivityIds/completedActivityIds/endActivityId，三态矩阵见类型注释）；
 * 历史实例缺失为防御态 definitionId=null（前端隐藏图区）；审批单不存在得 4010
 */
export function getApprovalDiagram(id: string): Promise<ApprovalDiagramVo> {
  return request<ApprovalDiagramVo>({ url: `/bpmn/approval/${id}/diagram`, method: 'get' })
}

/** 流程定义分页（v1 契约 §4.1，perms bpmn:definition:list）：latestVersion 过滤，key 升序（契约 §0.3 保留零变化） */
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
