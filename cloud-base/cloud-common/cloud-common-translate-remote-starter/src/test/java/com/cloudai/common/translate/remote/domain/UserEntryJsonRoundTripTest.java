package com.cloudai.common.translate.remote.domain;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.UserEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨服务 DTO 形态锁（设计 §7.1）：system 侧全局 Jackson 将 Long 序列化为字符串（防前端精度丢失），
 * 消费端标准 Jackson 反序列化 String → Long（默认 coercion）——锁住 /inner/user/all 的 id="1" 形态往返。
 */
class UserEntryJsonRoundTripTest {

    @Test
    void idStringFormDeserializesBackToLong() throws Exception {
        String json = "{\"code\":200,\"msg\":\"操作成功\",\"data\":"
                + "[{\"id\":\"1\",\"account\":\"admin\",\"nickname\":\"管理员\"}]}";

        R<List<UserEntry>> response = new ObjectMapper()
                .readValue(json, new TypeReference<R<List<UserEntry>>>() {});

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getId()).isEqualTo(1L);
        assertThat(response.getData().get(0).getAccount()).isEqualTo("admin");
        assertThat(response.getData().get(0).getNickname()).isEqualTo("管理员");
    }
}
