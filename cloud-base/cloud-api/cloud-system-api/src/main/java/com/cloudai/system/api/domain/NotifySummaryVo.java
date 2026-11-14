package com.cloudai.system.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 未读通知摘要出参镜像（外部面出参镜像，随契约 2026-11-14-aggregate-workbench-api §3 同步）。
 * 字段与下游 com.cloudai.system.vo.NotifySummaryVo 逐字一致（2 字段全 String——
 * Long→String 防前端精度丢失口径，latestId 无任何收件="0"）；不带任何注解，
 * 聚合透传场景纯转发 JSON。字段漂移检查基准：cloud-aggregate 的守护测试字段清单断言。
 */
@Data
public class NotifySummaryVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未读数（前端 Number() 消费） */
    private String unreadCount;

    /** 该用户最新收件行 id 水位（无任何收件="0"） */
    private String latestId;
}
