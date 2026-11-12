package com.cloudai.system.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 通知发送出参（契约 2026-11-10-task-center-api §11：复用 notify 契约 §2.1 出参形态）。
 * messageId 为 Long，线上 JSON 按全局 Jackson 口径序列化为 string。
 */
@Data
public class NotifySendResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息主表 ID */
    private Long messageId;

    /** 实际展开收件人数 */
    private Integer receiverCount;
}
