package com.cloudai.common.inner.client;

import com.cloudai.common.inner.auth.InnerAccessFilter;
import com.cloudai.common.inner.core.InnerSecretSource;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;

/**
 * Feign 出站全局拦截器（契约 2026-11-09-inner-access-secret-api v2 §2）：
 * 注入值为 InnerSecretSource 最新钥（活跃集首位——轮换后 ≤ 缓存 TTL 内全体发送方换注新钥）。
 * spring-cloud-openfeign 收集上下文全部 RequestInterceptor bean 按全局拦截器应用，
 * 全部声明式 client 与任意发起线程（登录请求 / MQ 消费 / xxl-job）无差别覆盖，业务代码零改动。
 * 无可用密钥（null）时跳过注入：不加空头（空头只会把「无密钥」伪装成「配了空值」，语义更糟）；
 * 此时调用到已启用的提供方会 401，由既有降级链吸收。
 */
@RequiredArgsConstructor
public class InnerSecretInterceptor implements RequestInterceptor {

    private final InnerSecretSource secretSource;

    @Override
    public void apply(RequestTemplate template) {
        String secret = secretSource.injectSecret();
        if (secret == null || secret.isEmpty()) {
            return;
        }
        template.header(InnerAccessFilter.HEADER_INNER_SECRET, secret);
    }
}
