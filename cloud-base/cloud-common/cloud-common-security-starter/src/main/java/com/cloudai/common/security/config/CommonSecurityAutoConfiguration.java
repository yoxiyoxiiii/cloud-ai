package com.cloudai.common.security.config;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.security.filter.HeaderAuthFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 资源端安全自动配置（仅 servlet 生效；网关是 WebFlux 自动退避）。
 * URL 级不做拦截（外部访问由网关白名单把门），方法级用 @PreAuthorize；
 * 未加注解的接口=匿名可达。@PreAuthorize 拒绝时 HTTP 200 + body code=403（全局约定）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HeaderAuthFilter headerAuthFilter() {
        return new HeaderAuthFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "cloudSecurityFilterChain")
    public SecurityFilterChain cloudSecurityFilterChain(HttpSecurity http, HeaderAuthFilter headerAuthFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(name = "securityExceptionAdvice")
    public SecurityExceptionAdvice securityExceptionAdvice() {
        return new SecurityExceptionAdvice();
    }

    /**
     * 方法级权限拒绝 → 统一 R 格式（HTTP 200 + code 403）。
     * 必须最高优先级：core-starter 的 GlobalExceptionHandler 有 Exception.class 兜底，
     * 无序时按 advice 字母序抢先，会把 AccessDeniedException 吞成 500。
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RestControllerAdvice
    public static class SecurityExceptionAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        public R<Void> handleAccessDenied(AccessDeniedException e) {
            return R.fail(ErrorCode.FORBIDDEN);
        }
    }
}
