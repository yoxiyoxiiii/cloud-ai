package com.cloudai.common.inner.client;

import com.cloudai.common.inner.auth.InnerAccessFilter;
import com.cloudai.common.inner.config.InnerProperties;
import com.cloudai.common.inner.core.InnerSecretSource;
import com.cloudai.common.inner.core.StaticInnerSecretSource;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /inner 面出站拦截器测试（契约 v2 §2）：来源有最新钥 → 注入 X-Inner-Secret 头（集合首位）；
 * 无可用密钥 → 跳过注入（无空头、不抛）。
 */
class InnerSecretInterceptorTest {

    private static final String SHARED_SECRET = "inner-shared-secret-for-test";

    @Test
    void configured_addsSecretHeader() {
        InnerProperties properties = new InnerProperties();
        properties.setSharedSecret(SHARED_SECRET);
        RequestTemplate template = new RequestTemplate();

        new InnerSecretInterceptor(new StaticInnerSecretSource(properties)).apply(template);

        assertThat(template.headers().get(InnerAccessFilter.HEADER_INNER_SECRET))
                .containsExactly(SHARED_SECRET);
    }

    @Test
    void unconfigured_skipsInjectionWithoutError() {
        RequestTemplate template = new RequestTemplate();

        new InnerSecretInterceptor(new StaticInnerSecretSource(new InnerProperties())).apply(template);

        // 不加空头：空值头只会把「未配置」伪装成「配了空值」
        assertThat(template.headers()).doesNotContainKey(InnerAccessFilter.HEADER_INNER_SECRET);
    }

    @Test
    void activeSet_injectsFirstEntryAsLatestSecret() {
        // 活跃集首位=最新钥：轮换后发送方换注新钥（重叠窗内旧钥只用于入站接受）
        InnerSecretSource activeSet = new InnerSecretSource() {
            @Override
            public List<String> acceptedSecrets() {
                return List.of("new-generation-secret", "old-generation-secret");
            }

            @Override
            public String injectSecret() {
                return "new-generation-secret";
            }
        };
        RequestTemplate template = new RequestTemplate();

        new InnerSecretInterceptor(activeSet).apply(template);

        assertThat(template.headers().get(InnerAccessFilter.HEADER_INNER_SECRET))
                .containsExactly("new-generation-secret");
    }
}
