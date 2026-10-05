package com.cloudai.sso.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.redis.util.RedisUtil;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.sso.client.SystemUserClient;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.sso.dto.LoginUserDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final String TEST_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private SystemUserClient userClient;
    @Mock
    private RedisUtil redisUtil;
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

    private void injectSecret() throws Exception {
        Field secretField = TokenService.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        secretField.set(tokenService, TEST_SECRET);
    }

    @Test
    void login_successIssuesTokensAndWritesRedis() throws Exception {
        injectSecret();
        when(userClient.getUserByAccount("admin")).thenReturn(R.ok(admin()));
        LoginResult result = tokenService.login("admin", "admin123", "127.0.0.1");
        assertThat(result.getAccessToken()).isNotBlank();
        assertThat(result.getRefreshToken()).isNotBlank();
        assertThat(result.getExpiresIn()).isEqualTo(7200L);
        verify(redisUtil).set(startsWith(SecurityConstants.ONLINE_KEY_PREFIX), any(OnlineSession.class), anyLong(), any(TimeUnit.class));
        verify(redisUtil).set(startsWith("sso:refresh:"), anyString(), anyLong(), any(TimeUnit.class));
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
    void logout_removesOnlineAndRefresh() throws Exception {
        injectSecret();
        String token = com.cloudai.common.security.util.JwtUtil.createToken(TEST_SECRET, 1L, "admin", "jti-1", 7200);
        tokenService.logout(token);
        verify(redisUtil).delete("sso:online:jti-1");
        verify(redisUtil).delete("sso:refresh:1");
    }
}
