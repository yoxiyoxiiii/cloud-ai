package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.NotifySendResult;
import com.cloudai.system.api.domain.SendByCodeRequest;
import com.cloudai.system.service.NotifySendService;
import com.cloudai.system.vo.NotifySendResultVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按模板编码发送通知（服务间 inner，任务中心契约 2026-11-10-task-center-api §11——
 * 2026-11-08-notify 契约 §6 预留形态转正）。
 * cloud-common-inner-starter 共享密钥准入，网关既有 /system/inner/** 屏蔽不变；
 * code 解析与发送全链在 NotifySendService（3044 透明透出）。
 */
@RestController
@RequestMapping("/inner/notify")
@RequiredArgsConstructor
public class NotifyInnerController {

    /** 无登录态审计操作人：inner 面统一标识（调用方业务语义在模板变量里，不占审计列） */
    private static final String INNER_OPERATOR = "inner-notify";

    private final NotifySendService notifySendService;

    /** 按模板编码发送：code 未命中 3044；成功返回消息 id 与收件数 */
    @PostMapping("/send-by-code")
    public R<NotifySendResult> sendByCode(@RequestBody SendByCodeRequest request) {
        NotifySendResultVo vo = notifySendService.sendByCode(request, INNER_OPERATOR);
        NotifySendResult result = new NotifySendResult();
        result.setMessageId(vo.getMessageId());
        result.setReceiverCount(vo.getReceiverCount());
        return R.ok(result);
    }
}
