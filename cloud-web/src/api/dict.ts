/**
 * 数据字典接口（契约 2026-10-07-dict-api §2/§3，cloud-system，网关前缀 /system）
 * 两级模型：字典类型（/system/dict/type）→ 字典项（/system/dict/data，经 typeId 归属，§1）
 * 注意：id/typeId 均为字符串（Long→String）；错误码 3008-3012 归字典域（契约 §5）
 */
import { request } from '../utils/request'
import type {
  DictDataPageQuery,
  DictItemVo,
  DictTypePageQuery,
  PageResult,
  SaveDictDataPayload,
  SaveDictTypePayload,
  SysDictDataVo,
  SysDictTypeVo,
  UpdateDictDataPayload,
  UpdateDictTypePayload,
} from '../types/api'

/** 字典类型分页（契约 §2.1）：含停用类型（管理端点不过滤），id 倒序；无搜索参数（契约现状） */
export function pageDictType(query: DictTypePageQuery): Promise<PageResult<SysDictTypeVo>> {
  return request<PageResult<SysDictTypeVo>>({
    url: '/system/dict/type/page',
    method: 'get',
    params: query,
  })
}

/** 新增字典类型（契约 §2.2）：返回新类型 id 字符串；dictKey 重复得 code 3009 */
export function createDictType(payload: SaveDictTypePayload): Promise<string> {
  return request<string>({ url: '/system/dict/type', method: 'post', data: payload })
}

/** 修改字典类型（契约 §2.3）：dictKey 可修改（唯一性校验排除自身）；null 不更新，前端全量提交三写字段 + id */
export function updateDictType(payload: UpdateDictTypePayload): Promise<null> {
  return request<null>({ url: '/system/dict/type', method: 'put', data: payload })
}

/** 删除字典类型（契约 §2.4）：存在未删项得 code 3011（禁删，先删字典项）；删除 = 逻辑删除 */
export function deleteDictType(id: string): Promise<null> {
  return request<null>({ url: `/system/dict/type/${id}`, method: 'delete' })
}

/** 字典项分页（契约 §3.1）：typeId 必传（缺失 1002，类型不存在 3008）；sort 升序 id 升序 */
export function pageDictData(query: DictDataPageQuery): Promise<PageResult<SysDictDataVo>> {
  return request<PageResult<SysDictDataVo>>({
    url: '/system/dict/data/page',
    method: 'get',
    params: query,
  })
}

/** 新增字典项（契约 §3.2）：返回新项 id 字符串；同类型 value 重复得 code 3012 */
export function createDictData(payload: SaveDictDataPayload): Promise<string> {
  return request<string>({ url: '/system/dict/data', method: 'post', data: payload })
}

/** 修改字典项（契约 §3.3）：前端全量提交五写字段 + id（typeId 亦提交）；查重排除自身 */
export function updateDictData(payload: UpdateDictDataPayload): Promise<null> {
  return request<null>({ url: '/system/dict/data', method: 'put', data: payload })
}

/** 删除字典项（契约 §3.4）：不校验所属类型存活（遗留数据治理入口）；墓碑占 (typeId, value) */
export function deleteDictData(id: string): Promise<null> {
  return request<null>({ url: `/system/dict/data/${id}`, method: 'delete' })
}

/**
 * 按字典键取启用项（契约 2026-10-07-translation-api §2.1）：消费口径（类型启用 ∧ 项启用）sort 升序；
 * 登录即可（无 @PreAuthorize，表单下拉是登录用户基础能力）；未知 dictKey / 类型停用 / 无启用项
 * 一律 200 + 空数组（表单容错优先，非错误）。本轮暂无页面调用——契约先行，后续表单下拉统一取数入口
 */
export function getDictItems(dictKey: string): Promise<DictItemVo[]> {
  return request<DictItemVo[]>({ url: `/system/dict/data/type/${dictKey}`, method: 'get' })
}
