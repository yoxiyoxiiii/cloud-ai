package com.cloudai.common.mcp;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 工具出参序列化测试：镜像全局 Jackson 三约定——
 * Long 转 String、LocalDateTime 转 yyyy-MM-dd HH:mm:ss、字符串原样。
 */
class McpToolResultConverterTest {

    private final McpToolResultConverter converter = new McpToolResultConverter();

    /** Long（含包装型与基本型）按文本序列化（契约 §3.3：无精度问题） */
    @Test
    void longValues_serializeAsText() {
        Map<String, Object> sample = Map.of(
                "id", 2111345684249718784L,
                "count", 42L);
        String json = converter.convert(sample, Object.class);
        assertThat(json).contains("\"id\":\"2111345684249718784\"")
                .contains("\"count\":\"42\"");
    }

    /** LocalDateTime 按项目统一格式（REST 面同款），非时间戳数组 */
    @Test
    void localDateTime_serializesWithGlobalPattern() {
        Map<String, Object> sample = Map.of(
                "createTime", LocalDateTime.of(2026, 10, 5, 13, 45, 14));
        String json = converter.convert(sample, Object.class);
        assertThat(json).contains("\"createTime\":\"2026-10-05 13:45:14\"");
    }

    /** 字符串结果原样返回（对齐默认转换器的 String 直通语义） */
    @Test
    void stringResult_passesThrough() {
        assertThat(converter.convert("PERMISSION_DENIED: system:user:list", String.class))
                .isEqualTo("PERMISSION_DENIED: system:user:list");
    }

    /** 嵌套结构（rows 列表内实体）同样走约定序列化 */
    @Test
    void nestedStructures_followConvention() {
        Map<String, Object> row = Map.of(
                "id", 1L, "account", "admin",
                "createTime", LocalDateTime.of(2026, 10, 1, 10, 0, 0));
        Map<String, Object> page = Map.of("total", 1L, "rows", List.of(row));
        String json = converter.convert(page, Object.class);
        assertThat(json)
                .contains("\"total\":\"1\"")
                .contains("\"id\":\"1\"")
                .contains("\"createTime\":\"2026-10-01 10:00:00\"");
    }
}
