package com.cloudai.common.inner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * /inner 面准入配置（真值放 Nacos per-service yaml，不入仓库）。
 * 无 enabled 开关是刻意语义：inner 端点在 system/bpmn 永远存在（非可选特性），
 * 「配置即生效 + fail-closed」没有合法的不保护态（论证见设计
 * docs/superpowers/specs/2026-11-09-inner-shared-secret-design.md §4）。
 */
@Data
@ConfigurationProperties(prefix = "cloud.inner")
public class InnerProperties {

    /** 共享密钥（v2 起为引导/兜底值——Redis 活跃集键不存在时回落）；空 = 未配置态：静态来源空集（fail-closed） */
    private String sharedSecret = "";

    /** 活跃集本地缓存 TTL（秒）；重叠窗须 ≥ 收发双方缓存 TTL 之和 + 任务调度周期的余量（默认值即定稿值，无 Nacos 变更） */
    private int cacheTtlSeconds = 30;
}
