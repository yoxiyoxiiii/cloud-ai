package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.vo.ApprovalDetailVo;
import com.cloudai.bpmn.vo.ApprovalDiagramVo;
import com.cloudai.bpmn.vo.ApprovalStepVo;
import com.cloudai.bpmn.vo.ApprovalVo;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
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

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批单查询单测（契约 2026-10-08-approval-platform-api §3：分页配置渲染 / 4010 /
 * 三源时间线 apply-approval-end / 嵌套 VO 手动翻译 / 图数据三态矩阵 + businessKey 历史锚点）。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalQueryServiceTest {

    @Mock
    private BpmnApprovalMapper approvalMapper;
    @Mock
    private BusinessTypeRegistry businessTypeRegistry;
    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private TranslationCacheService translationCacheService;
    @InjectMocks
    private ApprovalQueryService service;

    // ---- pageListMy ----

    @Test
    void pageListMy_enrichesWithConfig() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BpmnApproval> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 5);
        page.setRecords(List.of(approval(StatusEnum.APPROVING.getCode())));
        page.setTotal(1);
        when(approvalMapper.pageList(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                eq("userA"))).thenReturn(page);
        stubConfig();

        PageResult<ApprovalVo> result = service.pageListMy(query(), "userA");

        assertThat(result.getTotal()).isEqualTo(1);
        ApprovalVo vo = result.getRows().get(0);
        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getStatus()).isEqualTo("0");
        assertThat(vo.getBusinessTypeName()).isEqualTo("Leave");
        // 渲染源=审批单 id（5），非 businessKey（7）
        assertThat(vo.getDetailPath()).isEqualTo("/system/leave?approval=5");
    }

    @Test
    void pageListMy_configMissing_degradesToNull() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BpmnApproval> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 5);
        page.setRecords(List.of(approval(StatusEnum.APPROVING.getCode())));
        page.setTotal(1);
        when(approvalMapper.pageList(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                eq("userA"))).thenReturn(page);
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(null);

        PageResult<ApprovalVo> result = service.pageListMy(query(), "userA");

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getBusinessTypeName()).isNull();
        assertThat(result.getRows().get(0).getDetailPath()).isNull();
    }

    // ---- findById ----

    @Test
    void findById_notFoundRejected_4010() {
        when(approvalMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.findById(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
    }

    @Test
    void findById_approving_assemblesApplyAndApprovalStepsOnly() {
        BpmnApproval approval = approval(StatusEnum.APPROVING.getCode());
        when(approvalMapper.findById(5L)).thenReturn(approval);
        stubConfig();
        Comment comment = mock(Comment.class);
        lenient().when(comment.getUserId()).thenReturn("admin");
        lenient().when(comment.getFullMessage()).thenReturn("agree");
        lenient().when(comment.getTime()).thenReturn(new Date());
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of(comment));
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of("0", "审批中"));
        when(translationCacheService.findUserNames(anySet()))
                .thenReturn(Map.of("userA", "nick-userA", "admin", "nick-admin"));

        ApprovalDetailVo detail = service.findById(5L);

        assertThat(detail.getApproval().getStatusLabel()).isEqualTo("审批中");
        assertThat(detail.getApproval().getApplyUserName()).isEqualTo("nick-userA");
        assertThat(detail.getApproval().getApproverName()).isEqualTo("nick-admin");
        assertThat(detail.getSteps()).extracting(ApprovalStepVo::getStepKey)
                .containsExactly("apply", "approval");
        assertThat(detail.getSteps().get(0).getOperator()).isEqualTo("userA");
        assertThat(detail.getSteps().get(1).getOperator()).isEqualTo("admin");
        assertThat(detail.getSteps().get(1).getOperatorName()).isEqualTo("nick-admin");
        assertThat(detail.getSteps().get(1).getComment()).isEqualTo("agree");
    }

    @Test
    void findById_cancelled_endStepFallsBackToUpdateTime() {
        BpmnApproval cancelled = approval(StatusEnum.CANCELLED.getCode());
        cancelled.setProcessInstanceId(null);
        cancelled.setUpdateTime(LocalDateTime.of(2026, 10, 8, 12, 0));
        when(approvalMapper.findById(5L)).thenReturn(cancelled);
        stubConfig();
        // 撤销单实例关联已清空（processInstanceId=null）——不触评论查询
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of());
        when(translationCacheService.findUserNames(anySet())).thenReturn(Map.of());

        ApprovalDetailVo detail = service.findById(5L);

        assertThat(detail.getSteps()).extracting(ApprovalStepVo::getStepKey)
                .containsExactly("apply", "end");
        ApprovalStepVo end = detail.getSteps().get(1);
        assertThat(end.getResult()).isEqualTo("已撤销");
        assertThat(end.getTime()).isEqualTo("2026-10-08 12:00:00");
    }

    // ---- findDiagram（三态矩阵：撤销=历史缺失防御 / 运行中 / 终态） ----

    @Test
    void findDiagram_notFoundRejected_4010() {
        when(approvalMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.findDiagram(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
    }

    @Test
    void findDiagram_historyMissing_returnsDefensiveEmpty() {
        BpmnApproval cancelled = approval(StatusEnum.CANCELLED.getCode());
        cancelled.setProcessInstanceId(null);
        when(approvalMapper.findById(5L)).thenReturn(cancelled);
        stubConfig();
        org.flowable.engine.history.HistoricProcessInstanceQuery hq = stubBusinessKeyFallback();
        when(hq.list()).thenReturn(List.of());

        ApprovalDiagramVo vo = service.findDiagram(5L);

        assertThat(vo.getDefinitionId()).isNull();
        assertThat(vo.getProcessInstanceId()).isNull();
        assertThat(vo.getActiveActivityIds()).isEmpty();
        assertThat(vo.getCompletedActivityIds()).isEmpty();
        assertThat(vo.getEndActivityId()).isNull();
    }

    @Test
    void findDiagram_running_returnsActiveAndCompleted() {
        HistoricProcessInstance historic = stubHistoricByExactId("pid-1", null);
        lenient().when(historic.getProcessDefinitionId()).thenReturn("def-1");
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pid-1")).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(1L);
        when(runtimeService.getActiveActivityIds("pid-1")).thenReturn(List.of("approval"));
        stubHistoricActivities("pid-1", "start", "approval");
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));

        ApprovalDiagramVo vo = service.findDiagram(5L);

        assertThat(vo.getDefinitionId()).isEqualTo("def-1");
        assertThat(vo.getProcessInstanceId()).isEqualTo("pid-1");
        assertThat(vo.getActiveActivityIds()).containsExactly("approval");
        assertThat(vo.getCompletedActivityIds()).containsExactly("start", "approval");
        assertThat(vo.getEndActivityId()).isNull();
    }

    @Test
    void findDiagram_terminal_activeEmptyEndMapped() {
        HistoricProcessInstance historic = stubHistoricByExactId("pid-1", "endApprove");
        lenient().when(historic.getProcessDefinitionId()).thenReturn("def-1");
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pid-1")).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(0L);
        stubHistoricActivities("pid-1", "start", "approval", "decision", "endApprove");
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVED.getCode()));

        ApprovalDiagramVo vo = service.findDiagram(5L);

        assertThat(vo.getActiveActivityIds()).isEmpty();
        assertThat(vo.getCompletedActivityIds()).containsExactly("start", "approval", "decision", "endApprove");
        assertThat(vo.getEndActivityId()).isEqualTo("endApprove");
        verify(runtimeService, never()).getActiveActivityIds(any());
    }

    @Test
    void findDiagram_businessKeyFallback_multiRowTolerated_noSingleResult() {
        // 撤销单（实例 id 清空）businessKey 撞旧时代历史：回退链 list 取首条，不再 singleResult 撞两行抛
        BpmnApproval cancelled = approval(StatusEnum.CANCELLED.getCode());
        cancelled.setProcessInstanceId(null);
        when(approvalMapper.findById(5L)).thenReturn(cancelled);
        stubConfig();
        org.flowable.engine.history.HistoricProcessInstanceQuery hq = stubBusinessKeyFallback();
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getId()).thenReturn("pid-new");
        lenient().when(historic.getEndActivityId()).thenReturn(null);
        lenient().when(historic.getProcessDefinitionId()).thenReturn("def-1");
        // orderBy 启动倒序由引擎保证：list 首条即最新（新时代）实例——旧时代实例在第二位
        when(hq.list()).thenReturn(List.of(historic, mock(HistoricProcessInstance.class)));
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.processInstanceId("pid-new")).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(0L);
        stubHistoricActivities("pid-new", "start");

        ApprovalDiagramVo vo = service.findDiagram(5L);

        assertThat(vo.getDefinitionId()).isEqualTo("def-1");
        assertThat(vo.getProcessInstanceId()).isEqualTo("pid-new");
        verify(hq, never()).singleResult();
    }

    // ---- 脚手架 ----

    private com.cloudai.common.core.domain.PageQuery query() {
        com.cloudai.common.core.domain.PageQuery query = new com.cloudai.common.core.domain.PageQuery();
        query.setPageNum(1);
        query.setPageSize(5);
        return query;
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
        approval.setCreateTime(LocalDateTime.of(2026, 10, 8, 10, 0));
        return approval;
    }

    private void stubConfig() {
        BpmnBusinessType config = new BpmnBusinessType();
        config.setTypeCode("leave");
        config.setTypeName("Leave");
        config.setProcessKey("leave_approval");
        config.setDetailRoute("/system/leave?approval={businessKey}");
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(config);
        // {businessKey} 渲染源 = 审批单 id（契约 §1 域语言）：approval id 5（非 businessKey 7）
        lenient().when(businessTypeRegistry.renderDetailPath("/system/leave?approval={businessKey}", "5"))
                .thenReturn("/system/leave?approval=5");
    }

    /** 实例精确 id 锚点（非撤销态审批单存 processInstanceId，单行 singleResult 安全） */
    private HistoricProcessInstance stubHistoricByExactId(String processInstanceId, String endActivityId) {
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        when(hq.processInstanceId(processInstanceId)).thenReturn(hq);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getId()).thenReturn(processInstanceId);
        lenient().when(historic.getEndActivityId()).thenReturn(endActivityId);
        when(hq.singleResult()).thenReturn(historic);
        return historic;
    }

    /** businessKey 回退链（撤销单实例 id 已清空）：限定本类型流程 key + 启动时间倒序 list（多行容忍） */
    private org.flowable.engine.history.HistoricProcessInstanceQuery stubBusinessKeyFallback() {
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        when(hq.processInstanceBusinessKey("5")).thenReturn(hq);
        when(hq.processDefinitionKey("leave_approval")).thenReturn(hq);
        when(hq.orderByProcessInstanceStartTime()).thenReturn(hq);
        when(hq.desc()).thenReturn(hq);
        return hq;
    }

    private void stubHistoricActivities(String processInstanceId, String... activityIds) {
        org.flowable.engine.history.HistoricActivityInstanceQuery aq =
                mock(org.flowable.engine.history.HistoricActivityInstanceQuery.class);
        lenient().when(historyService.createHistoricActivityInstanceQuery()).thenReturn(aq);
        lenient().when(aq.processInstanceId(processInstanceId)).thenReturn(aq);
        lenient().when(aq.orderByHistoricActivityInstanceStartTime()).thenReturn(aq);
        lenient().when(aq.asc()).thenReturn(aq);
        List<HistoricActivityInstance> activities = java.util.Arrays.stream(activityIds)
                .map(id -> {
                    HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
                    lenient().when(activity.getActivityId()).thenReturn(id);
                    return activity;
                })
                .toList();
        lenient().when(aq.list()).thenReturn(activities);
    }
}
