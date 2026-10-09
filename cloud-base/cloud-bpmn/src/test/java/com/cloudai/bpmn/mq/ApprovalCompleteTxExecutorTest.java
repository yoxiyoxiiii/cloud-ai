package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.tx.TxContext;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 办理终态 executor 单测（契约 2026-10-09 §1.3 触发点 1 / 设计 D2 形态裁定）：
 * 置 1/置 2 回写 + 推断不一致防御回滚（多节点演进防御）+ 4016 语义与既有端点一致。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalCompleteTxExecutorTest {

    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private BpmnApprovalMapper approvalMapper;
    @InjectMocks
    private ApprovalCompleteTxExecutor executor;

    @Test
    void approve_endApprove_writesApprovedAndSetsBusinessRef() {
        stubTaskAndInstance("t-1", "pid-1", "12", "endApprove", new Date());
        ApprovalCompleteTxExecutor.CompleteCommand cmd = command("true", StatusEnum.APPROVED.getCode());
        TxContext ctx = context(cmd);

        Object out = executor.executeInTx(payload(), ctx);

        assertThat(out).isEqualTo(StatusEnum.APPROVED.getCode());
        verify(taskService).addComment(eq("t-1"), eq("pid-1"), eq("ok"));
        verify(taskService).complete(eq("t-1"), eq(Map.of("approve", Boolean.TRUE)));
        verify(approvalMapper).updateStatusById(eq(12L), eq(StatusEnum.APPROVED.getCode()),
                eq("pid-1"), eq("admin"), any());
        // tx_log 审计回执（缺失即回滚——starter listener 契约）
        assertThat(ctx.getBusinessType()).isEqualTo("leave");
        assertThat(ctx.getBusinessKey()).isEqualTo("7");
    }

    @Test
    void reject_endReject_writesRejected() {
        stubTaskAndInstance("t-1", "pid-1", "12", "endReject", new Date());

        Object out = executor.executeInTx(payload(),
                context(command("false", StatusEnum.REJECTED.getCode())));

        assertThat(out).isEqualTo(StatusEnum.REJECTED.getCode());
        verify(taskService).complete(eq("t-1"), eq(Map.of("approve", Boolean.FALSE)));
        verify(approvalMapper).updateStatusById(eq(12L), eq(StatusEnum.REJECTED.getCode()),
                eq("pid-1"), eq("admin"), any());
    }

    @Test
    void endActivityMismatchWithInference_thrownForRollback() {
        // 发送前按 approve=true 推断 1，实际引擎落 endReject→2：防御回滚（设计 D2——单节点模型必一致）
        stubTaskAndInstance("t-1", "pid-1", "12", "endReject", new Date());

        assertThatThrownBy(() -> executor.executeInTx(payload(),
                context(command("true", StatusEnum.APPROVED.getCode()))))
                .isInstanceOf(IllegalStateException.class);
        // 回写已发生但随整体事务回滚消失——抛出即回滚信号
        verify(approvalMapper).updateStatusById(eq(12L), eq(StatusEnum.REJECTED.getCode()),
                eq("pid-1"), eq("admin"), any());
    }

    @Test
    void instanceUnfinished_nullWriteBack_thrownForRollback() {
        // 未结束=多节点模型（未来演进）：不通知不办理，防御回滚
        stubTaskAndInstance("t-1", "pid-1", "12", null, null);

        assertThatThrownBy(() -> executor.executeInTx(payload(),
                context(command("true", StatusEnum.APPROVED.getCode()))))
                .isInstanceOf(IllegalStateException.class);
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void unknownEndActivity_nullWriteBack_thrownForRollback() {
        stubTaskAndInstance("t-1", "pid-1", "12", "endUnknown", new Date());

        assertThatThrownBy(() -> executor.executeInTx(payload(),
                context(command("true", StatusEnum.APPROVED.getCode()))))
                .isInstanceOf(IllegalStateException.class);
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void taskMissing_rejected_4016() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId("t-1")).thenReturn(query);
        when(query.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> executor.executeInTx(payload(),
                context(command("true", StatusEnum.APPROVED.getCode()))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4016);
    }

    @Test
    void alreadyCompleted_rejected_4016() {
        stubTaskAndInstance("t-1", "pid-1", null, null, null);
        org.mockito.Mockito.doThrow(new org.flowable.common.engine.api.FlowableObjectNotFoundException("task already completed"))
                .when(taskService).complete(eq("t-1"), anyMap());

        assertThatThrownBy(() -> executor.executeInTx(payload(),
                context(command("true", StatusEnum.APPROVED.getCode()))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4016);
    }

    // ---- 脚手架 ----

    private ApprovalEventMessage payload() {
        ApprovalEventMessage event = new ApprovalEventMessage();
        event.setEventType("TERMINAL");
        event.setBusinessType("leave");
        event.setBusinessKey("7");
        event.setTerminalStatus("1");
        return event;
    }

    private ApprovalCompleteTxExecutor.CompleteCommand command(String approve, Integer expectedStatus) {
        return new ApprovalCompleteTxExecutor.CompleteCommand("t-1", approve, "ok", "admin", expectedStatus);
    }

    private TxContext context(ApprovalCompleteTxExecutor.CompleteCommand cmd) {
        return new TxContext("tx-no", "APPROVAL_EVENT_NOTIFY", "TERMINAL",
                "leave:7:TERMINAL", ApprovalCompleteTxExecutor.CHANNEL, cmd);
    }

    private void stubTaskAndInstance(String taskId, String processInstanceId, String businessKey,
                                     String endActivityId, Date endTime) {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(taskId)).thenReturn(query);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(query.singleResult()).thenReturn(task);
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        lenient().when(hq.processInstanceId(processInstanceId)).thenReturn(hq);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getBusinessKey()).thenReturn(businessKey);
        lenient().when(historic.getEndTime()).thenReturn(endTime);
        lenient().when(historic.getEndActivityId()).thenReturn(endActivityId);
        lenient().when(hq.singleResult()).thenReturn(historic);
    }
}
