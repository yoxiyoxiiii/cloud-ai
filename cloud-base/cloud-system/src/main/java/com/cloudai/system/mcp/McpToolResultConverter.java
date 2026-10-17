package com.cloudai.system.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;
import java.time.format.DateTimeFormatter;

/**
 * MCP 工具出参序列化（镜像全局 Jackson 统一格式，REST 面同款口径）：
 * Long 转文本、LocalDateTime 转 yyyy-MM-dd HH:mm:ss——契约 §3.3 样例即此形态。
 * 框架默认转换器无时间模块（LocalDateTime 会序列化成时间戳数组），故经
 * @Tool 的 resultConverter 显式挂载本转换器。
 */
public class McpToolResultConverter implements ToolCallResultConverter {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(javaTimeModule())
            .registerModule(longTextModule());

    /** 无参构造：@Tool resultConverter 按类反射实例化 */
    public McpToolResultConverter() {
    }

    @Override
    public String convert(Object result, Type returnType) {
        if (result instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // 序列化失败兜底：toString 降级（框架默认转换器同款语义）
            return String.valueOf(result);
        }
    }

    private static JavaTimeModule javaTimeModule() {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(new LocalDateTimeSerializer(
                DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
        return module;
    }

    private static SimpleModule longTextModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return module;
    }
}
