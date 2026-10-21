<template>
    <div class="agent-chat">
        <div class="chat-msg">
            <div v-for="msg in msgList" class="msg-row">
                <div v-if="msg.mgsType == 'user'" class="bubble user">{{ msg.msgContent }}</div>
                <div v-else class="bubble ai">{{ msg.msgContent }}</div>
            </div>
            
        </div>
        <div class="chat-button">
            <div class="user-msg">
                <el-input v-model="agentChatMsg" placeholder="请输入你的问题"/>
            </div>
            <div>
                <el-button type="primary" @click="sendMsg">发 送</el-button>
            </div>
        </div>
    </div>
</template>


<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { AgentChatMsg } from './models';
import { getAuth } from '../../utils/storage';

// 消息数组
const msgList = reactive<AgentChatMsg[]>([])

const agentChatMsg = ref('')

// 发送问题到后端agent 中
const sendMsg = async ()=> {
    // 用户的消息添加到数组中
    msgList.push({
        msgContent: agentChatMsg.value,
        mgsType: "user"
    })

    // AI 占位置
    msgList.push({
        msgContent: "思考中....",
        mgsType: "ai"
    })

    // 最后一条消息：index
    const msgIndex = msgList.length - 1


    const auth = getAuth()
    const resp = await fetch("/api/ai/agent/chat/stream", {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(auth?.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
      },
      body: JSON.stringify({"userMsg":"你好"}),
    })

    // 判定接口是否成功响应
    if (!resp.ok || !resp.body) {
        throw new Error(`Http ${resp.status}`)   
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')

    let msg = ''
    msgList[msgIndex].msgContent = ''

    // 循环读取服务端返回的数据
    for (;;) {
      const { done , value } = await reader.read()
      // 解码器，上面的reader?.read() 返回的是二进制数字
      if (done) {
        break
      }

      // 二进制转换为字符串，stream 解决半包问题
      const chunk =  decoder.decode(value, {stream: true})
      msg = msg + chunk

      let index: number
      while ((index = msg.indexOf('\n\n'))>=0) {
        // 获取一帧的数据：data:{"content":"你好"}
        // 不一定是规范的一行数据
        const frame = msg.slice(0, index)
        // 直接丢弃：\n\n 这两个字符
        msg = msg.slice(index + 2)

        // 获取一行数据：data:{"content":"你好"}
        const dataLine = frame.split('\n').find((item)=> item.startsWith('data:'))
        if (!dataLine) {
            // 空行 直接跳过
            continue
        }
        const msgContent = dataLine.slice(("data:".length)).trim()
        if ('[DONE]' == msgContent) {
            // 结束
            return
        }
        const evt = JSON.parse(msgContent) as {content?: string}
        if (evt.content) {
            // console.log('token:', evt.content)
            // 追加内容 到AI的消息类型中
            msgList[msgIndex].msgContent += evt.content 
        }

      }
    }   
}

</script>

<style scoped>
/* 页面骨架：占满内容区，消息区弹性伸展 / 输入区固定高 */
.agent-chat {
    height: 100%;
    display: flex;
    flex-direction: column;
    gap: 12px;
}

/* 消息区：白底圆角卡片，内容超出滚动 */
.chat-msg {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 16px;
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 8px;
}

/* 一行消息：气泡默认占满行内剩余空间由 margin 收边 */
.msg-row {
    display: flex;
    margin-bottom: 12px;
}
.msg-row:last-child {
    margin-bottom: 0;
}

/* 气泡：限宽防长文撑满整行，pre-wrap 保留 AI 回答里的换行 */
.bubble {
    max-width: 78%;
    padding: 10px 14px;
    border-radius: 8px;
    line-height: 1.6;
    word-break: break-word;
    white-space: pre-wrap;
}

/* 用户气泡：margin-left:auto 推到行右端（主题蓝，右对齐语义） */
.bubble.user {
    margin-left: auto;
    background: #409eff;
    color: #fff;
}

/* AI 气泡：显式占左（默认即是，写明语义），浅灰底与用户蓝形成方向区分 */
.bubble.ai {
    margin-right: auto;
    background: #f5f7fa;
}

/* 输入区：与消息区同款白底卡片，输入框与按钮横排 */
.chat-button {
    flex-shrink: 0;
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 12px;
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 8px;
}

.user-msg {
    flex: 1;
}
</style>