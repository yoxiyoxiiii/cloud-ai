package com.cloudai.system.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 死信台账行 VO（契约 2026-10-11-mq-dlq-governance-api §3.1：列表不返 body——行多体大，详情取；
 * status 契约形态为字符串（"0"-"3"，字典 mq_dlq_status，DB TINYINT 由 convert 转字符串）；
 * id 契约 Long string 形态（全局 Jackson Long→String 口径））。
 */
@Data
public class MqDlqVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 台账行 id（Jackson Long→String） */
    private Long id;

    /** 死信原消息 msgId */
    private String msgId;

    /** 事务流水号（可空） */
    private String txNo;

    /** 原 topic（重投目标） */
    private String bornTopic;

    /** 死信来源消费组 */
    private String bornGroup;

    /** watch 消费组 */
    private String watchGroup;

    /** 消息 tag（可空，TX_APPROVAL_CREATE 无 tag） */
    private String tag;

    /** 消息幂等键（KEYS 优先/退化 msgId；同键多行=重投代际链） */
    private String msgKey;

    /** body 前 120 字符预览（辅助列表辨识，超长省略号；详情取全文） */
    private String bodyPreview;

    /** 死信时重试次数（实测 4：1 次首发+3 次重试，B7 实证） */
    private Integer reconsumeTimes;

    /** 消息产生时间 */
    private LocalDateTime bornTime;

    /** 进死信时间（主排序） */
    private LocalDateTime dlqTime;

    /** 处置状态（"0"待处理/"1"已重投/"2"已忽略/"3"已放弃，字典 mq_dlq_status） */
    private String status;

    /** 重投次数（上限 5） */
    private Integer resendCount;

    /** 最近重投时间 */
    private LocalDateTime lastResendTime;

    /** 最近处置操作人 */
    private String operateAccount;

    /** 最近处置时间 */
    private LocalDateTime operateTime;
}
