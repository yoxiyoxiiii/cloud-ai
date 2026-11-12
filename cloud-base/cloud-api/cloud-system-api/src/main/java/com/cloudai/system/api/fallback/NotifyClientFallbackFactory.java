package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.NotifyClient;
import com.cloudai.system.api.domain.NotifySendResult;
import com.cloudai.system.api.domain.SendByCodeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * NotifyClient 降级工厂：cloud-system 不可用时 R.fail——调用方（任务中心告警）
 * 为软失败语义，仅 log.error 吞掉不重试不阻断任务终态推进。
 */
@Slf4j
public class NotifyClientFallbackFactory implements FallbackFactory<NotifyClient> {

    @Override
    public NotifyClient create(Throwable cause) {
        return new NotifyClient() {

            @Override
            public R<NotifySendResult> sendByCode(SendByCodeRequest request) {
                log.error("cloud-system 通知服务降级（sendByCode）: templateCode={}",
                        request == null ? null : request.getTemplateCode(), cause);
                return R.fail("cloud-system 服务不可用");
            }
        };
    }
}
