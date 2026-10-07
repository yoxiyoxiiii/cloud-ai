package com.cloudai.common.translate.core;

import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.domain.UserEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 翻译 JSON 序列化形态单测（设计 §7.1）：DTO 往返、UserEntry.id 字符串化、raw 无 @class 类型头。
 */
class TranslateJsonTest {

    @Test
    void dictItemEntryRoundTrip() {
        DictItemEntry entry = new DictItemEntry();
        entry.setValue("0");
        entry.setLabel("正常");
        entry.setSort(1);

        String json = TranslateJson.write(List.of(entry));
        List<DictItemEntry> back = TranslateJson.read(json, new TypeReference<List<DictItemEntry>>() {
        });

        assertThat(back).hasSize(1);
        assertThat(back.get(0).getValue()).isEqualTo("0");
        assertThat(back.get(0).getLabel()).isEqualTo("正常");
        assertThat(back.get(0).getSort()).isEqualTo(1);
    }

    @Test
    void userEntryRoundTrip() {
        UserEntry entry = new UserEntry();
        entry.setId(1L);
        entry.setAccount("admin");
        entry.setNickname("管理员");

        String json = TranslateJson.write(List.of(entry));
        List<UserEntry> back = TranslateJson.read(json, new TypeReference<List<UserEntry>>() {
        });

        assertThat(back).hasSize(1);
        assertThat(back.get(0).getId()).isEqualTo(1L);
        assertThat(back.get(0).getAccount()).isEqualTo("admin");
        assertThat(back.get(0).getNickname()).isEqualTo("管理员");
    }

    @Test
    void userIdSerializedAsString() {
        UserEntry entry = new UserEntry();
        entry.setId(1L);
        entry.setAccount("admin");
        entry.setNickname("管理员");

        String json = TranslateJson.write(entry);

        // Long→String 与全局 Jackson 约定一致（防前端精度丢失）
        assertThat(json).contains("\"id\":\"1\"");
    }

    @Test
    void rawJsonHasNoClassTypeHeader() {
        DictItemEntry item = new DictItemEntry();
        item.setValue("0");
        item.setLabel("正常");
        item.setSort(1);
        UserEntry user = new UserEntry();
        user.setId(1L);
        user.setAccount("admin");
        user.setNickname("管理员");

        String dictJson = TranslateJson.write(List.of(item));
        String userJson = TranslateJson.write(List.of(user));

        // 跨服务共享硬约束：纯 JSON 投影，禁 GenericJackson2JsonRedisSerializer 的 @class 类型头
        assertThat(dictJson).doesNotContain("@class");
        assertThat(userJson).doesNotContain("@class");
    }
}
