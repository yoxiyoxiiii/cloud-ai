/**
 * 数据权限接口（契约 2026-10-10-data-permission-api §3，cloud-system，网关前缀 /system）
 * 注意：id 均为字符串（Long→String）；错误码 3031-3034 归数据权限域（契约 §8）
 * 规则即时生效（每请求实时求值，无快照无缓存——与功能权限的登录快照语义不同，契约 §1）
 */
import { request } from '../utils/request'
import type {
  DataPermExplainVo,
  DataPermLogPageQuery,
  DataPermLogVo,
  DataPermResourceVo,
  DataPermRuleConfigVo,
  DataPermRulePageQuery,
  DataPermRuleSavePayload,
  DataPermRuleVo,
  ExplainQuery,
  MyScopeVo,
  PageResult,
  RuleConfigQuery,
  SubjectOptionVo,
} from '../types/api'

/** 规则分页（契约 §3.1，perms system:dataPerm:list）：动态筛选组合，id 倒序；服务层补 subjectName */
export function pageRule(query: DataPermRulePageQuery): Promise<PageResult<DataPermRuleVo>> {
  return request<PageResult<DataPermRuleVo>>({
    url: '/system/data-perm/rule/page',
    method: 'get',
    params: query,
  })
}

/**
 * 规则配置回显（契约 §3.2，perms system:dataPerm:list）：三项全必填；
 * 无行规则返回 configured=false 且字段默认（前端弹窗据此走新增态）；主体无效 3032 / 资源非法 3034
 */
export function getRuleConfig(query: RuleConfigQuery): Promise<DataPermRuleConfigVo> {
  return request<DataPermRuleConfigVo>({
    url: '/system/data-perm/rule/config',
    method: 'get',
    params: query,
  })
}

/**
 * 保存规则（契约 §3.3，perms system:dataPerm:save）：upsert 全量覆盖语义——
 * 行规则按 uk 判存 insert/update + 列规则按主体物理全删后批插（空列表仅删不插）
 */
export function saveRule(payload: DataPermRuleSavePayload): Promise<null> {
  return request<null>({ url: '/system/data-perm/rule', method: 'post', data: payload })
}

/** 删除规则（契约 §3.4，perms system:dataPerm:remove）：连带物理删同主体全部列规则；不存在 3031 */
export function deleteRule(id: string): Promise<null> {
  return request<null>({ url: `/system/data-perm/rule/${id}`, method: 'delete' })
}

/** 资源注册表（契约 §3.5，perms system:dataPerm:list）：全部已注册资源与可配列清单（试点单元素 leave） */
export function listResources(): Promise<DataPermResourceVo[]> {
  return request<DataPermResourceVo[]>({ url: '/system/data-perm/resources', method: 'get' })
}

/** 主体选项（契约 §3.6，perms system:dataPerm:list）：type 0=角色 / 1=用户（否则 1002）；仅启用主体，label 后端拼好 */
export function listSubjectOptions(type: number): Promise<SubjectOptionVo[]> {
  return request<SubjectOptionVo[]>({ url: '/system/data-perm/subject-options', method: 'get', params: { type } })
}

/** 决策留痕分页（契约 §3.7，perms system:dataPerm:list）：账号/资源精确筛选，id 倒序；explain/my-scope 不留痕（不在本表） */
export function pageDataPermLog(query: DataPermLogPageQuery): Promise<PageResult<DataPermLogVo>> {
  return request<PageResult<DataPermLogVo>>({ url: '/system/data-perm/log/page', method: 'get', params: query })
}

/** 模拟解释（契约 §3.8，perms system:dataPerm:list）：以被模拟账号身份完整求值（不留痕不执行业务查询）；目标用户无效 3032 */
export function explainDataPerm(query: ExplainQuery): Promise<DataPermExplainVo> {
  return request<DataPermExplainVo>({ url: '/system/data-perm/explain', method: 'get', params: query })
}

/**
 * 我的数据范围（契约 §3.9，免 @PreAuthorize 登录即可——自查本人范围，设计 D12）：列表页提示条专用，不留痕。
 * resource 取值域：'leave' 请假单（契约 2026-10-10-data-permission-api §1）/
 * 'bpmn_approval' 审批单（远程资源，契约 2026-10-10-dataperm-component-api §4 新增，常量
 * DATAPERM_RESOURCE_APPROVAL）——非法 resource 得 3034，调用页 v-if 兜底不渲染提示条
 */
export function getMyScope(resource: string): Promise<MyScopeVo> {
  return request<MyScopeVo>({ url: '/system/data-perm/my-scope', method: 'get', params: { resource } })
}
