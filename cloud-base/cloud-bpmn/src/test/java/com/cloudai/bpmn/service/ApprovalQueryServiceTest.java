package com.cloudai.bpmn.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.vo.ApprovalDetailVo;
import com.cloudai.bpmn.vo.ApprovalDiagramVo;
import com.cloudai.bpmn.vo.ApprovalStepVo;
import com.cloudai.bpmn.vo.ApprovalVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.api.client.DataPermClient;
import com.cloudai.system.api.dataperm.DataPermColumnContext;
import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import com.cloudai.system.api.domain.PredicateVo;
import com.cloudai.system.api.domain.RowGroupVo;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审批单查询单测（契约 2026-10-08-approval-platform-api §3 行为面 + 2026-10-10-dataperm-component-api
 * §3 行级/列级语义 + 谓词轮 2026-10-11 §4.1 增量）：分页配置渲染 / 4010 先行 / 三源时间线 /
 * 嵌套 VO 手动翻译 / 图数据三态矩阵；组件化轮：list 三态（全部档白名单/空集短路零查库/
 * 求值失败 fail-closed）、detail/diagram IDOR 收口（4018 + deny 三参补痕 + deny 降级不阻断）、
 * title 列级经真 DataPermColumnApplier + 真 DataPermScopeVo.toColumnScope() 端到端
 * （D14 教训：不 mock 到底）；谓词轮（D33/D34）：条件组 toRowFilter 透传 + detail 行属性判定
 * （entity typed 形态 / ALL+条件 owner 无关 4018）——v1 四参构造 ScopeVo 经 toRowFilter
 * 回退分支保持既有断言语义（机械切换）。注解化轮（2026-10-12 D51-D58）适配：列级改断言
 * DataPermColumnContext 接线声明（resource/hidden/masked，VO 不再 mutate），diagram 零上下文锚。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalQueryServiceTest {

    @Mock
    private BpmnApprovalMapper approvalMapper;
    @Mock
    private BusinessTypeRegistry businessTypeRegistry;
    @Mock
    private DataPermClient dataPermClient;
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

    @AfterEach
    void clearColumnContext() {
        // 求值后即 set：list/detail 用例都可能留上下文——防跨用例污染（真 ThreadLocal）
        DataPermColumnContext.clear();
    }

    // ---- pageListMy：既有行为面（全部档 = 旧「恒按申请人」无过滤语义） ----

    @Test
    void pageListMy_enrichesWithConfig() {
        stubEvalAll("userA", "list", null);
        Page<BpmnApproval> page = new Page<>(1, 5);
        page.setRecords(List.of(approval(StatusEnum.APPROVING.getCode())));
        page.setTotal(1);
        when(approvalMapper.pageList(any(Page.class), argThat(RowFilter::isRowAll))).thenReturn(page);
        stubConfig();

        PageResult<ApprovalVo> result = service.pageListMy(query(), "userA");

        assertThat(result.getTotal()).isEqualTo(1);
        ApprovalVo vo = result.getRows().get(0);
        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getStatus()).isEqualTo("0");
        assertThat(vo.getBusinessTypeName()).isEqualTo("Leave");
        // 渲染源=审批单 id（5），非 businessKey（7）
        assertThat(vo.getDetailPath()).isEqualTo("/system/leave?approval=5");
        // 全部档无列动作：title 原值（真 applier 端到端）
        assertThat(vo.getTitle()).isEqualTo("annual leave");
    }

    @Test
    void pageListMy_configMissing_degradesToNull() {
        stubEvalAll("userA", "list", null);
        Page<BpmnApproval> page = new Page<>(1, 5);
        page.setRecords(List.of(approval(StatusEnum.APPROVING.getCode())));
        page.setTotal(1);
        when(approvalMapper.pageList(any(Page.class), argThat(RowFilter::isRowAll))).thenReturn(page);
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(null);

        PageResult<ApprovalVo> result = service.pageListMy(query(), "userA");

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getBusinessTypeName()).isNull();
        assertThat(result.getRows().get(0).getDetailPath()).isNull();
    }

    // ---- pageListMy：数据权限三态（契约 dataperm-component §3.1） ----

    @Test
    void pageListMy_whitelistScope_passesAccountFilterAndDeclaresColumnScope() {
        // 白名单 {userA} + title 脱敏：真 ScopeVo → 真 toColumnScope → 接线声明（D52——列改写归 advice）
        stubEval(new DataPermScopeVo(false, List.of("userA"), List.of(), List.of("title")), "userA", "list", null);
        Page<BpmnApproval> page = new Page<>(1, 5);
        page.setRecords(List.of(approval(StatusEnum.APPROVING.getCode())));
        page.setTotal(1);
        when(approvalMapper.pageList(any(Page.class), argThat(filter ->
                !filter.isRowAll() && filter.getGroups().get(0).getAccounts().equals(Set.of("userA")))))
                .thenReturn(page);
        stubConfig();

        PageResult<ApprovalVo> result = service.pageListMy(query(), "userA");

        assertThat(result.getRows()).hasSize(1);
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("bpmn_approval");
        assertThat(DataPermColumnContext.get().columnScope().getHiddenColumns()).isEmpty();
        assertThat(DataPermColumnContext.get().columnScope().getMaskedColumns()).containsExactly("title");
        // VO 在 Service 返回时是原始值（不再 mutate——title 改写归 advice 序列化前统一应用）
        assertThat(result.getRows().get(0).getTitle()).isEqualTo("annual leave");
    }

    @Test
    void pageListMy_emptyScope_shortCircuitsWithoutDbHit() {
        // 空集范围（无规则账号收敛 SELF 后仍无行）：短路空页，mapper 零交互（防 IN ()）；
        // 空集短路前已接线（set 位置红态锚，D52）
        stubEval(new DataPermScopeVo(false, List.of(), List.of(), List.of()), "userA", "list", null);

        PageResult<ApprovalVo> result = service.pageListMy(query(), "userA");

        assertThat(result.getTotal()).isEqualTo(0);
        assertThat(result.getRows()).isEmpty();
        verifyNoInteractions(approvalMapper);
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("bpmn_approval");
        assertThat(DataPermColumnContext.get().columnScope().isEmpty()).isTrue();
    }

    @Test
    void pageListMy_evaluateFailClosed_rejectsWithoutDbHit() {
        // system 不可用（降级 R.fail 1002）→ fail-closed：拒绝访问不查库（D21 无放行分支）
        when(dataPermClient.evaluate(any(DataPermEvaluateRequest.class)))
                .thenReturn(R.fail(1002, "数据权限服务不可用，请稍后重试"));

        assertThatThrownBy(() -> service.pageListMy(query(), "userA"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据权限服务不可用，请稍后重试");
        verifyNoInteractions(approvalMapper);
    }

    @Test
    void pageListMy_feignException_failClosedWithDefault1002() {
        // circuitbreaker 关闭期直连异常（二层兜底 catch）：同样 fail-closed
        when(dataPermClient.evaluate(any(DataPermEvaluateRequest.class)))
                .thenThrow(new RuntimeException("connect refused"));

        assertThatThrownBy(() -> service.pageListMy(query(), "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(1002);
        verifyNoInteractions(approvalMapper);
    }

    @Test
    void pageListMy_conditionalGroups_rowFilterPassedToMapper() {
        // ALL+条件组（id BETWEEN 1~100，owner 无关）：rowGroups wire 形态 → toRowFilter 类型化 →
        // mapper 收组间 OR（无条件并组在首 + ALL 条件组含谓词，D33）
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of("userA"), List.of(), List.of(),
                List.of(new RowGroupVo(false, List.of("userA"), List.of()),
                        new RowGroupVo(true, List.of(), List.of(idBetween(1, 100)))));
        stubEval(scopeVo, "userA", "list", null);
        Page<BpmnApproval> page = new Page<>(1, 5);
        page.setTotal(0);
        when(approvalMapper.pageList(any(Page.class), argThat(filter ->
                !filter.isRowAll() && filter.getGroups().size() == 2
                        && filter.getGroups().get(0).getPredicates().isEmpty()
                        && filter.getGroups().get(1).isAllAccounts()
                        && filter.getGroups().get(1).getPredicates().size() == 1))).thenReturn(page);

        service.pageListMy(query(), "userA");

        // matcher 内联即断言：组形态达 mapper（类型化值 BigDecimal 由 argThat 形状间接覆盖）
        verify(approvalMapper).pageList(any(Page.class), any(RowFilter.class));
    }

    // ---- findById ----

    @Test
    void findById_notFoundRejected_4010_beforeEvaluation() {
        when(approvalMapper.findById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.findById(9L, "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
        // 4010 先行：真不存在不做数据权限求值（不泄漏存在性差异给越权探测）
        verifyNoInteractions(dataPermClient);
    }

    @Test
    void findById_approving_assemblesApplyAndApprovalStepsOnly() {
        BpmnApproval approval = approval(StatusEnum.APPROVING.getCode());
        when(approvalMapper.findById(5L)).thenReturn(approval);
        stubEvalAll("userA", "detail", "5");
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

        ApprovalDetailVo detail = service.findById(5L, "userA");

        assertThat(detail.getApproval().getStatusLabel()).isEqualTo("审批中");
        assertThat(detail.getApproval().getApplyUserName()).isEqualTo("nick-userA");
        assertThat(detail.getApproval().getApproverName()).isEqualTo("nick-admin");
        assertThat(detail.getSteps()).extracting(ApprovalStepVo::getStepKey)
                .containsExactly("apply", "approval");
        assertThat(detail.getSteps().get(0).getOperator()).isEqualTo("userA");
        assertThat(detail.getSteps().get(1).getOperator()).isEqualTo("admin");
        assertThat(detail.getSteps().get(1).getOperatorName()).isEqualTo("nick-admin");
        assertThat(detail.getSteps().get(1).getComment()).isEqualTo("agree");
        // 无列动作：title 原值
        assertThat(detail.getApproval().getTitle()).isEqualTo("annual leave");
    }

    @Test
    void findById_cancelled_endStepFallsBackToUpdateTime() {
        BpmnApproval cancelled = approval(StatusEnum.CANCELLED.getCode());
        cancelled.setProcessInstanceId(null);
        cancelled.setUpdateTime(LocalDateTime.of(2026, 10, 8, 12, 0));
        when(approvalMapper.findById(5L)).thenReturn(cancelled);
        stubEvalAll("userA", "detail", "5");
        stubConfig();
        // 撤销单实例关联已清空（processInstanceId=null）——不触评论查询
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of());
        when(translationCacheService.findUserNames(anySet())).thenReturn(Map.of());

        ApprovalDetailVo detail = service.findById(5L, "userA");

        assertThat(detail.getSteps()).extracting(ApprovalStepVo::getStepKey)
                .containsExactly("apply", "end");
        ApprovalStepVo end = detail.getSteps().get(1);
        assertThat(end.getResult()).isEqualTo("已撤销");
        assertThat(end.getTime()).isEqualTo("2026-10-08 12:00:00");
    }

    // ---- findById：IDOR 收口 + 列级（契约 dataperm-component §3.2） ----

    @Test
    void findById_visible_declaresColumnScopeOnDetail() {
        // 白名单含归属账号（可见）+ title 脱敏：详情接线声明同列表（嵌套 approval 原始值，advice 下钻改写）
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubEval(new DataPermScopeVo(false, List.of("userA"), List.of(), List.of("title")),
                "userA", "detail", "5");
        stubConfig();
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of());
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet())).thenReturn(Map.of());
        when(translationCacheService.findUserNames(anySet())).thenReturn(Map.of());

        ApprovalDetailVo detail = service.findById(5L, "userA");

        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("bpmn_approval");
        assertThat(DataPermColumnContext.get().columnScope().getMaskedColumns()).containsExactly("title");
        assertThat(detail.getApproval().getTitle()).isEqualTo("annual leave");
        verify(dataPermClient, never()).deny(any(DataPermDenyRequest.class));
    }

    @Test
    void findById_outOfScope_deniesAndRejects4018() {
        // 白名单不含归属账号 userA（他人单）→ deny 三参补痕 + 4018
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubEval(new DataPermScopeVo(false, List.of("peerB"), List.of(), List.of()),
                "userB", "detail", "5");
        when(dataPermClient.deny(new DataPermDenyRequest("userB", "bpmn_approval", "5")))
                .thenReturn(R.ok());

        assertThatThrownBy(() -> service.findById(5L, "userB"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4018);
        verify(dataPermClient).deny(new DataPermDenyRequest("userB", "bpmn_approval", "5"));
        // 拒绝后不触时间线三源
        verifyNoInteractions(taskService, historyService);
    }

    @Test
    void findById_denyDegraded_doesNotBlockRejection() {
        // deny 补痕降级（R.fail 1002）：仅记日志，4018 拒绝语义不受影响（D21/D23）
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubEval(new DataPermScopeVo(false, List.of("peerB"), List.of(), List.of()),
                "userB", "detail", "5");
        when(dataPermClient.deny(any(DataPermDenyRequest.class)))
                .thenReturn(R.fail(1002, "数据权限服务不可用"));

        assertThatThrownBy(() -> service.findById(5L, "userB"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4018);
    }

    // ---- findById/diagram：谓词轮 D34 行属性判定 ----

    @Test
    void findById_allPlusIdPredicate_rowOutOfCondition_deniesAndRejects4018() {
        // ALL+id BETWEEN 1~100：本人单 id=150 ∉ [1,100] → 谓词不满足 → 组失败 → 4018 + deny
        //（owner 无关证明：账号维放行救不回条件不满足，契约 §4.1）
        BpmnApproval approval = approval(StatusEnum.APPROVING.getCode());
        approval.setId(150L);
        when(approvalMapper.findById(150L)).thenReturn(approval);
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(idBetween(1, 100)))));
        stubEval(scopeVo, "userA", "detail", "150");
        when(dataPermClient.deny(new DataPermDenyRequest("userA", "bpmn_approval", "150")))
                .thenReturn(R.ok());

        assertThatThrownBy(() -> service.findById(150L, "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4018);
        verify(dataPermClient).deny(new DataPermDenyRequest("userA", "bpmn_approval", "150"));
        verifyNoInteractions(taskService, historyService);
    }

    @Test
    void findById_createTimeGte_typedEntityCoercion_visible() {
        // entity typed 形态（D34 bpmn 侧验证点）：createTime LocalDateTime 直比 DATETIME 归一值
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        PredicateVo createTimeGte = new PredicateVo("createTime", "create_time", "GTE",
                FilterColumnTypeEnum.DATETIME, List.of("2026-01-01 00:00:00"));
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(createTimeGte))));
        stubEval(scopeVo, "userA", "detail", "5");
        stubConfig();
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of());
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of());
        when(translationCacheService.findUserNames(anySet())).thenReturn(Map.of());

        ApprovalDetailVo detail = service.findById(5L, "userA");

        // approval.createTime=2026-10-08 10:00 ≥ 2026-01-01 → 可见（BaseEntity 层级字段取值命中）
        assertThat(detail.getApproval().getId()).isEqualTo(5L);
        verify(dataPermClient, never()).deny(any(DataPermDenyRequest.class));
    }

    // ---- findDiagram（三态矩阵：撤销=历史缺失防御 / 运行中 / 终态） ----

    @Test
    void findDiagram_notFoundRejected_4010_beforeEvaluation() {
        when(approvalMapper.findById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.findDiagram(9L, "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
        verifyNoInteractions(dataPermClient);
    }

    @Test
    void findDiagram_outOfScope_deniesAndRejects4018_sameAsDetail() {
        // 图数据同详情行级判定路径（D24）：不通过 deny + 4018，不触引擎查询
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        stubEval(new DataPermScopeVo(false, List.of("peerB"), List.of(), List.of()),
                "userB", "detail", "5");
        when(dataPermClient.deny(new DataPermDenyRequest("userB", "bpmn_approval", "5")))
                .thenReturn(R.ok());

        assertThatThrownBy(() -> service.findDiagram(5L, "userB"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4018);
        verify(dataPermClient).deny(new DataPermDenyRequest("userB", "bpmn_approval", "5"));
        verifyNoInteractions(historyService, runtimeService);
    }

    @Test
    void findDiagram_historyMissing_returnsDefensiveEmpty() {
        BpmnApproval cancelled = approval(StatusEnum.CANCELLED.getCode());
        cancelled.setProcessInstanceId(null);
        when(approvalMapper.findById(5L)).thenReturn(cancelled);
        stubEvalAll("userA", "detail", "5");
        stubConfig();
        org.flowable.engine.history.HistoricProcessInstanceQuery hq = stubBusinessKeyFallback();
        when(hq.list()).thenReturn(List.of());

        ApprovalDiagramVo vo = service.findDiagram(5L, "userA");

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
        stubEvalAll("userA", "detail", "5");

        ApprovalDiagramVo vo = service.findDiagram(5L, "userA");

        assertThat(vo.getDefinitionId()).isEqualTo("def-1");
        assertThat(vo.getProcessInstanceId()).isEqualTo("pid-1");
        assertThat(vo.getActiveActivityIds()).containsExactly("approval");
        assertThat(vo.getCompletedActivityIds()).containsExactly("start", "approval");
        assertThat(vo.getEndActivityId()).isNull();
        // diagram 返回树无白名单类：求值后零接线零上下文（设计 §3.7 记档——不 set 不触发 advice）
        assertThat((Object) DataPermColumnContext.get()).isNull();
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
        stubEvalAll("userA", "detail", "5");

        ApprovalDiagramVo vo = service.findDiagram(5L, "userA");

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
        stubEvalAll("userA", "detail", "5");
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

        ApprovalDiagramVo vo = service.findDiagram(5L, "userA");

        assertThat(vo.getDefinitionId()).isEqualTo("def-1");
        assertThat(vo.getProcessInstanceId()).isEqualTo("pid-new");
        verify(hq, never()).singleResult();
    }

    // ---- 操作符扩展轮（D41/D43/D44，2026-10-11）：title PREFIX/NE 行属性判定 + id GT 行集透传 ----

    @Test
    void findById_titlePrefixMatched_visible() {
        // ALL+title PREFIX 'annual'：title="annual leave" 前缀命中 → 可见（owner 无关，契约 §4.3 增量）
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        PredicateVo titlePrefix = new PredicateVo("title", "title", "PREFIX",
                FilterColumnTypeEnum.STRING, List.of("annual"));
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(titlePrefix))));
        stubEval(scopeVo, "userA", "detail", "5");
        stubConfig();
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of());
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of());
        when(translationCacheService.findUserNames(anySet())).thenReturn(Map.of());

        ApprovalDetailVo detail = service.findById(5L, "userA");

        assertThat(detail.getApproval().getTitle()).isEqualTo("annual leave");
        verify(dataPermClient, never()).deny(any(DataPermDenyRequest.class));
    }

    @Test
    void findById_titlePrefixNotMatched_deniesAndRejects4018() {
        // 前缀不命中 → 组失败 → 4018 + deny（与 id BETWEEN 不满足同路径）
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        PredicateVo titlePrefix = new PredicateVo("title", "title", "PREFIX",
                FilterColumnTypeEnum.STRING, List.of("zzz"));
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(titlePrefix))));
        stubEval(scopeVo, "userA", "detail", "5");
        when(dataPermClient.deny(new DataPermDenyRequest("userA", "bpmn_approval", "5")))
                .thenReturn(R.ok());

        assertThatThrownBy(() -> service.findById(5L, "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4018);
        verify(dataPermClient).deny(new DataPermDenyRequest("userA", "bpmn_approval", "5"));
        verifyNoInteractions(taskService, historyService);
    }

    @Test
    void findById_titleNe_valueDifferent_visible() {
        // title NE 'other'：值不同 → 排除语义放行
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        PredicateVo titleNe = new PredicateVo("title", "title", "NE",
                FilterColumnTypeEnum.STRING, List.of("other"));
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(titleNe))));
        stubEval(scopeVo, "userA", "detail", "5");
        stubConfig();
        when(taskService.getProcessInstanceComments("pid-1")).thenReturn(List.of());
        when(translationCacheService.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of());
        when(translationCacheService.findUserNames(anySet())).thenReturn(Map.of());

        ApprovalDetailVo detail = service.findById(5L, "userA");

        assertThat(detail.getApproval().getId()).isEqualTo(5L);
        verify(dataPermClient, never()).deny(any(DataPermDenyRequest.class));
    }

    @Test
    void findById_titleNe_ciEqualValue_deniesAndRejects4018() {
        // NE 规则④求反（探针 P8）："ANNUAL LEAVE " 与 "annual leave" ci+尾随空格视为相等 → NE 不满足 → 4018
        when(approvalMapper.findById(5L)).thenReturn(approval(StatusEnum.APPROVING.getCode()));
        PredicateVo titleNe = new PredicateVo("title", "title", "NE",
                FilterColumnTypeEnum.STRING, List.of("ANNUAL LEAVE "));
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(titleNe))));
        stubEval(scopeVo, "userA", "detail", "5");
        when(dataPermClient.deny(new DataPermDenyRequest("userA", "bpmn_approval", "5")))
                .thenReturn(R.ok());

        assertThatThrownBy(() -> service.findById(5L, "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4018);
    }

    @Test
    void pageListMy_idGtPredicate_rowFilterPassedToMapper() {
        // ALL+id GT 100 条件组：wire 形态 → toRowFilter 类型化（GT/BigDecimal）→ mapper 收组
        PredicateVo idGt = new PredicateVo("id", "id", "GT",
                FilterColumnTypeEnum.NUMBER, List.of("100"));
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of("userA"), List.of(), List.of(),
                List.of(new RowGroupVo(true, List.of(), List.of(idGt))));
        stubEval(scopeVo, "userA", "list", null);
        Page<BpmnApproval> page = new Page<>(1, 5);
        page.setTotal(0);
        when(approvalMapper.pageList(any(Page.class), argThat(filter ->
                !filter.isRowAll() && filter.getGroups().size() == 1
                        && filter.getGroups().get(0).isAllAccounts()
                        && filter.getGroups().get(0).getPredicates().size() == 1
                        && "GT".equals(filter.getGroups().get(0).getPredicates().get(0).getOperator())
                        && filter.getGroups().get(0).getPredicates().get(0).getValues().get(0)
                                .equals(new java.math.BigDecimal("100"))))).thenReturn(page);

        service.pageListMy(query(), "userA");

        verify(approvalMapper).pageList(any(Page.class), any(RowFilter.class));
    }

    // ---- 脚手架 ----

    private PageQuery query() {
        PageQuery query = new PageQuery();
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

    /** 全部档求值桩（无列动作）：等价旧「恒按申请人」语义在 admin 视角的期望终态 */
    private void stubEvalAll(String account, String operation, String businessKey) {
        stubEval(new DataPermScopeVo(true, List.of(), List.of(), List.of()), account, operation, businessKey);
    }

    /** id BETWEEN 谓词（wire 字符串承载形态——provider toScopeVo 回写同款） */
    private PredicateVo idBetween(long low, long high) {
        return new PredicateVo("id", "id", "BETWEEN",
                FilterColumnTypeEnum.NUMBER, List.of(String.valueOf(low), String.valueOf(high)));
    }

    /** 求值桩（DataPermEvaluateRequest @Data equals 精确匹配四参） */
    private void stubEval(DataPermScopeVo scopeVo, String account, String operation, String businessKey) {
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest(account, "bpmn_approval", operation, businessKey)))
                .thenReturn(R.ok(scopeVo));
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
