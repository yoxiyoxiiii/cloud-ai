package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalNotifyMessage;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.service.BusinessTypeRegistry;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审批任务通知生产（契约 2026-11-08-approval-notify-mq-api §1.3，topic BPMN_TASK_NOTIFY：
 * 任务到达 TASK_ARRIVED / 审批结果 TERMINAL_RESULT 两类，普通同步发送）。
 * 统一不变式：通知一律在审批本地事务提交点之后由调用方顺序触发——回滚必不发；
 * 发布器吞掉一切发送异常（log.error），绝不阻断审批主链路（丢通知由待办列表兜底）。
 * 内部自取数（审批单行 + 业务类型配置 + 引擎活跃任务），调用方一行接入。
 * @author jy-root
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalNotifyPublisher {

    /** 事件时间格式（契约 §1.2：String 规避时区坑，GMT+8） */
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RocketMQTemplate rocketMQTemplate;
    private final JsonPayloads jsonPayloads;
    private final BpmnApprovalMapper approvalMapper;
    private final BusinessTypeRegistry businessTypeRegistry;
    private final TaskService taskService;

    /**
     * 审批单当前活跃任务逐个发任务到达通知：发起后查=首节点任务，办理后查=下一节点任务
     * （并行网关多任务逐个发）；实例已结束=空列表零发送。
     */
    public void publishTasksForApproval(Long approvalId) {
        try {
            doPublishTasks(approvalId);
        } catch (Exception e) {
            log.error("任务到达通知发送失败（已吞掉，不阻断审批主链路）: approvalId={}", approvalId, e);
        }
    }

    /** 审批终态结果通知申请人（通过/拒绝/撤销各一次；撤销删实例无后续任务，不涉任务到达通知） */
    public void publishTerminalResult(Long approvalId, Integer terminalStatus) {
        try {
            doPublishTerminal(approvalId, terminalStatus);
        } catch (Exception e) {
            log.error("审批结果通知发送失败（已吞掉，不阻断审批主链路）: approvalId={}, terminalStatus={}",
                    approvalId, terminalStatus, e);
        }
    }

    private void doPublishTasks(Long approvalId) {
        NotifyContext ctx = loadContext(approvalId);
        if (ctx == null) {
            return;
        }
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(ctx.approval().getProcessInstanceId()).active().list();
        for (Task task : tasks) {
            if (task.getAssignee() == null || task.getAssignee().isBlank()) {
                // 无明确受理人（candidate 类任务，平台暂无此形态）无从投递，留痕跳过
                log.info("任务无受理人跳过通知: approvalId={}, taskId={}", approvalId, task.getId());
                continue;
            }
            ApprovalNotifyMessage message = baseMessage(ApprovalMqTopics.TAG_TASK_ARRIVED, ctx);
            message.setReceiverAccount(task.getAssignee());
            message.setTaskId(task.getId());
            message.setTaskName(task.getName());
            send(message, message.getApprovalId() + ":" + message.getTaskId() + ":"
                    + ApprovalMqTopics.TAG_TASK_ARRIVED);
        }
    }

    private void doPublishTerminal(Long approvalId, Integer terminalStatus) {
        NotifyContext ctx = loadContext(approvalId);
        if (ctx == null) {
            return;
        }
        ApprovalNotifyMessage message = baseMessage(ApprovalMqTopics.TAG_TERMINAL_RESULT, ctx);
        message.setReceiverAccount(ctx.approval().getApplyUser());
        message.setTerminalStatus(String.valueOf(terminalStatus));
        send(message, message.getApprovalId() + ":" + ApprovalMqTopics.TAG_TERMINAL_RESULT);
    }

    /** 审批单行 + 业务类型配置加载：行不在/实例 id 空为正常不可达防御；配置缺失生产侧拦下不发
     *  （发出亦是消费端确定性失败）——均留痕返回 null */
    private NotifyContext loadContext(Long approvalId) {
        BpmnApproval approval = approvalMapper.findById(approvalId);
        if (approval == null || approval.getProcessInstanceId() == null) {
            log.error("审批单不存在或无流程实例，跳过任务通知: approvalId={}", approvalId);
            return null;
        }
        BpmnBusinessType config = businessTypeRegistry.findByTypeCodeOrNull(approval.getBusinessType());
        if (config == null) {
            log.error("业务类型配置缺失，任务通知拦下不发: businessType={}, approvalId={}",
                    approval.getBusinessType(), approvalId);
            return null;
        }
        return new NotifyContext(approval, config);
    }

    private ApprovalNotifyMessage baseMessage(String notifyType, NotifyContext ctx) {
        BpmnApproval approval = ctx.approval();
        ApprovalNotifyMessage message = new ApprovalNotifyMessage();
        message.setNotifyType(notifyType);
        message.setApprovalId(String.valueOf(approval.getId()));
        message.setBusinessType(approval.getBusinessType());
        message.setBusinessKey(approval.getBusinessKey());
        message.setTitle(approval.getTitle());
        message.setBusinessTypeName(ctx.config().getTypeName());
        message.setApplyUser(approval.getApplyUser());
        message.setOccurredAt(LocalDateTime.now().format(EVENT_TIME));
        return message;
    }

    /** 普通同步发送（契约 §1.2）：body 按全局 Jackson 口径序列化，KEYS 走标准 header
     *  （=消费端 L1 去重键：任务通知含 taskId 精确到任务实例——引擎回滚重试新 taskId 新键不重不漏；
     *  终态每审批单一次 approvalId 单键） */
    private void send(ApprovalNotifyMessage message, String keys) {
        Message<byte[]> mqMessage = MessageBuilder.withPayload(jsonPayloads.toBytes(message))
                .setHeader(RocketMQHeaders.KEYS, keys)
                .build();
        String destination = ApprovalMqTopics.TOPIC_BPMN_TASK_NOTIFY + ":" + message.getNotifyType();
        rocketMQTemplate.syncSend(destination, mqMessage);
    }

    /** 发送上下文：审批单行 + 业务类型配置 */
    private record NotifyContext(BpmnApproval approval, BpmnBusinessType config) {
    }
}
