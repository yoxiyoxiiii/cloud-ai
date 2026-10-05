package com.cloudai.common.security.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（Nacos cloud-common.yaml 的 cloud.jwt.*）
 */
@Data
@ConfigurationProperties(prefix = "cloud.jwt")
public class JwtProperties {

    /** HS512 签名密钥（>=64 字节） */
    private String secret;

    /** accessToken 有效期（秒） */
    private long accessTokenTtl = 7200;

    /** refreshToken 有效期（秒） */
    private long refreshTokenTtl = 604800;
}
