package com.cloudai.common.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class CommonJacksonAutoConfigurationTest {

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new CommonJacksonAutoConfiguration().jacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Data
    static class Dto {
        private LocalDateTime time;
        private Long id;
        private long total;
    }

    @Test
    void serialize_dateFormatAndLongAsString() throws Exception {
        Dto dto = new Dto();
        dto.setTime(LocalDateTime.of(2026, 10, 4, 12, 0, 0));
        dto.setId(123456789012345L);
        String json = mapper().writeValueAsString(dto);
        assertThat(json).contains("\"2026-10-04 12:00:00\"");
        assertThat(json).contains("\"123456789012345\"");
    }

    @Test
    void deserialize_dateFormat() throws Exception {
        Dto dto = mapper().readValue("{\"time\":\"2026-10-04 12:00:00\"}", Dto.class);
        assertThat(dto.getTime()).isEqualTo(LocalDateTime.of(2026, 10, 4, 12, 0, 0));
    }

    @Test
    void serialize_primitiveLongAsString() throws Exception {
        Dto dto = new Dto();
        dto.setTotal(123456789012345L);
        assertThat(mapper().writeValueAsString(dto)).contains("\"total\":\"123456789012345\"");
    }

    @Test
    void serialize_dateUsesGmt8() throws Exception {
        String json = mapper().writeValueAsString(Date.from(Instant.parse("2026-10-04T04:00:00Z")));
        assertThat(json).contains("2026-10-04 12:00:00");
    }
}
