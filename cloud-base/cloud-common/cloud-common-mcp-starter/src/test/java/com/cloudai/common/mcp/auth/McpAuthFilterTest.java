package com.cloudai.common.mcp.auth;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.mcp.config.McpProperties;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import com.cloudai.common.security.util.SecurityUtils;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * MCP 双主体认证过滤器测试（契约 §1.2）：
 * 无密钥头 401 / 错密钥 401 / 未配置密钥态拒绝全部 / 合法三关通过 / Redis 无会话 401；
 * 列级数据权限轮增量：三关全过后 SecurityContext 在链内可用（JWT 主体即规则主体）、
 * 未认证路径不构建、链返回后已清理。
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
        SecurityContextHolder.clearContext();
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

    // ---- 列级数据权限轮增量：SecurityContext 三态（JWT 主体即规则主体） ----

    @Test
    void allThreeGatesPass_securityContextAvailableDuringChain() throws Exception {
        String jti = UUID.randomUUID().toString();
        stubOnlineSession(jti, "{\"permissions\":[\"system:user:list\"]}");
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, CLIENT_SECRET);
        request.addHeader("Authorization", "Bearer " + mintToken(jti));
        LoginUser[] captured = new LoginUser[1];
        FilterChain capturingChain = (req, res) -> captured[0] = SecurityUtils.currentUser();

        filter().doFilter(request, response, capturingChain);

        // 链内可取当前登录人（字段与 principal 同源）——MCP 面读路径求值据此取规则主体
        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getUserId()).isEqualTo(1L);
        assertThat(captured[0].getAccount()).isEqualTo("admin");
        assertThat(captured[0].getPermissions()).containsExactly("system:user:list");
        // 链返回后已清理（无状态纪律：线程池复用防串号）
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void deniedPath_neverBuildsSecurityContext() throws Exception {
        // 未认证无身份可建：拒绝路径不触碰 SecurityContext
        request.addHeader("Authorization", "Bearer " + mintToken(UUID.randomUUID().toString()));

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---- D67 注册序集成级（设计 §12.6）：过滤器孤立单测覆盖不了链上的 context 置换层 ----

    /** 真实 SecurityContextHolderFilter 复刻生产拓扑（spring-security 链首在前、本过滤器在后） */
    private SecurityContextHolderFilter holderFilter() {
        return new SecurityContextHolderFilter(new RequestAttributeSecurityContextRepository());
    }

    @Test
    void afterSecurityChain_contextSurvivesToInnermost() throws Exception {
        // 生产注册序复刻：SecurityContextHolderFilter（spring-security 链首，-100）先跑并置空
        // deferred context，本过滤器（-90）在其后 setAuthentication——无再置换点，最内层可读主体
        String jti = UUID.randomUUID().toString();
        stubOnlineSession(jti, "{\"permissions\":[\"system:user:list\"]}");
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, CLIENT_SECRET);
        request.addHeader("Authorization", "Bearer " + mintToken(jti));
        LoginUser[] captured = new LoginUser[1];
        FilterChain productionOrder = (req, res) -> filter().doFilter(request, response,
                (r2, rs) -> captured[0] = SecurityUtils.currentUser());

        holderFilter().doFilter(request, response, productionOrder);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getUserId()).isEqualTo(1L);
        assertThat(captured[0].getAccount()).isEqualTo("admin");
        assertThat(captured[0].getPermissions()).containsExactly("system:user:list");
        // 链返回后已清（两层 finally 双清，无状态纪律）
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void oldOrder_holderFilterWipesContext_regressionProbe() throws Exception {
        // 反证回归探针：旧注册序（本过滤器先、SecurityContextHolderFilter 后）下
        // setDeferredContext 整体置换塞入的认证主体 → 最内层读到匿名。防未来改回 order。
        String jti = UUID.randomUUID().toString();
        stubOnlineSession(jti, "{\"permissions\":[\"system:user:list\"]}");
        request.addHeader(McpAuthFilter.HEADER_CLIENT_SECRET, CLIENT_SECRET);
        request.addHeader("Authorization", "Bearer " + mintToken(jti));
        LoginUser[] captured = new LoginUser[1];
        FilterChain oldOrder = (req, res) -> holderFilter().doFilter(request, response,
                (r2, rs) -> captured[0] = SecurityUtils.currentUser());

        filter().doFilter(request, response, oldOrder);

        assertThat(captured[0]).isNull();
    }
}
