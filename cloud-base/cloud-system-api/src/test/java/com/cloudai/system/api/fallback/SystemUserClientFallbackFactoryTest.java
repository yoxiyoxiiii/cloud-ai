package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.SystemUserClient;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.api.domain.UserEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 降级返回按消费方既有终态定制（设计 D4 等价表）：getUserByAccount 2002 / listAll 1002，data=null */
class SystemUserClientFallbackFactoryTest {

    private final SystemUserClientFallbackFactory factory = new SystemUserClientFallbackFactory();

    @Test
    void getUserByAccount_degradesToAuthUnavailable() {
        SystemUserClient client = factory.create(new RuntimeException("connection refused"));
        R<LoginUserDTO> resp = client.getUserByAccount("admin");
        assertThat(resp.getCode()).isEqualTo(2002);
        assertThat(resp.getMsg()).isEqualTo("用户服务不可用，请稍后重试");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void listAll_degradesToNeutralFail() {
        SystemUserClient client = factory.create(new RuntimeException("timeout"));
        R<List<UserEntry>> resp = client.listAll();
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("cloud-system 服务不可用");
        assertThat(resp.getData()).isNull();
    }
}
