package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.bpmn.service.TaskAppService;
import com.cloudai.bpmn.vo.TaskVo;
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
 * 任务端点委托单测（契约 2026-10-08-approval-platform-api §2.1/§2.2）：assignee 恒取当前登录人，
 * 分页参数透传 Service。
 */
@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock
    private TaskAppService taskAppService;
    @Mock
    private ApprovalWorkflowService workflowService;
    @InjectMocks
    private TaskController controller;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void todo_delegatesWithCurrentAccountAndPageQuery() {
        loginAs("admin");
        TaskVo vo = new TaskVo();
        vo.setTaskId("t-1");
        PageQuery query = new PageQuery();
        when(taskAppService.pageListTodo("admin", query)).thenReturn(PageResult.of(1L, List.of(vo)));

        R<PageResult<TaskVo>> result = controller.todo(query);

        verify(taskAppService).pageListTodo("admin", query);
        assertThat(result.getData().getTotal()).isEqualTo(1L);
        assertThat(result.getData().getRows()).extracting(TaskVo::getTaskId).containsExactly("t-1");
    }

    @Test
    void done_delegatesWithCurrentAccountAndPageQuery() {
        loginAs("admin");
        PageQuery query = new PageQuery();
        when(taskAppService.pageListDone("admin", query)).thenReturn(PageResult.of(0L, List.of()));

        R<PageResult<com.cloudai.bpmn.vo.TaskDoneVo>> result = controller.done(query);

        verify(taskAppService).pageListDone("admin", query);
        assertThat(result.getData().getRows()).isEmpty();
    }

    @Test
    void complete_delegatesWithCurrentAccount() {
        loginAs("admin");
        TaskCompleteRequest req = new TaskCompleteRequest();
        req.setTaskId("t-1");
        req.setApprove("true");

        R<Void> result = controller.complete(req);

        verify(workflowService).completeTask(req, "admin");
        assertThat(result.getCode()).isEqualTo(200);
    }

    private void loginAs(String account) {
        LoginUser user = new LoginUser();
        user.setAccount(account);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
