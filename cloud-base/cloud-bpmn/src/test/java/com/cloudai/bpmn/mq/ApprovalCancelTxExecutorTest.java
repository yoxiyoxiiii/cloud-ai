package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.tx.TxContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤销终态 executor 单测（契约 2026-10-09 §1.3 触发点 2）：复查+删实例+置 3 与 tx_log 同单事务；
 * 4010/4012/4011 语义沿 ApprovalWorkflowService 既有脚手架（findCancelableApproval，
 * 发送前置校验后到者防御——4010 主断言面在 ApprovalWorkflowServiceTest）。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalCancelTxExecutorTest {

    @Mock
    private ApprovalWorkflowService workflowService;
    @Mock
    private BpmnApprovalMapper approvalMapper;
    @InjectMocks
    private ApprovalCancelTxExecutor executor;

    @Test
    void happyPath_deletesInstanceMarksCancelledAndSetsBusinessRef() {
        BpmnApproval approval = approval(StatusEnum.APPROVING.getCode());
        when(workflowService.findCancelableApproval(5L, "userA")).thenReturn(approval);
        TxContext ctx = context();

        Object out = executor.executeInTx(payload(), ctx);

        assertThat(out).isEqualTo(StatusEnum.CANCELLED.getCode());
        verify(workflowService).deleteProcessInstance(approval);
        verify(approvalMapper).updateStatusById(eq(5L), eq(StatusEnum.CANCELLED.getCode()),
                isNull(), eq("userA"), any());
        assertThat(ctx.getBusinessType()).isEqualTo("leave");
        assertThat(ctx.getBusinessKey()).isEqualTo("7");
    }

    @Test
    void terminalRace_4011_propagates_noWrite() {
        // 发送前置校验后行已被并发办理置终态——复查防御 4011（契约 §1.3 触发点 2 复查语义）
        when(workflowService.findCancelableApproval(5L, "userA"))
                .thenThrow(new BusinessException(4011, "审批单已终态，不可撤销"));

        assertThatThrownBy(() -> executor.executeInTx(payload(), context()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4011);
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void instanceGoneRace_4011_propagates_noWrite() {
        // 实例已被并发办理删除——deleteProcessInstance 转 4011 沿既有竞态口径
        BpmnApproval approval = approval(StatusEnum.APPROVING.getCode());
        when(workflowService.findCancelableApproval(5L, "userA")).thenReturn(approval);
        doThrow(new BusinessException(4011, "审批单已终态，不可撤销"))
                .when(workflowService).deleteProcessInstance(approval);

        assertThatThrownBy(() -> executor.executeInTx(payload(), context()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4011);
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    // ---- 脚手架 ----

    private ApprovalEventMessage payload() {
        ApprovalEventMessage event = new ApprovalEventMessage();
        event.setEventType("TERMINAL");
        event.setBusinessType("leave");
        event.setBusinessKey("7");
        event.setTerminalStatus("3");
        return event;
    }

    private TxContext context() {
        return new TxContext("tx-no", "APPROVAL_EVENT_NOTIFY", "TERMINAL",
                "leave:7:TERMINAL", ApprovalCancelTxExecutor.CHANNEL,
                new ApprovalCancelTxExecutor.CancelCommand(5L, "userA"));
    }

    private BpmnApproval approval(Integer status) {
        BpmnApproval approval = new BpmnApproval();
        approval.setId(5L);
        approval.setBusinessType("leave");
        approval.setBusinessKey("7");
        approval.setStatus(status);
        approval.setApplyUser("userA");
        approval.setProcessInstanceId("pid-5");
        return approval;
    }
}
