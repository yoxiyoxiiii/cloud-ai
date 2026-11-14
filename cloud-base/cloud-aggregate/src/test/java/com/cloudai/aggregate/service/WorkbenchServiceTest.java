package com.cloudai.aggregate.service;

import com.cloudai.aggregate.security.AggregateUserContext;
import com.cloudai.aggregate.vo.WorkbenchVo;
import com.cloudai.bpmn.api.client.BpmnWorkbenchClient;
import com.cloudai.bpmn.api.domain.ApprovalVo;
import com.cloudai.bpmn.api.domain.TaskVo;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.system.api.client.NotifyUserClient;
import com.cloudai.system.api.domain.NotifySummaryVo;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 段状态转译表逐行（契约 §2）+ 段准入预判跳过 + 身份缺失 401 + 上下文清理。
 * mock Feign client 返回各形态 R，线程池用真实小池（并行路径真实执行）。
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchServiceTest {

    @Mock
    private BpmnWorkbenchClient bpmnWorkbenchClient;

    @Mock
    private NotifyUserClient notifyUserClient;

    private WorkbenchService service;

    @BeforeEach
    void setUp() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        service = new WorkbenchService(bpmnWorkbenchClient, notifyUserClient, executor);
    }

    @AfterEach
    void tearDown() {
        AggregateUserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /** 模拟网关注入五头的登录请求（perms 可空=无权限用户） */
    private void loginAs(String perms) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "1");
        request.addHeader(SecurityConstants.HEADER_USER_ACCOUNT, "admin");
        if (perms != null) {
            request.addHeader(SecurityConstants.HEADER_USER_PERMS, perms);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static NotifySummaryVo notifyVo(String unread, String latest) {
        NotifySummaryVo vo = new NotifySummaryVo();
        vo.setUnreadCount(unread);
        vo.setLatestId(latest);
        return vo;
    }

    @Test
    void all_downstream_ok_sections_ok_with_data() {
        loginAs("bpmn:task:list,bpmn:approval:list");
        when(bpmnWorkbenchClient.todo(1, 5))
                .thenReturn(R.ok(PageResult.of(12, List.of(new TaskVo()))));
        when(bpmnWorkbenchClient.page(1, 5))
                .thenReturn(R.ok(PageResult.of(31, List.of(new ApprovalVo()))));
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("3", "128")));

        WorkbenchVo vo = service.workbench();

        assertThat(vo.getTodo().getStatus()).isEqualTo(200);
        assertThat(vo.getTodo().getMsg()).isEqualTo("操作成功");
        assertThat(vo.getTodo().getData().getTotal()).isEqualTo(12);
        assertThat(vo.getTodo().getData().getRows()).hasSize(1);
        assertThat(vo.getApproval().getStatus()).isEqualTo(200);
        assertThat(vo.getApproval().getData().getTotal()).isEqualTo(31);
        assertThat(vo.getNotify().getStatus()).isEqualTo(200);
        assertThat(vo.getNotify().getData().getUnreadCount()).isEqualTo("3");
        assertThat(vo.getNotify().getData().getLatestId()).isEqualTo("128");
    }

    @Test
    void downstream_403_translates_to_7001_noPerm() {
        loginAs("bpmn:task:list,bpmn:approval:list");
        when(bpmnWorkbenchClient.todo(1, 5)).thenReturn(R.fail(403, "无操作权限"));
        when(bpmnWorkbenchClient.page(1, 5)).thenReturn(R.fail(403, "无操作权限"));
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("0", "0")));

        WorkbenchVo vo = service.workbench();

        assertThat(vo.getTodo().getStatus()).isEqualTo(7001);
        assertThat(vo.getTodo().getMsg()).isEqualTo("无操作权限");
        assertThat(vo.getTodo().getData()).isNull();
        assertThat(vo.getApproval().getStatus()).isEqualTo(7001);
        assertThat(vo.getApproval().getData()).isNull();
        assertThat(vo.getNotify().getStatus()).isEqualTo(200);
    }

    @Test
    void downstream_degraded_1002_translates_to_7002_with_downstream_msg() {
        loginAs("bpmn:task:list,bpmn:approval:list");
        when(bpmnWorkbenchClient.todo(1, 5)).thenReturn(R.fail(1002, "cloud-bpmn 服务不可用"));
        when(bpmnWorkbenchClient.page(1, 5)).thenReturn(R.fail(1002, "cloud-bpmn 服务不可用"));
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("0", "0")));

        WorkbenchVo vo = service.workbench();

        assertThat(vo.getTodo().getStatus()).isEqualTo(7002);
        assertThat(vo.getTodo().getMsg()).isEqualTo("cloud-bpmn 服务不可用");
        assertThat(vo.getTodo().getData()).isNull();
        assertThat(vo.getApproval().getStatus()).isEqualTo(7002);
    }

    @Test
    void downstream_other_code_translates_to_7002_with_downstream_msg() {
        loginAs("bpmn:task:list,bpmn:approval:list");
        when(bpmnWorkbenchClient.todo(1, 5)).thenReturn(R.fail(500, "系统异常，请稍后重试"));
        when(bpmnWorkbenchClient.page(1, 5)).thenReturn(R.fail(500, "系统异常，请稍后重试"));
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("0", "0")));

        WorkbenchVo vo = service.workbench();

        assertThat(vo.getTodo().getStatus()).isEqualTo(7002);
        assertThat(vo.getTodo().getMsg()).isEqualTo("系统异常，请稍后重试");
        assertThat(vo.getApproval().getStatus()).isEqualTo(7002);
    }

    @Test
    void feign_exception_translates_to_7002_fixed_msg_and_sections_isolated() {
        loginAs("bpmn:task:list,bpmn:approval:list");
        when(bpmnWorkbenchClient.todo(1, 5)).thenThrow(FeignException.class);
        when(bpmnWorkbenchClient.page(1, 5)).thenReturn(R.ok(PageResult.of(31, List.of(new ApprovalVo()))));
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("3", "128")));

        WorkbenchVo vo = service.workbench();

        assertThat(vo.getTodo().getStatus()).isEqualTo(7002);
        assertThat(vo.getTodo().getMsg()).isEqualTo("下游调用异常");
        assertThat(vo.getTodo().getData()).isNull();
        // 段间隔离：todo 段异常不拖累 approval / notify 段
        assertThat(vo.getApproval().getStatus()).isEqualTo(200);
        assertThat(vo.getNotify().getStatus()).isEqualTo(200);
    }

    @Test
    void no_bpmn_perms_precheck_skips_downstream_call() {
        loginAs("system:user:list");
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("0", "0")));

        WorkbenchVo vo = service.workbench();

        // 无 bpmn 权限：两段直接 7001，不发下游调用（省掉注定 403 的请求）
        assertThat(vo.getTodo().getStatus()).isEqualTo(7001);
        assertThat(vo.getTodo().getMsg()).isEqualTo("无操作权限");
        assertThat(vo.getTodo().getData()).isNull();
        assertThat(vo.getApproval().getStatus()).isEqualTo(7001);
        verify(bpmnWorkbenchClient, never()).todo(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(bpmnWorkbenchClient, never()).page(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        // notify 段恒发起（登录即可无 perms）
        assertThat(vo.getNotify().getStatus()).isEqualTo(200);
    }

    @Test
    void missing_user_identity_throws_401() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> service.workbench())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(401);
    }

    @Test
    void user_context_cleared_after_orchestration() {
        loginAs("bpmn:task:list,bpmn:approval:list");
        when(bpmnWorkbenchClient.todo(1, 5)).thenReturn(R.ok(PageResult.of(0, List.of())));
        when(bpmnWorkbenchClient.page(1, 5)).thenReturn(R.ok(PageResult.of(0, List.of())));
        when(notifyUserClient.summary()).thenReturn(R.ok(notifyVo("0", "0")));

        service.workbench();

        // 编排结束主线程上下文已清理（段任务 finally 各自清理，主线程 finally 兜底）——线程池复用防串号
        assertThat(AggregateUserContext.get()).isNull();
    }
}
