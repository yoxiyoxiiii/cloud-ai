package com.cloudai.common.rocketmq.tx;

/**
 * 事务消息发送门面（设计 D1）：半消息落 broker → starter listener 本地事务（tx_log + executor）→ COMMIT/ROLLBACK。
 * <p>同步语义：返回即本地事务已提交且消息已确认投递（COMMIT 决策前本地已持久——审查 R-1 不变式）；
 * 半消息发送失败抛 TxMessageSendException（调用方转译域码，如 system 3025）；
 * 本地事务失败原样上抛执行异常（调用方走系统错误兜底，本地零写）。</p>
 */
public interface TxMessageSender {

    /**
     * 发送事务半消息（topic:tag 目的地；keys=业务幂等键；payload=消息体对象，按全局 Jackson 口径序列化；
     * channel=TxLocalExecutor.channel；bizArg=executor 执行参数透传，不经 broker）
     */
    TxSendResult sendTransactional(String topic, String tag, String keys, Object payload, String channel, Object bizArg);
}
