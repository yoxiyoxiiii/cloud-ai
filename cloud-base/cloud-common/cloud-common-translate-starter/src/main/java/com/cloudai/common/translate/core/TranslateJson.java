package com.cloudai.common.translate.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 翻译缓存专用静态 ObjectMapper：缓存值手写纯 JSON String（禁 @class 类型头，跨服务共享——设计 D5）；
 * Long→String 与全局 Jackson 约定一致（CommonJacksonAutoConfiguration）。
 * 失败抛 IllegalStateException，由调用方（CacheService）catch 降级。
 */
public final class TranslateJson {

    private static final ObjectMapper MAPPER = buildMapper();

    private TranslateJson() {
    }

    private static ObjectMapper buildMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return new ObjectMapper().registerModule(module);
    }

    /** 序列化为 JSON 字符串（UserEntry.id 输出字符串形态） */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("translate json write failed", e);
        }
    }

    /** 反序列化（字符串数字可回填 Long 字段） */
    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("translate json read failed", e);
        }
    }
}
