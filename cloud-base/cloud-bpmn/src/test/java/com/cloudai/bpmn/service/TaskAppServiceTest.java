package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.entity.BpmnLeave.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
import com.cloudai.bpmn.vo.TaskDoneVo;
import com.cloudai.bpmn.vo.TaskVo;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务列表单测（契约 §3.1/§3.2：待办/已办拼装 + businessKey 回查 + endActivityId 结果判定 + 最新意见）。
 */
@ExtendWith(MockitoExtension.class)
class TaskAppServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private BpmnLeaveMapper leaveMapper;
    @InjectMocks
    private TaskAppService service;

    @Test
    void listTodo_mapsEngineTaskWithLeaveRow() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.orderByTaskCreateTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        Task task = mock(Task.class);
        lenient().when(task.getId()).thenReturn("t-1");
        lenient().when(task.getProcessInstanceId()).thenReturn("pid-1");
        lenient().when(task.getCreateTime()).thenReturn(new Date());
        when(query.list()).thenReturn(List.of(task));
        stubHistoricInstance("pid-1", "5", "endApprove", null);
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.APPROVING.getCode()));

        List<TaskVo> todos = service.listTodo("admin");

        assertThat(todos).hasSize(1);
        TaskVo vo = todos.get(0);
        assertThat(vo.getTaskId()).isEqualTo("t-1");
        assertThat(vo.getLeaveId()).isEqualTo("5");
        assertThat(vo.getLeaveTitle()).isEqualTo("annual leave");
        assertThat(vo.getLeaveType()).isEqualTo("3");
        assertThat(vo.getApplyUser()).isEqualTo("userA");
        assertThat(vo.getCreateTime()).isNotBlank();
    }

    @Test
    void listTodo_skipsWhenBusinessKeyMissing() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.orderByTaskCreateTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        Task task = mock(Task.class);
        lenient().when(task.getProcessInstanceId()).thenReturn("pid-1");
        when(query.list()).thenReturn(List.of(task));
        stubHistoricInstance("pid-1", null, null, null);

        List<TaskVo> todos = service.listTodo("admin");

        assertThat(todos).isEmpty();
        verify(leaveMapper, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void listDone_mapsHistoryTaskWithResultAndComment() {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.finished()).thenReturn(query);
        when(query.orderByHistoricTaskInstanceEndTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        lenient().when(task.getId()).thenReturn("t-1");
        lenient().when(task.getProcessInstanceId()).thenReturn("pid-1");
        lenient().when(task.getCreateTime()).thenReturn(new Date());
        lenient().when(task.getEndTime()).thenReturn(new Date());
        when(query.list()).thenReturn(List.of(task));
        stubHistoricInstance("pid-1", "5", "endApprove", new Date());
        Comment comment = mock(Comment.class);
        lenient().when(comment.getFullMessage()).thenReturn("agree");
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of(comment));
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.APPROVED.getCode()));

        List<TaskDoneVo> done = service.listDone("admin");

        assertThat(done).hasSize(1);
        TaskDoneVo vo = done.get(0);
        assertThat(vo.getTaskId()).isEqualTo("t-1");
        assertThat(vo.getLeaveId()).isEqualTo("5");
        assertThat(vo.getApprove()).isEqualTo("true");
        assertThat(vo.getComment()).isEqualTo("agree");
        assertThat(vo.getLeaveStatus()).isEqualTo("1");
        assertThat(vo.getEndTime()).isNotBlank();
    }

    @Test
    void listDone_rejectResultMapped() {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.finished()).thenReturn(query);
        when(query.orderByHistoricTaskInstanceEndTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        lenient().when(task.getId()).thenReturn("t-2");
        lenient().when(task.getProcessInstanceId()).thenReturn("pid-2");
        lenient().when(task.getCreateTime()).thenReturn(new Date());
        lenient().when(task.getEndTime()).thenReturn(new Date());
        when(query.list()).thenReturn(List.of(task));
        stubHistoricInstance("pid-2", "5", "endReject", new Date());
        when(taskService.getProcessInstanceComments("pid-2")).thenReturn(List.of());
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.REJECTED.getCode()));

        List<TaskDoneVo> done = service.listDone("admin");

        assertThat(done).hasSize(1);
        assertThat(done.get(0).getApprove()).isEqualTo("false");
        assertThat(done.get(0).getComment()).isNull();
        assertThat(done.get(0).getLeaveStatus()).isEqualTo("2");
    }

    // ---- 脚手架 ----

    private void stubHistoricInstance(String processInstanceId, String businessKey,
                                      String endActivityId, Date endTime) {
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        lenient().when(hq.processInstanceId(processInstanceId)).thenReturn(hq);
        HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
        lenient().when(instance.getId()).thenReturn(processInstanceId);
        lenient().when(instance.getBusinessKey()).thenReturn(businessKey);
        lenient().when(instance.getEndActivityId()).thenReturn(endActivityId);
        lenient().when(instance.getEndTime()).thenReturn(endTime);
        lenient().when(hq.singleResult()).thenReturn(instance);
    }

    private BpmnLeave leave(Integer status) {
        BpmnLeave leave = new BpmnLeave();
        leave.setId(5L);
        leave.setTitle("annual leave");
        leave.setLeaveType(BpmnLeave.TypeEnum.ANNUAL.getCode());
        leave.setStartDate(LocalDate.of(2026, 10, 8));
        leave.setEndDate(LocalDate.of(2026, 10, 9));
        leave.setStatus(status);
        leave.setApplyUser("userA");
        leave.setApprover("admin");
        leave.setProcessInstanceId("pid-1");
        return leave;
    }
}
