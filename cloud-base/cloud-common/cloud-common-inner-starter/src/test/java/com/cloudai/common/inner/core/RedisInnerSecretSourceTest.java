package com.cloudai.common.inner.core;

import com.cloudai.common.inner.config.InnerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 活跃集来源解析测试（契约 v2 §2 / 设计 §4.2 六分支）：
 * 缓存命中不访 Redis / 键存在解析+过期剔除 / 键不存在回落静态 / 访问报错粘性续服务 /
 * 冷启动报错空集（fail-closed）/ JSON 损坏按报错分支处理。
 * cacheTtlSeconds=0 时每次调用都强制刷新（TTL 判定恒不满足），用于驱动缓存过期分支。
 */
@ExtendWith(MockitoExtension.class)
class RedisInnerSecretSourceTest {

    private static final String STATIC_FALLBACK = "static-fallback-secret";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private InnerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new InnerProperties();
        properties.setSharedSecret(STATIC_FALLBACK);
    }

    private RedisInnerSecretSource source(int cacheTtlSeconds) {
        properties.setCacheTtlSeconds(cacheTtlSeconds);
        return new RedisInnerSecretSource(redisTemplate, new StaticInnerSecretSource(properties), properties);
    }

    private void stubGetReturning(String raw) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisInnerSecretSource.ACTIVE_SET_KEY)).thenReturn(raw);
    }

    private void stubGetThrowing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisInnerSecretSource.ACTIVE_SET_KEY))
                .thenThrow(new IllegalStateException("redis down"));
    }

    /** 活跃集 JSON（两把：first 未过期 / second 已过期由调用方按 now 构造） */
    private String activeSetJson(long firstExpireAtMs, long secondExpireAtMs) {
        return "{\"version\":7,\"secrets\":[{\"value\":\"first-secret\",\"expireAtMs\":" + firstExpireAtMs
                + "},{\"value\":\"second-secret\",\"expireAtMs\":" + secondExpireAtMs + "}]}";
    }

    @Test
    void cacheHit_doesNotTouchRedisWithinTtl() {
        stubGetReturning(activeSetJson(System.currentTimeMillis() + 60_000,
                System.currentTimeMillis() + 60_000));
        RedisInnerSecretSource src = source(30);

        List<String> first = src.acceptedSecrets();
        List<String> second = src.acceptedSecrets();

        assertThat(first).containsExactly("first-secret", "second-secret");
        assertThat(second).isEqualTo(first);
        // 缓存窗口内第二次调用不访 Redis（GET 仅一次）
        verify(valueOps, times(1)).get(RedisInnerSecretSource.ACTIVE_SET_KEY);
    }

    @Test
    void keyPresent_parsesAndDropsExpiredEntriesByLocalClock() {
        long now = System.currentTimeMillis();
        stubGetReturning(activeSetJson(now + 60_000, now - 1_000));
        RedisInnerSecretSource src = source(30);

        List<String> accepted = src.acceptedSecrets();

        // 过期项（expireAtMs <= now）被本地时钟剔除，顺序保持（首位=最新钥）
        assertThat(accepted).containsExactly("first-secret");
        assertThat(src.injectSecret()).isEqualTo("first-secret");
    }

    @Test
    void keyMissing_fallsBackToStaticConfiguredValue() {
        stubGetReturning(null);
        RedisInnerSecretSource src = source(30);

        List<String> accepted = src.acceptedSecrets();

        // 键不存在（GET 返回 null）≠ 访问报错：回落静态兜底值
        assertThat(accepted).containsExactly(STATIC_FALLBACK);
        assertThat(src.injectSecret()).isEqualTo(STATIC_FALLBACK);
    }

    @Test
    void redisErrorAfterSuccess_stickyCacheKeepsServing() {
        stubGetReturning(activeSetJson(System.currentTimeMillis() + 60_000,
                System.currentTimeMillis() + 60_000));
        RedisInnerSecretSource src = source(0);
        assertThat(src.acceptedSecrets()).containsExactly("first-secret", "second-secret");

        stubGetThrowing();
        List<String> sticky = src.acceptedSecrets();

        // 报错不回落静态：粘性缓存（最近成功结果）续服务
        assertThat(sticky).containsExactly("first-secret", "second-secret");
    }

    @Test
    void redisErrorOnColdStart_emptySetFailClosed() {
        stubGetThrowing();
        RedisInnerSecretSource src = source(0);

        assertThat(src.acceptedSecrets()).isEmpty();
        assertThat(src.injectSecret()).isNull();
    }

    @Test
    void corruptedJson_treatedAsErrorBranch_stickyThenEmptyOnColdStart() {
        stubGetReturning(activeSetJson(System.currentTimeMillis() + 60_000,
                System.currentTimeMillis() + 60_000));
        RedisInnerSecretSource src = source(0);
        assertThat(src.acceptedSecrets()).isNotEmpty();

        // JSON 损坏按「访问报错」分支：粘性续服务（冷启动场景为空集，与上一用例分支同源）
        stubGetReturning("{not-a-json");
        assertThat(src.acceptedSecrets()).isNotEmpty();

        RedisInnerSecretSource cold = source(0);
        stubGetReturning("{not-a-json");
        assertThat(cold.acceptedSecrets()).isEmpty();
    }

    @Test
    void stickyCache_alsoDropsExpiredEntries_neverRenews() throws InterruptedException {
        // 加载时未过期（30ms 余量），随后时间流逝到期，再叠加 Redis 报错进入粘性分支
        stubGetReturning(activeSetJson(System.currentTimeMillis() + 30,
                System.currentTimeMillis() + 30));
        RedisInnerSecretSource src = source(0);
        assertThat(src.acceptedSecrets()).isNotEmpty();
        Thread.sleep(200);

        stubGetThrowing();
        // 粘性结果中的已过期项同样被本地时钟剔除——宁可全体拒绝也不悄悄续期
        assertThat(src.acceptedSecrets()).isEmpty();
    }

    @Test
    void keyMissingAndStaticEmpty_emptySet() {
        properties.setSharedSecret("");
        stubGetReturning(null);
        RedisInnerSecretSource src = source(30);

        assertThat(src.acceptedSecrets()).isEmpty();
        assertThat(src.injectSecret()).isNull();
    }
}
