package com.cloudai.task.alert;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.NotifyClient;
import com.cloudai.system.api.domain.NotifySendResult;
import com.cloudai.system.api.domain.SendByCodeRequest;
import com.cloudai.task.entity.AsyncTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 失败告警链单测（契约 §11）：模板 code/收件维度/变量三元组/linkUrl 口径钉、
 * reason 截断、软失败三分支（降级 R / 调用异常 / null 响应）不外抛。
 */
@ExtendWith(MockitoExtension.class)
class TaskAlertServiceTest {

    @Mock
    private NotifyClient notifyClient;

    @InjectMocks
    private TaskAlertService service;

    private AsyncTask task() {
        AsyncTask task = new AsyncTask();
        task.setId(77L);
        task.setTaskType("demo-batch");
        return task;
    }

    @Test
    void sendsAlertWithContractPayload() {
        when(notifyClient.sendByCode(any())).thenReturn(R.ok(new NotifySendResult()));
        service.alertTaskFailed(task(), "boom");
        ArgumentCaptor<SendByCodeRequest> captor = ArgumentCaptor.forClass(SendByCodeRequest.class);
        org.mockito.Mockito.verify(notifyClient).sendByCode(captor.capture());
        SendByCodeRequest req = captor.getValue();
        assertThat(req.getTemplateCode()).isEqualTo("task_fail_alert");
        assertThat(req.getReceiverType()).isEqualTo(2);
        assertThat(req.getRoleIds()).containsExactly(1L);
        assertThat(req.getLinkUrl()).isEqualTo("/task/async");
        assertThat(req.getVariables())
                .containsEntry("taskType", "demo-batch")
                .containsEntry("taskId", "77")
                .containsEntry("reason", "boom");
    }

    /** 超长 reason 截断（防模板渲染后超消息列宽触发 3046） */
    @Test
    void truncatesLongReason() {
        when(notifyClient.sendByCode(any())).thenReturn(R.ok(new NotifySendResult()));
        String longReason = "x".repeat(500);
        service.alertTaskFailed(task(), longReason);
        ArgumentCaptor<SendByCodeRequest> captor = ArgumentCaptor.forClass(SendByCodeRequest.class);
        org.mockito.Mockito.verify(notifyClient).sendByCode(captor.capture());
        assertThat(captor.getValue().getVariables().get("reason")).hasSize(200);
    }

    @Test
    void degradedResponse_swallowed() {
        when(notifyClient.sendByCode(any())).thenReturn(R.fail("cloud-system 服务不可用"));
        assertThatCode(() -> service.alertTaskFailed(task(), "boom")).doesNotThrowAnyException();
    }

    @Test
    void feignException_swallowed() {
        when(notifyClient.sendByCode(any())).thenThrow(new RuntimeException("connect refused"));
        assertThatCode(() -> service.alertTaskFailed(task(), "boom")).doesNotThrowAnyException();
    }

    @Test
    void nullResponse_swallowed() {
        when(notifyClient.sendByCode(any())).thenReturn(null);
        assertThatCode(() -> service.alertTaskFailed(task(), "boom")).doesNotThrowAnyException();
    }
}
