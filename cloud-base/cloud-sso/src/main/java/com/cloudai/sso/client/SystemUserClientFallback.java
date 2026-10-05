package com.cloudai.sso.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.dto.LoginUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 注意：fallback 仅在启用熔断器（spring.cloud.openfeign.circuitbreaker.enabled=true + circuitbreaker 依赖）时生效；未启用时由 TokenService 捕获 FeignException 兜底。
 */
@Slf4j
@Component
public class SystemUserClientFallback implements SystemUserClient {

    @Override
    public R<LoginUserDTO> getUserByAccount(String account) {
        log.error("cloud-system 用户服务不可用，account={}", account);
        return R.fail(2002, "用户服务不可用，请稍后重试");
    }
}
