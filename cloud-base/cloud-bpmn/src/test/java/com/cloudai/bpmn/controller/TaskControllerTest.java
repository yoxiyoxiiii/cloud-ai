package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.service.LeaveWorkflowService;
import com.cloudai.bpmn.service.TaskAppService;
import com.cloudai.bpmn.vo.TaskVo;
import com.cloudai.common.core.domain.LoginUser;
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
 * 任务端点委托单测（契约 2026-10-07-bpmn-leave-api §3）：assignee 恒取当前登录人。
 */
@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock
    private TaskAppService taskAppService;
    @Mock
    private LeaveWorkflowService workflowService;
    @InjectMocks
    private TaskController controller;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void todo_delegatesWithCurrentAccount() {
        loginAs("admin");
        TaskVo vo = new TaskVo();
        vo.setTaskId("t-1");
        when(taskAppService.listTodo("admin")).thenReturn(List.of(vo));

        R<List<TaskVo>> result = controller.todo();

        verify(taskAppService).listTodo("admin");
        assertThat(result.getData()).extracting(TaskVo::getTaskId).containsExactly("t-1");
    }

    @Test
    void done_delegatesWithCurrentAccount() {
        loginAs("admin");
        when(taskAppService.listDone("admin")).thenReturn(List.of());

        R<List<com.cloudai.bpmn.vo.TaskDoneVo>> result = controller.done();

        verify(taskAppService).listDone("admin");
        assertThat(result.getData()).isEmpty();
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
