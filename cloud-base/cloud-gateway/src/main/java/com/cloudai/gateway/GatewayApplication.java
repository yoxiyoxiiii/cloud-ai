package com.cloudai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;

/**
 * 排除 ReactiveUserDetailsServiceAutoConfiguration：
 * security-starter 传递带入 spring-boot-starter-security（网关取 JwtUtil/常量），
 * 该自动配置在无用户存储时生成随机密码并打日志（噪音）；网关不做 HTTP Basic 认证。
 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
