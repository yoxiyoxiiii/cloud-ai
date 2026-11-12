package com.cloudai.task;

import com.cloudai.system.api.client.NotifyClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 通用任务中心服务：业务服务经 inner Feign 提交异步任务（类型码+JSON 参数），
 * 本服务负责任务持久化、DB 轮询秒级认领、进程内 handler 执行、进度/结果落库、
 * 自动重试、超时兜底与失败站内信告警（契约 2026-11-10-task-center-api）。
 * NotifyClient 消费 cloud-system /inner/notify/send-by-code 发失败告警。
 */
@SpringBootApplication
@MapperScan("com.cloudai.task.mapper")
@EnableFeignClients(clients = {NotifyClient.class})
public class TaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskApplication.class, args);
    }
}
