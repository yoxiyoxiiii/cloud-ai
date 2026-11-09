package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.vo.TaskDoneVo;
import com.cloudai.bpmn.vo.TaskVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务分页单测（契约 2026-10-08-approval-platform-api §2.1/§2.2 分页轮）：引擎 listPage/count
 * 参数透传、businessKey 回查快照 + 配置表渲染 + endActivityId 结果判定 + 最新意见、
 * 防御态脏行跳过补拉凑满当页、末页不足、空批终止。
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
    void pageListTodo_mapsEngineTaskWithApprovalSnapshot() {
        TaskQuery query = stubTodoQuery();
        Task first = todoTask("t-1", "pid-1");
        when(query.count()).thenReturn(1L);
        when(query.listPage(0, 10)).thenReturn(List.of(first));
        stubInstance("pid-1", "5", "endApprove", null);
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubConfig();

        PageResult<TaskVo> result = service.pageListTodo("admin", new PageQuery());

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRows()).hasSize(1);
        TaskVo vo = result.getRows().get(0);
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
    void pageListTodo_passesPaginationParamsToEngine() {
        TaskQuery query = stubTodoQuery();
        List<Task> batch = List.of(todoTask("t-1", "pid-1"), todoTask("t-2", "pid-1"),
                todoTask("t-3", "pid-1"), todoTask("t-4", "pid-1"), todoTask("t-5", "pid-1"));
        when(query.count()).thenReturn(15L);
        when(query.listPage(5, 5)).thenReturn(batch);
        stubInstance("pid-1", "5", "endApprove", null);
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubConfig();

        PageResult<TaskVo> result = service.pageListTodo("admin", pageQuery(2, 5));

        // 第 2 页 → firstResult=(2-1)*5=5、maxResults=5；total=引擎 count
        verify(query).listPage(5, 5);
        assertThat(result.getTotal()).isEqualTo(15L);
        assertThat(result.getRows()).hasSize(5);
    }

    @Test
    void pageListTodo_refillsPageWhenDirtyRowSkipped() {
        TaskQuery query = stubTodoQuery();
        Task dirty = todoTask("t-dirty", "pid-x");
        Task first = todoTask("t-1", "pid-1");
        Task second = todoTask("t-2", "pid-2");
        Task third = todoTask("t-3", "pid-3");
        when(query.count()).thenReturn(5L);
        when(query.listPage(0, 3)).thenReturn(List.of(dirty, first, second));
        when(query.listPage(3, 3)).thenReturn(List.of(third));
        stubInstance("pid-x", null, null, null);
        stubInstance("pid-1", "5", "endApprove", null);
        stubInstance("pid-2", "5", "endApprove", null);
        stubInstance("pid-3", "6", "endApprove", null);
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        when(approvalMapper.findById(6L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubConfig();

        PageResult<TaskVo> result = service.pageListTodo("admin", pageQuery(1, 3));

        // 首批 3 行 1 脏 → 净 2 行不足 3 → 续拉 offset=3（按原始条数推进）凑满；total 含脏行
        verify(query).listPage(0, 3);
        verify(query).listPage(3, 3);
        assertThat(result.getRows()).extracting(TaskVo::getTaskId)
                .containsExactly("t-1", "t-2", "t-3");
        assertThat(result.getTotal()).isEqualTo(5L);
    }

    @Test
    void pageListTodo_lastPageShorterThanPageSize() {
        TaskQuery query = stubTodoQuery();
        Task first = todoTask("t-1", "pid-1");
        when(query.count()).thenReturn(5L);
        when(query.listPage(4, 2)).thenReturn(List.of(first));
        stubInstance("pid-1", "5", "endApprove", null);
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubConfig();

        PageResult<TaskVo> result = service.pageListTodo("admin", pageQuery(3, 2));

        // total=5 第 3 页仅剩 1 行：不再续拉（已取尽），rows 允许不足 pageSize
        verify(query, times(1)).listPage(anyInt(), anyInt());
        assertThat(result.getTotal()).isEqualTo(5L);
        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    void pageListTodo_stopsOnEmptyBatchWhenEngineRangeShrunk() {
        TaskQuery query = stubTodoQuery();
        // count 与实际数据竞态：count=10 但 listPage 返回空批（越界/并发删除）——须终止防死循环
        when(query.count()).thenReturn(10L);
        when(query.listPage(0, 5)).thenReturn(List.of());

        PageResult<TaskVo> result = service.pageListTodo("admin", pageQuery(1, 5));

        verify(query, times(1)).listPage(anyInt(), anyInt());
        assertThat(result.getTotal()).isEqualTo(10L);
        assertThat(result.getRows()).isEmpty();
    }

    @Test
    void pageListTodo_skipsWhenBusinessKeyMissing() {
        TaskQuery query = stubTodoQuery();
        Task first = todoTask("t-1", "pid-1");
        when(query.count()).thenReturn(1L);
        when(query.listPage(0, 10)).thenReturn(List.of(first));
        stubInstance("pid-1", null, null, null);

        PageResult<TaskVo> result = service.pageListTodo("admin", new PageQuery());

        assertThat(result.getRows()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(1L);
        verify(approvalMapper, never()).findById(anyLong());
    }

    @Test
    void pageListTodo_skipsWhenBusinessKeyNonNumeric() {
        TaskQuery query = stubTodoQuery();
        Task first = todoTask("t-1", "pid-1");
        when(query.count()).thenReturn(1L);
        when(query.listPage(0, 10)).thenReturn(List.of(first));
        stubInstance("pid-1", "external-key", null, null);

        PageResult<TaskVo> result = service.pageListTodo("admin", new PageQuery());

        assertThat(result.getRows()).isEmpty();
        verify(approvalMapper, never()).findById(anyLong());
    }

    @Test
    void pageListTodo_keepsRowWithNullConfigFieldsWhenTypeMissing() {
        TaskQuery query = stubTodoQuery();
        Task first = todoTask("t-1", "pid-1");
        when(query.count()).thenReturn(1L);
        when(query.listPage(0, 10)).thenReturn(List.of(first));
        stubInstance("pid-1", "5", "endApprove", null);
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(null);

        PageResult<TaskVo> result = service.pageListTodo("admin", new PageQuery());

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getBusinessTypeName()).isNull();
        assertThat(result.getRows().get(0).getDetailPath()).isNull();
    }

    @Test
    void pageListDone_mapsHistoryTaskWithResultAndComment() {
        HistoricTaskInstanceQuery query = stubDoneQuery();
        HistoricTaskInstance first = doneTask("t-1", "pid-1");
        when(query.count()).thenReturn(1L);
        when(query.listPage(0, 10)).thenReturn(List.of(first));
        stubInstance("pid-1", "5", "endApprove", new Date());
        Comment comment = mock(Comment.class);
        lenient().when(comment.getFullMessage()).thenReturn("agree");
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of(comment));
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVED.getCode()));
        stubConfig();

        PageResult<TaskDoneVo> result = service.pageListDone("admin", new PageQuery());

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRows()).hasSize(1);
        TaskDoneVo vo = result.getRows().get(0);
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
    void pageListDone_rejectResultMapped() {
        HistoricTaskInstanceQuery query = stubDoneQuery();
        HistoricTaskInstance first = doneTask("t-2", "pid-2");
        when(query.count()).thenReturn(1L);
        when(query.listPage(0, 10)).thenReturn(List.of(first));
        stubInstance("pid-2", "5", "endReject", new Date());
        when(taskService.getProcessInstanceComments("pid-2")).thenReturn(List.of());
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.REJECTED.getCode()));
        stubConfig();

        PageResult<TaskDoneVo> result = service.pageListDone("admin", new PageQuery());

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getApprove()).isEqualTo("false");
        assertThat(result.getRows().get(0).getComment()).isNull();
        assertThat(result.getRows().get(0).getApprovalStatus()).isEqualTo("2");
    }

    // ---- 脚手架 ----

    private PageQuery pageQuery(int pageNum, int pageSize) {
        PageQuery query = new PageQuery();
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return query;
    }

    private TaskQuery stubTodoQuery() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.orderByTaskCreateTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        return query;
    }

    private HistoricTaskInstanceQuery stubDoneQuery() {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(query);
        when(query.taskAssignee("admin")).thenReturn(query);
        when(query.finished()).thenReturn(query);
        when(query.orderByHistoricTaskInstanceEndTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        return query;
    }

    private Task todoTask(String id, String processInstanceId) {
        Task task = mock(Task.class);
        lenient().when(task.getId()).thenReturn(id);
        lenient().when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        lenient().when(task.getCreateTime()).thenReturn(new Date());
        return task;
    }

    private HistoricTaskInstance doneTask(String id, String processInstanceId) {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        lenient().when(task.getId()).thenReturn(id);
        lenient().when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        lenient().when(task.getCreateTime()).thenReturn(new Date());
        lenient().when(task.getEndTime()).thenReturn(new Date());
        return task;
    }

    /** 同一 root query 下按 processInstanceId 各挂独立子查询 mock（多实例共存互不覆盖 singleResult） */
    private HistoricProcessInstanceQuery historicalRoot;

    private void stubInstance(String processInstanceId, String businessKey,
                              String endActivityId, Date endTime) {
        if (historicalRoot == null) {
            historicalRoot = mock(HistoricProcessInstanceQuery.class);
            lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicalRoot);
        }
        HistoricProcessInstanceQuery sub = mock(HistoricProcessInstanceQuery.class);
        lenient().when(historicalRoot.processInstanceId(processInstanceId)).thenReturn(sub);
        HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
        lenient().when(instance.getId()).thenReturn(processInstanceId);
        lenient().when(instance.getBusinessKey()).thenReturn(businessKey);
        lenient().when(instance.getEndActivityId()).thenReturn(endActivityId);
        lenient().when(instance.getEndTime()).thenReturn(endTime);
        lenient().when(sub.singleResult()).thenReturn(instance);
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
