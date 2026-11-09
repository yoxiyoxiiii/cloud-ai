package com.cloudai.common.inner.client;

import com.cloudai.common.inner.auth.InnerAccessFilter;
import com.cloudai.common.inner.config.InnerProperties;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;

/**
 * Feign 出站全局拦截器（契约 2026-11-09-inner-access-secret-api §2）：
 * 密钥已配置则对每笔 Feign 出站请求注入 X-Inner-Secret 头——spring-cloud-openfeign 收集
 * 上下文全部 RequestInterceptor bean 按全局拦截器应用，全部声明式 client 与任意发起线程
 * （登录请求 / MQ 消费 / xxl-job）无差别覆盖，业务代码零改动。
 * 未配置时跳过注入：不加空头（空头只会把「未配置」伪装成「配了空值」，语义更糟）；
 * 此时调用到已启用的提供方会 401，由既有降级链吸收。
 */
@RequiredArgsConstructor
public class InnerSecretInterceptor implements RequestInterceptor {

    private final InnerProperties properties;

    @Override
    public void apply(RequestTemplate template) {
        String secret = properties.getSharedSecret();
        if (secret == null || secret.isEmpty()) {
            return;
        }
        template.header(InnerAccessFilter.HEADER_INNER_SECRET, secret);
    }
}
