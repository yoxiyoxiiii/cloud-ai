package com.cloudai.sso.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.dto.LoginUserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * cloud-system 内部用户接口客户端
 */
@FeignClient(name = "cloud-system", contextId = "systemUserClient",
        path = "/inner/user", fallback = SystemUserClientFallback.class)
public interface SystemUserClient {

    @GetMapping("/{account}")
    R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account);
}
