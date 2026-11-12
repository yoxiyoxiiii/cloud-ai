package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.NotifySendResult;
import com.cloudai.system.api.domain.SendByCodeRequest;
import com.cloudai.system.service.NotifySendService;
import com.cloudai.system.vo.NotifySendResultVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按码发送 inner 端点单测（契约 2026-11-10-task-center-api §11）：
 * 委托 NotifySendService.sendByCode（code 解析/3044/发送全链在服务层）+ 出参映射 + operator 口径。
 */
@ExtendWith(MockitoExtension.class)
class NotifyInnerControllerTest {

    @Mock
    private NotifySendService notifySendService;

    @InjectMocks
    private NotifyInnerController controller;

    @Test
    void sendByCode_delegatesWithInnerOperatorAndMapsResult() {
        NotifySendResultVo sent = new NotifySendResultVo();
        sent.setMessageId(66L);
        sent.setReceiverCount(2);
        SendByCodeRequest request = new SendByCodeRequest();
        request.setTemplateCode("task_fail_alert");
        request.setReceiverType(2);
        request.setRoleIds(List.of(1L));
        request.setVariables(Map.of("taskType", "demo-batch"));
        request.setLinkUrl("/task/async");
        when(notifySendService.sendByCode(eq(request), eq("inner-notify"))).thenReturn(sent);

        R<NotifySendResult> resp = controller.sendByCode(request);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getMessageId()).isEqualTo(66L);
        assertThat(resp.getData().getReceiverCount()).isEqualTo(2);
        verify(notifySendService).sendByCode(eq(request), eq("inner-notify"));
    }
}
