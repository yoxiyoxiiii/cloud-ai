package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.NotifyUserMessagePageQuery;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.entity.SysNotifyUserStat;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.cloudai.system.mapper.SysNotifyUserStatMapper;
import com.cloudai.system.vo.NotifySummaryVo;
import com.cloudai.system.vo.NotifyUserMessageVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户侧消息服务（契约 2026-11-08-notify-api §3）：登录即可，数据按 userId 隔离。
 * summary 读未读统计表单行 PK（30s 轮询最热路径，不走收件表 COUNT/MAX）；
 * 已读/全部已读是两语句写（收件表条件写 + 统计表递减），故加 @Transactional 保证原子。
 */
@Service
@RequiredArgsConstructor
public class NotifyUserMessageService {

    /** 错误码分段：3xxx system，通知域 3043-3049（3048 收件记录不存在=不存在或非本人同文案） */
    private static final int ERR_RECEIPT_NOT_FOUND = 3048;

    private final SysNotifyUserMessageMapper userMessageMapper;
    private final SysNotifyUserStatMapper statMapper;

    /** 未读摘要：统计表单行 PK 查询（30s 轮询最热路径，不走 COUNT）——无行=该用户无收件，兜底 0/0 */
    public NotifySummaryVo findSummary(Long userId) {
        SysNotifyUserStat stat = statMapper.findByUser(userId);
        NotifySummaryVo vo = new NotifySummaryVo();
        vo.setUnreadCount(stat == null ? "0" : String.valueOf(stat.getUnreadCount()));
        vo.setLatestId(stat == null ? "0" : String.valueOf(stat.getLastReceiptId()));
        return vo;
    }

    /** 我的消息分页（收件行 id 倒序，新消息在前；type/readFlag 可选过滤；VO 由 mapper JOIN 直出） */
    public PageResult<NotifyUserMessageVo> pageList(Long userId, NotifyUserMessagePageQuery query) {
        IPage<NotifyUserMessageVo> page = userMessageMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), userId, query);
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    /** 单条已读：归属校验（非本人=不存在同文案 3048）→ 条件 UPDATE（read_flag=0 才更新，0 行幂等成功）
     *  → 按实际影响行数递减统计（0 行不递减；统计行缺失留每日对账收敛） */
    @Transactional(rollbackFor = Exception.class)
    public void updateRead(Long id, Long userId, String account) {
        SysNotifyUserMessage row = userMessageMapper.findByIdAndUser(id, userId);
        if (row == null) {
            throw new BusinessException(ERR_RECEIPT_NOT_FOUND, "收件记录不存在");
        }
        int affected = userMessageMapper.updateRead(id, userId, account);
        if (affected > 0) {
            statMapper.updateDecrease(userId, affected);
        }
    }

    /** 全部已读：当前用户全部未读行置已读，返回影响行数（前端 toast「已读 N 条」）；
     *  统计同批递减（与置已读同事务） */
    @Transactional(rollbackFor = Exception.class)
    public Integer updateReadAll(Long userId, String account) {
        int affected = userMessageMapper.updateReadAll(userId, account);
        if (affected > 0) {
            statMapper.updateDecrease(userId, affected);
        }
        return affected;
    }

    /** 统计对账：按收件表真相源全量重建统计表（聚合读 → 清空 → 重灌，编排在本方法，
     *  SQL 只做简单读写；xxl-job 每日调度兜底写路径漂移与发送防御层跳过批）。
     *  清空+重灌同事务原子，失败整体回滚；返回重建的用户统计行数 */
    @Transactional(rollbackFor = Exception.class)
    public int resetUnreadStat() {
        List<SysNotifyUserStat> aggs = userMessageMapper.listUnreadAgg();
        statMapper.deleteAll();
        if (!aggs.isEmpty()) {
            statMapper.saveBatch(aggs);
        }
        return aggs.size();
    }
}
