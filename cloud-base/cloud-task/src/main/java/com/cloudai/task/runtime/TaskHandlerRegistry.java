package com.cloudai.task.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务处理器注册表（设计 §5.3）：容器就绪后收集全部 @TaskHandler bean 建 type→handler 表。
 * 重复 type、注解值与 taskType() 不一致均启动即失败（fail-fast）；提交校验（6001）用 containsType。
 * 跨语言派发演进预留：未来远程执行器分支在本入口扩展（type→HTTP 端点注册表），提交方与任务表零改动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskHandlerRegistry implements SmartInitializingSingleton {

    private final List<TaskHandler> discovered;

    private final Map<String, TaskHandler> handlers = new HashMap<>();

    @Override
    public void afterSingletonsInstantiated() {
        for (TaskHandler handler : discovered) {
            String type = handler.taskType();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("任务处理器类型码为空: " + handler.getClass().getName());
            }
            com.cloudai.task.runtime.annotation.TaskHandler annotation =
                    handler.getClass().getAnnotation(com.cloudai.task.runtime.annotation.TaskHandler.class);
            if (annotation == null || annotation.value().isBlank() || !type.equals(annotation.value())) {
                throw new IllegalStateException("任务处理器注解缺失或与 taskType() 不一致: "
                        + handler.getClass().getName() + " type=" + type);
            }
            if (handlers.containsKey(type)) {
                throw new IllegalStateException("重复任务类型注册: " + type + " ("
                        + handlers.get(type).getClass().getName() + " 与 " + handler.getClass().getName() + ")");
            }
            handlers.put(type, handler);
        }
        log.info("任务处理器注册完成: {}", handlers.keySet());
    }

    /** 类型是否已注册（提交面 6001 校验依据） */
    public boolean containsType(String taskType) {
        return taskType != null && handlers.containsKey(taskType);
    }

    /** 按类型取处理器；未注册属提交面漏检（提交时已挡），此处兜底抛出 */
    public TaskHandler get(String taskType) {
        TaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            throw new IllegalStateException("任务类型未注册: " + taskType);
        }
        return handler;
    }
}
