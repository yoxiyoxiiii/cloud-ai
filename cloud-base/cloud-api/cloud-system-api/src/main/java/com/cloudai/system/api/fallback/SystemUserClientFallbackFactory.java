package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.SystemUserClient;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.api.domain.UserEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * SystemUserClient 降级工厂（设计 D4 等价表）：返回码按消费方既有终态定制——
 * getUserByAccount 返回 2002（sso login/refresh 的 code!=200 分支透传后逐字等价）；
 * listAll 返回 1002（bpmn 审批人校验 users==null 分支 → BusinessException("用户服务不可用") 默认 1002）。
 * 中性降级码演进记移交备忘。
 */
@Slf4j
public class SystemUserClientFallbackFactory implements FallbackFactory<SystemUserClient> {

    @Override
    public SystemUserClient create(Throwable cause) {
        return new SystemUserClient() {

            @Override
            public R<LoginUserDTO> getUserByAccount(String account) {
                log.error("cloud-system 用户服务降级（getUserByAccount）: account={}", account, cause);
                return R.fail(2002, "用户服务不可用，请稍后重试");
            }

            @Override
            public R<List<UserEntry>> listAll() {
                log.error("cloud-system 用户服务降级（listAll）", cause);
                return R.fail(1002, "cloud-system 服务不可用");
            }
        };
    }
}
