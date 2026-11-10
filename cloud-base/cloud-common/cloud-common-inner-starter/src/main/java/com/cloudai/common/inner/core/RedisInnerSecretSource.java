package com.cloudai.common.inner.core;

import com.cloudai.common.inner.config.InnerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 活跃集来源（契约 2026-11-09-inner-access-secret-api v2 §2，设计
 * docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md §4.2）：
 * 每请求走本地缓存（TTL 默认 30s），不直连 Redis。解析优先级：
 * ① 键存在 → 集合内剔除 expireAtMs 已过项后采用（过期密钥即使 Redis 不可达也被本地时钟剔除，绝不悄悄续期）；
 * ② 键不存在 → 回落静态配置值（引导/兜底）；静态也未配置 → 空集；
 * ③ 访问报错（含 JSON 损坏）→ 有粘性缓存（最近一次成功结果）则剔除过期项后续用（准入不因 Redis 抖动中断）；
 *    从未成功过 → 空集（fail-closed：报错不等于键不存在，不回落静态）。
 * 线程模型：不可变快照 + volatile 引用，synchronized 单飞刷新（本规模无惊群问题）。
 */
public class RedisInnerSecretSource implements InnerSecretSource {

    /** Redis 活跃集键（发布方 cloud-system 与全部消费方共用本常量，单一事实源；值无 TTL，生命周期由轮换任务显式管理） */
    public static final String ACTIVE_SET_KEY = "inner:secret:active";

    private static final Logger log = LoggerFactory.getLogger(RedisInnerSecretSource.class);

    private final StringRedisTemplate redisTemplate;
    private final StaticInnerSecretSource staticSource;
    private final long cacheTtlMillis;

    /** 独立 ObjectMapper：活跃集结构无定制格式，不依赖宿主配置（同 InnerAccessFilter 拒绝体序列化先例） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最近一次成功加载的不可变快照（null=从未成功过） */
    private volatile Snapshot snapshot;

    public RedisInnerSecretSource(StringRedisTemplate redisTemplate,
                                  StaticInnerSecretSource staticSource,
                                  InnerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.staticSource = staticSource;
        this.cacheTtlMillis = properties.getCacheTtlSeconds() * 1000L;
    }

    @Override
    public List<String> acceptedSecrets() {
        return resolve();
    }

    @Override
    public String injectSecret() {
        List<String> secrets = resolve();
        return secrets.isEmpty() ? null : secrets.get(0);
    }

    /** 解析主路径：缓存命中不访 Redis；过期后单飞刷新（双检防刷新后重复拉取） */
    private List<String> resolve() {
        long now = System.currentTimeMillis();
        Snapshot snap = snapshot;
        if (snap != null && now - snap.loadedAtMs < cacheTtlMillis) {
            return snap.activeValues(now);
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            snap = snapshot;
            if (snap != null && now - snap.loadedAtMs < cacheTtlMillis) {
                return snap.activeValues(now);
            }
            String raw = null;
            try {
                raw = redisTemplate.opsForValue().get(ACTIVE_SET_KEY);
                Snapshot loaded = raw == null ? staticFallbackSnapshot(now) : parseSnapshot(raw, now);
                snapshot = loaded;
                return loaded.activeValues(now);
            } catch (Exception e) {
                // 报文不区分失败原因（不给校验进度 oracle）；日志记原文长度不记内容（密钥真值不进日志）
                log.warn("inner 活跃集加载失败（原文长度={}，不记内容）: {}",
                        raw == null ? -1 : raw.length(), e.getMessage());
                if (snap != null) {
                    // 粘性续服务：最近成功结果剔除过期项后继续用（轮换阻塞由任务侧告警，准入不停）
                    return snap.activeValues(now);
                }
                // 冷启动且从未成功：空集（fail-closed，出站跳过注头、入站恒 401）
                return List.of();
            }
        }
    }

    /** 键不存在分支：回落静态兜底值（无过期语义，用 Long.MAX_VALUE 承载）；静态未配置 → 空集快照 */
    private Snapshot staticFallbackSnapshot(long now) {
        String fallback = staticSource.injectSecret();
        if (fallback == null || fallback.isEmpty()) {
            return new Snapshot(List.of(), now);
        }
        return new Snapshot(List.of(new InnerActiveSecretSet.SecretEntry(fallback, Long.MAX_VALUE)), now);
    }

    /** JSON 损坏按「访问报错」分支处理（粘性或空集），异常向上抛由 resolve 统一收口 */
    private Snapshot parseSnapshot(String raw, long now) throws com.fasterxml.jackson.core.JsonProcessingException {
        InnerActiveSecretSet set = objectMapper.readValue(raw, InnerActiveSecretSet.class);
        List<InnerActiveSecretSet.SecretEntry> entries =
                set.secrets() == null ? List.of() : set.secrets();
        return new Snapshot(entries, now);
    }

    /** 加载快照：条目集 + 加载时刻（TTL 判定锚点）；activeValues 每次按调用时刻时钟剔除（缓存内同样不续期） */
    private record Snapshot(List<InnerActiveSecretSet.SecretEntry> entries, long loadedAtMs) {

        List<String> activeValues(long now) {
            List<String> values = new ArrayList<>(entries.size());
            for (InnerActiveSecretSet.SecretEntry entry : entries) {
                if (entry.expireAtMs() > now) {
                    values.add(entry.value());
                }
            }
            return values;
        }
    }
}
