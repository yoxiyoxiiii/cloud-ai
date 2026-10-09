package com.cloudai.system.mq;

import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.common.rocketmq.tx.TxContext;
import com.cloudai.common.rocketmq.tx.TxLocalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 发起请假本地事务 executor（通道 leave-create，契约 2026-10-09 §1.2 生产语义）。
 * executeInTx = insert sys_leave(status=0)（snowflake 预生成 id 显式写入，设计 R2），
 * 与 mq_tx_log insert 同在 starter listener 的 TransactionTemplate 单事务（审查 R-1 executor 形态，
 * 实现内禁 @Transactional）；bizArg=生产方法组装好的完整 SysLeave（审计四值已显式）。
 */
@Component
@RequiredArgsConstructor
public class LeaveCreateTxExecutor implements TxLocalExecutor<ApprovalCreateInnerRequest> {

    public static final String CHANNEL = "leave-create";

    private final SysLeaveMapper leaveMapper;

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public Class<ApprovalCreateInnerRequest> payloadType() {
        return ApprovalCreateInnerRequest.class;
    }

    @Override
    public Object executeInTx(ApprovalCreateInnerRequest payload, TxContext ctx) {
        SysLeave leave = (SysLeave) ctx.getBizArg();
        leaveMapper.save(leave);
        ctx.setBusinessRef(payload.getBusinessType(), payload.getBusinessKey());
        return leave.getId();
    }
}
