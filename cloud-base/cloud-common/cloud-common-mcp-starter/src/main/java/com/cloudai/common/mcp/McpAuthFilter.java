package com.cloudai.common.mcp;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * MCP 面双主体认证（契约 2026-10-17-mcp-tools-api §1.2）：
 * 服务密钥 → JWT 验签 → Redis 在线检查，三关全过构建 McpPrincipal。
 * 拒绝=HTTP 401 + R JSON（真实状态码，网关 AuthGlobalFilter 401 先例）；身份不取任何明文 header（防伪造）。
 */
@Slf4j
@RequiredArgsConstructor
public class McpAuthFilter implements Filter {

    public static final String HEADER_CLIENT_SECRET = "X-MCP-Client-Secret";
    private static final String BEARER_PREFIX = "Bearer ";

    private final McpProperties mcpProperties;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate stringRedisTemplate;

    /** 在线会话投影：只取权限字段，未知字段忽略（同网关 AuthGlobalFilter 口径） */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        if (!clientSecretMatches(request)) {
            deny(response);
            return;
        }
        Claims claims = parseClaims(request);
        if (claims == null) {
            deny(response);
            return;
        }
        McpPrincipal principal = loadPrincipal(claims);
        if (principal == null) {
            deny(response);
            return;
        }
        request.setAttribute(McpPrincipal.ATTR, principal);
        chain.doFilter(request, response);
    }

    /** 第一关：服务密钥。未配置态拒绝全部；常量时间比较防时序侧信道 */
    private boolean clientSecretMatches(HttpServletRequest request) {
        String secret = request.getHeader(HEADER_CLIENT_SECRET);
        String configured = mcpProperties.getClientSecret();
        if (secret == null || configured == null || configured.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }

    /** 第二关：JWT 验签（sso 颁发同款 HS512）；伪造/过期统一 401 */
    private Claims parseClaims(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            return JwtUtil.parseToken(jwtProperties.getSecret(),
                    authorization.substring(BEARER_PREFIX.length()));
        } catch (Exception e) {
            log.warn("MCP JWT 验签失败: {}", e.getMessage());
            return null;
        }
    }

    /** 第三关：Redis 在线会话（注销/强退/过期即拦截）；subject 非数字按 401 兜底 */
    private McpPrincipal loadPrincipal(Claims claims) {
        String json = stringRedisTemplate.opsForValue()
                .get(SecurityConstants.ONLINE_KEY_PREFIX + claims.getId());
        if (json == null) {
            return null;
        }
        List<String> perms = List.of();
        try {
            OnlineView session = objectMapper.readValue(json, OnlineView.class);
            if (session != null && session.getPermissions() != null) {
                perms = session.getPermissions();
            }
        } catch (IOException e) {
            // 会话值损坏按无权限处理（工具层 guard 会拒绝）
            log.warn("MCP 在线会话值解析失败, jti={}", claims.getId());
        }
        try {
            return new McpPrincipal(
                    Long.valueOf(claims.getSubject()), claims.get("account", String.class), perms);
        } catch (NumberFormatException e) {
            log.warn("MCP JWT subject 非数字用户 id: {}", claims.getSubject());
            return null;
        }
    }

    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(R.fail(401, "MCP 认证失败"));
        } catch (IOException e) {
            body = "{\"code\":401,\"msg\":\"MCP 认证失败\"}".getBytes(StandardCharsets.UTF_8);
        }
        response.getOutputStream().write(body);
    }

    /** 在线会话 JSON 投影（Redis 会话契约：纯 JSON 无 @class） */
    @lombok.Data
    private static class OnlineView {
        private List<String> permissions;
    }
}
