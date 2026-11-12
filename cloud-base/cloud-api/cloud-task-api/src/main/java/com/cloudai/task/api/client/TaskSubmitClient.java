package com.cloudai.task.api.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.api.domain.TaskSubmitResult;
import com.cloudai.task.api.fallback.TaskSubmitClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * cloud-task 任务提交客户端（契约 2026-11-10-task-center-api §2：POST /inner/task/submit）。
 * inner 面共享密钥由 cloud-common-inner-starter Feign 拦截器自动携带；
 * 降级返回 R.fail(msg)，消费方按 code != 200 分支透出 msg。
 */
@FeignClient(name = "cloud-task", contextId = "taskSubmitClient", path = "/inner/task",
        fallbackFactory = TaskSubmitClientFallbackFactory.class)
public interface TaskSubmitClient {

    /** 提交异步任务（成功返回 taskId；类型未注册 6001 / 参数非法 6002 / 幂等键冲突 6003） */
    @PostMapping("/submit")
    R<TaskSubmitResult> submit(@RequestBody TaskSubmitRequest request);
}
