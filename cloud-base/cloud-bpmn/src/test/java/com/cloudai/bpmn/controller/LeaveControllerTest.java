package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.dto.LeaveCreateRequest;
import com.cloudai.bpmn.service.BpmnLeaveManageService;
import com.cloudai.bpmn.service.LeaveWorkflowService;
import com.cloudai.bpmn.vo.LeaveDetailVo;
import com.cloudai.bpmn.vo.LeaveVo;
import com.cloudai.bpmn.vo.UserOptionVo;
import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假单端点委托单测（契约 2026-10-07-bpmn-leave-api §2）：controller 只取当前登录人并转发，
 * 口径在 Service；申请人恒取信任链 X-User-Account（SecurityUtils），不接受传入。
 */
@ExtendWith(MockitoExtension.class)
class LeaveControllerTest {

    @Mock
    private LeaveWorkflowService workflowService;
    @Mock
    private BpmnLeaveManageService manageService;
    @InjectMocks
    private LeaveController controller;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void add_delegatesWithCurrentAccount() {
        loginAs("admin");
        LeaveCreateRequest req = new LeaveCreateRequest();
        when(workflowService.saveLeave(req, "admin")).thenReturn(7L);

        R<Long> result = controller.add(req);

        verify(workflowService).saveLeave(req, "admin");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo(7L);
    }

    @Test
    void page_delegatesWithCurrentAccount() {
        loginAs("userA");
        PageQuery query = new PageQuery();
        PageResult<LeaveVo> page = PageResult.of(0, List.of());
        when(manageService.pageListMy(query, "userA")).thenReturn(page);

        R<PageResult<LeaveVo>> result = controller.page(query);

        verify(manageService).pageListMy(query, "userA");
        assertThat(result.getData()).isSameAs(page);
    }

    @Test
    void detail_delegates() {
        LeaveDetailVo detail = new LeaveDetailVo();
        when(manageService.findById(5L)).thenReturn(detail);

        R<LeaveDetailVo> result = controller.detail(5L);

        verify(manageService).findById(5L);
        assertThat(result.getData()).isSameAs(detail);
    }

    @Test
    void cancel_delegatesWithCurrentAccount() {
        loginAs("userA");

        R<Void> result = controller.cancel(5L);

        verify(workflowService).cancelLeave(5L, "userA");
        assertThat(result.getCode()).isEqualTo(200);
    }

    @Test
    void approvers_delegates() {
        UserOptionVo option = new UserOptionVo();
        option.setAccount("admin");
        when(manageService.listApprovers()).thenReturn(List.of(option));

        R<List<UserOptionVo>> result = controller.approvers();

        verify(manageService).listApprovers();
        assertThat(result.getData()).extracting(UserOptionVo::getAccount).containsExactly("admin");
    }

    private void loginAs(String account) {
        LoginUser user = new LoginUser();
        user.setAccount(account);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
