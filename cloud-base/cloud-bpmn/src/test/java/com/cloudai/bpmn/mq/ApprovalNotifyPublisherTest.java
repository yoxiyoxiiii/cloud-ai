package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalNotifyMessage;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.service.BusinessTypeRegistry;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批任务通知发布器单测（契约 2026-11-08-approval-notify-mq-api §1.2 消息体逐字段 + §1.3 发送语义）：
 * 逐任务发送/KEYS 组装/无受理人跳过/实例结束空集零发送/行与配置缺失拦下/发送异常吞掉不抛。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalNotifyPublisherTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private BpmnApprovalMapper approvalMapper;
    @Mock
    private BusinessTypeRegistry businessTypeRegistry;
    @Mock
    private TaskService taskService;

    private final JsonPayloads jsonPayloads = new JsonPayloads(new ObjectMapper());

    private ApprovalNotifyPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ApprovalNotifyPublisher(rocketMQTemplate, jsonPayloads,
                approvalMapper, businessTypeRegistry, taskService);
    }

    @Test
    void tasks_activeWithAssignee_sendsPerTaskWithKeysAndBody() {
        stubRowAndConfig();
        stubActiveTasks(task("t-1", "zhangsan", "审批"), task("t-2", "lisi", "复核"));

        publisher.publishTasksForApproval(5L);

        ArgumentCaptor<Message<byte[]>> captor = taskCaptor();
        verify(rocketMQTemplate, org.mockito.Mockito.times(2))
                .syncSend(eq("BPMN_TASK_NOTIFY:TASK_ARRIVED"), captor.capture());
        // KEYS=消费端去重键：{approvalId}:{taskId}:TASK_ARRIVED——taskId 维度精确到任务实例
        assertThat(captor.getAllValues().get(0).getHeaders().get("KEYS")).isEqualTo("5:t-1:TASK_ARRIVED");
        assertThat(captor.getAllValues().get(1).getHeaders().get("KEYS")).isEqualTo("5:t-2:TASK_ARRIVED");
        ApprovalNotifyMessage first = parse(captor.getAllValues().get(0));
        assertThat(first.getNotifyType()).isEqualTo("TASK_ARRIVED");
        assertThat(first.getApprovalId()).isEqualTo("5");
        assertThat(first.getBusinessType()).isEqualTo("leave");
        assertThat(first.getBusinessKey()).isEqualTo("7");
        assertThat(first.getTitle()).isEqualTo("张三的请假申请");
        assertThat(first.getBusinessTypeName()).isEqualTo("请假申请");
        assertThat(first.getApplyUser()).isEqualTo("zhangsan");
        assertThat(first.getReceiverAccount()).isEqualTo("zhangsan");
        assertThat(first.getTaskId()).isEqualTo("t-1");
        assertThat(first.getTaskName()).isEqualTo("审批");
        assertThat(first.getTerminalStatus()).isNull();
        assertThat(first.getOccurredAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void tasks_blankAssigneeSkipped() {
        stubRowAndConfig();
        stubActiveTasks(task("t-1", null, "审批"), task("t-2", "  ", "复核"), task("t-3", "zhangsan", "审批"));

        publisher.publishTasksForApproval(5L);

        // 无明确受理人（candidate 类任务平台暂无形态）无从投递——仅 t-3 发出
        ArgumentCaptor<Message<byte[]>> captor = taskCaptor();
        verify(rocketMQTemplate).syncSend(eq("BPMN_TASK_NOTIFY:TASK_ARRIVED"), captor.capture());
        assertThat(captor.getValue().getHeaders().get("KEYS")).isEqualTo("5:t-3:TASK_ARRIVED");
    }

    @Test
    void tasks_instanceEnded_emptyListZeroSend() {
        // 当前单节点模型办理路径即此态：实例已结束活跃任务空集——零发送非异常
        stubRowAndConfig();
        TaskQuery query = mock(TaskQuery.class);
        lenient().when(taskService.createTaskQuery()).thenReturn(query);
        lenient().when(query.processInstanceId("pid-5")).thenReturn(query);
        lenient().when(query.active()).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        publisher.publishTasksForApproval(5L);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
    }

    @Test
    void tasks_rowMissing_noSend() {
        when(approvalMapper.findById(5L)).thenReturn(null);

        publisher.publishTasksForApproval(5L);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
    }

    @Test
    void tasks_processInstanceIdNull_noSend() {
        BpmnApproval approval = approvalRow();
        approval.setProcessInstanceId(null);
        when(approvalMapper.findById(5L)).thenReturn(approval);

        publisher.publishTasksForApproval(5L);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
    }

    @Test
    void tasks_configMissing_intercepted() {
        // 配置缺失生产侧拦下不发（发出亦是消费端确定性失败）；类型名必须由生产方填充
        when(approvalMapper.findById(5L)).thenReturn(approvalRow());
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(null);

        publisher.publishTasksForApproval(5L);

        verify(taskService, never()).createTaskQuery();
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
    }

    @Test
    void tasks_sendFails_swallowedNeverThrows() {
        stubRowAndConfig();
        stubActiveTasks(task("t-1", "zhangsan", "审批"));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("broker down"));

        // 铁律：发送异常吞掉（log.error），绝不阻断审批主链路
        assertThatCode(() -> publisher.publishTasksForApproval(5L)).doesNotThrowAnyException();
    }

    @Test
    void terminal_sendsToApplierWithSingleKey() {
        stubRowAndConfig();

        publisher.publishTerminalResult(5L, 1);

        ArgumentCaptor<Message<byte[]>> captor = taskCaptor();
        verify(rocketMQTemplate).syncSend(eq("BPMN_TASK_NOTIFY:TERMINAL_RESULT"), captor.capture());
        // 终态每审批单一次（终态不可再变更守护），approvalId 单键去重足够
        assertThat(captor.getValue().getHeaders().get("KEYS")).isEqualTo("5:TERMINAL_RESULT");
        ApprovalNotifyMessage message = parse(captor.getValue());
        assertThat(message.getNotifyType()).isEqualTo("TERMINAL_RESULT");
        assertThat(message.getReceiverAccount()).isEqualTo("zhangsan");
        assertThat(message.getTerminalStatus()).isEqualTo("1");
        assertThat(message.getTaskId()).isNull();
        assertThat(message.getTaskName()).isNull();
        assertThat(message.getBusinessTypeName()).isEqualTo("请假申请");
    }

    @Test
    void terminal_sendFails_swallowedNeverThrows() {
        stubRowAndConfig();
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("broker down"));

        assertThatCode(() -> publisher.publishTerminalResult(5L, 3)).doesNotThrowAnyException();
    }

    // ---- 脚手架 ----

    private void stubRowAndConfig() {
        when(approvalMapper.findById(5L)).thenReturn(approvalRow());
        BpmnBusinessType config = new BpmnBusinessType();
        config.setTypeCode("leave");
        config.setTypeName("请假申请");
        config.setProcessKey("leave_approval");
        when(businessTypeRegistry.findByTypeCodeOrNull("leave")).thenReturn(config);
    }

    private void stubActiveTasks(Task... tasks) {
        TaskQuery query = mock(TaskQuery.class);
        lenient().when(taskService.createTaskQuery()).thenReturn(query);
        lenient().when(query.processInstanceId("pid-5")).thenReturn(query);
        lenient().when(query.active()).thenReturn(query);
        when(query.list()).thenReturn(List.of(tasks));
    }

    private Task task(String id, String assignee, String name) {
        Task task = mock(Task.class);
        lenient().when(task.getId()).thenReturn(id);
        lenient().when(task.getAssignee()).thenReturn(assignee);
        lenient().when(task.getName()).thenReturn(name);
        return task;
    }

    private BpmnApproval approvalRow() {
        BpmnApproval approval = new BpmnApproval();
        approval.setId(5L);
        approval.setBusinessType("leave");
        approval.setBusinessKey("7");
        approval.setTitle("张三的请假申请");
        approval.setProcessKey("leave_approval");
        approval.setApplyUser("zhangsan");
        approval.setApprover("lisi");
        approval.setProcessInstanceId("pid-5");
        return approval;
    }

    private ApprovalNotifyMessage parse(Message<byte[]> message) {
        return jsonPayloads.parse(message.getPayload(), ApprovalNotifyMessage.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Message<byte[]>> taskCaptor() {
        return ArgumentCaptor.forClass((Class) Message.class);
    }
}
