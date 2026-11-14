package com.cloudai.aggregate.vo;

import com.cloudai.system.api.domain.NotifySummaryVo;
import lombok.Data;

import java.io.Serializable;

/**
 * 未读通知摘要段（cloud-system）：段对象统一结构 status/msg/data——status ≠ 200 时 data 恒 null。
 */
@Data
public class NotifySectionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 段状态码（SectionStatusEnum：200/7001/7002） */
    private Integer status;

    /** 段状态说明文案（判断只认 status，msg 仅展示） */
    private String msg;

    /** 段数据（未读数 + 最新收件行水位；失败段 null） */
    private NotifySummaryVo data;
}
