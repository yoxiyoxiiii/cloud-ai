package com.cloudai.bpmn.service;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.api.domain.VariableItem;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.mq.ApprovalCancelTxExecutor;
import com.cloudai.bpmn.mq.ApprovalCompleteTxExecutor;
import com.cloudai.bpmn.mq.ApprovalEventPublisher;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.tx.TxMessageSendException;
import com.cloudai.common.rocketmq.tx.TxMessageSender;
import com.cloudai.system.api.client.SystemUserClient;
import com.cloudai.system.api.domain.UserEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用审批编排单测（契约 2026-10-08-approval-platform-api §4/§3/§2.3 + 2026-10-09 MQ 事务消息 §1.3）：
 * createApproval 4010-4017 全触发 + 平台四变量注入（消费侧业务 @Transactional 不变）；
 * completeTask/cancelApproval 编排化断言面——前置校验失败零发送即抛（4016/4010/4012/4011）、
 * TERMINAL 半消息发送参数（topic/tag/KEYS/事件体/通道/bizArg）、TxMessageSendException→1002 系转译。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceTest {

    @Mock
    private BpmnApprovalMapper approvalMapper;
    @Mock
    private BusinessTypeRegistry businessTypeRegistry;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private SystemUserClient systemUserClient;
    @Mock
    private TxMessageSender txMessageSender;
    /** 真实组装逻辑（spy 仅注入）：事件体/KEYS 断言看真实产物 */
    @Spy
    private ApprovalEventPublisher eventPublisher =
            new ApprovalEventPublisher(new RocketMQTemplate(), new JsonPayloads(new ObjectMapper()));
    @InjectMocks
    private ApprovalWorkflowService service;

    // ---- createApproval（消费侧业务，行为不变） ----

    @Test
    void createApproval_happyPath_insertsStartsAndBackfills() {
        stubConfigFound();
        stubApproverProjection("admin");
        stubBusinessAbsent();
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn("pid-1");
        when(runtimeService.startProcessInstanceByKey(eq("leave_approval"), eq("12"), anyMap()))
                .thenReturn(instance);
        doAnswer(inv -> {
            inv.getArgument(0, BpmnApproval.class).setId(12L);
            return 1;
        }).when(approvalMapper).save(any(BpmnApproval.class));

        InnerApprovalCreateVo vo = service.createApproval(request("admin", null));

        assertThat(vo.getApprovalId()).isEqualTo("12");
        assertThat(vo.getStatus()).isEqualTo("0");
        ArgumentCaptor<BpmnApproval> captor = ArgumentCaptor.forClass(BpmnApproval.class);
        verify(approvalMapper).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StatusEnum.APPROVING.getCode());
        assertThat(captor.getValue().getCreateBy()).isEqualTo("userA");
        assertThat(captor.getValue().getProcessKey()).isEqualTo("leave_approval");
        assertThat(captor.getValue().getProcessInstanceId()).isNull();
        verify(approvalMapper).updateStatusById(eq(12L), eq(StatusEnum.APPROVING.getCode()),
                eq("pid-1"), eq("userA"), any());
        // 消费入口不直接发事件——SUCCESS 补偿事件由 ApprovalCreateConsumer 负责（契约 §1.2）
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void createApproval_injectsPlatformVariablesAndPassthrough() {
        stubConfigFound();
        stubApproverProjection("admin");
        stubBusinessAbsent();
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn("pid-1");
        when(runtimeService.startProcessInstanceByKey(anyString(), anyString(), anyMap()))
                .thenReturn(instance);
        doAnswer(inv -> {
            inv.getArgument(0, BpmnApproval.class).setId(12L);
            return 1;
        }).when(approvalMapper).save(any(BpmnApproval.class));

        VariableItem days = new VariableItem();
        days.setName("days");
        days.setValue("3");
        // 同名变量 "approver"：平台四变量最后注入，以平台为准（防 assignee 被覆盖，契约 §1）
        VariableItem hijack = new VariableItem();
        hijack.setName("approver");
        hijack.setValue("hacker");
        service.createApproval(request("admin", List.of(days, hijack)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService).startProcessInstanceByKey(eq("leave_approval"), eq("12"), vars.capture());
        assertThat(vars.getValue())
                .containsEntry("approvalId", 12L)
                .containsEntry("applyUser", "userA")
                .containsEntry("approver", "admin")
                .containsEntry("title", "annual leave")
                .containsEntry("days", "3");
    }

    @Test
    void createApproval_unknownTypeRejected_4014() {
        when(businessTypeRegistry.findByTypeCode("leave")).thenThrow(
                new BusinessException(4014, "业务类型不存在"));

        assertThatThrownBy(() -> service.createApproval(request("admin", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4014);
        verify(approvalMapper, never()).save(any());
    }

    @Test
    void createApproval_approverNotInProjectionRejected_4013() {
        stubConfigFound();
        stubApproverProjection("admin");

        BusinessException ex = catchThrowableOfType(
                () -> service.createApproval(request("nobody", null)), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(4013);
        // msg 逐字（动态后缀）：审批人无效: {approver}
        assertThat(ex.getMessage()).isEqualTo("审批人无效: nobody");
        verify(approvalMapper, never()).save(any());
    }

    @Test
    void createApproval_existingBusinessRejected_4015_anyStatus() {
        stubConfigFound();
        stubApproverProjection("admin");
        // 终态行仍占 uk（任意状态存在即拒——行永不删，驳回重发=业务方新单据，契约 §1）
        BpmnApproval existing = approval(12L, StatusEnum.REJECTED.getCode());
        when(approvalMapper.findByBusiness("leave", "7")).thenReturn(existing);

        BusinessException ex = catchThrowableOfType(
                () -> service.createApproval(request("admin", null)), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(4015);
        // msg 逐字（契约 §6）：该业务单据已存在审批
        assertThat(ex.getMessage()).isEqualTo("该业务单据已存在审批");
        verify(approvalMapper, never()).save(any());
    }

    @Test
    void createApproval_duplicateKeyFallback_4015() {
        stubConfigFound();
        stubApproverProjection("admin");
        stubBusinessAbsent();
        when(approvalMapper.save(any(BpmnApproval.class)))
                .thenThrow(new DuplicateKeyException("uk_business duplicate"));

        BusinessException ex = catchThrowableOfType(
                () -> service.createApproval(request("admin", null)), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(4015);
        assertThat(ex.getMessage()).isEqualTo("该业务单据已存在审批");
        verify(runtimeService, never()).startProcessInstanceByKey(anyString(), anyString(), anyMap());
    }

    @Test
    void createApproval_feignFailureRejected_1002() {
        stubConfigFound();
        when(systemUserClient.listAll()).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.createApproval(request("admin", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(1002);
        verify(approvalMapper, never()).save(any());
    }

    @Test
    void createApproval_definitionMissingRejected_4017() {
        stubConfigFound();
        stubApproverProjection("admin");
        stubBusinessAbsent();
        when(runtimeService.startProcessInstanceByKey(anyString(), anyString(), anyMap()))
                .thenThrow(new org.flowable.common.engine.api.FlowableObjectNotFoundException("no processes deployed with key"));
        doAnswer(inv -> {
            inv.getArgument(0, BpmnApproval.class).setId(12L);
            return 1;
        }).when(approvalMapper).save(any(BpmnApproval.class));

        assertThatThrownBy(() -> service.createApproval(request("admin", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4017);
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    // ---- listStatusByBusiness ----

    @Test
    void listStatusByBusiness_echoesAllKeysWithNullFill() {
        BpmnApproval a1 = approval(12L, StatusEnum.APPROVED.getCode());
        a1.setBusinessKey("101");
        BpmnApproval a2 = approval(13L, StatusEnum.APPROVING.getCode());
        a2.setBusinessKey("103");
        when(approvalMapper.listByBusinessKeys("leave", List.of("101", "102", "103")))
                .thenReturn(List.of(a1, a2));

        List<InnerApprovalStatusVo> out = service.listStatusByBusiness(query("101", "102", "103"));

        // 全键回包保序：无审批单的键 approvalId/status 均 null（契约 §4.2）
        assertThat(out).extracting(InnerApprovalStatusVo::getBusinessKey)
                .containsExactly("101", "102", "103");
        assertThat(out.get(0).getApprovalId()).isEqualTo("12");
        assertThat(out.get(0).getStatus()).isEqualTo("1");
        assertThat(out.get(1).getApprovalId()).isNull();
        assertThat(out.get(1).getStatus()).isNull();
        assertThat(out.get(2).getApprovalId()).isEqualTo("13");
        assertThat(out.get(2).getStatus()).isEqualTo("0");
    }

    // ---- cancelApproval / cancelByBusiness（编排化断言面） ----

    @Test
    void cancelApproval_notFoundRejected_4010_noSend() {
        when(approvalMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.cancelApproval(9L, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void cancelApproval_notApplierRejected_4012_noSend() {
        when(approvalMapper.findById(5L)).thenReturn(approval(5L, StatusEnum.APPROVING.getCode()));
        assertThatThrownBy(() -> service.cancelApproval(5L, "userB"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4012);
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void cancelApproval_terminalRejected_4011_noSend() {
        when(approvalMapper.findById(5L)).thenReturn(approval(5L, StatusEnum.APPROVED.getCode()));
        assertThatThrownBy(() -> service.cancelApproval(5L, "userA"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4011);
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void cancelApproval_happyPath_sendsTerminalCancelEvent() {
        when(approvalMapper.findById(5L)).thenReturn(approval(5L, StatusEnum.APPROVING.getCode()));

        service.cancelApproval(5L, "userA");

        ArgumentCaptor<ApprovalEventMessage> event = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(txMessageSender).sendTransactional(eq(ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY),
                eq(ApprovalMqTopics.TAG_TERMINAL), eq("leave:7:TERMINAL"), event.capture(),
                eq(ApprovalCancelTxExecutor.CHANNEL), any(ApprovalCancelTxExecutor.CancelCommand.class));
        assertThat(event.getValue().getEventType()).isEqualTo("TERMINAL");
        assertThat(event.getValue().getTerminalStatus()).isEqualTo("3");
        assertThat(event.getValue().getBusinessKey()).isEqualTo("7");
        // 引擎删实例与置 3 已迁入 executor（本地事务内）——service 不再直接写
        verify(runtimeService, never()).deleteProcessInstance(anyString(), anyString());
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cancelApproval_senderFailure_translated_1002() {
        when(approvalMapper.findById(5L)).thenReturn(approval(5L, StatusEnum.APPROVING.getCode()));
        when(txMessageSender.sendTransactional(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new TxMessageSendException("half message failed", null));

        BusinessException ex = catchThrowableOfType(() -> service.cancelApproval(5L, "userA"),
                BusinessException.class);

        // 半消息发送失败本地零写——1002 系（bpmn 无 3xxx 账本，契约 §3 只增 system 3025）
        assertThat(ex.getMessage()).isEqualTo("消息服务不可用");
        assertThat(ex.getCode()).isEqualTo(1002);
    }

    @Test
    void cancelByBusiness_notFoundRejected_4010() {
        when(approvalMapper.findByBusiness("leave", "404")).thenReturn(null);
        ApprovalCancelInnerRequest req = new ApprovalCancelInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKey("404");
        req.setOperator("userA");

        assertThatThrownBy(() -> service.cancelByBusiness(req))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
    }

    @Test
    void cancelByBusiness_found_delegatesToIdSend() {
        when(approvalMapper.findByBusiness("leave", "7")).thenReturn(approval(5L, StatusEnum.APPROVING.getCode()));
        // cancelApproval 内部按 id 复查行（requireApproval）
        when(approvalMapper.findById(5L)).thenReturn(approval(5L, StatusEnum.APPROVING.getCode()));
        ApprovalCancelInnerRequest req = new ApprovalCancelInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKey("7");
        req.setOperator("userA");

        service.cancelByBusiness(req);

        verify(txMessageSender).sendTransactional(eq(ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY),
                eq(ApprovalMqTopics.TAG_TERMINAL), eq("leave:7:TERMINAL"), any(),
                eq(ApprovalCancelTxExecutor.CHANNEL), any(ApprovalCancelTxExecutor.CancelCommand.class));
    }

    // ---- completeTask（编排化断言面） ----

    @Test
    void completeTask_taskMissingRejected_4016_noSend() {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId("t-404")).thenReturn(query);
        when(query.singleResult()).thenReturn(null);
        assertThatThrownBy(() -> service.completeTask(complete("t-404", "true"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4016);
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void completeTask_approve_sendsTerminalCompleteEvent() {
        stubTaskAndInstance("t-1", "pid-1", "12");
        when(approvalMapper.findById(12L)).thenReturn(approval(12L, StatusEnum.APPROVING.getCode()));

        service.completeTask(complete("t-1", "true"), "admin");

        ArgumentCaptor<ApprovalEventMessage> event = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        ArgumentCaptor<Object> bizArg = ArgumentCaptor.forClass(Object.class);
        verify(txMessageSender).sendTransactional(eq(ApprovalMqTopics.TOPIC_APPROVAL_EVENT_NOTIFY),
                eq(ApprovalMqTopics.TAG_TERMINAL), eq("leave:7:TERMINAL"), event.capture(),
                eq(ApprovalCompleteTxExecutor.CHANNEL), bizArg.capture());
        // terminalStatus 按 approve 推断（单节点模型与 endActivityId 映射一致，设计 D2）
        assertThat(event.getValue().getTerminalStatus()).isEqualTo("1");
        ApprovalCompleteTxExecutor.CompleteCommand cmd =
                (ApprovalCompleteTxExecutor.CompleteCommand) bizArg.getValue();
        assertThat(cmd.taskId()).isEqualTo("t-1");
        assertThat(cmd.approve()).isEqualTo("true");
        assertThat(cmd.comment()).isEqualTo("ok");
        assertThat(cmd.opUser()).isEqualTo("admin");
        assertThat(cmd.expectedStatus()).isEqualTo(StatusEnum.APPROVED.getCode());
        // 引擎推进已迁 executor——service 不再直接写引擎/审批表
        verify(taskService, never()).complete(anyString(), anyMap());
        verify(approvalMapper, never()).updateStatusById(anyLong(), any(), any(), any(), any());
    }

    @Test
    void completeTask_reject_infersRejected() {
        stubTaskAndInstance("t-1", "pid-1", "12");
        when(approvalMapper.findById(12L)).thenReturn(approval(12L, StatusEnum.APPROVING.getCode()));

        service.completeTask(complete("t-1", "false"), "userB");

        ArgumentCaptor<ApprovalEventMessage> event = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(txMessageSender).sendTransactional(anyString(), anyString(), anyString(), event.capture(),
                eq(ApprovalCompleteTxExecutor.CHANNEL), any());
        assertThat(event.getValue().getTerminalStatus()).isEqualTo("2");
    }

    @Test
    void completeTask_taskWithoutApprovalDomain_4010_noSend() {
        // 历史实例 businessKey 缺失=非平台审批域任务：无法组装事件（防御 4010）
        stubTaskAndInstance("t-1", "pid-1", null);
        assertThatThrownBy(() -> service.completeTask(complete("t-1", "true"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void completeTask_approvalRowMissing_4010_noSend() {
        stubTaskAndInstance("t-1", "pid-1", "12");
        when(approvalMapper.findById(12L)).thenReturn(null);
        assertThatThrownBy(() -> service.completeTask(complete("t-1", "true"), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(4010);
        verify(txMessageSender, never()).sendTransactional(anyString(), anyString(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void completeTask_senderFailure_translated_1002() {
        stubTaskAndInstance("t-1", "pid-1", "12");
        when(approvalMapper.findById(12L)).thenReturn(approval(12L, StatusEnum.APPROVING.getCode()));
        when(txMessageSender.sendTransactional(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new TxMessageSendException("half message failed", null));

        BusinessException ex = catchThrowableOfType(
                () -> service.completeTask(complete("t-1", "true"), "admin"), BusinessException.class);

        assertThat(ex.getMessage()).isEqualTo("消息服务不可用");
        assertThat(ex.getCode()).isEqualTo(1002);
    }

    // ---- 脚手架 ----

    private void stubConfigFound() {
        BpmnBusinessType config = new BpmnBusinessType();
        config.setTypeCode("leave");
        config.setTypeName("请假申请");
        config.setProcessKey("leave_approval");
        config.setDetailRoute("/system/leave?approval={businessKey}");
        when(businessTypeRegistry.findByTypeCode("leave")).thenReturn(config);
    }

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

    private void stubBusinessAbsent() {
        when(approvalMapper.findByBusiness("leave", "7")).thenReturn(null);
    }

    private void stubTaskAndInstance(String taskId, String processInstanceId, String businessKey) {
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(taskId)).thenReturn(query);
        Task task = mock(Task.class);
        lenient().when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(query.singleResult()).thenReturn(task);
        org.flowable.engine.history.HistoricProcessInstanceQuery hq =
                mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hq);
        lenient().when(hq.processInstanceId(processInstanceId)).thenReturn(hq);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getBusinessKey()).thenReturn(businessKey);
        lenient().when(hq.singleResult()).thenReturn(historic);
    }

    private ApprovalCreateInnerRequest request(String approver, List<VariableItem> variables) {
        ApprovalCreateInnerRequest req = new ApprovalCreateInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKey("7");
        req.setTitle("annual leave");
        req.setApplyUser("userA");
        req.setApprover(approver);
        req.setVariables(variables);
        return req;
    }

    private ApprovalStatusQueryInnerRequest query(String... keys) {
        ApprovalStatusQueryInnerRequest req = new ApprovalStatusQueryInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKeys(List.of(keys));
        return req;
    }

    private TaskCompleteRequest complete(String taskId, String approve) {
        TaskCompleteRequest req = new TaskCompleteRequest();
        req.setTaskId(taskId);
        req.setApprove(approve);
        req.setComment("ok");
        return req;
    }

    private BpmnApproval approval(Long id, Integer status) {
        BpmnApproval approval = new BpmnApproval();
        approval.setId(id);
        approval.setBusinessType("leave");
        approval.setBusinessKey("7");
        approval.setTitle("annual leave");
        approval.setProcessKey("leave_approval");
        approval.setStatus(status);
        approval.setApplyUser("userA");
        approval.setApprover("admin");
        approval.setProcessInstanceId("pid-5");
        return approval;
    }
}
