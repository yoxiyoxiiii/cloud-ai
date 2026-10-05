package com.cloudai.sso.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.sso.client.SystemUserClient;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.sso.dto.LoginUserDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final String TEST_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private SystemUserClient userClient;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @InjectMocks
    private TokenService tokenService;

    private LoginUserDTO admin() {
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(1L);
        dto.setAccount("admin");
        dto.setPassword(new BCryptPasswordEncoder().encode("admin123"));
        dto.setPermissions(List.of("system:user:list"));
        dto.setStatus(0);
        return dto;
    }

    /** 在线会话按新约定以纯 JSON 字符串存储（无 @class/集合类型包裹），网关 OnlineSessionView 才能解析 */
    private String sessionJson(OnlineSession session) {
        try {
            return JSON.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @InjectMocks 构造注入拿不到非 mock 的 JwtProperties（为 null），反射补上测试密钥与默认 TTL */
    private void injectSecret() throws Exception {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(TEST_SECRET);
        Field propsField = TokenService.class.getDeclaredField("jwtProperties");
        propsField.setAccessible(true);
        propsField.set(tokenService, jwtProperties);
    }

    @Test
    void login_successIssuesTokensAndWritesRedis() throws Exception {
        injectSecret();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(userClient.getUserByAccount("admin")).thenReturn(R.ok(admin()));
        LoginResult result = tokenService.login("admin", "admin123", "127.0.0.1");
        assertThat(result.getAccessToken()).isNotBlank();
        assertThat(result.getRefreshToken()).isNotBlank();
        assertThat(result.getExpiresIn()).isEqualTo(7200L);
        verify(valueOps).set(startsWith(SecurityConstants.ONLINE_KEY_PREFIX), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps).set(startsWith("sso:refresh:"), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void login_unknownAccountThrows() {
        when(userClient.getUserByAccount("ghost")).thenReturn(R.ok(null));
        assertThatThrownBy(() -> tokenService.login("ghost", "x", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void login_wrongPasswordThrows() {
        when(userClient.getUserByAccount("admin")).thenReturn(R.ok(admin()));
        assertThatThrownBy(() -> tokenService.login("admin", "wrong", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void login_disabledUserThrows() {
        LoginUserDTO dto = admin();
        dto.setStatus(1);
        when(userClient.getUserByAccount("admin")).thenReturn(R.ok(dto));
        assertThatThrownBy(() -> tokenService.login("admin", "admin123", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    void refresh_replacesTokensWithoutDeletingNewRefreshKey() throws Exception {
        injectSecret();
        when(stringRedisTemplate.keys("sso:refresh:*")).thenReturn(Set.of("sso:refresh:1"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sso:refresh:1"))
                .thenReturn("{\"tokenId\":\"jti-old\",\"token\":\"rt-old\"}");
        OnlineSession session = new OnlineSession();
        session.setTokenId("jti-old");
        session.setUserId(1L);
        session.setAccount("admin");
        session.setPermissions(List.of("p1"));
        session.setLoginTime(1L);
        session.setIp("127.0.0.1");
        when(valueOps.get("sso:online:jti-old")).thenReturn(sessionJson(session));
        LoginUserDTO fresh = admin();
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(fresh));

        LoginResult result = tokenService.refresh("rt-old");

        assertThat(result.getRefreshToken()).isNotBlank();
        assertThat(result.getAccessToken()).isNotBlank();
        verify(stringRedisTemplate).delete("sso:online:jti-old");
        verify(stringRedisTemplate, never()).delete("sso:refresh:1");
    }

    @Test
    void refresh_deletesOnlyBoundSession() throws Exception {
        injectSecret();
        when(stringRedisTemplate.keys("sso:refresh:*")).thenReturn(Set.of("sso:refresh:1"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sso:refresh:1"))
                .thenReturn("{\"tokenId\":\"jti-2\",\"token\":\"rt-2\"}");
        OnlineSession session2 = new OnlineSession();
        session2.setTokenId("jti-2");
        session2.setUserId(1L);
        session2.setAccount("admin");
        session2.setPermissions(List.of("p1"));
        session2.setLoginTime(1L);
        session2.setIp("1.2.3.4");
        when(valueOps.get("sso:online:jti-2")).thenReturn(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(session2));
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(admin()));

        tokenService.refresh("rt-2");

        verify(stringRedisTemplate).delete("sso:online:jti-2");   // 只删绑定会话
        verify(stringRedisTemplate, org.mockito.Mockito.never()).delete("sso:online:jti-1"); // 不误杀
    }

    @Test
    void refresh_disabledUserRejected() throws Exception {
        injectSecret();
        when(stringRedisTemplate.keys("sso:refresh:*")).thenReturn(Set.of("sso:refresh:1"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sso:refresh:1"))
                .thenReturn("{\"tokenId\":\"jti-old\",\"token\":\"rt-old\"}");
        OnlineSession session = new OnlineSession();
        session.setTokenId("jti-old");
        session.setUserId(1L);
        session.setAccount("admin");
        session.setLoginTime(1L);
        when(valueOps.get("sso:online:jti-old")).thenReturn(sessionJson(session));
        LoginUserDTO disabled = admin();
        disabled.setStatus(1);
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(disabled));
        assertThatThrownBy(() -> tokenService.refresh("rt-old"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话已失效");
        verify(stringRedisTemplate).delete("sso:online:jti-old");
    }

    @Test
    void refresh_usesLatestPermissions() throws Exception {
        injectSecret();
        when(stringRedisTemplate.keys("sso:refresh:*")).thenReturn(Set.of("sso:refresh:1"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("sso:refresh:1"))
                .thenReturn("{\"tokenId\":\"jti-old\",\"token\":\"rt-old\"}");
        OnlineSession session = new OnlineSession();
        session.setTokenId("jti-old");
        session.setUserId(1L);
        session.setAccount("admin");
        session.setPermissions(List.of("old:perm"));
        session.setLoginTime(1L);
        session.setIp("1.2.3.4");
        when(valueOps.get("sso:online:jti-old")).thenReturn(sessionJson(session));
        LoginUserDTO fresh = admin();
        fresh.setPermissions(List.of("new:perm"));
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(fresh));

        tokenService.refresh("rt-old");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(startsWith(SecurityConstants.ONLINE_KEY_PREFIX), captor.capture(), anyLong(), any(TimeUnit.class));
        OnlineSession written = JSON.readValue(captor.getValue(), OnlineSession.class);
        assertThat(written.getPermissions()).containsExactly("new:perm");
    }

    @Test
    void logout_expiredTokenIsIdempotent() throws Exception {
        injectSecret();
        String token = com.cloudai.common.security.util.JwtUtil.createToken(TEST_SECRET, 1L, "admin", "jti-x", -10);
        tokenService.logout(token);
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void logout_removesOnlineAndRefresh() throws Exception {
        injectSecret();
        String token = com.cloudai.common.security.util.JwtUtil.createToken(TEST_SECRET, 1L, "admin", "jti-1", 7200);
        tokenService.logout(token);
        verify(stringRedisTemplate).delete("sso:online:jti-1");
        verify(stringRedisTemplate).delete("sso:refresh:1");
    }
}
