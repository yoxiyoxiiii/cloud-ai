package com.cloudai.gateway.filter;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
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
import java.util.stream.Collectors;

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

    /** 前缀白名单：登录/刷新、demo、inner 与 MCP 屏蔽（SetStatus 路由接管）、网关自身监控 */
    private static final List<String> WHITELIST = List.of(
            "/sso/auth/login", "/sso/auth/refresh",
            "/sso/demo", "/system/demo", "/bpmn/demo",
            "/sso/inner", "/system/inner", "/bpmn/inner",
            "/system/mcp",
            "/actuator"
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    /** 忽略未知字段（会话 JSON 含网关不需的字段；值格式见 SecurityConstants.ONLINE_KEY_PREFIX 契约） */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // 白名单：剥离外部 X-User-* 后放行
        ServerWebExchange safeExchange = stripUserHeaders(exchange);
        // 放行白名单
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(safeExchange);
        }

        // 获取用户token
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        Claims claims;
        try {
            // 解析token
            claims = JwtUtil.parseToken(jwtProperties.getSecret(), authorization.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            // IllegalArgumentException：jjwt 对空串等非法输入抛出（非 JwtException 子类）
            return unauthorized(exchange);
        }
        // 读在线会话原始 JSON（StringRedisTemplate 规避 @class 反序列化；null=注销/强退/过期 → 401）
        // redis 获取登录用户信息
        String json = stringRedisTemplate.opsForValue().get(SecurityConstants.ONLINE_KEY_PREFIX + claims.getId());
        if (json == null) {
            return unauthorized(exchange);
        }

        // 权限数据
        List<String> perms = List.of();
        List<Long> roleIds = null;
        List<Long> deptIds = null;
        try {
            OnlineSessionView session = objectMapper.readValue(json, OnlineSessionView.class);
            if (session != null) {
                if (session.getPermissions() != null) {
                    perms = session.getPermissions();
                }
                roleIds = session.getRoleIds();
                deptIds = session.getDeptIds();
            }
        } catch (JsonProcessingException e) {
            // 会话值损坏按无权限处理（下游 @PreAuthorize 会拒绝）
        }
        UserHeaderValues values = new UserHeaderValues(claims.getSubject(),
                claims.get("account", String.class),
                String.join(SecurityConstants.PERMS_SEPARATOR, perms),
                joinIds(roleIds), joinIds(deptIds));
        ServerWebExchange authed = withUserHeaders(safeExchange, values);
        return chain.filter(authed);
    }

    private ServerWebExchange stripUserHeaders(ServerWebExchange exchange) {
        return exchange.mutate().request(builder -> builder
                .headers(headers -> {
                    headers.remove(SecurityConstants.HEADER_USER_ID);
                    headers.remove(SecurityConstants.HEADER_USER_ACCOUNT);
                    headers.remove(SecurityConstants.HEADER_USER_PERMS);
                    headers.remove(SecurityConstants.HEADER_USER_ROLE_IDS);
                    headers.remove(SecurityConstants.HEADER_USER_DEPT_IDS);
                })).build();
    }

    // 通过请求头，透传到下游服务
    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, UserHeaderValues values) {
        return exchange.mutate().request(builder -> builder
                .headers(headers -> {
                    headers.set(SecurityConstants.HEADER_USER_ID, values.userId());
                    headers.set(SecurityConstants.HEADER_USER_ACCOUNT, values.account() == null ? "" : values.account());
                    headers.set(SecurityConstants.HEADER_USER_PERMS, values.perms());
                    headers.set(SecurityConstants.HEADER_USER_ROLE_IDS, values.roleIds());
                    headers.set(SecurityConstants.HEADER_USER_DEPT_IDS, values.deptIds());
                })).build();
    }

    /** id 列表转逗号分隔串；null/空表（无角色/无部门/旧格式会话缺字段）注入空串，消费侧解析为空列表 */
    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(SecurityConstants.PERMS_SEPARATOR));
    }

    /** 注入下游的身份头值聚合（五个 X-User-* 头的字符串形态；方法入参超 3 个须封装对象） */
    private record UserHeaderValues(String userId, String account, String perms,
                                    String roleIds, String deptIds) {
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

    /** 网关侧在线会话投影（只取权限/角色/部门字段；@class 头与其余字段被 ObjectMapper 忽略；旧会话缺新字段反序列化为 null → 注入空串） */
    @lombok.Data
    public static class OnlineSessionView {
        private List<String> permissions;
        private List<Long> roleIds;
        private List<Long> deptIds;
    }
}
