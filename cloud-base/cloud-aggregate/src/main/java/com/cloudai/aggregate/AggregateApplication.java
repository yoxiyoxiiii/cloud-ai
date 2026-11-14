package com.cloudai.aggregate;

import com.cloudai.bpmn.api.client.BpmnWorkbenchClient;
import com.cloudai.system.api.client.NotifyUserClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 纯读聚合服务：一次请求并行编排多个下游外部用户面（工作台三段=我的待办/我的审批/未读通知摘要），
 * 段式失败状态（任一段失败不影响其它段，整体恒 HTTP 200 + code 200）。
 * 无数据库/无 Redis/无 xxl-job/无 MQ/无 /inner 面（设计 2026-11-14-aggregate-service-design §1）；
 * Feign 走 Nacos 注册发现直连下游，用户身份经出站拦截器透传（下游安全链照常把关）。
 */
@SpringBootApplication
@EnableFeignClients(clients = {BpmnWorkbenchClient.class, NotifyUserClient.class})
public class AggregateApplication {

    public static void main(String[] args) {
        SpringApplication.run(AggregateApplication.class, args);
    }
}
