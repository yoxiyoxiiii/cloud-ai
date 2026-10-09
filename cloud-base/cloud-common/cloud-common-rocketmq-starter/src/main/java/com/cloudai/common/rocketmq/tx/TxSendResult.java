package com.cloudai.common.rocketmq.tx;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 事务消息发送结果：txNo（回查流水号）+ executor.executeInTx 返回值（如 leaveId，生产调用方取用）。
 */
@Getter
@RequiredArgsConstructor
public class TxSendResult {

    private final String txNo;
    private final Object result;
}
