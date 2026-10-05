package com.cloudai.gateway.filter;

import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
        var headers = exchange.getRequest().getHeaders();
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ID)).isEqualTo("1");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_ACCOUNT)).isEqualTo("admin");
        assertThat(headers.getFirst(SecurityConstants.HEADER_USER_PERMS)).isEqualTo("system:user:list");
    }
}
