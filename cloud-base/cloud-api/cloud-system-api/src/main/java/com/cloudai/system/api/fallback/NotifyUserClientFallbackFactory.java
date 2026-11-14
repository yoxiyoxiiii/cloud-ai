package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.NotifyUserClient;
import com.cloudai.system.api.domain.NotifySummaryVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * NotifyUserClient 降级工厂：log.error 根因（cause）后返回中性失败 R（code=1002），
 * 聚合服务按契约 §2 转译为段状态 7002（msg 取本降级文案）。bean 经 SystemApiAutoConfiguration 注册。
 */
@Slf4j
public class NotifyUserClientFallbackFactory implements FallbackFactory<NotifyUserClient> {

    /** 中性降级码：1xxx 通用业务失败（与 NotifyClientFallbackFactory 同口径） */
    static final int DEGRADED_CODE = 1002;

    static final String DEGRADED_MSG = "cloud-system 服务不可用";

    @Override
    public NotifyUserClient create(Throwable cause) {
        return new NotifyUserClient() {

            @Override
            public R<NotifySummaryVo> summary() {
                log.error("通知摘要服务降级（summary）", cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }
        };
    }
}
