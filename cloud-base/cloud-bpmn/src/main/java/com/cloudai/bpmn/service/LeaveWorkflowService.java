package com.cloudai.bpmn.service;

import com.cloudai.bpmn.client.SystemUserClient;
import com.cloudai.bpmn.dto.LeaveCreateRequest;
import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.entity.BpmnLeave.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.domain.UserEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 请假流程编排（设计 D7 核心数据流）：发起/撤销/办理三方法，
 * 业务表写与引擎写同一本地事务（@Transactional rollbackFor，D3 同事务 IT 保障）——防「业务表成功+引擎失败」孤儿单。
 * 错误码 4xxx 账本见契约 2026-10-07-bpmn-leave-api §5。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveWorkflowService {

    private static final int ERR_LEAVE_NOT_FOUND = 4001;
    private static final int ERR_LEAVE_TERMINAL = 4002;
    private static final int ERR_NOT_APPLIER = 4003;
    private static final int ERR_APPROVER_INVALID = 4004;
    private static final int ERR_TASK_INVALID = 4005;
    private static final int ERR_DATE_INVALID = 4006;
    private static final int ERR_DEFINITION_MISSING = 4007;

    private static final String PROCESS_KEY = "leave_approval";
    private static final String VAR_APPROVE = "approve";
    private static final String ACTIVITY_END_APPROVE = "endApprove";
    private static final String ACTIVITY_END_REJECT = "endReject";

    private final BpmnLeaveMapper leaveMapper;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final SystemUserClient systemUserClient;

    /** 发起请假：校验（日期 4006/审批人 4004）→ insert 审批中 → 启动实例（businessKey=id）→ 同事务回填实例关联 */
    @Transactional(rollbackFor = Exception.class)
    public Long saveLeave(LeaveCreateRequest req, String applyUser) {
        LocalDate startDate = parseDate(req.getStartDate());
        LocalDate endDate = parseDate(req.getEndDate());
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BusinessException(ERR_DATE_INVALID, "请假日期无效：结束日期不能早于开始日期");
        }
        validateApprover(req.getApprover());

        BpmnLeave leave = buildLeave(req, applyUser, startDate, endDate);
        leaveMapper.save(leave);

        String processInstanceId = startProcess(leave);
        leaveMapper.updateStatusById(leave.getId(), StatusEnum.APPROVING.getCode(),
                processInstanceId, applyUser, LocalDateTime.now());
        return leave.getId();
    }

    /** 撤销请假：4001→4003→4002（先身份后状态）→ 删流程实例 → 置已撤销（实例关联清空，契约 §2.2） */
    @Transactional(rollbackFor = Exception.class)
    public void cancelLeave(Long id, String opUser) {
        BpmnLeave leave = leaveMapper.findById(id);
        if (leave == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        if (!leave.getApplyUser().equals(opUser)) {
            throw new BusinessException(ERR_NOT_APPLIER, "仅申请人本人可撤销");
        }
        if (StatusEnum.of(leave.getStatus()).isTerminal()) {
            throw new BusinessException(ERR_LEAVE_TERMINAL, "请假单已终态，不可撤销");
        }
        deleteProcessInstance(leave);
        leaveMapper.updateStatusById(id, StatusEnum.CANCELLED.getCode(), null, opUser, LocalDateTime.now());
    }

    /** 办理任务：意见落 ACT_HI_COMMENT（操作人经引擎 Authentication 记入）→ complete(approve) →
     *  实例结束按 endActivityId 回写状态（endApprove→1 / endReject→2；未结束=未来多节点模型不回写，记档） */
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(TaskCompleteRequest req, String opUser) {
        Task task = taskService.createTaskQuery().taskId(req.getTaskId()).singleResult();
        if (task == null) {
            throw new BusinessException(ERR_TASK_INVALID, "任务不存在或已被办理");
        }
        Authentication.setAuthenticatedUserId(opUser);
        try {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), req.getComment());
            taskService.complete(task.getId(), Map.of(VAR_APPROVE, Boolean.parseBoolean(req.getApprove())));
        } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException e) {
            log.error("任务已不存在或已被办理: {}", req.getTaskId(), e);
            throw new BusinessException(ERR_TASK_INVALID, "任务不存在或已被办理");
        } catch (org.flowable.common.engine.api.FlowableException e) {
            log.error("流程引擎办理异常: {}", req.getTaskId(), e);
            throw new BusinessException("流程引擎办理异常");
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
        writeBackStatus(task, opUser);
    }

    /** 审批人有效性校验：Feign 全量投影比对（4004）；Feign 不可用 log.error 后 1002（不引 fallback） */
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

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private BpmnLeave buildLeave(LeaveCreateRequest req, String applyUser, LocalDate start, LocalDate end) {
        BpmnLeave leave = new BpmnLeave();
        leave.setTitle(req.getTitle());
        leave.setLeaveType(Integer.valueOf(req.getLeaveType()));
        leave.setStartDate(start);
        leave.setEndDate(end);
        leave.setReason(req.getReason());
        leave.setStatus(StatusEnum.APPROVING.getCode());
        leave.setApplyUser(applyUser);
        leave.setApprover(req.getApprover());
        auditCreate(leave, applyUser);
        return leave;
    }

    /** 手写 SQL 无自动填充：审计四值显式构造（插入时 update 值 = create 值） */
    private void auditCreate(BpmnLeave leave, String applyUser) {
        LocalDateTime now = LocalDateTime.now();
        leave.setCreateBy(applyUser);
        leave.setCreateTime(now);
        leave.setUpdateBy(applyUser);
        leave.setUpdateTime(now);
    }

    /** 启动流程实例（businessKey=请假单 id 字符串；定义缺失 4007 环境防御） */
    private String startProcess(BpmnLeave leave) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leaveId", leave.getId());
        variables.put("applyUser", leave.getApplyUser());
        variables.put("approver", leave.getApprover());
        variables.put("title", leave.getTitle());
        try {
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                    PROCESS_KEY, String.valueOf(leave.getId()), variables);
            return instance.getId();
        } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException e) {
            log.error("流程定义未部署: {}", PROCESS_KEY, e);
            throw new BusinessException(ERR_DEFINITION_MISSING, "流程定义未部署");
        }
    }

    /** 删流程实例；实例已不存在=并发办理竞态（后到者感知 4002）——历史终态防御不透传引擎栈 */
    private void deleteProcessInstance(BpmnLeave leave) {
        String processInstanceId = leave.getProcessInstanceId();
        if (processInstanceId == null) {
            return;
        }
        try {
            runtimeService.deleteProcessInstance(processInstanceId, "leave cancelled by applier");
        } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException e) {
            log.error("流程实例已不存在（并发办理或历史终态）: {}", processInstanceId, e);
            throw new BusinessException(ERR_LEAVE_TERMINAL, "请假单已终态，不可撤销");
        }
    }

    /** 结束态回写：历史实例（含运行中）取 businessKey；未结束不回写（多节点模型演进项记档） */
    private void writeBackStatus(Task task, String opUser) {
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        if (historic == null || historic.getBusinessKey() == null || historic.getEndTime() == null) {
            return;
        }
        Integer newStatus = mapEndActivityToStatus(historic.getEndActivityId());
        if (newStatus == null) {
            log.warn("未知 endActivityId，不回写请假单状态: {}", historic.getEndActivityId());
            return;
        }
        leaveMapper.updateStatusById(Long.valueOf(historic.getBusinessKey()), newStatus,
                task.getProcessInstanceId(), opUser, LocalDateTime.now());
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
}
