package com.cloudai.common.rocketmq.tx;

/**
 * 半消息发送失败（namesrv/broker 不可达、发送异常）——区别于本地事务失败（后者原样透传执行异常）。
 * 调用方捕获后转译域码（如 system 3025 消息服务不可用），本地业务零写。
 */
public class TxMessageSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TxMessageSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
