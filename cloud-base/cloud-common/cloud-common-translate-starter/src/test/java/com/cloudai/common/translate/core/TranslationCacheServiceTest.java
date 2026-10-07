package com.cloudai.common.translate.core;

import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.domain.UserEntry;
import com.cloudai.common.translate.provider.DictSourceProvider;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存服务单测（mock RedisTemplate，设计 §7.1）：未命中回源回写 TTL / 命中零回源 /
 * 回源异常不回写 / Provider 缺省降级 / DEL 转发与失败不抛 / 批量查询过滤。
 */
@SuppressWarnings("unchecked")
class TranslationCacheServiceTest {

    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
    private final ObjectProvider<DictSourceProvider> dictProvider = mock(ObjectProvider.class);
    private final ObjectProvider<UserSourceProvider> userProvider = mock(ObjectProvider.class);
    private final DictSourceProvider dictSource = mock(DictSourceProvider.class);
    private final UserSourceProvider userSource = mock(UserSourceProvider.class);

    private TranslationCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TranslationCacheService(redisTemplate, dictProvider, userProvider);
    }

    private DictItemEntry item(String value, String label, int sort) {
        DictItemEntry entry = new DictItemEntry();
        entry.setValue(value);
        entry.setLabel(label);
        entry.setSort(sort);
        return entry;
    }

    private UserEntry user(String account, String nickname) {
        UserEntry entry = new UserEntry();
        entry.setId(1L);
        entry.setAccount(account);
        entry.setNickname(nickname);
        return entry;
    }

    @Test
    void missThenLoadFromProviderAndWriteTtl() {
        when(valueOps.get("trans:dict:user_status")).thenReturn(null);
        when(dictProvider.getIfAvailable()).thenReturn(dictSource);
        when(dictSource.listByDictKey("user_status"))
                .thenReturn(List.of(item("0", "正常", 1), item("1", "停用", 2)));

        List<DictItemEntry> items = service.listDictItems("user_status");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getLabel()).isEqualTo("正常");
        String cachedJson = TranslateJson.write(items);
        verify(valueOps).set(eq("trans:dict:user_status"), eq(cachedJson), eq(Duration.ofMinutes(30)));
    }

    @Test
    void hitZeroProviderLoad() {
        String json = TranslateJson.write(List.of(item("0", "正常", 1)));
        when(valueOps.get("trans:dict:user_status")).thenReturn(json);

        List<DictItemEntry> items = service.listDictItems("user_status");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getValue()).isEqualTo("0");
        verify(dictProvider, never()).getIfAvailable();
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void providerThrows_emptyResultNoCacheWrite() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(dictProvider.getIfAvailable()).thenReturn(dictSource);
        when(dictSource.listByDictKey("user_status")).thenThrow(new RuntimeException("db down"));

        List<DictItemEntry> items = service.listDictItems("user_status");

        assertThat(items).isEmpty();
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void providerAbsent_emptyResultNoCacheWrite() {
        when(valueOps.get("trans:user")).thenReturn(null);
        when(userProvider.getIfAvailable()).thenReturn(null);

        List<UserEntry> users = service.listUsers();

        assertThat(users).isEmpty();
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void redisReadFails_fallsBackToProvider() {
        when(valueOps.get("trans:dict:user_status")).thenThrow(new RuntimeException("redis down"));
        when(dictProvider.getIfAvailable()).thenReturn(dictSource);
        when(dictSource.listByDictKey("user_status")).thenReturn(List.of(item("0", "正常", 1)));

        List<DictItemEntry> items = service.listDictItems("user_status");

        assertThat(items).hasSize(1);
    }

    @Test
    void deleteDictForwardsKey() {
        service.deleteDict("user_status");
        verify(redisTemplate).delete("trans:dict:user_status");
    }

    @Test
    void deleteUsersForwardsKey() {
        service.deleteUsers();
        verify(redisTemplate).delete("trans:user");
    }

    @Test
    void deleteFails_noThrow() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThatCode(() -> service.deleteDict("user_status")).doesNotThrowAnyException();
        assertThatCode(() -> service.deleteUsers()).doesNotThrowAnyException();
    }

    @Test
    void findDictLabels_filtersRequestedValues() {
        String json = TranslateJson.write(List.of(
                item("0", "正常", 1), item("1", "停用", 2), item("2", "未知", 3)));
        when(valueOps.get("trans:dict:user_status")).thenReturn(json);

        Map<String, String> labels = service.findDictLabels("user_status", List.of("1"));

        assertThat(labels).containsOnlyKeys("1").containsEntry("1", "停用");
    }

    @Test
    void findUserNames_mapsRequestedAccounts() {
        String json = TranslateJson.write(List.of(
                user("admin", "管理员"), user("deleted_user", "已删")));
        when(valueOps.get("trans:user")).thenReturn(json);

        Map<String, String> names = service.findUserNames(List.of("admin", "ghost"));

        assertThat(names).containsOnlyKeys("admin").containsEntry("admin", "管理员");
    }

    @Test
    void corruptedCacheValue_reloadsFromProvider() {
        when(valueOps.get("trans:dict:user_status")).thenReturn("not-a-json");
        when(dictProvider.getIfAvailable()).thenReturn(dictSource);
        when(dictSource.listByDictKey("user_status")).thenReturn(List.of(item("0", "正常", 1)));

        List<DictItemEntry> items = service.listDictItems("user_status");

        assertThat(items).hasSize(1);
    }

    @Test
    void listUsersProviderRoundTripWithIdAsString() {
        when(valueOps.get("trans:user")).thenReturn(null);
        when(userProvider.getIfAvailable()).thenReturn(userSource);
        when(userSource.listAll()).thenReturn(List.of(user("admin", "管理员")));

        List<UserEntry> users = service.listUsers();

        assertThat(users).hasSize(1);
        // 回写的值是纯 JSON 字符串且 id 为字符串形态（无 @class——TranslateJsonTest 另行断言）
        String json = (String) captureWrittenValue("trans:user");
        assertThat(json).contains("\"id\":\"1\"").doesNotContain("@class");
    }

    private Object captureWrittenValue(String key) {
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(valueOps).set(eq(key), captor.capture(), any(Duration.class));
        return captor.getValue();
    }
}
