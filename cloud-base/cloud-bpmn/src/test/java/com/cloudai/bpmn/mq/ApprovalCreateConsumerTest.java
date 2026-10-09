package com.cloudai.bpmn.mq;

import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发起审批消费单测（契约 2026-10-09 §1.2 结果分流四分支：
 * 成功→SUCCESS 事件 / 4015 幂等吸收零事件 / 白名单→FAILED 事件 / 系统态重抛重试；
 * 补偿事件发送失败不对称处置：SUCCESS 仅记档、FAILED 重抛）。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalCreateConsumerTest {

    @Mock
    private ApprovalWorkflowService workflowService;
    /** 真实组装逻辑（spy）：事件体断言看真实产物；sendPlain 打桩走 do* API 不触真实 template */
    @Spy
    private ApprovalEventPublisher eventPublisher =
            new ApprovalEventPublisher(new RocketMQTemplate(), new JsonPayloads(new ObjectMapper()));

    private final JsonPayloads jsonPayloads = new JsonPayloads(new ObjectMapper());

    private ApprovalCreateConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ApprovalCreateConsumer(workflowService, eventPublisher, jsonPayloads);
        // 默认发送成功（零事件分支不触达——lenient 防严格桩误报）
        lenient().doNothing().when(eventPublisher).sendPlain(any());
    }

    @Test
    void success_sendsSuccessEventAndAcks() {
        when(workflowService.createApproval(any())).thenReturn(createVo("12"));

        assertThatCode(() -> consumer.onMessage(msg(request()))).doesNotThrowAnyException();

        ArgumentCaptor<ApprovalEventMessage> captor = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(eventPublisher).sendPlain(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("CREATE_RESULT");
        assertThat(captor.getValue().getResult()).isEqualTo("SUCCESS");
        assertThat(captor.getValue().getApprovalId()).isEqualTo("12");
        assertThat(captor.getValue().getBusinessKey()).isEqualTo("7");
    }

    @Test
    void success_eventSendFails_onlyLogsAndAcks() {
        // SUCCESS 事件丢失由读时纠偏兜底收敛——不阻塞 ACK（主控裁定不对称处置）
        when(workflowService.createApproval(any())).thenReturn(createVo("12"));
        doThrow(new RuntimeException("broker down")).when(eventPublisher).sendPlain(any());

        assertThatCode(() -> consumer.onMessage(msg(request()))).doesNotThrowAnyException();
    }

    @Test
    void exists_4015_absorbedWithoutEvent() {
        // 同单据重投：uk_business 已兜底建过审批单，幂等吸收零事件（契约 §1.2 分支 2）
        when(workflowService.createApproval(any()))
                .thenThrow(new BusinessException(4015, "该业务单据已存在审批"));

        assertThatCode(() -> consumer.onMessage(msg(request()))).doesNotThrowAnyException();
        verify(eventPublisher, never()).sendPlain(any());
    }

    @Test
    void approverInvalid_4013_sendsFailedEventAndAcks() {
        when(workflowService.createApproval(any()))
                .thenThrow(new BusinessException(4013, "审批人无效: ghost"));

        assertThatCode(() -> consumer.onMessage(msg(request()))).doesNotThrowAnyException();

        ArgumentCaptor<ApprovalEventMessage> captor = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(eventPublisher).sendPlain(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("FAILED");
        assertThat(captor.getValue().getApprovalId()).isNull();
        assertThat(captor.getValue().getReason()).isEqualTo("审批人无效: ghost");
    }

    @Test
    void typeUnknown_4014_sendsFailedEventAndAcks() {
        when(workflowService.createApproval(any()))
                .thenThrow(new BusinessException(4014, "业务类型不存在"));
        consumer.onMessage(msg(request()));

        ArgumentCaptor<ApprovalEventMessage> captor = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(eventPublisher).sendPlain(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("FAILED");
        assertThat(captor.getValue().getReason()).isEqualTo("业务类型不存在");
    }

    @Test
    void definitionMissing_4017_sendsFailedEventAndAcks() {
        when(workflowService.createApproval(any()))
                .thenThrow(new BusinessException(4017, "流程定义未部署"));
        consumer.onMessage(msg(request()));

        ArgumentCaptor<ApprovalEventMessage> captor = ArgumentCaptor.forClass(ApprovalEventMessage.class);
        verify(eventPublisher).sendPlain(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("FAILED");
        assertThat(captor.getValue().getReason()).isEqualTo("流程定义未部署");
    }

    @Test
    void failedEventSendFails_rethrowsForRetry() {
        // FAILED 事件丢失无纠偏可救——重抛走重试×3→死信（主控裁定）；确定性失败重放结果一致可重发
        when(workflowService.createApproval(any()))
                .thenThrow(new BusinessException(4013, "审批人无效: ghost"));
        doThrow(new RuntimeException("broker down")).when(eventPublisher).sendPlain(any());

        assertThatThrownBy(() -> consumer.onMessage(msg(request())))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
    }

    @Test
    void systemStateBusinessError_rethrowsForRetry() {
        // 白名单外业务错误（如 Feign 投影不可用 1002）= 系统态：重抛重试（契约 §1.2 分支 4）
        when(workflowService.createApproval(any()))
                .thenThrow(new BusinessException("用户服务不可用"));

        assertThatThrownBy(() -> consumer.onMessage(msg(request())))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(1002);
        verify(eventPublisher, never()).sendPlain(any());
    }

    @Test
    void unparseableBody_thrownForRetry() {
        MessageExt bad = mock(MessageExt.class);
        when(bad.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(bad))
                .isInstanceOf(IllegalArgumentException.class);
        verify(workflowService, never()).createApproval(any());
    }

    // ---- 脚手架 ----

    private ApprovalCreateInnerRequest request() {
        ApprovalCreateInnerRequest req = new ApprovalCreateInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKey("7");
        req.setTitle("annual leave");
        req.setApplyUser("userA");
        req.setApprover("admin");
        return req;
    }

    private MessageExt msg(ApprovalCreateInnerRequest req) {
        try {
            MessageExt msg = mock(MessageExt.class);
            when(msg.getBody()).thenReturn(new ObjectMapper().writeValueAsBytes(req));
            return msg;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private InnerApprovalCreateVo createVo(String approvalId) {
        InnerApprovalCreateVo vo = new InnerApprovalCreateVo();
        vo.setApprovalId(approvalId);
        vo.setStatus("0");
        return vo;
    }
}
