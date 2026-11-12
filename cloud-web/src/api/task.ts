/**
 * 任务中心接口（契约 2026-11-10-task-center-api）
 * 管理面四端点在 cloud-task（网关前缀 /task）；演示提交端点在 cloud-system（/system/task-demo/submit，
 * 不是 /task 前缀——服务内经 Feign 转 cloud-task 的 inner 提交面）。
 * perms（契约 §10）：分页/详情 task:asyncTask:list（隐含在菜单可见性）/ 取消 task:asyncTask:cancel /
 * 重试 task:asyncTask:retry / 演示提交 task:demo:submit。
 * 错误码 6xxx task 段（契约 §9）：6004 当前状态不允许取消 / 6005 不允许重试 / 6006 任务不存在 /
 * 6002 参数越界——拦截器统一 toast 透出后端 msg，页面不重复处理。
 * 注意：taskId 一律字符串（Long→String 口径）。
 */
import { request } from '../utils/request'
import type {
  DemoSubmitPayload,
  PageResult,
  TaskDetailVo,
  TaskPageQuery,
  TaskSubmitResult,
  TaskVo,
} from '../types/api'

/** 任务分页（契约 §3，perms task:asyncTask:list）：rows 按提交时间倒序 */
export function pageTask(query: TaskPageQuery): Promise<PageResult<TaskVo>> {
  return request<PageResult<TaskVo>>({ url: '/task/page', method: 'get', params: query })
}

/** 任务详情（契约 §4，perms task:asyncTask:list）：含 params/result/超时/取消标志/执行器；6006 不存在 */
export function getTaskDetail(id: string): Promise<TaskDetailVo> {
  return request<TaskDetailVo>({ url: `/task/${id}`, method: 'get' })
}

/** 取消任务（契约 §5，perms task:asyncTask:cancel）：排队直接取消；执行中为协作式（下一检查点生效）；终态 6004 */
export function cancelTask(id: string): Promise<null> {
  return request<null>({ url: `/task/${id}/cancel`, method: 'post' })
}

/** 重试任务（契约 §6，perms task:asyncTask:retry）：仅失败/已取消可重试（状态回排队、retryCount+1）；其余 6005 */
export function retryTask(id: string): Promise<null> {
  return request<null>({ url: `/task/${id}/retry`, method: 'post' })
}

/** 提交演示任务（契约 §7，perms task:demo:submit）：模拟业务模块经 Feign 提交，submitterAccount=当前登录账号 */
export function submitDemoTask(payload: DemoSubmitPayload): Promise<TaskSubmitResult> {
  return request<TaskSubmitResult>({ url: '/system/task-demo/submit', method: 'post', data: payload })
}
