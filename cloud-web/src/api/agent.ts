import { request } from '../utils/request'

// agent msg 返回对象
export interface AgentChatMsgVo {

}

// 请求对象
export interface AgentChatMsgReq {
    userMsg: string
}


export function agentChat(req: AgentChatMsgReq): Promise<AgentChatMsgVo> {
  return request<AgentChatMsgVo>({ url: '/ai/agent/chat/stream', method: 'post' , data: req})
}
