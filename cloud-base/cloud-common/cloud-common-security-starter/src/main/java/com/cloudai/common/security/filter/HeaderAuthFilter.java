package com.cloudai.common.security.filter;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.security.constant.SecurityConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 从网关透传的 X-User-* header 构建认证上下文；无 header 即匿名；
 * userId 非数字视为无效（伪造防护兜底），整单按匿名处理。
 * 请求结束后清理 SecurityContext（无状态，线程池复用防串号）。
 * 信任前提：本过滤器信任 X-User-* 仅因网关已剥离外部同名 header；直连服务端口等同身份伪造入口。
 */
public class HeaderAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            buildAuthentication(request);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void buildAuthentication(HttpServletRequest request) {
        String userId = request.getHeader(SecurityConstants.HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            return;
        }
        Long uid;
        try {
            uid = Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            return;
        }
        LoginUser user = new LoginUser();
        user.setUserId(uid);
        user.setAccount(request.getHeader(SecurityConstants.HEADER_USER_ACCOUNT));
        String perms = request.getHeader(SecurityConstants.HEADER_USER_PERMS);
        List<String> permList = (perms == null || perms.isBlank())
                ? List.of()
                : Arrays.stream(perms.split(SecurityConstants.PERMS_SEPARATOR))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
        user.setPermissions(permList);
        var authorities = permList.stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
