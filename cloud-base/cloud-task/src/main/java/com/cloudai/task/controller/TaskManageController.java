package com.cloudai.task.controller;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.task.dto.TaskPageQuery;
import com.cloudai.task.service.TaskManageService;
import com.cloudai.task.vo.TaskDetailVo;
import com.cloudai.task.vo.TaskVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务管理面（契约 2026-11-10-task-center-api §4）。
 * 网关 /task/** StripPrefix=1 后落到本服务根路径——方法级路径不带服务前缀。
 */
@RestController
@RequiredArgsConstructor
public class TaskManageController {

    private final TaskManageService manageService;

    /** 分页查询（状态/类型/提交人筛选，含终态全生命周期） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('task:asyncTask:list')")
    public R<PageResult<TaskVo>> page(TaskPageQuery query) {
        PageResult<TaskVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 任务详情（含 params/result 执行明细） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('task:asyncTask:list')")
    public R<TaskDetailVo> detail(@PathVariable("id") Long id) {
        TaskDetailVo detail = manageService.findById(id);
        return R.ok(detail);
    }

    /** 取消任务：排队中直接终态取消；执行中置协作取消标记（handler 检查点生效） */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('task:asyncTask:cancel')")
    public R<Void> cancel(@PathVariable("id") Long id) {
        manageService.cancel(id);
        return R.ok();
    }

    /** 手动重试：仅失败/已取消可重试，回排队重新执行（retry_count+1） */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('task:asyncTask:retry')")
    public R<Void> retry(@PathVariable("id") Long id) {
        manageService.retry(id);
        return R.ok();
    }
}
