package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysNotifyMessage;

/**
 * 站内信消息主表 SQL（XML：mapper/SysNotifyMessageMapper.xml）。
 * 发送流水表（无 deleted 列、不可编辑）：本轮唯一写路径为发送 insert；
 * 过期清理由 xxl-job notifyCleanJobHandler 物理删无收件行残留的孤儿行。
 */
public interface SysNotifyMessageMapper {

    /** 发送落一行（标题/内容为解析后快照，receiver_type/receiver_count 审计快照；主键回填 useGeneratedKeys） */
    int save(SysNotifyMessage message);

    /** 清理任务第二步：物理删无收件行残留的消息主表行（NOT EXISTS 命中 uk_message_user 最左前缀） */
    int deleteOrphanMessages();
}
