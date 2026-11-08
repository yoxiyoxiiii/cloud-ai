package com.cloudai.system.mq;

import com.cloudai.bpmn.api.domain.ApprovalNotifyMessage;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.entity.SysNotifyMessage.ReceiverTypeEnum;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.service.NotifySendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批任务通知消费单测（契约 2026-11-08-approval-notify-mq-api §1.4）：
 * 两分支变量组装逐字/linkUrl/单收件人/审计操作人；确定性失败（收件人无效停用/模板缺失/
 * terminalStatus 未知/门面业务异常）log 后 ACK 不抛；系统态（未知 notifyType/解析失败）抛出走重试；
 * dedupGroup 与注解 consumerGroup 同源（L1 去重表与订阅组恒一致）。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalNotifyConsumerTest {

    @Mock
    private ConsumeDedupDao consumeDedupDao;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysNotifyTemplateMapper templateMapper;
    @Mock
    private NotifySendService sendService;

    private final JsonPayloads jsonPayloads = new JsonPayloads(new ObjectMapper());

    private ApprovalNotifyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ApprovalNotifyConsumer(consumeDedupDao, jsonPayloads,
                userMapper, templateMapper, sendService);
    }

    @Test
    void taskArrived_assemblesVariablesAndSendsToAssignee() throws Exception {
        stubReceiver("zhangsan", 7L, "张三");
        stubApplyUser("lisi", "李四");
        stubTemplate("approval_remind", 2L);

        consumer.doConsume(msg(arrivedMessage()));

        ArgumentCaptor<NotifySendRequest> captor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(sendService).saveNotify(captor.capture(), eq("bpmn-notify"));
        NotifySendRequest req = captor.getValue();
        assertThat(req.getTemplateId()).isEqualTo(2L);
        assertThat(req.getReceiverType()).isEqualTo(ReceiverTypeEnum.USER.getCode());
        assertThat(req.getUserIds()).containsExactly(7L);
        assertThat(req.getLinkUrl()).isEqualTo("/bpmn/task");
        // 变量组装按契约 §1.4 表逐字：五变量（昵称/申请人昵称/标题/类型名/节点名）
        assertThat(req.getVariables())
                .containsEntry("nickname", "张三")
                .containsEntry("applyUserName", "李四")
                .containsEntry("title", "张三的请假申请")
                .containsEntry("businessTypeName", "请假申请")
                .containsEntry("taskName", "审批");
    }

    @Test
    void terminalResult_assemblesVariablesAndSendsToApplier() throws Exception {
        stubReceiver("lisi", 8L, "李四");
        stubTemplate("approval_result", 3L);
        ApprovalNotifyMessage message = terminalMessage();
        message.setTerminalStatus("2");

        consumer.doConsume(msg(message));

        ArgumentCaptor<NotifySendRequest> captor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(sendService).saveNotify(captor.capture(), eq("bpmn-notify"));
        NotifySendRequest req = captor.getValue();
        assertThat(req.getTemplateId()).isEqualTo(3L);
        assertThat(req.getUserIds()).containsExactly(8L);
        assertThat(req.getLinkUrl()).isEqualTo("/bpmn/approval");
        // 终态四变量：昵称/标题/类型名/结果文案（1/2/3 → 已通过/已拒绝/已撤销）
        assertThat(req.getVariables())
                .containsEntry("nickname", "李四")
                .containsEntry("title", "张三的请假申请")
                .containsEntry("businessTypeName", "请假申请")
                .containsEntry("resultText", "已拒绝");
    }

    @Test
    void terminalResult_resultTextMapsAllTerminalStatuses() throws Exception {
        stubReceiver("lisi", 8L, "李四");
        stubTemplate("approval_result", 3L);

        assertResultText("1", "已通过");
        assertResultText("3", "已撤销");
    }

    @Test
    void receiverMissing_deterministicAckWithoutSend() throws Exception {
        when(userMapper.findByAccount("zhangsan")).thenReturn(null);

        assertThatCode(() -> consumer.doConsume(msg(arrivedMessage()))).doesNotThrowAnyException();
        verify(sendService, never()).saveNotify(any(), anyString());
    }

    @Test
    void receiverDisabled_deterministicAckWithoutSend() throws Exception {
        SysUser disabled = user("zhangsan", 7L, "张三");
        disabled.setStatus(SysUser.StatusEnum.DISABLED.getCode());
        when(userMapper.findByAccount("zhangsan")).thenReturn(disabled);

        assertThatCode(() -> consumer.doConsume(msg(arrivedMessage()))).doesNotThrowAnyException();
        verify(sendService, never()).saveNotify(any(), anyString());
    }

    @Test
    void templateMissing_deterministicAckWithoutSend() throws Exception {
        stubReceiver("zhangsan", 7L, "张三");
        when(templateMapper.findByCode("approval_remind")).thenReturn(null);

        assertThatCode(() -> consumer.doConsume(msg(arrivedMessage()))).doesNotThrowAnyException();
        verify(sendService, never()).saveNotify(any(), anyString());
    }

    @Test
    void applyUserDeleted_fallsBackToAccountWithWarn() throws Exception {
        stubReceiver("zhangsan", 7L, "张三");
        when(userMapper.findByAccount("lisi")).thenReturn(null);
        stubTemplate("approval_remind", 2L);

        consumer.doConsume(msg(arrivedMessage()));

        // 申请人已删回退账号展示（历史单据长尾不阻断通知）
        ArgumentCaptor<NotifySendRequest> captor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(sendService).saveNotify(captor.capture(), anyString());
        assertThat(captor.getValue().getVariables()).containsEntry("applyUserName", "lisi");
    }

    @Test
    void sendFacadeBusinessError_deterministicAck() throws Exception {
        // 门面确定性失败（如模板被管理员改后 3045 变量缺失）：重试结果一致——ACK
        stubReceiver("zhangsan", 7L, "张三");
        stubApplyUser("lisi", "李四");
        stubTemplate("approval_remind", 2L);
        when(sendService.saveNotify(any(), anyString()))
                .thenThrow(new BusinessException(3045, "模板变量缺失"));

        assertThatCode(() -> consumer.doConsume(msg(arrivedMessage()))).doesNotThrowAnyException();
    }

    @Test
    void unknownNotifyType_thrownForRetry() throws Exception {
        ApprovalNotifyMessage message = arrivedMessage();
        message.setNotifyType("SOMETHING_ELSE");

        assertThatThrownBy(() -> consumer.doConsume(msg(message)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sendService, never()).saveNotify(any(), anyString());
    }

    @Test
    void unknownTerminalStatus_deterministicAck() throws Exception {
        stubReceiver("lisi", 8L, "李四");
        stubTemplate("approval_result", 3L);
        ApprovalNotifyMessage message = terminalMessage();
        message.setTerminalStatus("9");

        // terminalStatus 未知=契约违例但重试无意义（消息体不会变）——确定性 ACK
        assertThatCode(() -> consumer.doConsume(msg(message))).doesNotThrowAnyException();
        verify(sendService, never()).saveNotify(any(), anyString());
    }

    @Test
    void unparseableBody_thrownForRetry() {
        MessageExt bad = mock(MessageExt.class);
        lenient().when(bad.getKeys()).thenReturn("k");
        when(bad.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.doConsume(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedupGroup_matchesListenerAnnotationGroup() {
        // L1 去重表 group 列与 @RocketMQMessageListener 订阅组名必须同源（两处漂移=去重失效）
        RocketMQMessageListener annotation = ApprovalNotifyConsumer.class
                .getAnnotation(RocketMQMessageListener.class);
        assertThat(annotation.consumerGroup()).isEqualTo(consumer.dedupGroup());
        assertThat(consumer.dedupGroup()).isEqualTo("g_system_notify");
    }

    // ---- 脚手架 ----

    private void assertResultText(String terminalStatus, String expected) throws Exception {
        ApprovalNotifyMessage message = terminalMessage();
        message.setTerminalStatus(terminalStatus);
        consumer.doConsume(msg(message));
        ArgumentCaptor<NotifySendRequest> captor = ArgumentCaptor.forClass(NotifySendRequest.class);
        // 累计验证：取最近一次调用的变量集断言 resultText 映射
        verify(sendService, org.mockito.Mockito.atLeastOnce()).saveNotify(captor.capture(), anyString());
        assertThat(captor.getValue().getVariables()).containsEntry("resultText", expected);
    }

    private void stubReceiver(String account, Long id, String nickname) {
        when(userMapper.findByAccount(account)).thenReturn(user(account, id, nickname));
    }

    private void stubApplyUser(String account, String nickname) {
        lenient().when(userMapper.findByAccount(account)).thenReturn(user(account, 9L, nickname));
    }

    private void stubTemplate(String code, Long id) {
        SysNotifyTemplate template = new SysNotifyTemplate();
        template.setId(id);
        template.setCode(code);
        when(templateMapper.findByCode(code)).thenReturn(template);
    }

    private SysUser user(String account, Long id, String nickname) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setAccount(account);
        user.setNickname(nickname);
        user.setStatus(SysUser.StatusEnum.NORMAL.getCode());
        return user;
    }

    private ApprovalNotifyMessage arrivedMessage() {
        ApprovalNotifyMessage message = baseMessage("TASK_ARRIVED");
        message.setReceiverAccount("zhangsan");
        message.setTaskId("1025");
        message.setTaskName("审批");
        return message;
    }

    private ApprovalNotifyMessage terminalMessage() {
        ApprovalNotifyMessage message = baseMessage("TERMINAL_RESULT");
        message.setReceiverAccount("lisi");
        message.setTerminalStatus("1");
        return message;
    }

    private ApprovalNotifyMessage baseMessage(String notifyType) {
        ApprovalNotifyMessage message = new ApprovalNotifyMessage();
        message.setNotifyType(notifyType);
        message.setApprovalId("12");
        message.setBusinessType("leave");
        message.setBusinessKey("5");
        message.setTitle("张三的请假申请");
        message.setBusinessTypeName("请假申请");
        message.setApplyUser("lisi");
        message.setOccurredAt("2026-11-08 15:00:00");
        return message;
    }

    private MessageExt msg(ApprovalNotifyMessage message) {
        try {
            MessageExt msg = mock(MessageExt.class);
            lenient().when(msg.getKeys()).thenReturn("12:1025:TASK_ARRIVED");
            when(msg.getBody()).thenReturn(new ObjectMapper().writeValueAsBytes(message));
            return msg;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
