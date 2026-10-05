package com.cloudai.common.security.filter;

import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.domain.LoginUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class HeaderAuthFilterTest {

    private final HeaderAuthFilter filter = new HeaderAuthFilter();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void buildsAuthenticationFromHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "1");
        request.addHeader(SecurityConstants.HEADER_USER_ACCOUNT, "admin");
        request.addHeader(SecurityConstants.HEADER_USER_PERMS, "system:user:list,system:user:add");

        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            captured.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = captured.get();
        assertThat(auth).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        LoginUser user = (LoginUser) auth.getPrincipal();
        assertThat(user.getUserId()).isEqualTo(1L);
        assertThat(user.getAccount()).isEqualTo("admin");
        assertThat(user.getPermissions()).containsExactly("system:user:list", "system:user:add");
        assertThat(new ArrayList<GrantedAuthority>(auth.getAuthorities()))
                .contains(new SimpleGrantedAuthority("system:user:list"), new SimpleGrantedAuthority("system:user:add"));
    }

    @Test
    void noHeadersRemainsAnonymous() throws Exception {
        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            captured.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(captured.get()).isNull();
    }

    @Test
    void malformedUserIdTreatedAsAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "abc");
        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            captured.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(captured.get()).isNull();
    }

    @Test
    void contextClearedAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "1");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
