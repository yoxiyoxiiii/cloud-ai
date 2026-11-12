package com.cloudai.system.controller.manage;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.DemoTaskSubmitRequest;
import com.cloudai.system.service.TaskDemoService;
import com.cloudai.task.api.domain.TaskSubmitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示任务提交（契约 2026-11-10-task-center-api §7，网关 /system/task-demo/submit）。
 * 种子场景入口：模拟业务模块经 Feign 向任务中心提交批量任务。
 */
@RestController
@RequestMapping("/task-demo")
@RequiredArgsConstructor
public class TaskDemoController {

    private final TaskDemoService demoService;

    /** 提交演示任务（total/failPercent 可缺省；返回新任务 ID） */
    @PostMapping("/submit")
    @PreAuthorize("hasAuthority('task:demo:submit')")
    public R<TaskSubmitResult> submit(@RequestBody(required = false) DemoTaskSubmitRequest req) {
        TaskSubmitResult result = demoService.submit(req);
        return R.ok(result);
    }
}
