package com.cloudai.system.mq;

import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.common.rocketmq.tx.TxContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 发起请假本地事务 executor 单测（契约 2026-10-09 §1.2 生产语义 / 设计 R2；投影轮 D6）：
 * insert 走 bizArg 预组装实体（snowflake id 显式写入；纯业务行无状态列）+ tx_log 审计回执。
 */
@ExtendWith(MockitoExtension.class)
class LeaveCreateTxExecutorTest {

    @Mock
    private SysLeaveMapper leaveMapper;
    @InjectMocks
    private LeaveCreateTxExecutor executor;

    @Test
    void executeInTx_savesBizArgLeaveAndSetsBusinessRef() {
        SysLeave leave = new SysLeave();
        leave.setId(1234567890L);
        leave.setTitle("annual leave");
        TxContext ctx = new TxContext("tx-no", "TX_APPROVAL_CREATE", null,
                "leave:1234567890", LeaveCreateTxExecutor.CHANNEL, leave);
        ApprovalCreateInnerRequest payload = new ApprovalCreateInnerRequest();
        payload.setBusinessType("leave");
        payload.setBusinessKey("1234567890");

        Object out = executor.executeInTx(payload, ctx);

        verify(leaveMapper).save(leave);
        assertThat(out).isEqualTo(1234567890L);
        // tx_log 审计回执（缺失即回滚——starter listener 契约）
        assertThat(ctx.getBusinessType()).isEqualTo("leave");
        assertThat(ctx.getBusinessKey()).isEqualTo("1234567890");
    }
}
