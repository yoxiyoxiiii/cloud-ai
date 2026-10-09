package com.cloudai.common.rocketmq.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 消息体序列化工具单测：byte[]/String 双入口解析、null/坏 JSON 防御、往返一致 */
class JsonPayloadsTest {

    private JsonPayloads payloads;

    @BeforeEach
    void setUp() {
        payloads = new JsonPayloads(new ObjectMapper());
    }

    @Test
    void parse_byteArrayInput() {
        Sample sample = payloads.parse("{\"name\":\"leave\"}".getBytes(StandardCharsets.UTF_8), Sample.class);
        assertThat(sample.getName()).isEqualTo("leave");
    }

    @Test
    void parse_stringInput() {
        Sample sample = payloads.parse("{\"name\":\"leave\"}", Sample.class);
        assertThat(sample.getName()).isEqualTo("leave");
    }

    @Test
    void parse_nullBody_throwsIllegalArgument() {
        assertThatThrownBy(() -> payloads.parse(null, Sample.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消息体为空");
    }

    @Test
    void parse_malformedJson_throwsIllegalArgument() {
        assertThatThrownBy(() -> payloads.parse("{bad", Sample.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消息体解析失败");
    }

    @Test
    void toBytes_thenParse_roundTrips() {
        Sample source = new Sample();
        source.setName("leave");

        Sample back = payloads.parse(payloads.toBytes(source), Sample.class);

        assertThat(back.getName()).isEqualTo("leave");
    }

    @Data
    static class Sample implements Serializable {
        private String name;
    }
}
