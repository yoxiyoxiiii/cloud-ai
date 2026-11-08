package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.NotifyUserMessagePageQuery;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.cloudai.system.vo.NotifySummaryVo;
import com.cloudai.system.vo.NotifyUserMessageVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户侧消息服务（契约 2026-11-08-notify-api §3）：登录即可，数据按 userId 隔离。
 * 已读/全部已读均为单表单语句——不加事务（语句级原子已保证，条件写幂等三防）。
 */
@Service
@RequiredArgsConstructor
public class NotifyUserMessageService {

    /** 错误码分段：3xxx system，通知域 3043-3049（3048 收件记录不存在=不存在或非本人同文案） */
    private static final int ERR_RECEIPT_NOT_FOUND = 3048;

    private final SysNotifyUserMessageMapper userMessageMapper;

    /** 未读摘要：未读数 + 最新收件行 id 水位（无任何收件 latestId="0"） */
    public NotifySummaryVo findSummary(Long userId) {
        NotifySummaryVo vo = new NotifySummaryVo();
        vo.setUnreadCount(String.valueOf(userMessageMapper.countUnread(userId)));
        Long latestId = userMessageMapper.findLatestId(userId);
        vo.setLatestId(latestId == null ? "0" : String.valueOf(latestId));
        return vo;
    }

    /** 我的消息分页（收件行 id 倒序，新消息在前；type/readFlag 可选过滤；VO 由 mapper JOIN 直出） */
    public PageResult<NotifyUserMessageVo> pageList(Long userId, NotifyUserMessagePageQuery query) {
        IPage<NotifyUserMessageVo> page = userMessageMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), userId, query);
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    /** 单条已读：归属校验（非本人=不存在同文案 3048）→ 条件 UPDATE（read_flag=0 才更新，0 行幂等成功） */
    public void updateRead(Long id, Long userId, String account) {
        SysNotifyUserMessage row = userMessageMapper.findByIdAndUser(id, userId);
        if (row == null) {
            throw new BusinessException(ERR_RECEIPT_NOT_FOUND, "收件记录不存在");
        }
        userMessageMapper.updateRead(id, userId, account);
    }

    /** 全部已读：当前用户全部未读行置已读，返回影响行数（前端 toast「已读 N 条」） */
    public Integer updateReadAll(Long userId, String account) {
        return userMessageMapper.updateReadAll(userId, account);
    }
}
