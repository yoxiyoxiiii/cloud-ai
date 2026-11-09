package com.cloudai.common.inner.auth;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.inner.config.InnerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /inner 面入站准入过滤器（契约 2026-11-09-inner-access-secret-api §1）：
 * 请求头 X-Inner-Secret 与配置密钥常量时间比对（MessageDigest.isEqual，防时序侧信道），
 * 未携带 / 值不匹配 / 服务未配置三态一律 HTTP 401 + R JSON 统一报文。
 * fail-closed：密钥未配置时拒绝全部调用但不阻断启动——inner 端点在 system/bpmn 永远存在，
 * 没有「合法不保护」态，未配置只能按最严处理。
 * 纯拒绝闸门：不构建主体、不触碰 SecurityContext，通过的请求进入既有 spring security 链
 * （inner 调用方不发 X-User-*，主体保持匿名，端点行为零变化）。
 */
@Slf4j
@RequiredArgsConstructor
public class InnerAccessFilter implements Filter {

    public static final String HEADER_INNER_SECRET = "X-Inner-Secret";

    private final InnerProperties properties;

    /** 仅用于拒绝响应体序列化（R 结构无定制格式，独立实例不依赖宿主 ObjectMapper 配置） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String reason = denyReason(request.getHeader(HEADER_INNER_SECRET),
                properties.getSharedSecret());
        if (reason != null) {
            // 日志内部区分原因供运维排障；响应报文统一，不向调用方泄露校验进度
            log.warn("inner 面拒绝: uri={}, remoteAddr={}, 原因={}",
                    request.getRequestURI(), request.getRemoteAddr(), reason);
            deny(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 三态判定：null=放行；未配置判定优先于缺头（fail-closed 态任何请求都拒绝） */
    private String denyReason(String secret, String configured) {
        if (configured == null || configured.isEmpty()) {
            return "服务未配置 cloud.inner.shared-secret（fail-closed 拒绝全部）";
        }
        if (secret == null) {
            return "请求未携带 " + HEADER_INNER_SECRET;
        }
        if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8))) {
            return "密钥不匹配";
        }
        return null;
    }

    /** 拒绝响应：真实 HTTP 401 + R JSON（网关 AuthGlobalFilter / MCP McpAuthFilter 先例——认证拒绝用真实状态码） */
    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(R.fail(401, "inner 认证失败"));
        } catch (IOException e) {
            body = "{\"code\":401,\"msg\":\"inner 认证失败\"}".getBytes(StandardCharsets.UTF_8);
        }
        response.getOutputStream().write(body);
    }
}
