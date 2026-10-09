package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.util.BpmnDateUtil;
import com.cloudai.bpmn.vo.TaskDoneVo;
import com.cloudai.bpmn.vo.TaskVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务查询通用化（契约 2026-10-08-approval-platform-api §2.1/§2.2）：ACT_RU_TASK 待办 +
 * ACT_HI_TASKINST 已办，businessKey（=审批单 id）回查 bpmn_approval 快照 + 配置表渲染，
 * 零业务表回查、零跨服务。不分页（个人量级小，additive 演进项记移交）；businessKey 需经
 * HistoricProcessInstance 取（Task 接口无投影，历史实例对运行中实例同样有痕）。只读服务不加事务注解。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAppService {

    private static final String ACTIVITY_END_APPROVE = "endApprove";
    private static final String ACTIVITY_END_REJECT = "endReject";

    private final TaskService taskService;
    private final HistoryService historyService;
    private final BpmnApprovalMapper approvalMapper;
    private final BusinessTypeRegistry businessTypeRegistry;

    /** 待办列表（assignee=当前登录人，任务创建时间倒序）；审批单缺失/键异常行跳过（记档不炸列表） */
    public List<TaskVo> listTodo(String assignee) {
        List<Task> tasks = taskService.createTaskQuery()
                .taskAssignee(assignee)
                .orderByTaskCreateTime().desc()
                .list();
        List<TaskVo> out = new ArrayList<>();
        Map<String, BpmnBusinessType> configCache = new HashMap<>();
        for (Task task : tasks) {
            BpmnApproval approval = findApproval(task.getProcessInstanceId());
            if (approval == null) {
                continue;
            }
            out.add(buildTaskVo(task, approval, configCache));
        }
        return out;
    }

    /** 已办列表（taskAssignee=当前登录人且 finished，办理时间倒序）；审批单缺失行跳过（同上） */
    public List<TaskDoneVo> listDone(String assignee) {
        List<HistoricTaskInstance> tasks = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(assignee)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();
        List<TaskDoneVo> out = new ArrayList<>();
        Map<String, BpmnBusinessType> configCache = new HashMap<>();
        for (HistoricTaskInstance task : tasks) {
            BpmnApproval approval = findApproval(task.getProcessInstanceId());
            if (approval == null) {
                continue;
            }
            out.add(buildDoneVo(task, approval, configCache));
        }
        return out;
    }

    /** businessKey → bpmn_approval 快照；三重跳过各有 log.warn 痕迹（契约 §2.1/§2.2 防御态）：
     *  实例缺 businessKey / 键非数字 / 键无审批单行（旧轮残留，businessKey=旧业务 id）→ null 跳过 */
    private BpmnApproval findApproval(String processInstanceId) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null || instance.getBusinessKey() == null) {
            log.warn("任务关联的流程实例无 businessKey，跳过: {}", processInstanceId);
            return null;
        }
        Long approvalId;
        try {
            approvalId = Long.valueOf(instance.getBusinessKey());
        } catch (NumberFormatException e) {
            log.warn("businessKey 非审批单 id，跳过: {}", instance.getBusinessKey());
            return null;
        }
        BpmnApproval approval = approvalMapper.findById(approvalId);
        if (approval == null) {
            log.warn("businessKey 无对应审批单行，跳过: businessKey={}", instance.getBusinessKey());
        }
        return approval;
    }

    private TaskVo buildTaskVo(Task task, BpmnApproval approval, Map<String, BpmnBusinessType> configCache) {
        TaskVo vo = new TaskVo();
        vo.setTaskId(task.getId());
        vo.setApprovalId(String.valueOf(approval.getId()));
        vo.setBusinessType(approval.getBusinessType());
        vo.setTitle(approval.getTitle());
        vo.setApplyUser(approval.getApplyUser());
        vo.setCreateTime(BpmnDateUtil.format(task.getCreateTime()));
        enrichConfig(vo, approval, configCache);
        return vo;
    }

    private TaskDoneVo buildDoneVo(HistoricTaskInstance task, BpmnApproval approval,
                                   Map<String, BpmnBusinessType> configCache) {
        TaskDoneVo vo = new TaskDoneVo();
        vo.setTaskId(task.getId());
        vo.setApprovalId(String.valueOf(approval.getId()));
        vo.setBusinessType(approval.getBusinessType());
        vo.setTitle(approval.getTitle());
        vo.setApplyUser(approval.getApplyUser());
        vo.setCreateTime(BpmnDateUtil.format(task.getCreateTime()));
        enrichConfig(vo, approval, configCache);
        vo.setEndTime(BpmnDateUtil.format(task.getEndTime()));
        vo.setApprove(approveText(task.getProcessInstanceId()));
        vo.setComment(latestComment(task.getProcessInstanceId()));
        vo.setApprovalStatus(approval.getStatus() == null ? null : String.valueOf(approval.getStatus()));
        return vo;
    }

    /** 配置渲染回填：businessTypeName + detailPath（容忍版查询——配置缺失置 null 不炸列表，契约 §9 降级）；
     *  单次列表调用内按 type_code 记忆化（uk_type_code 等值查询，列表内不重复打库） */
    private void enrichConfig(TaskVo vo, BpmnApproval approval, Map<String, BpmnBusinessType> configCache) {
        String typeCode = approval.getBusinessType();
        BpmnBusinessType config = configCache.computeIfAbsent(typeCode,
                code -> businessTypeRegistry.findByTypeCodeOrNull(code));
        if (config == null) {
            log.error("业务类型配置缺失，businessTypeName/detailPath 置 null: {}", typeCode);
            return;
        }
        vo.setBusinessTypeName(config.getTypeName());
        // {businessKey} 渲染源 = 审批单 id（契约 §1 域语言，§2.1 示例 approvalId 12 → approval=12）
        vo.setDetailPath(businessTypeRegistry.renderDetailPath(config.getDetailRoute(),
                String.valueOf(approval.getId())));
    }

    /** 办理结果由实例 endActivityId 判定（endApprove→"true"/endReject→"false"；未知 null） */
    private String approveText(String processInstanceId) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null) {
            return null;
        }
        if (ACTIVITY_END_APPROVE.equals(instance.getEndActivityId())) {
            return "true";
        }
        if (ACTIVITY_END_REJECT.equals(instance.getEndActivityId())) {
            return "false";
        }
        return null;
    }

    /** 实例最新一条审批意见（ACT_HI_COMMENT，时间序末位） */
    private String latestComment(String processInstanceId) {
        List<Comment> comments = taskService.getProcessInstanceComments(processInstanceId);
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        Comment latest = comments.get(comments.size() - 1);
        return latest.getFullMessage();
    }
}
