package com.cloudai.common.inner.core;

import com.cloudai.common.inner.config.InnerProperties;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 静态配置来源（v1 行为子集）：包装 cloud.inner.shared-secret 单值。
 * v2 起静态键语义降级为引导/兜底值——Redis 活跃集键不存在时回落本来源；
 * 静态未配置则空集（fail-closed）。无 Redis 宿主恒用本来源，行为与 v1 完全一致。
 */
@RequiredArgsConstructor
public class StaticInnerSecretSource implements InnerSecretSource {

    private final InnerProperties properties;

    @Override
    public List<String> acceptedSecrets() {
        String secret = properties.getSharedSecret();
        return secret == null || secret.isEmpty() ? List.of() : List.of(secret);
    }

    @Override
    public String injectSecret() {
        String secret = properties.getSharedSecret();
        return secret == null || secret.isEmpty() ? null : secret;
    }
}
