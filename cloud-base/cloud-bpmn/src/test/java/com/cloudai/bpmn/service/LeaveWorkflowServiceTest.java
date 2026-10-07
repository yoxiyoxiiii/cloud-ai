package com.cloudai.bpmn.service;

import com.cloudai.bpmn.client.SystemUserClient;
import com.cloudai.bpmn.dto.LeaveCreateRequest;
import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.domain.UserEntry;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假流程编排单测（契约 2026-10-07-bpmn-leave-api §5：4001-4007 全触发 + 状态机迁移 + endActivityId 映射）。
 */
@ExtendWith(MockitoExtension.class)
class LeaveWorkflowServiceTest {

    @Mock
    private BpmnLeaveMapper leaveMapper;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private SystemUserClient systemUserClient;
    @InjectMocks
    private LeaveWorkflowService service;

    // ---- saveLeave ----

    @Test
    void saveLeave_happyPath_insertsStartsAndBackfills() {
        stubApproverProjection("admin");
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn("pid-1");
        when(runtimeService.startProcessInstanceByKey(eq("leave_approval"), eq("7"), anyMap()))
                .thenReturn(instance);
        doAnswer(inv -> {
            inv.getArgument(0, BpmnLeave.class).setId(7L);
            return 1;
        }).when(leaveMapper).save(any(BpmnLeave.class));

        Long id = service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "admin");

        assertThat(id).isEqualTo(7L);
        ArgumentCaptor<BpmnLeave> captor = ArgumentCaptor.forClass(BpmnLeave.class);
        verify(leaveMapper).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BpmnLeave.StatusEnum.APPROVING.getCode());
        assertThat(captor.getValue().getCreateBy()).isEqualTo("admin");
        assertThat(captor.getValue().getProcessInstanceId()).isNull();
        verify(leaveMapper).updateStatusById(eq(7L), eq(BpmnLeave.StatusEnum.APPROVING.getCode()),
                eq("pid-1"), eq("admin"), any());
    }

    @Test
    void saveLeave_injectsProcessVariables() {
        stubApproverProjection("admin");
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn("pid-1");
        when(runtimeService.startProcessInstanceByKey(anyString(), anyString(), anyMap()))
                .thenReturn(instance);
        doAnswer(inv -> {
            inv.getArgument(0, BpmnLeave.class).setId(7L);
            return 1;
        }).when(leaveMapper).save(any(BpmnLeave.class));

        service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService).startProcessInstanceByKey(eq("leave_approval"), eq("7"), vars.capture());
        assertThat(vars.getValue())
                .containsEntry("leaveId", 7L)
                .containsEntry("applyUser", "userA")
                .containsEntry("approver", "admin")
                .containsEntry("title", "annual leave");
    }

    @Test
    void saveLeave_endBeforeStartRejected_4006() {
        assertThatThrownBy(() -> service.saveLeave(request("2026-10-09", "2026-10-08", "admin"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4006);
        verify(leaveMapper, never()).save(any());
    }

    @Test
    void saveLeave_unparseableDateRejected_4006() {
        // DTO @Pattern 已拦格式，Service 解析防御（不可解析同归 4006）
        assertThatThrownBy(() -> service.saveLeave(request("2026-13-99", "2026-10-09", "admin"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4006);
    }

    @Test
    void saveLeave_approverNotInProjectionRejected_4004() {
        stubApproverProjection("admin");
        assertThatThrownBy(() -> service.saveLeave(request("2026-10-08", "2026-10-09", "nobody"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4004);
        verify(leaveMapper, never()).save(any());
    }

    @Test
    void saveLeave_feignFailureRejected_1002() {
        when(systemUserClient.listAll()).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(1002);
        verify(leaveMapper, never()).save(any());
    }

    @Test
    void saveLeave_definitionMissingRejected_4007() {
        stubApproverProjection("admin");
        when(runtimeService.startProcessInstanceByKey(anyString(), anyString(), anyMap()))
                .thenThrow(new FlowableObjectNotFoundException("no processes deployed with key"));
        doAnswer(inv -> {
            inv.getArgument(0, BpmnLeave.class).setId(7L);
            return 1;
        }).when(leaveMapper).save(any(BpmnLeave.class));

        assertThatThrownBy(() -> service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4007);
        verify(leaveMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    // ---- cancelLeave ----

    @Test
    void cancelLeave_notFoundRejected_4001() {
        when(leaveMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.cancelLeave(9L, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4001);
    }

    @Test
    void cancelLeave_notApplierRejected_4003() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave(5L, "userA", "admin"));
        assertThatThrownBy(() -> service.cancelLeave(5L, "userB"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4003);
        verify(runtimeService, never()).deleteProcessInstance(anyString(), anyString());
    }

    @Test
    void cancelLeave_terminalRejected_4002() {
        BpmnLeave approved = approvingLeave(5L, "admin", "admin");
        approved.setStatus(BpmnLeave.StatusEnum.APPROVED.getCode());
        when(leaveMapper.findById(5L)).thenReturn(approved);
        assertThatThrownBy(() -> service.cancelLeave(5L, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4002);
        verify(runtimeService, never()).deleteProcessInstance(anyString(), anyString());
    }

    @Test
    void cancelLeave_happyPath_deletesInstanceAndMarksCancelled() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave(5L, "admin", "admin"));
        service.cancelLeave(5L, "admin");
        verify(runtimeService).deleteProcessInstance(eq("pid-5"), anyString());
        verify(leaveMapper).updateStatusById(eq(5L), eq(BpmnLeave.StatusEnum.CANCELLED.getCode()),
                eq(null), eq("admin"), any());
    }

    @Test
    void cancelLeave_instanceAlreadyGoneRejected_4002() {
        // 并发竞态：审批先完成删除实例（后到者感知）——设计 §3
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave(5L, "admin", "admin"));
        doThrow(new FlowableObjectNotFoundException("no process instance"))
                .when(runtimeService).deleteProcessInstance(anyString(), anyString());
        assertThatThrownBy(() -> service.cancelLeave(5L, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4002);
        verify(leaveMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    // ---- completeTask ----

    @Test
    void completeTask_taskMissingRejected_4005() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId("t-404")).thenReturn(query);
        when(query.singleResult()).thenReturn(null);
        assertThatThrownBy(() -> service.completeTask(complete("t-404", "true"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4005);
    }

    @Test
    void completeTask_approve_writesBackApproved() {
        stubTaskAndInstance("t-1", "pid-1", "5", "endApprove", new Date());
        service.completeTask(complete("t-1", "true"), "admin");
        verify(taskService).addComment(eq("t-1"), eq("pid-1"), eq("ok"));
        verify(taskService).complete(eq("t-1"), eq(Map.of("approve", Boolean.TRUE)));
        verify(leaveMapper).updateStatusById(eq(5L), eq(BpmnLeave.StatusEnum.APPROVED.getCode()),
                eq("pid-1"), eq("admin"), any());
    }

    @Test
    void completeTask_reject_writesBackRejected() {
        stubTaskAndInstance("t-1", "pid-1", "5", "endReject", new Date());
        service.completeTask(complete("t-1", "false"), "userA");
        verify(taskService).complete(eq("t-1"), eq(Map.of("approve", Boolean.FALSE)));
        verify(leaveMapper).updateStatusById(eq(5L), eq(BpmnLeave.StatusEnum.REJECTED.getCode()),
                eq("pid-1"), eq("userA"), any());
    }

    @Test
    void completeTask_instanceUnfinished_noWriteBack() {
        // 未结束=未来多节点模型：不回写，保持审批中（设计 D7 记档）
        stubTaskAndInstance("t-1", "pid-1", "5", null, null);
        service.completeTask(complete("t-1", "true"), "admin");
        verify(leaveMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void completeTask_unknownEndActivity_noWriteBack() {
        stubTaskAndInstance("t-1", "pid-1", "5", "endUnknown", new Date());
        service.completeTask(complete("t-1", "true"), "admin");
        verify(leaveMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void completeTask_alreadyCompletedRejected_4005() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId("t-1")).thenReturn(query);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("t-1");
        when(task.getProcessInstanceId()).thenReturn("pid-1");
        when(query.singleResult()).thenReturn(task);
        doThrow(new FlowableObjectNotFoundException("task already completed"))
                .when(taskService).complete(eq("t-1"), anyMap());
        assertThatThrownBy(() -> service.completeTask(complete("t-1", "true"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4005);
    }

    @Test
    void completeTask_engineFailureRejected_1002() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId("t-1")).thenReturn(query);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("t-1");
        when(task.getProcessInstanceId()).thenReturn("pid-1");
        when(query.singleResult()).thenReturn(task);
        doThrow(new FlowableException("engine broken"))
                .when(taskService).complete(eq("t-1"), anyMap());
        assertThatThrownBy(() -> service.completeTask(complete("t-1", "true"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(1002);
    }

    // ---- 脚手架 ----

    private void stubApproverProjection(String... accounts) {
        List<UserEntry> entries = java.util.Arrays.stream(accounts)
                .map(a -> {
                    UserEntry e = new UserEntry();
                    e.setAccount(a);
                    e.setNickname("nick-" + a);
                    return e;
                })
                .toList();
        when(systemUserClient.listAll()).thenReturn(R.ok(entries));
    }

    private void stubTaskAndInstance(String taskId, String processInstanceId, String businessKey,
                                     String endActivityId, Date endTime) {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(taskId)).thenReturn(query);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(query.singleResult()).thenReturn(task);
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        lenient().when(hq.processInstanceId(processInstanceId)).thenReturn(hq);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getBusinessKey()).thenReturn(businessKey);
        lenient().when(historic.getEndTime()).thenReturn(endTime);
        lenient().when(historic.getEndActivityId()).thenReturn(endActivityId);
        lenient().when(hq.singleResult()).thenReturn(historic);
    }

    private LeaveCreateRequest request(String start, String end, String approver) {
        LeaveCreateRequest req = new LeaveCreateRequest();
        req.setTitle("annual leave");
        req.setLeaveType("3");
        req.setStartDate(start);
        req.setEndDate(end);
        req.setReason("trip");
        req.setApprover(approver);
        return req;
    }

    private TaskCompleteRequest complete(String taskId, String approve) {
        TaskCompleteRequest req = new TaskCompleteRequest();
        req.setTaskId(taskId);
        req.setApprove(approve);
        req.setComment("ok");
        return req;
    }

    private BpmnLeave approvingLeave(Long id, String applyUser, String approver) {
        BpmnLeave leave = new BpmnLeave();
        leave.setId(id);
        leave.setStatus(BpmnLeave.StatusEnum.APPROVING.getCode());
        leave.setApplyUser(applyUser);
        leave.setApprover(approver);
        leave.setProcessInstanceId("pid-5");
        return leave;
    }
}
