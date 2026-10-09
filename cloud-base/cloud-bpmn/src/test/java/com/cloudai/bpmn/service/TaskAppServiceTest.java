package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
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

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务列表单测（契约 2026-10-08-approval-platform-api §2.1/§2.2 通用化：
 * businessKey 回查 bpmn_approval 快照 + 配置表渲染 + endActivityId 结果判定 + 最新意见 + 键异常防御）。
 */
@ExtendWith(MockitoExtension.class)
class TaskAppServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private BpmnApprovalMapper approvalMapper;
    @Mock
    private BusinessTypeRegistry businessTypeRegistry;
    @InjectMocks
    private TaskAppService service;

    @Test
    void listTodo_mapsEngineTaskWithApprovalSnapshot() {
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
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubConfig();

        List<TaskVo> todos = service.listTodo("admin");

        assertThat(todos).hasSize(1);
        TaskVo vo = todos.get(0);
        assertThat(vo.getTaskId()).isEqualTo("t-1");
        assertThat(vo.getApprovalId()).isEqualTo("5");
        assertThat(vo.getBusinessType()).isEqualTo("leave");
        assertThat(vo.getBusinessTypeName()).isEqualTo("Leave");
        assertThat(vo.getTitle()).isEqualTo("annual leave");
        // 渲染源=审批单 id（5），非 businessKey（7）——契约 §1 域语言
        assertThat(vo.getDetailPath()).isEqualTo("/system/leave?approval=5");
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
        verify(approvalMapper, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void listTodo_skipsWhenBusinessKeyNonNumeric() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.orderByTaskCreateTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        Task task = mock(Task.class);
        lenient().when(task.getProcessInstanceId()).thenReturn("pid-1");
        when(query.list()).thenReturn(List.of(task));
        stubHistoricInstance("pid-1", "external-key", null, null);

        List<TaskVo> todos = service.listTodo("admin");

        assertThat(todos).isEmpty();
        verify(approvalMapper, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void listTodo_keepsRowWithNullConfigFieldsWhenTypeMissing() {
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
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(null);

        List<TaskVo> todos = service.listTodo("admin");

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).getBusinessTypeName()).isNull();
        assertThat(todos.get(0).getDetailPath()).isNull();
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
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVED.getCode()));
        stubConfig();

        List<TaskDoneVo> done = service.listDone("admin");

        assertThat(done).hasSize(1);
        TaskDoneVo vo = done.get(0);
        assertThat(vo.getTaskId()).isEqualTo("t-1");
        assertThat(vo.getApprovalId()).isEqualTo("5");
        assertThat(vo.getBusinessTypeName()).isEqualTo("Leave");
        assertThat(vo.getDetailPath()).isEqualTo("/system/leave?approval=5");
        assertThat(vo.getApprove()).isEqualTo("true");
        assertThat(vo.getComment()).isEqualTo("agree");
        assertThat(vo.getApprovalStatus()).isEqualTo("1");
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
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.REJECTED.getCode()));
        stubConfig();

        List<TaskDoneVo> done = service.listDone("admin");

        assertThat(done).hasSize(1);
        assertThat(done.get(0).getApprove()).isEqualTo("false");
        assertThat(done.get(0).getComment()).isNull();
        assertThat(done.get(0).getApprovalStatus()).isEqualTo("2");
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

    private BpmnApproval approval(Integer status) {
        BpmnApproval approval = new BpmnApproval();
        approval.setId(5L);
        approval.setBusinessType("leave");
        approval.setBusinessKey("7");
        approval.setTitle("annual leave");
        approval.setProcessKey("leave_approval");
        approval.setStatus(status);
        approval.setApplyUser("userA");
        approval.setApprover("admin");
        approval.setProcessInstanceId("pid-1");
        return approval;
    }

    private void stubConfig() {
        BpmnBusinessType config = new BpmnBusinessType();
        config.setTypeCode("leave");
        config.setTypeName("Leave");
        config.setProcessKey("leave_approval");
        config.setDetailRoute("/system/leave?approval={businessKey}");
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(config);
        lenient().when(businessTypeRegistry.renderDetailPath("/system/leave?approval={businessKey}", "5"))
                .thenReturn("/system/leave?approval=5");
    }
}
