/**
 * 认证接口（契约 §2，cloud-sso，网关前缀 /sso）
 * login 使用 skipErrorMessage：登录页内联展示后端 msg，不弹全局 toast
 * getMe 为 additive 新端点（契约 2026-10-07-perms-api §2；pilot §2.1-2.5 与 LoginResult 零变化）
 */
import { request } from '../utils/request'
import type { CurrentUserVo, LoginResult, OnlineSessionVo } from '../types/api'

export interface LoginPayload {
  account: string
  password: string
}

/** 登录（契约 §2.1）：错误码 2001 账号或密码错误 / 2003 账号已停用 / 2002 服务不可用 */
export function login(payload: LoginPayload): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/sso/auth/login',
    method: 'post',
    data: payload,
    skipErrorMessage: true,
  })
}

/** 刷新（契约 §2.2，MVP 未消费：401 一律跳登录，设计 §6 方案 B） */
export function refresh(refreshToken: string): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/sso/auth/refresh',
    method: 'post',
    data: { refreshToken },
  })
}

/** 注销（契约 §2.3）：需 Bearer；注销后该 token 立即失效 */
export function logout(): Promise<null> {
  return request<null>({ url: '/sso/auth/logout', method: 'post' })
}

/** 在线会话列表（契约 §2.4，MVP 未消费） */
export function listOnline(): Promise<OnlineSessionVo[]> {
  return request<OnlineSessionVo[]>({ url: '/sso/auth/online', method: 'get' })
}

/** 强退会话（契约 §2.5，MVP 未消费） */
export function kickOnline(tokenId: string): Promise<null> {
  return request<null>({ url: `/sso/auth/online/${tokenId}`, method: 'delete' })
}

/** 当前会话信息（契约 perms-api §2）：账号 + 权限快照（登录时快照，会话内不变） */
export function getMe(): Promise<CurrentUserVo> {
  return request<CurrentUserVo>({ url: '/sso/auth/me', method: 'get' })
}
