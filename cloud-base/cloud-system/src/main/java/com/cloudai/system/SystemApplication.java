package com.cloudai.system;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.task.api.client.TaskSubmitClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 系统管理服务（RBAC + 字典 + 请假业务台账）。
 * 审批平台化（2026-10-08）：请假迁入本服务，审批编排经 Feign 走 cloud-bpmn /inner/approval；
 * 声明统一归位 cloud-bpmn-api（2026-10-09），显式 clients 接入（fallbackFactory 等价降级）。
 * 任务中心（2026-11-10）：演示任务提交经 TaskSubmitClient 走 cloud-task /inner/task。
 */
@SpringBootApplication
@MapperScan("com.cloudai.system.mapper")
@EnableFeignClients(clients = {BpmnApprovalClient.class, TaskSubmitClient.class})
public class SystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
