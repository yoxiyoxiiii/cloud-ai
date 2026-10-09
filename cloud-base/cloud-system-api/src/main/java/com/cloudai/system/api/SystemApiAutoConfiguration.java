package com.cloudai.system.api;

import com.cloudai.system.api.fallback.SystemUserClientFallbackFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * cloud-system-api 自动装配：注册降级工厂 bean——api 包不在消费方组件扫描范围，
 * @FeignClient(fallbackFactory=...) 要求其为 Spring bean（引 jar 即生效，零配置）。
 * circuitbreaker 开关关闭时 bean 空闲无害（不会被调用）。
 */
@AutoConfiguration
public class SystemApiAutoConfiguration {

    @Bean
    public SystemUserClientFallbackFactory systemUserClientFallbackFactory() {
        return new SystemUserClientFallbackFactory();
    }
}
