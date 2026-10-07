package com.cloudai.common.translate.core;

import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.domain.UserEntry;
import com.cloudai.common.translate.provider.DictSourceProvider;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 翻译缓存：Redis 单键 String（纯 JSON，无 @class）+ 回源编排 + TTL 30 分钟兜底（设计 §4/D5）。
 *
 * <p>降级总纲：Redis 读失败按未命中走回源、写失败/回源失败仅 log.error 返回空——
 * 翻译相关失败不产生对前端的错误码，译文降级为 null（契约 §1/§4）。</p>
 */
@Slf4j
public class TranslationCacheService {

    /** 兜底 TTL：写操作 DEL 是主失效通道，TTL 覆盖多实例/DEL 失败/事务竞态路径 */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** 键契约：trans:dict:{dictKey} / trans:user（设计 §4.1） */
    static final String DICT_KEY_PREFIX = "trans:dict:";
    static final String USER_KEY = "trans:user";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectProvider<DictSourceProvider> dictSourceProvider;
    private final ObjectProvider<UserSourceProvider> userSourceProvider;
    private final Duration ttl;
    private final AtomicBoolean dictProviderMissWarned = new AtomicBoolean();
    private final AtomicBoolean userProviderMissWarned = new AtomicBoolean();

    public TranslationCacheService(RedisTemplate<String, Object> redisTemplate,
                                   ObjectProvider<DictSourceProvider> dictSourceProvider,
                                   ObjectProvider<UserSourceProvider> userSourceProvider) {
        this(redisTemplate, dictSourceProvider, userSourceProvider, DEFAULT_TTL);
    }

    public TranslationCacheService(RedisTemplate<String, Object> redisTemplate,
                                   ObjectProvider<DictSourceProvider> dictSourceProvider,
                                   ObjectProvider<UserSourceProvider> userSourceProvider,
                                   Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.dictSourceProvider = dictSourceProvider;
        this.userSourceProvider = userSourceProvider;
        this.ttl = ttl;
    }

    /** 字典标签批量查询（翻译回填）：value→label；未命中的 value 不入 map（译文 null 降级语义） */
    public Map<String, String> findDictLabels(String dictKey, Collection<String> values) {
        Map<String, String> labels = new HashMap<>();
        if (values.isEmpty()) {
            return labels;
        }
        Set<String> wanted = new HashSet<>(values);
        for (DictItemEntry item : listDictItems(dictKey)) {
            if (wanted.contains(item.getValue())) {
                labels.put(item.getValue(), item.getLabel());
            }
        }
        return labels;
    }

    /** 字典项消费（翻译与下拉端点共用）：Redis 单键读 → 未命中 Provider 回源 → 回写 TTL */
    public List<DictItemEntry> listDictItems(String dictKey) {
        String key = DICT_KEY_PREFIX + dictKey;
        List<DictItemEntry> cached = readCache(key, new TypeReference<List<DictItemEntry>>() {});
        if (cached != null) {
            return cached;
        }
        DictSourceProvider provider = dictSourceProvider.getIfAvailable();
        if (provider == null) {
            warnProviderMissOnce(dictProviderMissWarned, "DictSourceProvider");
            return List.of();
        }
        List<DictItemEntry> items;
        try {
            items = provider.listByDictKey(dictKey);
        } catch (Exception e) {
            log.error("dict provider load failed, dictKey={}", dictKey, e);
            return List.of();
        }
        List<DictItemEntry> safe = items == null ? List.of() : items;
        writeCache(key, safe);
        return safe;
    }

    /** 用户昵称批量查询（翻译回填）：account→nickname；未命中不入 map */
    public Map<String, String> findUserNames(Collection<String> accounts) {
        Map<String, String> names = new HashMap<>();
        if (accounts.isEmpty()) {
            return names;
        }
        Set<String> wanted = new HashSet<>(accounts);
        for (UserEntry entry : listUsers()) {
            if (wanted.contains(entry.getAccount())) {
                names.put(entry.getAccount(), entry.getNickname());
            }
        }
        return names;
    }

    /** 全量用户消费：Redis 单键读 → 未命中 Provider 回源 → 回写 TTL */
    public List<UserEntry> listUsers() {
        List<UserEntry> cached = readCache(USER_KEY, new TypeReference<List<UserEntry>>() {
        });
        if (cached != null) {
            return cached;
        }
        UserSourceProvider provider = userSourceProvider.getIfAvailable();
        if (provider == null) {
            warnProviderMissOnce(userProviderMissWarned, "UserSourceProvider");
            return List.of();
        }
        List<UserEntry> users;
        try {
            users = provider.listAll();
        } catch (Exception e) {
            log.error("user provider load failed", e);
            return List.of();
        }
        List<UserEntry> safe = users == null ? List.of() : users;
        writeCache(USER_KEY, safe);
        return safe;
    }

    /** 字典写操作失效（DEL 失败仅 log.error 不抛——DB 已提交，TTL 兜底，设计 §4.2） */
    public void deleteDict(String dictKey) {
        deleteKey(DICT_KEY_PREFIX + dictKey);
    }

    /** 用户写操作失效 */
    public void deleteUsers() {
        deleteKey(USER_KEY);
    }

    private void deleteKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("trans cache delete failed, key={}", key, e);
        }
    }

    /** 读失败/值形态异常按未命中处理（回源降级） */
    private <T> T readCache(String key, TypeReference<T> type) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (!(raw instanceof String json)) {
                return null;
            }
            return TranslateJson.read(json, type);
        } catch (Exception e) {
            log.error("trans cache read failed, key={}", key, e);
            return null;
        }
    }

    private void writeCache(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, TranslateJson.write(value), ttl);
        } catch (Exception e) {
            log.error("trans cache write failed, key={}", key, e);
        }
    }

    /** Provider 缺省告警只发一次（避免每次翻译刷日志） */
    private void warnProviderMissOnce(AtomicBoolean flag, String name) {
        if (flag.compareAndSet(false, true)) {
            log.warn("no {} bean, translation source falls back to empty (labels will be null)", name);
        }
    }
}
