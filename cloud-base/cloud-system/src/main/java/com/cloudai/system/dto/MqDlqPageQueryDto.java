package com.cloudai.system.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 死信台账分页筛选（契约 2026-10-11-mq-dlq-governance-api §3.1，筛选全可选；
 * status 入参 Integer（query 参数 wire 本就是字符串形态）；时间参数 yyyy-MM-dd HH:mm:ss）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MqDlqPageQueryDto extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 处置状态过滤（"0"待处理/"1"已重投/"2"已忽略/"3"已放弃；缺省=全部） */
    private Integer status;

    /** 死信来源消费组（精确，如 g_bpmn_approval_create） */
    private String bornGroup;

    /** 消息键模糊（LIKE %x%，前后模糊） */
    private String msgKey;

    /** 死信时间起（闭区间，yyyy-MM-dd HH:mm:ss） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dlqTimeBegin;

    /** 死信时间止（闭区间，yyyy-MM-dd HH:mm:ss） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dlqTimeEnd;
}
