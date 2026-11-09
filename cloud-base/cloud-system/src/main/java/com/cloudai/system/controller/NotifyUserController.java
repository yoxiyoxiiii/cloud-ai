package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.NotifyUserMessagePageQuery;
import com.cloudai.system.service.NotifyUserMessageService;
import com.cloudai.system.vo.NotifySummaryVo;
import com.cloudai.system.vo.NotifyUserMessageVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧消息端点（契约 2026-11-08-notify-api §3，网关前缀 /system/notify）。
 * 登录即可（无 perms——铃铛/消息中心是全员功能），数据按网关注入的 X-User-Id 隔离：
 * 当前用户取 SecurityUtils.currentUser().getUserId()，跨用户访问一律按"不存在"处理（3048）。
 * @author jy-root
 */
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
public class NotifyUserController {

    private final NotifyUserMessageService userMessageService;

    /** 未读摘要（30s 轮询唯一接口）：unreadCount 未读数 + latestId 最新收件行水位（变化才拉分页弹框） */
    @GetMapping("/summary")
    public R<NotifySummaryVo> summary() {
        NotifySummaryVo vo = userMessageService.findSummary(SecurityUtils.currentUser().getUserId());
        return R.ok(vo);
    }

    /** 我的消息分页（收件行 id 倒序；type/readFlag 可选过滤） */
    @GetMapping("/page")
    public R<PageResult<NotifyUserMessageVo>> page(NotifyUserMessagePageQuery query) {
        PageResult<NotifyUserMessageVo> page = userMessageService.pageList(
                SecurityUtils.currentUser().getUserId(), query);
        return R.ok(page);
    }

    /** 单条已读（收件行 id；非本人/不存在同文案 3048；重复已读幂等成功） */
    @PutMapping("/read/{id}")
    public R<Void> read(@PathVariable("id") Long id) {
        userMessageService.updateRead(id, SecurityUtils.currentUser().getUserId(), SecurityUtils.currentAccount());
        return R.ok();
    }

    /** 全部已读（返回本次影响行数，前端 toast「已读 N 条」） */
    @PutMapping("/read-all")
    public R<Integer> readAll() {
        Integer affected = userMessageService.updateReadAll(
                SecurityUtils.currentUser().getUserId(), SecurityUtils.currentAccount());
        return R.ok(affected);
    }
}
