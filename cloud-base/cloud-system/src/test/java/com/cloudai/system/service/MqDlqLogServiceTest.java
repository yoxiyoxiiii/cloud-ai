package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.system.config.CloudSystemMqDlqProperties;
import com.cloudai.system.entity.MqDlqLog;
import com.cloudai.system.mapper.MqDlqLogMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信治理服务单测（契约 2026-10-11-mq-dlq-governance-api §3.3-§3.5 / 设计 D6/D7）：
 * resend 六分支（3038/3040/3041/dedup 清行/3039/成功行流转）+ ignore/reset 状态机边界 +
 * watch 落库 DuplicateKey 吸收。
 */
@ExtendWith(MockitoExtension.class)
class MqDlqLogServiceTest {

    @Mock
    private MqDlqLogMapper dlqLogMapper;
    @Mock
    private ConsumeDedupDao consumeDedupDao;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Spy
    private CloudSystemMqDlqProperties properties = new CloudSystemMqDlqProperties();
    @InjectMocks
    private MqDlqLogService service;

    private MqDlqLog dlq(Long id, int status, int resendCount) {
        MqDlqLog dlq = new MqDlqLog();
        dlq.setId(id);
        dlq.setMsgId("msg-id-1");
        dlq.setBornTopic("TX_APPROVAL_CREATE");
        dlq.setBornGroup("g_bpmn_approval_create");
        dlq.setMsgKey("leave:e2e-dlq-1");
        dlq.setTag(null);
        dlq.setBody("{\"businessType\":\"leave\"}");
        dlq.setStatus(status);
        dlq.setResendCount(resendCount);
        return dlq;
    }

    @Test
    void findDetail_notFoundRejected() {
        when(dlqLogMapper.findDlqLogById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.findDetail(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信记录不存在");
    }

    @Test
    void updateResend_notFoundRejected() {
        when(dlqLogMapper.findDlqLogById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateResend(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信记录不存在");
    }

    @Test
    void updateResend_terminalStatusRejected() {
        when(dlqLogMapper.findDlqLogById(1L)).thenReturn(dlq(1L, MqDlqLog.StatusEnum.IGNORED.getCode(), 0));
        assertThatThrownBy(() -> service.updateResend(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信状态不允许该操作");
        when(dlqLogMapper.findDlqLogById(2L)).thenReturn(dlq(2L, MqDlqLog.StatusEnum.ABANDONED.getCode(), 5));
        assertThatThrownBy(() -> service.updateResend(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信状态不允许该操作");
        verify(consumeDedupDao, never()).delete(anyString(), anyString());
    }

    @Test
    void updateResend_limitExceededAutoAbandoned() {
        int max = properties.getResendMax();
        when(dlqLogMapper.findDlqLogById(1L)).thenReturn(
                dlq(1L, MqDlqLog.StatusEnum.RESENT.getCode(), max));
        assertThatThrownBy(() -> service.updateResend(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重投次数已达上限，已置为已放弃");
        // 自动置弃留痕（resend_count 保持不清零）后拒绝
        verify(dlqLogMapper).updateStatus(eq(1L), eq(MqDlqLog.StatusEnum.ABANDONED.getCode()),
                any());
        verify(consumeDedupDao, never()).delete(anyString(), anyString());
    }

    @Test
    void updateResend_sendFailedTranslated() {
        when(dlqLogMapper.findDlqLogById(1L)).thenReturn(dlq(1L, MqDlqLog.StatusEnum.PENDING.getCode(), 0));
        doThrow(new RuntimeException("broker unreachable")).when(rocketMQTemplate)
                .syncSend(anyString(), any(Message.class));
        assertThatThrownBy(() -> service.updateResend(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息重投发送失败");
        // 行状态不动（可重试）
        verify(dlqLogMapper, never()).updateResend(anyLong(), any());
    }

    @Test
    void updateResend_successClearsDedupSendsAndUpdatesRow() {
        MqDlqLog dlq = dlq(1L, MqDlqLog.StatusEnum.RESENT.getCode(), 2);
        dlq.setTag("CREATE_RESULT");
        dlq.setTxNo("tx-no-1");
        when(dlqLogMapper.findDlqLogById(1L)).thenReturn(dlq);
        assertThatCode(() -> service.updateResend(1L)).doesNotThrowAnyException();
        // ② 清 dedup 残留行（born_group, msg_key）
        verify(consumeDedupDao).delete("g_bpmn_approval_create", "leave:e2e-dlq-1");
        // ③ syncSend 原 topic（tag 拼接 destination）+ 成功后行流转
        verify(rocketMQTemplate).syncSend(eq("TX_APPROVAL_CREATE:CREATE_RESULT"), any(Message.class));
        verify(dlqLogMapper).updateResend(eq(1L), any());
    }

    @Test
    void updateIgnore_onlyPendingAllowed() {
        when(dlqLogMapper.findDlqLogById(1L)).thenReturn(dlq(1L, MqDlqLog.StatusEnum.RESENT.getCode(), 1));
        assertThatThrownBy(() -> service.updateIgnore(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信状态不允许该操作");
        verify(dlqLogMapper, never()).updateStatus(anyLong(), anyInt(), any());

        when(dlqLogMapper.findDlqLogById(2L)).thenReturn(dlq(2L, MqDlqLog.StatusEnum.PENDING.getCode(), 0));
        assertThatCode(() -> service.updateIgnore(2L)).doesNotThrowAnyException();
        verify(dlqLogMapper).updateStatus(eq(2L), eq(MqDlqLog.StatusEnum.IGNORED.getCode()), any());
    }

    @Test
    void updateReset_onlyTerminalAllowed() {
        when(dlqLogMapper.findDlqLogById(1L)).thenReturn(dlq(1L, MqDlqLog.StatusEnum.PENDING.getCode(), 0));
        assertThatThrownBy(() -> service.updateReset(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("死信状态不允许该操作");
        verify(dlqLogMapper, never()).updateReset(anyLong(), any());

        when(dlqLogMapper.findDlqLogById(2L)).thenReturn(dlq(2L, MqDlqLog.StatusEnum.IGNORED.getCode(), 3));
        assertThatCode(() -> service.updateReset(2L)).doesNotThrowAnyException();
        verify(dlqLogMapper).updateReset(eq(2L), any());
    }

    @Test
    void saveWatched_duplicateAbsorbed() {
        when(dlqLogMapper.saveDlqLog(any(MqDlqLog.class)))
                .thenThrow(new DuplicateKeyException("uk_msg_id"));
        assertThatCode(() -> service.saveWatched(dlq(1L, MqDlqLog.StatusEnum.PENDING.getCode(), 0)))
                .doesNotThrowAnyException();
    }
}
