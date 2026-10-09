package com.cloudai.common.rocketmq.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 消息体 JSON 解析/序列化工具：复用全局 ObjectMapper 口径（core-starter Jackson 定制：GMT+8、
 * yyyy-MM-dd HH:mm:ss、Long→String）——发送与消费两端一致，事件时间字段用 String 规避时区坑（设计 D1）。
 */
@RequiredArgsConstructor
public class JsonPayloads {

    private final ObjectMapper objectMapper;

    /** 消息体 → 目标类型（body 为全局口径 JSON；解析失败抛 IllegalArgumentException → 消费重试路径） */
    public <T> T parse(Object body, Class<T> type) {
        if (body == null) {
            throw new IllegalArgumentException("消息体为空: " + type.getSimpleName());
        }
        try {
            if (body instanceof byte[] bytes) {
                return objectMapper.readValue(bytes, type);
            }
            return objectMapper.readValue(body.toString().getBytes(StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalArgumentException("消息体解析失败: " + type.getSimpleName(), e);
        }
    }

    /** 对象 → 全局口径 JSON 字节（发送端锁定序列化形态，不经 rocketmq-spring converter） */
    public byte[] toBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("消息体序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }
}
