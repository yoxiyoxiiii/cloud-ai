package com.cloudai.common.rocketmq.tx;

import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.TxLogDao;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统一事务消息本地监听器（绑定默认 rocketMQTemplate）：TransactionTemplate 单事务包裹
 * 「executor.executeInTx 完整业务写 + insert mq_tx_log」，COMMIT 决策返回前本地事务已提交（审查 R-1 不变式）。
 * <p>回查三分支（设计 D2）：tx_no 查 mq_tx_log——行在=COMMIT、行无=ROLLBACK、查询异常=UNKNOWN 下轮再查；
 * tx_log 行与业务写同事务，行存在即业务已提交，本地回滚则行随事务消失，自洽。</p>
 */
@Slf4j
@RocketMQTransactionListener
public class RocketMqTxListener implements RocketMQLocalTransactionListener {

    // 本地事务执行器
    private final TxExecutorRegistry executorRegistry;
    // 事务消息日志表（消息落库-记录）
    private final TxLogDao txLogDao;
    private final JsonPayloads jsonPayloads;

    // spring 本地事务管理
    private final TransactionTemplate transactionTemplate;

    public RocketMqTxListener(TxExecutorRegistry executorRegistry, TxLogDao txLogDao,
                              JsonPayloads jsonPayloads, PlatformTransactionManager transactionManager) {
        this.executorRegistry = executorRegistry;
        this.txLogDao = txLogDao;
        this.jsonPayloads = jsonPayloads;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 半消息已落 broker 后的本地事务执行：executor 未注册/业务异常 → ROLLBACK（半消息丢弃，本地零写留档） */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        TxSendCommand command = (TxSendCommand) arg;
        TxLocalExecutor<Object> executor = findExecutor(command.getChannel());
        if (executor == null) {
            log.error("事务通道未注册 executor，回滚半消息: channel={}", command.getChannel());
            command.fillError(new IllegalStateException("channel not registered: " + command.getChannel()));
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        Object payload = jsonPayloads.parse(msg.getPayload(), executor.payloadType());
        TxContext ctx = new TxContext(command.getTxNo(), command.getTopic(), command.getTag(),
                command.getKeys(), command.getChannel(), command.getBizArg());
        try {
            // 消息表和本地写在一个事务里面
            Object result = transactionTemplate.execute((TransactionCallback<Object>) status -> {
                // 本地事务执行器
                Object value = executor.executeInTx(payload, ctx);
                // 消息写表
                txLogDao.insert(command.getTxNo(), command.getTopic(), command.getChannel(),
                        ctx.getBusinessType(), ctx.getBusinessKey(), digest(value));
                return value;
            });
            command.fillResult(result);
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("事务消息本地事务失败，回滚: txNo={}, channel={}", command.getTxNo(), command.getChannel(), e);
            command.fillError(e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /** broker 回查（确认丢失路径）：tx_log 行在=COMMIT / 行无=ROLLBACK / 查询异常=UNKNOWN */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        Object txNoHeader = msg.getHeaders().get(TxMessageSenderImpl.HEADER_TX_NO);
        if (txNoHeader == null || txNoHeader.toString().isBlank()) {
            log.error("回查消息缺失 TX_NO 头，UNKNOWN 待下轮: headers={}", msg.getHeaders());
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        String txNo = txNoHeader.toString();
        try {
            boolean committed = txLogDao.existsByTxNo(txNo);
            log.info("事务消息回查: txNo={}, committed={}", txNo, committed);
            return committed ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            log.error("事务消息回查查询失败，UNKNOWN 待下轮: txNo={}", txNo, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    @SuppressWarnings("unchecked")
    private TxLocalExecutor<Object> findExecutor(String channel) {
        TxLocalExecutor<?> executor = executorRegistry.find(channel);
        return executor == null ? null : (TxLocalExecutor<Object>) executor;
    }

    /** executor 返回值摘要（截断 500，仅运维观测；标量返回值 toString 即 JSON 形态） */
    private String digest(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
