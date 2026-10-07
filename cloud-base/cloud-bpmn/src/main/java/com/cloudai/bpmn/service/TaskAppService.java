package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
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
import java.util.List;

/**
 * 任务查询（契约 §3.1/§3.2）：ACT_RU_TASK 待办 + ACT_HI_TASKINST 已办，businessKey 回查请假单。
 * 不分页（个人量级小，additive 演进项记移交3）；businessKey 需经 HistoricProcessInstance 取
 * （Task 接口无投影，历史实例对运行中实例同样有痕）。只读服务不加事务注解。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAppService {

    private static final String ACTIVITY_END_APPROVE = "endApprove";
    private static final String ACTIVITY_END_REJECT = "endReject";

    private final TaskService taskService;
    private final HistoryService historyService;
    private final BpmnLeaveMapper leaveMapper;

    /** 待办列表（assignee=当前登录人，任务创建时间倒序） */
    public List<TaskVo> listTodo(String assignee) {
        List<Task> tasks = taskService.createTaskQuery()
                .taskAssignee(assignee)
                .orderByTaskCreateTime().desc()
                .list();
        List<TaskVo> out = new ArrayList<>();
        for (Task task : tasks) {
            HistoricProcessInstance instance = findInstance(task.getProcessInstanceId());
            BpmnLeave leave = findLeave(instance);
            if (leave == null) {
                continue;
            }
            out.add(buildTaskVo(task, leave));
        }
        return out;
    }

    /** 已办列表（taskAssignee=当前登录人且 finished，办理时间倒序） */
    public List<TaskDoneVo> listDone(String assignee) {
        List<HistoricTaskInstance> tasks = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(assignee)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();
        List<TaskDoneVo> out = new ArrayList<>();
        for (HistoricTaskInstance task : tasks) {
            HistoricProcessInstance instance = findInstance(task.getProcessInstanceId());
            BpmnLeave leave = findLeave(instance);
            if (leave == null) {
                continue;
            }
            out.add(buildDoneVo(task, instance, leave));
        }
        return out;
    }

    private TaskVo buildTaskVo(Task task, BpmnLeave leave) {
        TaskVo vo = new TaskVo();
        vo.setTaskId(task.getId());
        vo.setLeaveId(String.valueOf(leave.getId()));
        vo.setLeaveTitle(leave.getTitle());
        vo.setLeaveType(leave.getLeaveType() == null ? null : String.valueOf(leave.getLeaveType()));
        vo.setApplyUser(leave.getApplyUser());
        vo.setCreateTime(BpmnDateUtil.format(task.getCreateTime()));
        return vo;
    }

    private TaskDoneVo buildDoneVo(HistoricTaskInstance task, HistoricProcessInstance instance, BpmnLeave leave) {
        TaskDoneVo vo = new TaskDoneVo();
        vo.setTaskId(task.getId());
        vo.setLeaveId(String.valueOf(leave.getId()));
        vo.setLeaveTitle(leave.getTitle());
        vo.setLeaveType(leave.getLeaveType() == null ? null : String.valueOf(leave.getLeaveType()));
        vo.setApplyUser(leave.getApplyUser());
        vo.setCreateTime(BpmnDateUtil.format(task.getCreateTime()));
        vo.setEndTime(BpmnDateUtil.format(task.getEndTime()));
        vo.setApprove(approveText(instance));
        vo.setComment(latestComment(task.getProcessInstanceId()));
        vo.setLeaveStatus(leave.getStatus() == null ? null : String.valueOf(leave.getStatus()));
        return vo;
    }

    /** 办理结果由实例 endActivityId 判定（endApprove→"true"/endReject→"false"；未知 null） */
    private String approveText(HistoricProcessInstance instance) {
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

    private HistoricProcessInstance findInstance(String processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
    }

    private BpmnLeave findLeave(HistoricProcessInstance instance) {
        if (instance == null || instance.getBusinessKey() == null) {
            log.warn("任务关联的流程实例无 businessKey，跳过: {}",
                    instance == null ? null : instance.getId());
            return null;
        }
        return leaveMapper.findById(Long.valueOf(instance.getBusinessKey()));
    }
}
