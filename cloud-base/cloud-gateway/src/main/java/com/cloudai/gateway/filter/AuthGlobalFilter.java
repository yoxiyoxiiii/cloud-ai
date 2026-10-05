package com.cloudai.gateway.filter;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.util.JwtUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关鉴权：白名单放行；其余验签 JWT + Redis 在线检查（注销/强退立即生效）；
 * 通过后剥离外部 X-User-* 伪造 header 并注入真实用户信息透传下游。
 * 401 返回统一 R JSON（网关层使用真实 HTTP 401 状态，与服务层 HTTP200+body.code 约定并存）。
 * 注：Redis 为同步调用（内网低延迟，MVP 取舍；高并发场景改 reactive 或本地缓存）。
 */
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 前缀白名单：登录/刷新、demo、inner 屏蔽（SetStatus 路由接管）、网关自身监控 */
    private static final List<String> WHITELIST = List.of(
            "/sso/auth/login", "/sso/auth/refresh",
            "/sso/demo", "/system/demo", "/bpmn/demo",
            "/sso/inner", "/system/inner", "/bpmn/inner",
            "/actuator");

    private final StringRedisTemplate stringRedisTemplate;

    /** 忽略未知字段（会话 JSON 含网关不需的字段；值格式见 SecurityConstants.ONLINE_KEY_PREFIX 契约） */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${cloud.jwt.secret}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // 白名单：剥离外部 X-User-* 后放行
        ServerWebExchange safeExchange = stripUserHeaders(exchange);
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(safeExchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        Claims claims;
        try {
            claims = JwtUtil.parseToken(secret, authorization.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            // IllegalArgumentException：jjwt 对空串等非法输入抛出（非 JwtException 子类）
            return unauthorized(exchange);
        }
        // 读在线会话原始 JSON（StringRedisTemplate 规避 @class 反序列化；null=注销/强退/过期 → 401）
        String json = stringRedisTemplate.opsForValue().get(SecurityConstants.ONLINE_KEY_PREFIX + claims.getId());
        if (json == null) {
            return unauthorized(exchange);
        }
        List<String> perms = List.of();
        try {
            OnlineSessionView session = objectMapper.readValue(json, OnlineSessionView.class);
            if (session != null && session.getPermissions() != null) {
                perms = session.getPermissions();
            }
        } catch (JsonProcessingException e) {
            // 会话值损坏按无权限处理（下游 @PreAuthorize 会拒绝）
        }
        ServerWebExchange authed = withUserHeaders(safeExchange,
                claims.getSubject(), claims.get("account", String.class), String.join(SecurityConstants.PERMS_SEPARATOR, perms));
        return chain.filter(authed);
    }

    private ServerWebExchange stripUserHeaders(ServerWebExchange exchange) {
        return exchange.mutate().request(builder -> builder
                .headers(headers -> {
                    headers.remove(SecurityConstants.HEADER_USER_ID);
                    headers.remove(SecurityConstants.HEADER_USER_ACCOUNT);
                    headers.remove(SecurityConstants.HEADER_USER_PERMS);
                })).build();
    }

    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, String userId,
                                              String account, String perms) {
        return exchange.mutate().request(builder -> builder
                .headers(headers -> {
                    headers.set(SecurityConstants.HEADER_USER_ID, userId);
                    headers.set(SecurityConstants.HEADER_USER_ACCOUNT, account == null ? "" : account);
                    headers.set(SecurityConstants.HEADER_USER_PERMS, perms);
                })).build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(R.fail(401, "认证失败或未登录"));
        } catch (JsonProcessingException e) {
            body = "{\"code\":401,\"msg\":\"认证失败或未登录\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }

    /** 网关侧在线会话投影（只取权限字段；@class 头与其余字段被 ObjectMapper 忽略） */
    @lombok.Data
    public static class OnlineSessionView {
        private List<String> permissions;
    }
}
