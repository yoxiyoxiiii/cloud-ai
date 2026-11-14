package com.cloudai.system.api.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.NotifySummaryVo;
import com.cloudai.system.api.fallback.NotifyUserClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * cloud-system 未读通知摘要客户端（契约 2026-11-14-aggregate-workbench-api §7：复用下游外部用户面
 * GET /notify/summary，非 /inner 面——消费方为聚合服务 cloud-aggregate，调用时由其用户头转发拦截器
 * 携带网关注入的 X-User-* 五头；端点本身登录即可，无 perms）。
 * contextId 与既有 inner 发送面 NotifyClient 区分（两者同指 cloud-system 但路径与信任模型不同）。
 */
@FeignClient(name = "cloud-system", contextId = "notifyUserClient",
        fallbackFactory = NotifyUserClientFallbackFactory.class)
public interface NotifyUserClient {

    /** 未读摘要（unreadCount 未读数 + latestId 最新收件行水位；无入参） */
    @GetMapping("/notify/summary")
    R<NotifySummaryVo> summary();
}
