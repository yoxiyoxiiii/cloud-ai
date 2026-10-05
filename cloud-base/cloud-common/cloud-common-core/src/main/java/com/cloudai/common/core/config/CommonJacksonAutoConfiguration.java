package com.cloudai.common.core.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson 统一格式：GMT+8、yyyy-MM-dd HH:mm:ss、Long 转 String（防前端 JS 精度丢失）
 */
@AutoConfiguration
public class CommonJacksonAutoConfiguration {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 运行在 Boot 属性 customizer（order 0）之后，刻意覆盖 spring.jackson.* 配置以锁定全局统一格式；
     * 个别字段如需特殊格式用 @JsonFormat 局部覆盖。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
            builder.simpleDateFormat(DATE_TIME_PATTERN);
            builder.serializers(new LocalDateTimeSerializer(formatter));
            builder.deserializers(new LocalDateTimeDeserializer(formatter));
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.timeZone(TimeZone.getTimeZone("GMT+8"));
        };
    }
}
