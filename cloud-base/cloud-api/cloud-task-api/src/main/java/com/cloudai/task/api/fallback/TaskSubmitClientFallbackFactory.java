package com.cloudai.task.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.task.api.client.TaskSubmitClient;
import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.api.domain.TaskSubmitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * TaskSubmitClient 降级工厂：cloud-task 不可用时返回 R.fail——
 * 消费方按 code != 200 分支透出 msg（契约 2026-11-10-task-center-api §2）。
 */
@Slf4j
public class TaskSubmitClientFallbackFactory implements FallbackFactory<TaskSubmitClient> {

    @Override
    public TaskSubmitClient create(Throwable cause) {
        return new TaskSubmitClient() {

            @Override
            public R<TaskSubmitResult> submit(TaskSubmitRequest request) {
                log.error("cloud-task 任务提交降级（submit）: taskType={}", request == null ? null : request.getTaskType(), cause);
                return R.fail("cloud-task 服务不可用");
            }
        };
    }
}
