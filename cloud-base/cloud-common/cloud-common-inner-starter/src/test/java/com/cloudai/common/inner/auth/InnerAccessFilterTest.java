package com.cloudai.common.inner.auth;

import com.cloudai.common.inner.config.InnerProperties;
import com.cloudai.common.inner.core.InnerSecretSource;
import com.cloudai.common.inner.core.StaticInnerSecretSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /inner 面准入过滤器测试（契约 v2 §1）：集合任一命中放行（重叠窗两把并存）/ 空集 401 /
 * 无头 401 / 错值 401 / 正确值放行。拒绝响应体精确断言（统一报文不区分三态）；
 * 放行断言链被调用（纯闸门不吞请求）。构造入参为 InnerSecretSource（默认包 StaticInnerSecretSource，
 * 多把密钥场景用匿名来源——比对逻辑只依赖接口）。
 */
class InnerAccessFilterTest {

    private static final String SHARED_SECRET = "inner-shared-secret-for-test";

    /** 拒绝响应统一报文（三态同体，不给调用方校验进度 oracle） */
    private static final String DENY_BODY = "{\"code\":401,\"msg\":\"inner 认证失败\",\"data\":null}";

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/inner/user/all");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    /** 静态来源（v1 单值形态） */
    private InnerSecretSource staticSource() {
        InnerProperties properties = new InnerProperties();
        properties.setSharedSecret(SHARED_SECRET);
        return new StaticInnerSecretSource(properties);
    }

    private InnerAccessFilter filter(InnerSecretSource source) {
        return new InnerAccessFilter(source);
    }

    @Test
    void missingHeader_denied401WithUnifiedBody() throws Exception {
        filter(staticSource()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(DENY_BODY);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void wrongSecret_denied401() throws Exception {
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, "not-the-secret");

        filter(staticSource()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(DENY_BODY);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void emptyAcceptedSet_rejectsAll() throws Exception {
        InnerSecretSource empty = new StaticInnerSecretSource(new InnerProperties());
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, SHARED_SECRET);

        filter(empty).doFilter(request, response, chain);

        // fail-closed：集合为空时即使密钥本身正确也拒绝（最严默认；Redis 冷启动失败/全部过期同态）
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(DENY_BODY);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void correctSecret_passesThroughToChain() throws Exception {
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, SHARED_SECRET);

        filter(staticSource()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void anySecretInAcceptedSet_passes() throws Exception {
        // 轮换重叠窗：新旧两把同时有效，命中任一放行（次把命中场景）
        InnerSecretSource overlapping = new InnerSecretSource() {
            @Override
            public List<String> acceptedSecrets() {
                return List.of("new-generation-secret", "old-generation-secret");
            }

            @Override
            public String injectSecret() {
                return "new-generation-secret";
            }
        };
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, "old-generation-secret");

        filter(overlapping).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
