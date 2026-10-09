package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.entity.BpmnApproval;
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
 * 发起审批消费（契约 2026-10-09 §1.2，topic TX_APPROVAL_CREATE；投影轮设计 D5 必达性补强）。
 * L2 业务 uk 幂等路径：uk_business 兜底——同单据重投至多建一张审批单，4015/DuplicateKey 幂等吸收。
 * 结果分流：成功→CREATE_RESULT/SUCCESS 补偿事件（含 processInstanceId）；4015→查行补发 SUCCESS
 * 事件（首投事件丢失的收敛路径）；4013/4014/4017 确定性失败白名单→CREATE_RESULT/FAILED 补偿事件→ACK；
 * 其余系统态抛出走重试 ×3→死信（人工，dashboard）。
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
                req.getBusinessType(), req.getBusinessKey(), created), true);
    }

    /** 确定性失败分流：4015 补发收敛（必达性补强）；白名单发 FAILED 事件；其余（系统态）重抛走重试 */
    private void dispatchBusinessFailure(ApprovalCreateInnerRequest req, BusinessException e) {
        int code = e.getCode();
        if (code == ERR_APPROVAL_EXISTS) {
            reissueSuccessOnDuplicate(req);
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

    /** 4015 幂等吸收必达性补强（设计 D5）：同单据重投查 uk 行补发 SUCCESS 事件——首投成功事件
     *  sendPlain 失败仅记档（Round D 不对称处置）后、broker 再重投的收敛路径。行不在（理论不发生——
     *  4015 必有既有行，竞态窗口防御）log+ACK；补发失败重抛走重试（重投再补发同值幂等无害，×3→死信人工）。 */
    private void reissueSuccessOnDuplicate(ApprovalCreateInnerRequest req) {
        BpmnApproval existing = workflowService.findApprovalViewByBusiness(
                req.getBusinessType(), req.getBusinessKey());
        if (existing == null) {
            log.error("同单据重复发起但审批单不存在（4015 竞态窗口，人工核查）: businessType={}, businessKey={}",
                    req.getBusinessType(), req.getBusinessKey());
            return;
        }
        log.info("同单据重复发起补发 SUCCESS 事件: businessType={}, businessKey={}, approvalId={}",
                req.getBusinessType(), req.getBusinessKey(), existing.getId());
        eventPublisher.sendPlain(eventPublisher.createResultSuccess(
                req.getBusinessType(), req.getBusinessKey(), toCreateVo(existing)));
    }

    /** 行数据 → 发起出参（补发事件只取 approvalId/pid 两字段，status 语义不进事件） */
    private InnerApprovalCreateVo toCreateVo(BpmnApproval approval) {
        InnerApprovalCreateVo vo = new InnerApprovalCreateVo();
        vo.setApprovalId(String.valueOf(approval.getId()));
        vo.setProcessInstanceId(approval.getProcessInstanceId());
        return vo;
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
