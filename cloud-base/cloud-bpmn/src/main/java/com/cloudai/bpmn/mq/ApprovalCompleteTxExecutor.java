package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.tx.TxContext;
import com.cloudai.common.rocketmq.tx.TxLocalExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 办理任务终态通知 executor（通道 terminal-complete，契约 2026-10-09 §1.3 触发点 1）。
 * executeInTx = 意见落 ACT_HI_COMMENT + complete(approve) + writeBackStatus 置 1/2，
 * 与 mq_tx_log insert 同在 starter listener 的 TransactionTemplate 单事务（审查 R-1 executor 形态，
 * 实现内禁 @Transactional）；实际回写值与发送前推断值不一致时抛异常整体回滚——
 * 单节点模型必一致，此为多节点模型演进防御（设计 D2）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalCompleteTxExecutor implements TxLocalExecutor<ApprovalEventMessage> {

    public static final String CHANNEL = "terminal-complete";

    private static final int ERR_TASK_INVALID = 4016;

    private static final String VAR_APPROVE = "approve";
    private static final String ACTIVITY_END_APPROVE = "endApprove";
    private static final String ACTIVITY_END_REJECT = "endReject";

    private final TaskService taskService;
    private final HistoryService historyService;
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
        CompleteCommand cmd = (CompleteCommand) ctx.getBizArg();
        Integer actual = advanceAndWriteBack(cmd);
        if (!cmd.expectedStatus().equals(actual)) {
            // null=实例未结束（多节点）或 endActivityId 未知；非 null=与 approve 推断不符——一律防御回滚
            log.error("办理回写值与发送前推断不一致，整体回滚: taskId={}, expected={}, actual={}",
                    cmd.taskId(), cmd.expectedStatus(), actual);
            throw new IllegalStateException("终态回写与推断不一致: expected=" + cmd.expectedStatus()
                    + ", actual=" + actual);
        }
        ctx.setBusinessRef(payload.getBusinessType(), payload.getBusinessKey());
        return actual;
    }

    /** 引擎推进 + 结束态回写；返回实际回写状态（未结束/未知 endActivityId 返回 null），异常语义与既有端点一致 */
    private Integer advanceAndWriteBack(CompleteCommand cmd) {
        Task task = taskService.createTaskQuery().taskId(cmd.taskId()).singleResult();
        if (task == null) {
            throw new BusinessException(ERR_TASK_INVALID, "任务不存在或已被办理");
        }
        Authentication.setAuthenticatedUserId(cmd.opUser());
        try {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), cmd.comment());
            taskService.complete(task.getId(), Map.of(VAR_APPROVE, Boolean.parseBoolean(cmd.approve())));
        } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException e) {
            log.error("任务已不存在或已被办理: {}", cmd.taskId(), e);
            throw new BusinessException(ERR_TASK_INVALID, "任务不存在或已被办理");
        } catch (org.flowable.common.engine.api.FlowableException e) {
            log.error("流程引擎办理异常: {}", cmd.taskId(), e);
            throw new BusinessException("流程引擎办理异常");
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
        return writeBackStatus(task, cmd.opUser());
    }

    /** 结束态回写：历史实例结束且 endActivityId 可映射才回写（未结束/未知返回 null） */
    private Integer writeBackStatus(Task task, String opUser) {
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        if (historic == null || historic.getBusinessKey() == null || historic.getEndTime() == null) {
            return null;
        }
        Integer newStatus = mapEndActivityToStatus(historic.getEndActivityId());
        if (newStatus == null) {
            log.warn("未知 endActivityId，不回写审批单状态: {}", historic.getEndActivityId());
            return null;
        }
        approvalMapper.updateStatusById(Long.valueOf(historic.getBusinessKey()), newStatus,
                task.getProcessInstanceId(), opUser, LocalDateTime.now());
        return newStatus;
    }

    /** endApprove→已通过 / endReject→已拒绝；其余（未知）null 不回写 */
    private Integer mapEndActivityToStatus(String endActivityId) {
        if (ACTIVITY_END_APPROVE.equals(endActivityId)) {
            return StatusEnum.APPROVED.getCode();
        }
        if (ACTIVITY_END_REJECT.equals(endActivityId)) {
            return StatusEnum.REJECTED.getCode();
        }
        return null;
    }

    /** executor 执行参数（bizArg JVM 内透传不经 broker）：expectedStatus=按 approve 推断的终态 */
    public record CompleteCommand(String taskId, String approve, String comment, String opUser,
                                  Integer expectedStatus) {
    }
}
