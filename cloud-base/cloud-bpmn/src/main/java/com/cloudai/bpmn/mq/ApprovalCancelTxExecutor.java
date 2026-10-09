package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.common.rocketmq.tx.TxContext;
import com.cloudai.common.rocketmq.tx.TxLocalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 撤销终态通知 executor（通道 terminal-cancel，契约 2026-10-09 §1.3 触发点 2）。
 * executeInTx = 4010/4012/4011 复查（发送前置校验后到者防御）+ 删流程实例 + 置已撤销，
 * 与 mq_tx_log insert 同在 starter listener 单事务（审查 R-1 executor 形态）。
 * 复查与删实例复用 ApprovalWorkflowService 既有脚手架（与 createApproval 同一错误语义源）。
 */
@Component
@RequiredArgsConstructor
public class ApprovalCancelTxExecutor implements TxLocalExecutor<ApprovalEventMessage> {

    public static final String CHANNEL = "terminal-cancel";

    private final ApprovalWorkflowService workflowService;
    private final BpmnApprovalMapper approvalMapper;

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public Class<ApprovalEventMessage> payloadType() {
        return ApprovalEventMessage.class;
    }

    @Override
    public Object executeInTx(ApprovalEventMessage payload, TxContext ctx) {
        CancelCommand cmd = (CancelCommand) ctx.getBizArg();
        BpmnApproval approval = workflowService.findCancelableApproval(cmd.approvalId(), cmd.operator());
        workflowService.deleteProcessInstance(approval);
        approvalMapper.updateStatusById(cmd.approvalId(), StatusEnum.CANCELLED.getCode(),
                null, cmd.operator(), LocalDateTime.now());
        ctx.setBusinessRef(payload.getBusinessType(), payload.getBusinessKey());
        return StatusEnum.CANCELLED.getCode();
    }

    /** executor 执行参数（bizArg JVM 内透传不经 broker） */
    public record CancelCommand(Long approvalId, String operator) {
    }
}
