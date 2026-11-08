package com.cloudai.system.controller.manage;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.service.NotifySendService;
import com.cloudai.system.vo.NotifySendResultVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知发送管理端点（契约 2026-11-08-notify-api §2.1，网关前缀 /system/notify/send）。
 * 同步事务发送：消息主表 1 行 + 收件 N 行同事务；消息内容为解析后快照。
 */
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
public class NotifySendManageController {

    private final NotifySendService sendService;

    /** 发送通知（1001 参数配套 / 3044 模板 / 3045 变量缺失 / 3046 超长 / 3047 收件人空） */
    @PostMapping("/send")
    @PreAuthorize("hasAuthority('system:notify:send')")
    public R<NotifySendResultVo> send(@Valid @RequestBody NotifySendRequest req) {
        NotifySendResultVo result = sendService.saveNotify(req);
        return R.ok(result);
    }
}
