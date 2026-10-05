package com.cloudai.sso.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.dto.LoginUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SystemUserClientFallback implements SystemUserClient {

    @Override
    public R<LoginUserDTO> getUserByAccount(String account) {
        log.error("cloud-system 用户服务不可用，account={}", account);
        return R.fail(2002, "用户服务不可用，请稍后重试");
    }
}
