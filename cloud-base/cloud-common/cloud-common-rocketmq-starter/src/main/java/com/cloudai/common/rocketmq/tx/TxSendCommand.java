package com.cloudai.common.rocketmq.tx;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 事务消息发送指令（JVM 内传递：sender → template.sendMessageInTransaction arg → listener）。
 * 不经 broker 序列化；result/error 为 listener 回填通道（发送同步返回后 sender 取用判定）。
 */
@Getter
@RequiredArgsConstructor
public class TxSendCommand {

    private final String txNo;
    private final String topic;
    private final String tag;
    private final String keys;
    private final String channel;
    private final Object bizArg;

    /** listener 回填：executor 返回值（COMMIT 时有效） */
    private Object result;

    /** listener 回填：本地事务失败根因（ROLLBACK 时有效，sender 原样上抛） */
    private Throwable error;

    public void fillResult(Object result) {
        this.result = result;
    }

    public void fillError(Throwable error) {
        this.error = error;
    }
}
