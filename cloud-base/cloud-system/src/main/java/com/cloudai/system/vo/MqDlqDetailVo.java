package com.cloudai.system.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 死信详情 VO（契约 §3.2：MqDlqVo 全字段 + body 原文全文 + 截断标记 + 落库/更新时间）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MqDlqDetailVo extends MqDlqVo {

    private static final long serialVersionUID = 1L;

    /** 消息体原文全文（TEXT 存量；截断行尾部有截断标记，前端 pre 展示） */
    private String body;

    /** body 是否被 60000 字节截断（设计 R4，前端提示） */
    private Boolean bodyTruncated;

    /** watch 落库时间 */
    private LocalDateTime createTime;

    /** 最近更新 */
    private LocalDateTime updateTime;
}
