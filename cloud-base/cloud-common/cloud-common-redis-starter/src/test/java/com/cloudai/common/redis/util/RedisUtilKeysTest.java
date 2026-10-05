package com.cloudai.common.redis.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedisUtilKeysTest {

    @Test
    void keysReturnsMatchingKeys() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        try {
            RedisTemplate<String, Object> template =
                    new com.cloudai.common.redis.config.CommonRedisAutoConfiguration().redisTemplate(factory);
            template.afterPropertiesSet();
            RedisUtil redis = new RedisUtil(template);
            Assumptions.assumeTrue("PONG".equalsIgnoreCase(factory.getConnection().ping()), "本机 Redis 未运行，跳过");
            redis.set("test:keys:a", "1");
            redis.set("test:keys:b", "2");
            Set<String> keys = redis.keys("test:keys:*");
            assertThat(keys).contains("test:keys:a", "test:keys:b");
            redis.delete("test:keys:a");
            redis.delete("test:keys:b");
        } finally {
            factory.destroy();
        }
    }
}
