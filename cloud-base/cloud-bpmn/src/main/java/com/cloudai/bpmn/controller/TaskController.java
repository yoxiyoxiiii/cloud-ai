package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.dto.TaskCompleteRequest;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.bpmn.service.TaskAppService;
import com.cloudai.bpmn.vo.TaskDoneVo;
import com.cloudai.bpmn.vo.TaskVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务端点（契约 2026-10-08-approval-platform-api §2.1/§2.2，网关前缀 /bpmn/task）。
 * 待办/已办 assignee 恒取当前登录人；列表分页（PageQuery 默认 1/10，total=引擎 count）。
 * @author jy-root
 */
@RestController
@RequestMapping("/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskAppService taskAppService;
    private final ApprovalWorkflowService workflowService;

    /** 待办分页（ACT_RU_TASK，任务创建时间倒序） */
    @GetMapping("/todo")
    @PreAuthorize("hasAuthority('bpmn:task:list')")
    public R<PageResult<TaskVo>> todo(PageQuery query) {
        PageResult<TaskVo> todos = taskAppService.pageListTodo(SecurityUtils.currentAccount(), query);
        return R.ok(todos);
    }

    /** 已办分页（ACT_HI_TASKINST finished，办理时间倒序） */
    @GetMapping("/done")
    @PreAuthorize("hasAuthority('bpmn:task:list')")
    public R<PageResult<TaskDoneVo>> done(PageQuery query) {
        PageResult<TaskDoneVo> done = taskAppService.pageListDone(SecurityUtils.currentAccount(), query);
        return R.ok(done);
    }

    /** 办理任务（意见落 ACT_HI_COMMENT + complete(approve) + 终态回写 bpmn_approval，同一事务） */
    @PostMapping("/complete")
    @PreAuthorize("hasAuthority('bpmn:task:complete')")
    public R<Void> complete(@Valid @RequestBody TaskCompleteRequest req) {
        workflowService.completeTask(req, SecurityUtils.currentAccount());
        return R.ok();
    }
}
