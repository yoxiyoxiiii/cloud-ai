package com.cloudai.bpmn.service;

import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.bpmn.api.domain.VariableItem;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.mq.ApprovalCancelTxExecutor;
import com.cloudai.bpmn.mq.ApprovalCompleteTxExecutor;
import com.cloudai.bpmn.mq.ApprovalEventPublisher;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.tx.TxMessageSendException;
import com.cloudai.common.rocketmq.tx.TxMessageSender;
import com.cloudai.system.api.client.SystemUserClient;
import com.cloudai.system.api.domain.UserEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用审批编排（设计 2026-10-09 D1/D3 + 事务消息 D2 形态裁定）：发起/撤销/办理三方法 + 状态批量查询。
 * createApproval 仍 @Transactional——消费侧业务（MQ 消费与 /inner 端点复用）非事务消息生产方，不受
 * executor 形态裁定约束；completeTask/cancelApproval/cancelByBusiness 为 MQ 终态通知生产方，一律编排化
 * （去 @Transactional）：前置校验 + TxMessageSender 半消息发送，完整业务写在 executor.executeInTx 内
 * 与 mq_tx_log 同在 starter listener 单事务。错误码 4xxx 账本见契约 2026-10-08-approval-platform-api §6。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalWorkflowService {

    private static final int ERR_APPROVAL_NOT_FOUND = 4010;
    private static final int ERR_APPROVAL_TERMINAL = 4011;
    private static final int ERR_NOT_APPLIER = 4012;
    private static final int ERR_APPROVER_INVALID = 4013;
    private static final int ERR_APPROVAL_EXISTS = 4015;
    private static final int ERR_TASK_INVALID = 4016;
    private static final int ERR_DEFINITION_MISSING = 4017;

    private static final String VAR_APPROVAL_ID = "approvalId";
    private static final String VAR_APPLY_USER = "applyUser";
    private static final String VAR_APPROVER = "approver";
    private static final String VAR_TITLE = "title";

    private final BpmnApprovalMapper approvalMapper;
    private final BusinessTypeRegistry businessTypeRegistry;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final SystemUserClient systemUserClient;
    private final TxMessageSender txMessageSender;
    private final ApprovalEventPublisher eventPublisher;

    /** 发起审批（契约 §4.1）：配置校验 4014 → 审批人校验 4013 → uk 查重 4015 →
     *  insert bpmn_approval(审批中) → 启动实例（businessKey=id，平台四变量+variables）→ 同事务回填实例关联 */
    @Transactional(rollbackFor = Exception.class)
    public InnerApprovalCreateVo createApproval(ApprovalCreateInnerRequest req) {
        BpmnBusinessType config = businessTypeRegistry.findByTypeCode(req.getBusinessType());
        validateApprover(req.getApprover());
        requireBusinessAbsent(req.getBusinessType(), req.getBusinessKey());

        BpmnApproval approval = buildApproval(req, config);
        try {
            approvalMapper.save(approval);
        } catch (DuplicateKeyException e) {
            // 并发 TOCTOU 兜底：前置查重与 insert 之间的竞态由 uk_business 兜住，同码转译
            log.error("审批单唯一键冲突: businessType={}, businessKey={}", req.getBusinessType(), req.getBusinessKey(), e);
            throw new BusinessException(ERR_APPROVAL_EXISTS, "该业务单据已存在审批");
        }

        String processInstanceId = startProcess(approval, req.getVariables());
        approvalMapper.updateStatusById(approval.getId(), StatusEnum.APPROVING.getCode(),
                processInstanceId, req.getApplyUser(), LocalDateTime.now());
        return buildCreateVo(approval.getId(), processInstanceId);
    }

    /** 按业务键查审批单行（投影轮设计 D5：ApprovalCreateConsumer 4015 补发分支取行数据
     *  approvalId/processInstanceId——consumer 不直接触 mapper，分层） */
    public BpmnApproval findApprovalViewByBusiness(String businessType, String businessKey) {
        return approvalMapper.findByBusiness(businessType, businessKey);
    }

    /** 批量查状态（契约 §4.2）：businessKey 全集回包，无审批单的键 approvalId/status 均 null——
     *  查询语义宽松：businessType 不存在不报 4014（回 null 行）；只读不加事务注解 */
    public List<InnerApprovalStatusVo> listStatusByBusiness(ApprovalStatusQueryInnerRequest req) {
        List<BpmnApproval> rows = approvalMapper.listByBusinessKeys(req.getBusinessType(), req.getBusinessKeys());
        Map<String, BpmnApproval> byKey = new LinkedHashMap<>();
        for (BpmnApproval row : rows) {
            byKey.put(row.getBusinessKey(), row);
        }
        List<InnerApprovalStatusVo> out = new ArrayList<>();
        for (String key : req.getBusinessKeys()) {
            out.add(toStatusVo(key, byKey.get(key)));
        }
        return out;
    }

    /** 按审批单 id 撤销（契约 §3.3，编排化）：4010→4012→4011 前置校验 → TERMINAL/3 事务半消息
     *  （executor 内复查+删实例+置 3 与 mq_tx_log 同单事务，消息投递即本地已提交——审查 R-1 不变式） */
    public void cancelApproval(Long id, String operator) {
        BpmnApproval approval = findCancelableApproval(id, operator);
        sendTerminalEvent(approval, StatusEnum.CANCELLED.getCode(),
                ApprovalCancelTxExecutor.CHANNEL, new ApprovalCancelTxExecutor.CancelCommand(id, operator));
    }

    /** 按业务键撤销（契约 §4.3 /inner 通道）：(businessType, businessKey) 定位 → 4010 → 复用 id 撤销编排 */
    public void cancelByBusiness(ApprovalCancelInnerRequest req) {
        BpmnApproval approval = approvalMapper.findByBusiness(req.getBusinessType(), req.getBusinessKey());
        if (approval == null) {
            throw new BusinessException(ERR_APPROVAL_NOT_FOUND, "审批单不存在");
        }
        cancelApproval(approval.getId(), req.getOperator());
    }

    /** 办理任务（契约 §2.3，编排化）：4016 前置 → 定位审批单 → terminalStatus 按 approve 推断
     *  （单节点模型与 endActivityId 映射一致）→ TERMINAL 事务半消息（executor 内 addComment+complete+
     *  回写置 1/2，实际与推断不一致整体回滚——多节点演进防御，设计 D2） */
    public void completeTask(TaskCompleteRequest req, String opUser) {
        Task task = taskService.createTaskQuery().taskId(req.getTaskId()).singleResult();
        if (task == null) {
            throw new BusinessException(ERR_TASK_INVALID, "任务不存在或已被办理");
        }
        BpmnApproval approval = requireApprovalByTask(task);
        // 通过或者拒绝
        Integer expected = Boolean.parseBoolean(req.getApprove())
                ? StatusEnum.APPROVED.getCode() : StatusEnum.REJECTED.getCode();

        // 任务办理，发布MQ 消息
        sendTerminalEvent(approval, expected, ApprovalCompleteTxExecutor.CHANNEL,
                new ApprovalCompleteTxExecutor.CompleteCommand(
                        task.getId(), req.getApprove(), req.getComment(), opUser, expected));
    }

    // ---- 发起脚手架 ----

    /** 审批人有效性校验：Feign 全量投影比对（4013）；Feign 不可用 log.error 后 1002（不引 fallback） */
    private void validateApprover(String approver) {
        R<List<UserEntry>> response;
        try {
            response = systemUserClient.listAll();
        } catch (Exception e) {
            log.error("system 用户投影 Feign 调用失败", e);
            throw new BusinessException("用户服务不可用");
        }
        List<UserEntry> users = response == null ? null : response.getData();
        if (users == null) {
            log.error("system 用户投影返回异常: {}", response);
            throw new BusinessException("用户服务不可用");
        }
        boolean present = users.stream().anyMatch(u -> approver.equals(u.getAccount()));
        if (!present) {
            throw new BusinessException(ERR_APPROVER_INVALID, "审批人无效: " + approver);
        }
    }

    /** uk 查重前置（任意状态存在即拒——行永不删、uk 跨终态生效，契约 §1）；并发竞态由 DuplicateKey 兜底 */
    private void requireBusinessAbsent(String businessType, String businessKey) {
        if (approvalMapper.findByBusiness(businessType, businessKey) != null) {
            throw new BusinessException(ERR_APPROVAL_EXISTS, "该业务单据已存在审批");
        }
    }

    /** 手写 SQL 无自动填充：审计四值显式构造（插入时 update 值 = create 值）；
     *  process_key 从配置快照（防配置后改漂移，设计 D1） */
    private BpmnApproval buildApproval(ApprovalCreateInnerRequest req, BpmnBusinessType config) {
        BpmnApproval approval = new BpmnApproval();
        approval.setBusinessType(req.getBusinessType());
        approval.setBusinessKey(req.getBusinessKey());
        approval.setTitle(req.getTitle());
        approval.setProcessKey(config.getProcessKey());
        approval.setStatus(StatusEnum.APPROVING.getCode());
        approval.setApplyUser(req.getApplyUser());
        approval.setApprover(req.getApprover());
        LocalDateTime now = LocalDateTime.now();
        approval.setCreateBy(req.getApplyUser());
        approval.setCreateTime(now);
        approval.setUpdateBy(req.getApplyUser());
        approval.setUpdateTime(now);
        return approval;
    }

    /** 启动流程实例（businessKey=审批单 id 字符串；配置 process_key 快照启动）。
     *  平台规范四变量（approvalId/applyUser/approver/title）最后注入——调用方 variables 同名时以平台为准
     *  （防 ${approver} assignee 被覆盖破坏模型约定，契约 §1 BPMN 模型作者指南） */
    private String startProcess(BpmnApproval approval, List<VariableItem> variables) {
        Map<String, Object> vars = new HashMap<>();
        if (variables != null) {
            for (VariableItem item : variables) {
                vars.put(item.getName(), item.getValue());
            }
        }
        vars.put(VAR_APPROVAL_ID, approval.getId());
        vars.put(VAR_APPLY_USER, approval.getApplyUser());
        vars.put(VAR_APPROVER, approval.getApprover());
        vars.put(VAR_TITLE, approval.getTitle());
        try {
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                    approval.getProcessKey(), String.valueOf(approval.getId()), vars);
            return instance.getId();
        } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException e) {
            log.error("流程定义未部署: {}", approval.getProcessKey(), e);
            throw new BusinessException(ERR_DEFINITION_MISSING, "流程定义未部署");
        }
    }

    /** 出参透出流程实例 id（契约 2026-10-09 投影轮 §4.1：发起即回，同事务已有值——零新查询） */
    private InnerApprovalCreateVo buildCreateVo(Long id, String processInstanceId) {
        InnerApprovalCreateVo vo = new InnerApprovalCreateVo();
        vo.setApprovalId(String.valueOf(id));
        vo.setProcessInstanceId(processInstanceId);
        vo.setStatus(String.valueOf(StatusEnum.APPROVING.getCode()));
        return vo;
    }

    /** pid 透出（契约投影轮 §4.2）：无审批单/撤销后 null——撤销 updateStatusById 传 null 清空列 */
    private InnerApprovalStatusVo toStatusVo(String businessKey, BpmnApproval approval) {
        InnerApprovalStatusVo vo = new InnerApprovalStatusVo();
        vo.setBusinessKey(businessKey);
        if (approval != null) {
            vo.setApprovalId(String.valueOf(approval.getId()));
            vo.setProcessInstanceId(approval.getProcessInstanceId());
            vo.setStatus(approval.getStatus() == null ? null : String.valueOf(approval.getStatus()));
        }
        return vo;
    }

    // ---- 撤销脚手架（public：生产前置校验与 executor 复用，同一错误语义源） ----

    private BpmnApproval requireApproval(Long id) {
        BpmnApproval approval = approvalMapper.findById(id);
        if (approval == null) {
            throw new BusinessException(ERR_APPROVAL_NOT_FOUND, "审批单不存在");
        }
        return approval;
    }

    /** 撤销校验序（契约 §3.3/§4.3）：4010 行在 → 4012 仅申请人本人 → 4011 已终态；
     *  public 供 ApprovalCancelTxExecutor 复查复用（发送前置校验后到者防御） */
    public BpmnApproval findCancelableApproval(Long id, String operator) {
        BpmnApproval approval = requireApproval(id);
        checkCancelable(approval, operator);
        return approval;
    }

    private void checkCancelable(BpmnApproval approval, String operator) {
        if (!approval.getApplyUser().equals(operator)) {
            throw new BusinessException(ERR_NOT_APPLIER, "仅申请人本人可撤销");
        }
        if (StatusEnum.of(approval.getStatus()).isTerminal()) {
            throw new BusinessException(ERR_APPROVAL_TERMINAL, "审批单已终态，不可撤销");
        }
    }

    /** 删流程实例；实例已不存在=并发办理竞态（后到者感知 4011）——历史终态防御不透传引擎栈 */
    public void deleteProcessInstance(BpmnApproval approval) {
        String processInstanceId = approval.getProcessInstanceId();
        if (processInstanceId == null) {
            return;
        }
        try {
            runtimeService.deleteProcessInstance(processInstanceId, "approval cancelled by applier");
        } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException e) {
            log.error("流程实例已不存在（并发办理或历史终态）: {}", processInstanceId, e);
            throw new BusinessException(ERR_APPROVAL_TERMINAL, "审批单已终态，不可撤销");
        }
    }

    // ---- 办理脚手架 ----

    /** 任务→审批单定位：历史实例 businessKey（=审批单 id）→ findById；businessKey 缺失=非平台审批域任务（防御 4010） */
    private BpmnApproval requireApprovalByTask(Task task) {
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        String businessKey = historic == null ? null : historic.getBusinessKey();
        if (businessKey == null) {
            log.error("任务未关联平台审批单: taskId={}, processInstanceId={}", task.getId(), task.getProcessInstanceId());
            throw new BusinessException(ERR_APPROVAL_NOT_FOUND, "审批单不存在");
        }
        return requireApproval(Long.valueOf(businessKey));
    }

    /** TERMINAL 事件半消息发送（两通道共用）：TxMessageSendException=半消息失败（本地零写）转 1002 系 */
    private void sendTerminalEvent(BpmnApproval approval, Integer terminalStatus, String channel, Object bizArg) {
        ApprovalEventMessage event = eventPublisher.terminal(
                approval.getBusinessType(), approval.getBusinessKey(), terminalStatus);
        try {
            txMessageSender.sendTransactional(ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY,
                    ApprovalMqTopics.TAG_TERMINAL, eventPublisher.notifyKeys(event), event, channel, bizArg);
        } catch (TxMessageSendException e) {
            log.error("终态通知事务半消息发送失败: channel={}, keys={}",
                    channel, eventPublisher.notifyKeys(event), e);
            throw new BusinessException("消息服务不可用");
        }
    }
}
