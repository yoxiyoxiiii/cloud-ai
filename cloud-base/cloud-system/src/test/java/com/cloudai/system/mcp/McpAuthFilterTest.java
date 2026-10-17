package com.cloudai.system.mcp;

import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * MCP 双主体认证过滤器测试（契约 §1.2）：
 * 无密钥头 401 / 错密钥 401 / 未配置密钥态拒绝全部 / 合法三关通过 / Redis 无会话 401。
 */
@ExtendWith(MockitoExtension.class)
class McpAuthFilterTest {

    /** HS512 要求 >=64 字节，测试密钥按此长度构造 */
    private static final String JWT_SECRET = "test-jwt-secret-for-mcp-auth-filter-unit-tests-0123456789abcdef0";
    private static final String CLIENT_SECRET = "mcp-shared-secret-for-test";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtProperties jwtProperties;
    private McpProperties mcpProperties;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(JWT_SECRET);
        mcpProperties = new McpProperties();
        mcpProperties.setClientSecret(CLIENT_SECRET);
        request = new MockHttpServletRequest("POST", "/mcp");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    private McpAuthFilter filter() {
        return new McpAuthFilter(mcpProperties, jwtProperties, stringRedisTemplate);
    }

    private void stubOnlineSession(String jti, String json) {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("sso:online:" + jti)).willReturn(json);
    }

    /** sso 同款口径签发：subject=用户id 字符串、claim account、jti */
    private String mintToken(String jti) {
        return JwtUtil.createToken(JWT_SECRET, 1L, "admin", jti, 600);
    }

    @Test
    void missingSecretHeader_denied401() throws Exception {
        request.addHeader("Authorization", "Bearer " + mintToken(UUID.randomUUID().toString()));
        filter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":401");
    }

    @Test
    void wrongSecret_denied401() throws Exception {
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, "not-the-secret");
        request.addHeader("Authorization", "Bearer " + mintToken(UUID.randomUUID().toString()));
        filter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unconfiguredSecret_rejectsAll() throws Exception {
        mcpProperties.setClientSecret("");
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, CLIENT_SECRET);
        request.addHeader("Authorization", "Bearer " + mintToken(UUID.randomUUID().toString()));
        filter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void allThreeGatesPass_buildsPrincipalAndChains() throws Exception {
        String jti = UUID.randomUUID().toString();
        stubOnlineSession(jti, "{\"permissions\":[\"system:user:list\"]}");
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, CLIENT_SECRET);
        request.addHeader("Authorization", "Bearer " + mintToken(jti));

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        McpPrincipal principal = (McpPrincipal) request.getAttribute(McpPrincipal.ATTR);
        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo(1L);
        assertThat(principal.account()).isEqualTo("admin");
        assertThat(principal.perms()).containsExactly("system:user:list");
    }

    @Test
    void onlineSessionMissing_denied401() throws Exception {
        String jti = UUID.randomUUID().toString();
        stubOnlineSession(jti, null);
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, CLIENT_SECRET);
        request.addHeader("Authorization", "Bearer " + mintToken(jti));

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute(McpPrincipal.ATTR)).isNull();
        verify(valueOperations).get(anyString());
    }
}
