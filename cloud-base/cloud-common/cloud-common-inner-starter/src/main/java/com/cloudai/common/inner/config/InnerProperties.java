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

    /** 共享密钥（全体服务同一把）；空 = 未配置态：/inner/** 恒 401（fail-closed）且 Feign 出站不注入密钥头 */
    private String sharedSecret = "";
}
