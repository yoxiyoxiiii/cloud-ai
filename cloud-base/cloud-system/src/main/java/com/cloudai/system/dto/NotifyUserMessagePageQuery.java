package com.cloudai.system.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 用户侧我的消息分页查询入参（契约 2026-11-08-notify-api §3.2：type / readFlag 可选过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotifyUserMessagePageQuery extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息类型：1公告 2提醒（SysNotifyTemplate.TypeEnum） */
    private Integer type;

    /** 已读标记：0未读 1已读（SysNotifyUserMessage.ReadFlagEnum） */
    private Integer readFlag;
}
