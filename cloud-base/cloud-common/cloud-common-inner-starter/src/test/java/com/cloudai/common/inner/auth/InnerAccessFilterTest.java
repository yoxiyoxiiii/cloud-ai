package com.cloudai.common.inner.auth;

import com.cloudai.common.inner.config.InnerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /inner 面准入过滤器测试（契约 §1）：无头 401 / 错密钥 401 / 未配置态拒绝全部 / 正确值放行。
 * 拒绝响应体精确断言（统一报文不区分三态）；放行断言链被调用（纯闸门不吞请求）。
 */
class InnerAccessFilterTest {

    private static final String SHARED_SECRET = "inner-shared-secret-for-test";

    /** 拒绝响应统一报文（三态同体，不给调用方校验进度 oracle） */
    private static final String DENY_BODY = "{\"code\":401,\"msg\":\"inner 认证失败\",\"data\":null}";

    private InnerProperties properties;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        properties = new InnerProperties();
        properties.setSharedSecret(SHARED_SECRET);
        request = new MockHttpServletRequest("GET", "/inner/user/all");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    private InnerAccessFilter filter() {
        return new InnerAccessFilter(properties);
    }

    @Test
    void missingHeader_denied401WithUnifiedBody() throws Exception {
        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(DENY_BODY);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void wrongSecret_denied401() throws Exception {
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, "not-the-secret");

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(DENY_BODY);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unconfiguredSecret_rejectsAll() throws Exception {
        properties.setSharedSecret("");
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, SHARED_SECRET);

        filter().doFilter(request, response, chain);

        // fail-closed：服务未配置时即使密钥本身正确也拒绝（最严默认）
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(DENY_BODY);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void correctSecret_passesThroughToChain() throws Exception {
        request.addHeader(InnerAccessFilter.HEADER_INNER_SECRET, SHARED_SECRET);

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
