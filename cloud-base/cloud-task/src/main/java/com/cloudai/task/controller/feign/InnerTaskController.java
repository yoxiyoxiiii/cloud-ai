package com.cloudai.task.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.api.domain.TaskSubmitResult;
import com.cloudai.task.service.TaskSubmitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务提交面（服务间 inner，契约 §2）：cloud-common-inner-starter 共享密钥准入，
 * 网关屏蔽 /task/inner/**（外网不可达），无登录态——提交人由业务方显式传入。
 */
@RestController
@RequestMapping("/inner/task")
@RequiredArgsConstructor
public class InnerTaskController {

    private final TaskSubmitService submitService;

    /** 提交任务（校验链 6001/6002/6003 → 排队态落库），返回任务 ID */
    @PostMapping("/submit")
    public R<TaskSubmitResult> submit(@Valid @RequestBody TaskSubmitRequest request) {
        Long taskId = submitService.submit(request);
        TaskSubmitResult result = new TaskSubmitResult();
        result.setTaskId(taskId);
        return R.ok(result);
    }
}
