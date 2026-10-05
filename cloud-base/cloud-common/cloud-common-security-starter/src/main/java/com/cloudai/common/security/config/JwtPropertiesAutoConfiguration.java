package com.cloudai.common.security.config;

import com.cloudai.common.security.props.JwtProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * JWT 配置属性注册（无条件——网关 WebFlux 与 servlet 服务均生效）
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtPropertiesAutoConfiguration {
}
