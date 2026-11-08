package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户侧我的消息行 VO（契约 2026-11-08-notify-api §3.2）：收件行 JOIN 消息主表直出
 * （派生列 send_time 无实体承载，沿 SysUserMapper.pageList VO 直出先例）。
 * id 是收件行ID（已读操作目标），messageId 是消息主表ID；Long 序列化为 String 防前端精度丢失（全局 Jackson 口径）。
 */
@Data
public class NotifyUserMessageVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 收件行ID（已读操作的目标标识） */
    private Long id;

    /** 消息主表ID */
    private Long messageId;

    /** 消息标题（发送时快照） */
    private String title;

    /** 消息内容（发送时快照） */
    private String content;

    /** 消息类型：1公告 2提醒 */
    private Integer type;

    /** 消息级别：0普通 1重要 2紧急（弹框样式映射：info 4.5s / warning 8s / error 常驻） */
    private Integer level;

    /** 跳转相对路由，null=纯展示 */
    private String linkUrl;

    /** 已读标记：0未读 1已读 */
    private Integer readFlag;

    /** 已读时间（未读为 null） */
    private LocalDateTime readTime;

    /** 发送时间（主表 create_time 快照） */
    private LocalDateTime sendTime;
}
