package com.cloudai.aggregate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 聚合服务编排配置。架构口径非密钥仓库化，Nacos 可覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloud.aggregate")
public class AggregateProperties {

    /** 段并行线程池线程数（core=max）：3 段 × 并发请求余量，默认 8 */
    private int poolSize = 8;
}
