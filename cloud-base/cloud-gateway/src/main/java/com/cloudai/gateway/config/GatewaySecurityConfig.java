package com.cloudai.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 网关自身的响应式安全链显式放行：网关鉴权由 AuthGlobalFilter 承担（JWT 验签 + Redis 在线检查 + X-User-* 透传）。
 * 背景：依赖 security-starter（取 JwtUtil/常量）传递带入 spring-boot-starter-security，
 * Boot 的 ReactiveSecurityAutoConfiguration 会激活默认链（Basic + CSRF 全量拦截），必须显式覆盖。
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }
}
