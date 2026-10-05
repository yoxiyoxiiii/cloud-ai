package com.cloudai.common.redis.config;

import com.cloudai.common.redis.util.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommonRedisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("redisConnectionFactory", RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withConfiguration(AutoConfigurations.of(CommonRedisAutoConfiguration.class));

    @Test
    void registersRedisTemplateAndUtil() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("redisTemplate");
            assertThat(ctx).hasSingleBean(RedisUtil.class);
        });
    }
}
