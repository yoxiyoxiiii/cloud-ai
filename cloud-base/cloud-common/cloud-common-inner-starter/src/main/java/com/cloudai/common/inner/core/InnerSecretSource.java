package com.cloudai.common.inner.core;

import java.util.List;

/**
 * inner 面密钥来源抽象（契约 2026-11-09-inner-access-secret-api v2 §2）：
 * 入站过滤器与出站拦截器依赖本接口而非配置直读——来源可以是静态 Nacos 单值（v1 行为）
 * 或 Redis 活跃集（轮换分发），宿主无感知。
 */
public interface InnerSecretSource {

    /**
     * 入站接受集（已按本地时钟剔除过期项；通常 1 把，轮换重叠窗内 2 把）。
     * 空集语义 = fail-closed：服务无可用密钥，/inner/** 恒 401。
     */
    List<String> acceptedSecrets();

    /**
     * 出站注入的最新钥（活跃集首位；静态来源为配置单值）。
     * null 语义 = 无可用密钥：跳过注头（不加空头，不伪装成「配了空值」）。
     */
    String injectSecret();
}
