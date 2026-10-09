package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.consume.UkIdempotentListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 发起审批消费（契约 2026-10-09 §1.2，topic TX_APPROVAL_CREATE）。
 * L2 业务 uk 幂等路径：uk_business 兜底——同单据重投至多建一张审批单，4015/DuplicateKey 幂等吸收 ACK。
 * 结果分流：成功→CREATE_RESULT/SUCCESS 补偿事件；4015→log+ACK；4013/4014/4017 确定性失败白名单→
 * CREATE_RESULT/FAILED 补偿事件→ACK；其余系统态抛出走重试 ×3→死信（人工，dashboard）。
 */
@Slf4j
@Component
@UkIdempotentListener("uk_business(bpmn_approval)：同单据重投 4015/DuplicateKeyException 幂等吸收，至多建一张审批单")
@RocketMQMessageListener(topic = ApprovalMqTopics.TOPIC_TX_APPROVAL_CREATE,
        consumerGroup = "g_bpmn_approval_create", maxReconsumeTimes = 3)
@RequiredArgsConstructor
public class ApprovalCreateConsumer implements RocketMQListener<MessageExt> {

    private static final int ERR_APPROVER_INVALID = 4013;
    private static final int ERR_TYPE_UNKNOWN = 4014;
    private static final int ERR_APPROVAL_EXISTS = 4015;
    private static final int ERR_DEFINITION_MISSING = 4017;

    private final ApprovalWorkflowService workflowService;
    private final ApprovalEventPublisher eventPublisher;
    private final JsonPayloads jsonPayloads;

    @Override
    public void onMessage(MessageExt msg) {
        ApprovalCreateInnerRequest req = jsonPayloads.parse(msg.getBody(), ApprovalCreateInnerRequest.class);
        InnerApprovalCreateVo created;
        try {
            created = workflowService.createApproval(req);
        } catch (BusinessException e) {
            dispatchBusinessFailure(req, e);
            return;
        }
        publishCreateResult(eventPublisher.createResultSuccess(
                req.getBusinessType(), req.getBusinessKey(), Long.valueOf(created.getApprovalId())), true);
    }

    /** 确定性失败分流：4015 幂等吸收零事件；白名单发 FAILED 事件；其余（系统态）重抛走重试 */
    private void dispatchBusinessFailure(ApprovalCreateInnerRequest req, BusinessException e) {
        int code = e.getCode();
        if (code == ERR_APPROVAL_EXISTS) {
            log.info("同单据重复发起幂等吸收: businessType={}, businessKey={}, code={}",
                    req.getBusinessType(), req.getBusinessKey(), code);
            return;
        }
        if (code == ERR_APPROVER_INVALID || code == ERR_TYPE_UNKNOWN || code == ERR_DEFINITION_MISSING) {
            log.error("发起审批确定性失败（白名单）: businessType={}, businessKey={}, code={}, msg={}",
                    req.getBusinessType(), req.getBusinessKey(), code, e.getMessage());
            publishCreateResult(eventPublisher.createResultFailed(
                    req.getBusinessType(), req.getBusinessKey(), e.getMessage()), false);
            return;
        }
        throw e;
    }

    /** 补偿事件发送失败不对称处置：SUCCESS 仅记档（读时纠偏兜底收敛）；FAILED 重抛重试（无纠偏可救，×3→死信） */
    private void publishCreateResult(ApprovalEventMessage event, boolean successEvent) {
        try {
            eventPublisher.sendPlain(event);
        } catch (Exception e) {
            if (successEvent) {
                log.error("发起成功事件发送失败（读时纠偏兜底收敛）: keys={}", eventPublisher.notifyKeys(event), e);
                return;
            }
            throw e;
        }
    }
}
