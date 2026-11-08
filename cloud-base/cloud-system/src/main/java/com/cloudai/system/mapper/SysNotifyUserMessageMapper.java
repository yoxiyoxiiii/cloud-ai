package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.dto.NotifyUserMessagePageQuery;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.vo.NotifyUserMessageVo;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信用户收件表 SQL（XML：mapper/SysNotifyUserMessageMapper.xml）。
 * 写扩散流水表（无 deleted 列、无业务删除入口）：未读统计与已读操作只碰本表，
 * 列表 JOIN 消息主表取文案快照；过期已读行由清理任务物理删。
 */
public interface SysNotifyUserMessageMapper {

    /** 发送批量落收件行（Service 已按 LinkedHashSet 去重并分批；uk 冲突由 Service 捕获吸收） */
    int saveBatch(@Param("list") List<SysNotifyUserMessage> list);

    /** 未读计数（30s 轮询最热查询，命中 idx_user_read） */
    long countUnread(@Param("userId") Long userId);

    /** 用户最新收件行 id（前端水位，无收件返回 null 由 Service 转 "0"） */
    Long findLatestId(@Param("userId") Long userId);

    /** 用户消息分页（JOIN 主表取 title/content/type/level/link_url，send_time=主表 create_time；VO 直出） */
    IPage<NotifyUserMessageVo> pageList(Page<NotifyUserMessageVo> page,
                                        @Param("userId") Long userId,
                                        @Param("query") NotifyUserMessagePageQuery query);

    /** 已读归属校验：按收件行 id + 用户定位（非本人/不存在一律 null，不泄露他人数据存在性） */
    SysNotifyUserMessage findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 单条已读：条件写（read_flag=0 才更新）幂等三防，0 行=重复已读幂等成功 */
    int updateRead(@Param("id") Long id,
                   @Param("userId") Long userId,
                   @Param("account") String account);

    /** 全部已读：当前用户全部未读行置已读，返回影响行数 */
    int updateReadAll(@Param("userId") Long userId,
                      @Param("account") String account);

    /** 清理任务第一步：物理删过期已读行（未读行永不删——删未读=丢用户消息；命中 idx_read_time） */
    int deleteReadBefore(@Param("cutoff") LocalDateTime cutoff);
}
