package com.cloudai.system.api.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.NotifySendResult;
import com.cloudai.system.api.domain.SendByCodeRequest;
import com.cloudai.system.api.fallback.NotifyClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * cloud-system 站内信发送客户端（契约 2026-11-10-task-center-api §11：POST /inner/notify/send-by-code，
 * 形态沿 2026-11-08-notify-api §6 预留转正）。供无登录态的系统间调用方（如任务中心失败告警）按模板
 * code 发送；inner 面共享密钥由 cloud-common-inner-starter Feign 拦截器自动携带。
 */
@FeignClient(name = "cloud-system", contextId = "notifyClient", path = "/inner/notify",
        fallbackFactory = NotifyClientFallbackFactory.class)
public interface NotifyClient {

    /** 按模板 code 发送（模板不存在 3044；出参=消息ID与实际收件人数） */
    @PostMapping("/send-by-code")
    R<NotifySendResult> sendByCode(@RequestBody SendByCodeRequest request);
}
