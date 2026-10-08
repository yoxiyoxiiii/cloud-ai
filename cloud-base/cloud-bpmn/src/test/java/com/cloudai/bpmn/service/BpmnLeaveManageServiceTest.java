package com.cloudai.bpmn.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.bpmn.client.SystemUserClient;
import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.entity.BpmnLeave.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
import com.cloudai.bpmn.vo.ApprovalStepVo;
import com.cloudai.bpmn.vo.LeaveDetailVo;
import com.cloudai.bpmn.vo.LeaveDiagramVo;
import com.cloudai.bpmn.vo.LeaveVo;
import com.cloudai.bpmn.vo.UserOptionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.common.translate.domain.UserEntry;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假单查询/时间线拼装单测（设计 D8：apply/approval/end 三源拼装 + 终态才出 end 步 + 手动译字回填）。
 */
@ExtendWith(MockitoExtension.class)
class BpmnLeaveManageServiceTest {

    @Mock
    private BpmnLeaveMapper leaveMapper;
    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private SystemUserClient systemUserClient;
    @Mock
    private TranslationCacheService translationCacheService;
    @InjectMocks
    private BpmnLeaveManageService service;

    @Test
    void pageListMy_mapsFieldsAndTotal() {
        Page<BpmnLeave> page = new Page<>(1, 10);
        BpmnLeave leave = leave(StatusEnum.APPROVING.getCode(), "userA", "admin", "pid-1");
        page.setRecords(List.of(leave));
        page.setTotal(1);
        when(leaveMapper.pageList(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("userA")))
                .thenReturn(page);

        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<LeaveVo> result = service.pageListMy(query, "userA");

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRows()).hasSize(1);
        LeaveVo vo = result.getRows().get(0);
        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getTitle()).isEqualTo("annual leave");
        assertThat(vo.getLeaveType()).isEqualTo("3");
        assertThat(vo.getStatus()).isEqualTo("0");
        assertThat(vo.getStartDate()).isEqualTo("2026-10-08");
        assertThat(vo.getEndDate()).isEqualTo("2026-10-09");
        assertThat(vo.getProcessInstanceId()).isEqualTo("pid-1");
    }

    @Test
    void findById_notFoundRejected_4001() {
        when(leaveMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.findById(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4001);
    }

    @Test
    void findById_approvedTimeline_threeStepsWithLabels() {
        BpmnLeave leave = leave(StatusEnum.APPROVED.getCode(), "userA", "admin", "pid-1");
        when(leaveMapper.findById(5L)).thenReturn(leave);
        Comment comment = mock(Comment.class);
        lenient().when(comment.getUserId()).thenReturn("admin");
        lenient().when(comment.getFullMessage()).thenReturn("agree");
        lenient().when(comment.getTime()).thenReturn(new Date());
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of(comment));
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        lenient().when(hq.processInstanceId("pid-1")).thenReturn(hq);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getEndTime()).thenReturn(new Date());
        lenient().when(hq.singleResult()).thenReturn(historic);
        stubTranslations(StatusEnum.APPROVED, "userA", "admin");

        LeaveDetailVo detail = service.findById(5L);

        assertThat(detail.getLeave().getStatusLabel()).isEqualTo("已通过");
        assertThat(detail.getLeave().getLeaveTypeLabel()).isEqualTo("年假");
        assertThat(detail.getLeave().getApplyUserName()).isEqualTo("nick-userA");
        assertThat(detail.getLeave().getApproverName()).isEqualTo("nick-admin");
        assertThat(detail.getSteps()).hasSize(3);
        ApprovalStepVo apply = detail.getSteps().get(0);
        assertThat(apply.getStepKey()).isEqualTo("apply");
        assertThat(apply.getOperatorName()).isEqualTo("nick-userA");
        ApprovalStepVo approval = detail.getSteps().get(1);
        assertThat(approval.getStepKey()).isEqualTo("approval");
        assertThat(approval.getComment()).isEqualTo("agree");
        assertThat(approval.getOperatorName()).isEqualTo("nick-admin");
        ApprovalStepVo end = detail.getSteps().get(2);
        assertThat(end.getStepKey()).isEqualTo("end");
        assertThat(end.getResult()).isEqualTo("已通过");
        assertThat(end.getTime()).isNotBlank();
    }

    @Test
    void findById_cancelledTimeline_twoStepsNoEngineCalls() {
        BpmnLeave leave = leave(StatusEnum.CANCELLED.getCode(), "userA", "admin", null);
        when(leaveMapper.findById(5L)).thenReturn(leave);
        stubTranslations(StatusEnum.CANCELLED, "userA", "admin");

        LeaveDetailVo detail = service.findById(5L);

        verify(taskService, never()).getProcessInstanceComments(anyString());
        assertThat(detail.getSteps()).hasSize(2);
        ApprovalStepVo end = detail.getSteps().get(1);
        assertThat(end.getStepKey()).isEqualTo("end");
        assertThat(end.getResult()).isEqualTo("已撤销");
        // 实例关联已清空：end 步时间回落到状态变更时间（updateTime）
        assertThat(end.getTime()).isEqualTo("2026-10-07 10:00:00");
    }

    @Test
    void findById_approvingTimeline_noEndStep() {
        BpmnLeave leave = leave(StatusEnum.APPROVING.getCode(), "userA", "admin", "pid-1");
        when(leaveMapper.findById(5L)).thenReturn(leave);
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of());
        stubTranslations(StatusEnum.APPROVING, "userA", "admin");

        LeaveDetailVo detail = service.findById(5L);

        assertThat(detail.getSteps()).hasSize(1);
        assertThat(detail.getSteps().get(0).getStepKey()).isEqualTo("apply");
        assertThat(detail.getLeave().getStatusLabel()).isEqualTo("审批中");
    }

    @Test
    void findById_selfApproval_doesNotThrowOnDuplicateParty() {
        // 回归：同人自审批（applyUser==approver=admin）——Set.of 重复元素曾抛 IllegalArgumentException（B9 curl 实测）
        BpmnLeave leave = leave(StatusEnum.APPROVED.getCode(), "admin", "admin", "pid-1");
        when(leaveMapper.findById(5L)).thenReturn(leave);
        Comment comment = mock(Comment.class);
        lenient().when(comment.getUserId()).thenReturn("admin");
        lenient().when(comment.getFullMessage()).thenReturn("ok");
        lenient().when(comment.getTime()).thenReturn(new Date());
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of(comment));
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        lenient().when(hq.processInstanceId("pid-1")).thenReturn(hq);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getEndTime()).thenReturn(new Date());
        lenient().when(hq.singleResult()).thenReturn(historic);
        stubTranslations(StatusEnum.APPROVED, "admin", "admin");

        LeaveDetailVo detail = service.findById(5L);

        assertThat(detail.getLeave().getApplyUserName()).isEqualTo("nick-admin");
        assertThat(detail.getLeave().getApproverName()).isEqualTo("nick-admin");
        assertThat(detail.getSteps()).hasSize(3);
    }

    @Test
    void listApprovers_passesThroughProjection() {
        when(systemUserClient.listAll()).thenReturn(R.ok(List.of(entry(1L, "admin", "Administrator"),
                entry(2L, "userA", "User A"))));
        List<UserOptionVo> options = service.listApprovers();
        assertThat(options).hasSize(2);
        assertThat(options.get(0).getId()).isEqualTo(1L);
        assertThat(options.get(0).getAccount()).isEqualTo("admin");
        assertThat(options.get(0).getNickname()).isEqualTo("Administrator");
        assertThat(options.get(1).getAccount()).isEqualTo("userA");
    }

    @Test
    void listApprovers_feignFailureRejected_1002() {
        when(systemUserClient.listAll()).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> service.listApprovers())
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(1002);
    }

    // ---- findDiagram（契约 2026-10-08 §3 三态矩阵 + 防御态）----

    @Test
    void findDiagram_notFoundRejected_4001() {
        when(leaveMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.findDiagram(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4001);
    }

    @Test
    void findDiagram_approving_activeNodeWithDedupCompleted() {
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.APPROVING.getCode(), "userA", "admin", "pid-1"));
        stubHistoricInstance(null);
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pid-1")).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(1L);
        when(runtimeService.getActiveActivityIds("pid-1")).thenReturn(List.of("approval"));
        stubHistoricActivities("start", "approval", "decision", "approval");

        LeaveDiagramVo diagram = service.findDiagram(5L);

        assertThat(diagram.getDefinitionId()).isEqualTo("leave_approval:1:4");
        assertThat(diagram.getProcessInstanceId()).isEqualTo("pid-1");
        assertThat(diagram.getActiveActivityIds()).containsExactly("approval");
        // LinkedHashSet 去重保序：approval 重复出现只留一次
        assertThat(diagram.getCompletedActivityIds()).containsExactly("start", "approval", "decision");
        assertThat(diagram.getEndActivityId()).isNull();
    }

    @Test
    void findDiagram_approved_endNodeWithEmptyActive() {
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.APPROVED.getCode(), "userA", "admin", "pid-1"));
        stubHistoricInstance("endApprove");
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pid-1")).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(0L);
        stubHistoricActivities("start", "approval", "decision", "endApprove");

        LeaveDiagramVo diagram = service.findDiagram(5L);

        assertThat(diagram.getActiveActivityIds()).isEmpty();
        assertThat(diagram.getCompletedActivityIds())
                .containsExactly("start", "approval", "decision", "endApprove");
        assertThat(diagram.getEndActivityId()).isEqualTo("endApprove");
    }

    @Test
    void findDiagram_cancelled_endNullCompletedTruncated() {
        // R4 单测面：撤销态契约定型 endActivityId=null、completed 截断至删除点（B5 实测定型记档）
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.CANCELLED.getCode(), "userA", "admin", null));
        stubHistoricInstance(null);
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pid-1")).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(0L);
        stubHistoricActivities("start");

        LeaveDiagramVo diagram = service.findDiagram(5L);

        assertThat(diagram.getActiveActivityIds()).isEmpty();
        assertThat(diagram.getCompletedActivityIds()).containsExactly("start");
        assertThat(diagram.getEndActivityId()).isNull();
    }

    @Test
    void findDiagram_historyMissing_defensiveVo() {
        // 防御态（理论不发生）：definitionId=null 空集合直返，不设「无图」错误码（契约 §3）
        when(leaveMapper.findById(5L)).thenReturn(leave(StatusEnum.APPROVING.getCode(), "userA", "admin", "pid-1"));
        org.flowable.engine.history.HistoricProcessInstanceQuery missing =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(missing);
        when(missing.processInstanceBusinessKey("5")).thenReturn(missing);
        when(missing.singleResult()).thenReturn(null);

        LeaveDiagramVo diagram = service.findDiagram(5L);

        assertThat(diagram.getDefinitionId()).isNull();
        assertThat(diagram.getProcessInstanceId()).isNull();
        assertThat(diagram.getActiveActivityIds()).isEmpty();
        assertThat(diagram.getCompletedActivityIds()).isEmpty();
        assertThat(diagram.getEndActivityId()).isNull();
    }

    /** 历史实例锚点（businessKey 单查）通用 stub：endActivityId 可空 */
    private void stubHistoricInstance(String endActivityId) {
        org.flowable.engine.history.HistoricProcessInstanceQuery query =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceBusinessKey("5")).thenReturn(query);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(historic.getId()).thenReturn("pid-1");
        when(historic.getProcessDefinitionId()).thenReturn("leave_approval:1:4");
        when(historic.getEndActivityId()).thenReturn(endActivityId);
        when(query.singleResult()).thenReturn(historic);
    }

    /** 历史活动时间升序查询 stub（activityId 序列即返回序） */
    private void stubHistoricActivities(String... activityIds) {
        org.flowable.engine.history.HistoricActivityInstanceQuery query =
                mock(org.flowable.engine.history.HistoricActivityInstanceQuery.class);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
        when(query.processInstanceId("pid-1")).thenReturn(query);
        when(query.orderByHistoricActivityInstanceStartTime()).thenReturn(query);
        when(query.asc()).thenReturn(query);
        java.util.List<HistoricActivityInstance> activities = new java.util.ArrayList<>();
        for (String activityId : activityIds) {
            HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
            lenient().when(activity.getActivityId()).thenReturn(activityId);
            activities.add(activity);
        }
        when(query.list()).thenReturn(activities);
    }

    // ---- 脚手架 ----

    private void stubTranslations(StatusEnum status, String applyUser, String approver) {
        when(translationCacheService.findDictLabels(org.mockito.ArgumentMatchers.eq("bpmn_leave_status"),
                anySet())).thenReturn(Map.of(String.valueOf(status.getCode()), statusLabel(status)));
        when(translationCacheService.findDictLabels(org.mockito.ArgumentMatchers.eq("bpmn_leave_type"),
                anySet())).thenReturn(Map.of("3", "年假"));
        // 自审批（applyUser==approver）重复键合法——HashMap 容忍，Map.of 会抛 duplicate key
        Map<String, String> users = new java.util.HashMap<>();
        users.put(applyUser, "nick-" + applyUser);
        users.put(approver, "nick-" + approver);
        when(translationCacheService.findUserNames(anySet())).thenReturn(users);
    }

    private String statusLabel(StatusEnum status) {
        return switch (status) {
            case APPROVING -> "审批中";
            case APPROVED -> "已通过";
            case REJECTED -> "已拒绝";
            case CANCELLED -> "已撤销";
        };
    }

    private BpmnLeave leave(Integer status, String applyUser, String approver, String processInstanceId) {
        BpmnLeave leave = new BpmnLeave();
        leave.setId(5L);
        leave.setTitle("annual leave");
        leave.setLeaveType(BpmnLeave.TypeEnum.ANNUAL.getCode());
        leave.setStartDate(LocalDate.of(2026, 10, 8));
        leave.setEndDate(LocalDate.of(2026, 10, 9));
        leave.setReason("trip");
        leave.setStatus(status);
        leave.setApplyUser(applyUser);
        leave.setApprover(approver);
        leave.setProcessInstanceId(processInstanceId);
        leave.setCreateTime(LocalDateTime.of(2026, 10, 7, 9, 0, 0));
        leave.setUpdateTime(LocalDateTime.of(2026, 10, 7, 10, 0, 0));
        return leave;
    }

    private UserEntry entry(Long id, String account, String nickname) {
        UserEntry e = new UserEntry();
        e.setId(id);
        e.setAccount(account);
        e.setNickname(nickname);
        return e;
    }
}
