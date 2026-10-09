package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.common.rocketmq.consume.DedupRocketMQListener;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;

import java.time.LocalDateTime;

/**
 * 审批事件投影消费者——每业务服务唯一「流程状态结果监听对象」（契约 2026-10-09-approval-projection-api
 * §2.2 / 设计 D3）：消费 APPROVAL_EVENT_NOTIFY 三分支 upsert 投影表，业务零感知、零业务 Mapper。
 * <p>bean 经 ApprovalProjectionAutoConfiguration 注册（默认关、消费方显式开）；rocketmq-spring 的
 * RocketMQMessageListenerBeanPostProcessor 按 bean 类注解收集（含自动装配 bean，R1 验证记档），
 * consumerGroup 占位符由容器注册时 resolvePlaceholders 解析（R2 验证记档）。</p>
 * <p>幂等双层（契约 §1.3）：L1 继承本基类去重表照走 + L2 投影 uk_business（ON DUPLICATE 语义）。
 * 失败分类沿现口径：幂等吸收/条件成功 → ACK；未知 eventType/result/terminalStatus →
 * IllegalArgumentException 重试 ×3 → %DLQ% 死信人工。</p>
 */
@Slf4j
@RocketMQMessageListener(topic = ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY,
        consumerGroup = "${cloud.bpmn.projection.consumer-group}", maxReconsumeTimes = 3)
public class ApprovalProjectionListener extends DedupRocketMQListener {

    /** 投影写入审计人：MQ 消费无用户上下文，系统操作者记档（沿 Round D 同名先例） */
    static final String AUDIT_OPERATOR = "bpmn-event";

    private final JsonPayloads jsonPayloads;
    private final ApprovalProjectionDao projectionDao;
    private final ApprovalProjectionProperties properties;

    public ApprovalProjectionListener(ConsumeDedupDao consumeDedupDao, JsonPayloads jsonPayloads,
                                      ApprovalProjectionDao projectionDao,
                                      ApprovalProjectionProperties properties) {
        super(consumeDedupDao);
        this.jsonPayloads = jsonPayloads;
        this.projectionDao = projectionDao;
        this.properties = properties;
    }

    @Override
    protected String dedupGroup() {
        // 与注解 consumerGroup 同属性源解析（占位符同一处配置），防两处漂移
        return properties.getConsumerGroup();
    }

    @Override
    protected void doConsume(MessageExt msg) {
        ApprovalEventMessage event = jsonPayloads.parse(msg.getBody(), ApprovalEventMessage.class);
        if (ApprovalMqTopics.TAG_CREATE_RESULT.equals(event.getEventType())) {
            applyCreateResult(event);
        } else if (ApprovalMqTopics.TAG_TERMINAL.equals(event.getEventType())) {
            applyTerminal(event);
        } else {
            throw new IllegalArgumentException("未知事件类型: " + event.getEventType());
        }
    }

    /** 发起结果：SUCCESS upsert create_result=1+approval_id+pid；FAILED upsert create_result=2（设计 D4 列族） */
    private void applyCreateResult(ApprovalEventMessage event) {
        if (ApprovalMqTopics.RESULT_SUCCESS.equals(event.getResult())) {
            if (event.getApprovalId() == null || event.getApprovalId().isBlank()) {
                throw new IllegalArgumentException("SUCCESS 事件缺 approvalId: " + event.getBusinessKey());
            }
            // pid 防御容忍 null（列可空，对账 status-list 回补——契约 §2.1 必填由生产端保证）
            projectionDao.applyCreateSuccess(event, AUDIT_OPERATOR, LocalDateTime.now());
        } else if (ApprovalMqTopics.RESULT_FAILED.equals(event.getResult())) {
            projectionDao.applyCreateFailed(event, AUDIT_OPERATOR, LocalDateTime.now());
        } else {
            throw new IllegalArgumentException("未知发起结果: " + event.getResult());
        }
    }

    /** 终态：1通过/2拒绝/3撤销 upsert approval_status（值域白名单防 4 落投影——永不落4 不变式） */
    private void applyTerminal(ApprovalEventMessage event) {
        int terminalStatus;
        try {
            terminalStatus = Integer.parseInt(event.getTerminalStatus());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("终态值非法: " + event.getTerminalStatus(), e);
        }
        if (terminalStatus < 1 || terminalStatus > 3) {
            throw new IllegalArgumentException("终态值越界（投影值域 1-3）: " + terminalStatus);
        }
        projectionDao.applyTerminal(event, terminalStatus, AUDIT_OPERATOR, LocalDateTime.now());
    }
}
