package com.cloudai.system.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.DemoTaskSubmitRequest;
import com.cloudai.task.api.client.TaskSubmitClient;
import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.api.domain.TaskSubmitResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 演示任务提交单测（契约 2026-11-10-task-center-api §7）：缺省归一、范围越界 6002、
 * 提交人取当前登录账号、远端错误码/降级 msg 原样透出、Feign 异常二层兜底。
 */
@ExtendWith(MockitoExtension.class)
class TaskDemoServiceTest {

    @Mock
    private TaskSubmitClient taskSubmitClient;

    @InjectMocks
    private TaskDemoService service;

    @BeforeAll
    static void setUpAccount() {
        // SecurityUtils.currentAccount 走 UsernamePasswordAuthenticationToken + LoginUser principal
        com.cloudai.common.core.domain.LoginUser loginUser = new com.cloudai.common.core.domain.LoginUser();
        loginUser.setAccount("admin");
        Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                loginUser, null, java.util.List.of());
        SecurityContext context = org.mockito.Mockito.mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterAll
    static void clearAccount() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submit_defaultsAndPayload() {
        TaskSubmitResult data = new TaskSubmitResult();
        data.setTaskId(9L);
        when(taskSubmitClient.submit(any())).thenReturn(R.ok(data));
        TaskSubmitResult result = service.submit(new DemoTaskSubmitRequest());
        assertThat(result.getTaskId()).isEqualTo(9L);

        ArgumentCaptor<TaskSubmitRequest> captor = ArgumentCaptor.forClass(TaskSubmitRequest.class);
        org.mockito.Mockito.verify(taskSubmitClient).submit(captor.capture());
        TaskSubmitRequest sent = captor.getValue();
        assertThat(sent.getTaskType()).isEqualTo("demo-batch");
        assertThat(sent.getParams()).isEqualTo("{\"total\":100,\"failPercent\":0}");
        assertThat(sent.getSubmitterAccount()).isEqualTo("admin");
        assertThat(sent.getSubmitSource()).isEqualTo("cloud-system");
        assertThat(sent.getBizKey()).isNull();
        assertThat(sent.getMaxRetries()).isNull();
        assertThat(sent.getTimeoutSeconds()).isNull();
    }

    @Test
    void submit_outOfRange_6002() {
        DemoTaskSubmitRequest req = new DemoTaskSubmitRequest();
        req.setTotal(10001);
        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6002);

        req.setTotal(100);
        req.setFailPercent(-1);
        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6002);
    }

    /** 远端业务错误码（如 6001/6003）原样透出——HTTP 200 + body code/msg */
    @Test
    void submit_remoteBizError_passthroughCodeAndMsg() {
        when(taskSubmitClient.submit(any())).thenReturn(R.fail(6001, "任务类型未注册"));
        assertThatThrownBy(() -> service.submit(new DemoTaskSubmitRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6001)
                .hasMessageContaining("任务类型未注册");
    }

    /** fallbackFactory 降级 R（1002 + msg）同样透出 */
    @Test
    void submit_degradedR_passthrough() {
        when(taskSubmitClient.submit(any())).thenReturn(R.fail("cloud-task 服务不可用"));
        assertThatThrownBy(() -> service.submit(new DemoTaskSubmitRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 1002)
                .hasMessageContaining("cloud-task 服务不可用");
    }

    /** 熔断未包装时的二层兜底（catch FeignException 路径） */
    @Test
    void submit_feignException_translatesToUnavailable() {
        when(taskSubmitClient.submit(any())).thenThrow(new RuntimeException("connect refused"));
        assertThatThrownBy(() -> service.submit(new DemoTaskSubmitRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 1002)
                .hasMessageContaining("cloud-task 服务不可用");
    }

    @Test
    void submit_nullRequest_usesDefaults() {
        when(taskSubmitClient.submit(any())).thenReturn(R.ok(new TaskSubmitResult()));
        service.submit(null);
        ArgumentCaptor<TaskSubmitRequest> captor = ArgumentCaptor.forClass(TaskSubmitRequest.class);
        org.mockito.Mockito.verify(taskSubmitClient).submit(captor.capture());
        assertThat(captor.getValue().getParams()).contains("\"total\":100");
    }
}
