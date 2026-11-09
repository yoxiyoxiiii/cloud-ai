package com.cloudai.common.inner.client;

import com.cloudai.common.inner.auth.InnerAccessFilter;
import com.cloudai.common.inner.config.InnerProperties;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /inner 面出站拦截器测试（契约 §2）：已配置 → 注入 X-Inner-Secret 头；未配置 → 跳过注入（无空头、不抛）。
 */
class InnerSecretInterceptorTest {

    private static final String SHARED_SECRET = "inner-shared-secret-for-test";

    @Test
    void configured_addsSecretHeader() {
        InnerProperties properties = new InnerProperties();
        properties.setSharedSecret(SHARED_SECRET);
        RequestTemplate template = new RequestTemplate();

        new InnerSecretInterceptor(properties).apply(template);

        assertThat(template.headers().get(InnerAccessFilter.HEADER_INNER_SECRET))
                .containsExactly(SHARED_SECRET);
    }

    @Test
    void unconfigured_skipsInjectionWithoutError() {
        InnerProperties properties = new InnerProperties();
        RequestTemplate template = new RequestTemplate();

        new InnerSecretInterceptor(properties).apply(template);

        // 不加空头：空值头只会把「未配置」伪装成「配了空值」
        assertThat(template.headers()).doesNotContainKey(InnerAccessFilter.HEADER_INNER_SECRET);
    }
}
