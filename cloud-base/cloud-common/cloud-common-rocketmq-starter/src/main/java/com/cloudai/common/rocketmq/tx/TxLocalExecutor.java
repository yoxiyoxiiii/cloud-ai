package com.cloudai.common.rocketmq.tx;

/**
 * 事务消息本地业务执行器（一个业务通道一个 executor bean，channel 唯一标识——设计 D1）。
 * <p>形态裁定（审查 R-1）：所有事务消息生产方一律 executor 形态——service 生产方法编排化去 @Transactional，
 * 完整业务写在本实现内，与 mq_tx_log insert 同在 starter listener 的 TransactionTemplate 单事务内；
 * executor 实现内<b>不得</b>再声明 @Transactional（双事务边界禁令）；COMMIT 决策返回前本地事务必须已提交。</p>
 * <p>executeInTx 抛异常 → 整体回滚（业务写消失 + tx_log 行消失）→ 半消息 ROLLBACK。</p>
 */
public interface TxLocalExecutor<T> {

    /** 业务通道标识（如 leave-create / terminal-complete / terminal-cancel） */
    String channel();

    /** 消息体反序列化目标类型（listener 按此类型解析半消息 body 后传入） */
    Class<T> payloadType();

    /**
     * 本地事务内业务写（starter 已包事务 + tx_log insert，实现内只写业务表）。
     * 返回值经 TxSendResult 透传给生产调用方（如 leaveId）。
     * 实现必须在返回前 ctx.setBusinessRef(businessType, businessKey)（tx_log 审计列，缺失回滚）。
     */
    Object executeInTx(T payload, TxContext ctx);
}
