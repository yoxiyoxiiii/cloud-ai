package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 发送通知出参 VO（契约 2026-11-08-notify-api §2.1）：消息主表ID + 实际展开收件人数。
 * messageId 为 Long，序列化为 String 防前端精度丢失（全局 Jackson 口径）。
 */
@Data
public class NotifySendResultVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息主表ID */
    private Long messageId;

    /** 实际展开收件人数（剔除无效用户后） */
    private Integer receiverCount;
}
