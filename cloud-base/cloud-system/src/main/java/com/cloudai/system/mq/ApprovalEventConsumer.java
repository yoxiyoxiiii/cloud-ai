package com.cloudai.system.mq;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.system.entity.SysLeave.StatusEnum;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.common.rocketmq.consume.DedupRocketMQListener;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审批事件通知消费（契约 2026-10-09 §1.3，topic APPROVAL_EVENT_NOTIFY）。
 * L1 通用去重表幂等 + 条件 UPDATE 双保险（幂等/乱序/重投三防）：
 * CREATE_RESULT/SUCCESS→approval_id 回填 WHERE IS NULL；FAILED→status=4 WHERE status=0；
 * TERMINAL→status={1|2|3} WHERE status=0——未命中（已终态/已回填）空更新 ACK。
 * 未知 eventType/result 或非法数值抛 IllegalArgumentException 走重试×3→死信（人工）。
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY,
        consumerGroup = "g_system_approval_event", maxReconsumeTimes = 3)
public class ApprovalEventConsumer extends DedupRocketMQListener {

    /** 事件回写审计人：MQ 消费无用户上下文，系统操作者记档（与种子账号命名风格一致） */
    static final String AUDIT_OPERATOR = "bpmn-event";

    private final SysLeaveMapper leaveMapper;
    private final JsonPayloads jsonPayloads;

    public ApprovalEventConsumer(com.cloudai.common.rocketmq.dao.ConsumeDedupDao consumeDedupDao,
                                 SysLeaveMapper leaveMapper, JsonPayloads jsonPayloads) {
        super(consumeDedupDao);
        this.leaveMapper = leaveMapper;
        this.jsonPayloads = jsonPayloads;
    }

    @Override
    protected String dedupGroup() {
        return "g_system_approval_event";
    }

    @Override
    protected void doConsume(MessageExt msg) {
        ApprovalEventMessage event = jsonPayloads.parse(msg.getBody(), ApprovalEventMessage.class);
        Long leaveId = Long.valueOf(event.getBusinessKey());
        if (ApprovalMqTopics.TAG_CREATE_RESULT.equals(event.getEventType())) {
            applyCreateResult(event, leaveId);
        } else if (ApprovalMqTopics.TAG_TERMINAL.equals(event.getEventType())) {
            applyTerminal(event, leaveId);
        } else {
            throw new IllegalArgumentException("未知事件类型: " + event.getEventType());
        }
    }

    /** 发起结果：SUCCESS 回填 approval_id（WHERE IS NULL）；FAILED 置发起失败终态（WHERE status=0） */
    private void applyCreateResult(ApprovalEventMessage event, Long leaveId) {
        if (ApprovalMqTopics.RESULT_SUCCESS.equals(event.getResult())) {
            int updated = leaveMapper.updateApprovalIdIfAbsent(leaveId, Long.valueOf(event.getApprovalId()),
                    AUDIT_OPERATOR, LocalDateTime.now());
            logApplied("approval_id 回填", leaveId, updated);
        } else if (ApprovalMqTopics.RESULT_FAILED.equals(event.getResult())) {
            int updated = leaveMapper.updateStatusIfApproving(leaveId, StatusEnum.FAILED.getCode(),
                    AUDIT_OPERATOR, LocalDateTime.now());
            logApplied("发起失败终态置 4", leaveId, updated);
        } else {
            throw new IllegalArgumentException("未知发起结果: " + event.getResult());
        }
    }

    /** 终态回写：1通过/2拒绝/3撤销（WHERE status=0——已终态行含 4 空更新 ACK） */
    private void applyTerminal(ApprovalEventMessage event, Long leaveId) {
        Integer terminalStatus = Integer.valueOf(event.getTerminalStatus());
        int updated = leaveMapper.updateStatusIfApproving(leaveId, terminalStatus,
                AUDIT_OPERATOR, LocalDateTime.now());
        logApplied("终态回写 " + terminalStatus, leaveId, updated);
    }

    /** 空更新=已终态/已回填（乱序/重投三防），INFO 记档不视为异常 */
    private void logApplied(String action, Long leaveId, int updated) {
        if (updated == 0) {
            log.info("事件回写未命中行（已终态/已回填，幂等 ACK）: action={}, leaveId={}", action, leaveId);
        }
    }
}
