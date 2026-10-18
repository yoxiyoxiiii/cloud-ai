package com.cloudai.gateway.filter;

import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthGlobalFilterTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ONLINE_JSON =
            "{\"tokenId\":\"jti-1\",\"userId\":1,\"account\":\"admin\",\"permissions\":[\"system:user:list\"],\"loginTime\":1,\"ip\":\"127.0.0.1\"}";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private GatewayFilterChain chain;

    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        filter = new AuthGlobalFilter(stringRedisTemplate, jwtProperties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** spring-test 6.1 的 builder 无 headers(Consumer) 重载（6.2 才有）；HttpHeaders 本身是 MultiValueMap，中转适配 */
    private MockServerWebExchange get(String uri, java.util.function.Consumer<HttpHeaders> headers) {
        HttpHeaders mutable = new HttpHeaders();
        headers.accept(mutable);
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri).headers(mutable).build());
    }

    @Test
    void emptyBearerTokenReturns401Not500() {
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> h.setBearerAuth(""));
        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tamperedTokenReturns401() {
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> h.setBearerAuth("bad.token.sig"));
        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void missingOnlineSessionReturns401() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "jti-1", 7200);
        when(valueOps.get("sso:online:jti-1")).thenReturn(null);
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> h.setBearerAuth(token));
        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void validSessionForwardsWithUserHeadersAndStripsForged() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "jti-1", 7200);
        when(valueOps.get("sso:online:jti-1")).thenReturn(ONLINE_JSON);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> {
                    h.setBearerAuth(token);
                    h.set(SecurityConstants.HEADER_USER_ID, "999");
                    h.set(SecurityConstants.HEADER_USER_PERMS, "evil:perm");
                });
        filter.filter(exchange, chain).block();
        // Spring Framework 6.2 起 mutate() 返回装饰器不再回写 mock 自身——断言下沉到 chain 实收的 exchange
        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        var headers = forwarded.getValue().getRequest().getHeaders();
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ID)).isEqualTo("1");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ACCOUNT)).isEqualTo("admin");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_PERMS)).isEqualTo("system:user:list");
    }

    /** 会话含 roleIds/deptIds 时注入两新头（逗号分隔；部门头=自身+祖先链值） */
    @Test
    void sessionWithRoleAndDeptIdsInjectsNewHeaders() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "jti-1", 7200);
        String sessionJson = "{\"tokenId\":\"jti-1\",\"userId\":1,\"account\":\"admin\","
                + "\"permissions\":[\"ai:kb:list\"],\"roleIds\":[2,5],\"deptIds\":[1,3,12]}";
        when(valueOps.get("sso:online:jti-1")).thenReturn(sessionJson);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> h.setBearerAuth(token));
        filter.filter(exchange, chain).block();
        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        var headers = forwarded.getValue().getRequest().getHeaders();
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ROLE_IDS)).isEqualTo("2,5");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_DEPT_IDS)).isEqualTo("1,3,12");
    }

    /** 外部伪造两新头被剥离：下游收到的是会话真值，非请求原值 */
    @Test
    void forgedRoleAndDeptHeadersAreStripped() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "jti-1", 7200);
        when(valueOps.get("sso:online:jti-1")).thenReturn(ONLINE_JSON);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> {
                    h.setBearerAuth(token);
                    h.set(SecurityConstants.HEADER_USER_ROLE_IDS, "777");
                    h.set(SecurityConstants.HEADER_USER_DEPT_IDS, "888,999");
                });
        filter.filter(exchange, chain).block();
        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        var headers = forwarded.getValue().getRequest().getHeaders();
        // ONLINE_JSON 无 roleIds/deptIds 字段 → 反序列化 null → 注入空串（伪造值 777/888 不透传）
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ROLE_IDS)).isEmpty();
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_DEPT_IDS)).isEmpty();
    }

    /** 旧格式会话（升级前登录，JSON 无新字段）兼容：两新头注入空串，既有三头不受影响 */
    @Test
    void legacySessionWithoutNewFieldsInjectsEmptyStrings() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "jti-1", 7200);
        when(valueOps.get("sso:online:jti-1")).thenReturn(ONLINE_JSON);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = get("http://localhost/system/user/page",
                h -> h.setBearerAuth(token));
        filter.filter(exchange, chain).block();
        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        var headers = forwarded.getValue().getRequest().getHeaders();
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ROLE_IDS)).isEqualTo("");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_DEPT_IDS)).isEqualTo("");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_PERMS)).isEqualTo("system:user:list");
    }
}
