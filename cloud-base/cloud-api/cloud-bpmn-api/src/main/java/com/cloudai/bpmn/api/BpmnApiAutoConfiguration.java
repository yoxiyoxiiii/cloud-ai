package com.cloudai.bpmn.api;

import com.cloudai.bpmn.api.fallback.BpmnApprovalClientFallbackFactory;
import com.cloudai.bpmn.api.fallback.BpmnWorkbenchClientFallbackFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * cloud-bpmn-api 自动装配：注册降级工厂 bean——api 包不在消费方组件扫描范围，
 * @FeignClient(fallbackFactory=...) 要求其为 Spring bean（引 jar 即生效，零配置）。
 * circuitbreaker 开关关闭时 bean 空闲无害（不会被调用）。
 */
@AutoConfiguration
public class BpmnApiAutoConfiguration {

    @Bean
    public BpmnApprovalClientFallbackFactory bpmnApprovalClientFallbackFactory() {
        return new BpmnApprovalClientFallbackFactory();
    }

    /** 工作台查询客户端降级（契约 2026-11-14-aggregate-workbench-api §7：聚合服务段调用） */
    @Bean
    public BpmnWorkbenchClientFallbackFactory bpmnWorkbenchClientFallbackFactory() {
        return new BpmnWorkbenchClientFallbackFactory();
    }
}
