package com.cloudai.common.rocketmq.tx;

import lombok.Getter;

/**
 * 事务消息执行上下文（listener 构建，传入 executor.executeInTx）。
 * bizArg 为生产调用方经 TxMessageSender 透传的执行参数（不经 broker 序列化，JVM 内传递）。
 */
@Getter
public class TxContext {

    private final String txNo;
    private final String topic;
    private final String tag;
    private final String keys;
    private final String channel;
    private final Object bizArg;

    /** 业务键回执（executor 填写，tx_log business_type/business_key 审计列来源） */
    private String businessType;
    private String businessKey;

    public TxContext(String txNo, String topic, String tag, String keys, String channel, Object bizArg) {
        this.txNo = txNo;
        this.topic = topic;
        this.tag = tag;
        this.keys = keys;
        this.channel = channel;
        this.bizArg = bizArg;
    }

    /** executor 必填回执：mq_tx_log 审计列（缺失时 tx_log insert 失败整体回滚） */
    public void setBusinessRef(String businessType, String businessKey) {
        this.businessType = businessType;
        this.businessKey = businessKey;
    }
}
