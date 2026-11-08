package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 未读摘要 VO（契约 2026-11-08-notify-api §3.1：30s 轮询唯一接口出参）。
 * 两值显式 String（Long→String 防前端精度丢失口径；latestId 无任何收件="0"），
 * 前端 Number() 消费；latestId 是水位——变化才拉我的消息分页弹框。
 */
@Data
public class NotifySummaryVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未读数（未读=未处理，单状态口径） */
    private String unreadCount;

    /** 该用户最新收件行 id（前端水位；无任何收件="0"） */
    private String latestId;
}
