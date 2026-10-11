package com.cloudai.system.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MQ 死信治理台账（契约 2026-10-11-mq-dlq-governance-api §3 / 设计 D5）：
 * watch 监听 %DLQ% 落库 + 人工重投/忽略/重置留痕。
 * 无 deleted 列、不继承 BaseEntity——沿 mq_tx_log/mq_consume_dedup 先例（MQ 审计表物理清理，
 * 无逻辑删除语义；处置操作人语义由 operate_account 列承载，非 create_by）。
 * 重投代际链：重投产生的新消息新 msgId，再次死信落新行（uk_msg_id 按 msgId 不按 KEYS——
 * 同 msg_key 多行即重投代际证据链，页面按 msg_key 过滤可见）。
 */
@Data
public class MqDlqLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** body 落库防御上限（字节；契约消息均为小 JSON，设计 R4）——watch 解码超限截断 */
    public static final int BODY_MAX_BYTES = 60000;

    /** 截断尾标记（详情 bodyTruncated 判定依据：endsWith——无冗余列，设计 D5 精神） */
    public static final String TRUNCATED_TAIL = "...[TRUNCATED]";

    private Long id;

    /** 死信原消息 msgId（broker 全局唯一；watch 重复投递由 uk_msg_id 吸收） */
    private String msgId;

    /** 事务消息流水号（原消息 userProperty TX_NO，可空；重投时恢复该属性） */
    private String txNo;

    /** 原 topic（重投目标，TX_APPROVAL_CREATE/APPROVAL_EVENT_NOTIFY） */
    private String bornTopic;

    /** 死信来源消费组（DLQ topic 名 %DLQ% 前缀 strip 派生；重投前清 mq_consume_dedup 残留行的分组键） */
    private String bornGroup;

    /** watch 消费组（g_system_dlq_watch_*，落库行来源定位） */
    private String watchGroup;

    /** 消息 tag（可空；重投时拼 destination=topic:tag） */
    private String tag;

    /** 消息幂等键（与 DedupRocketMQListener.resolveMsgKey 同口径：KEYS 优先、缺省退化 msgId） */
    private String msgKey;

    /** 消息体原文（UTF-8 解码；超 60000 字节截断并加截断尾标记） */
    private String body;

    /** 死信时已重试次数（maxReconsumeTimes=3，实测 4——1 次首发+3 次重试的投递计数口径，B7 实证） */
    private Integer reconsumeTimes;

    /** 消息产生时间（bornTimestamp，业务发生时间） */
    private LocalDateTime bornTime;

    /** 进死信时间（storeTimestamp，列表主排序与清理锚点） */
    private LocalDateTime dlqTime;

    /** 处置状态（字段保持 Integer 映射；Java 侧禁魔法数，SQL 字面量除外） */
    private Integer status;

    /** 重投次数（上限 cloud.system.mq-dlq.resend-max=5） */
    private Integer resendCount;

    /** 最近重投时间 */
    private LocalDateTime lastResendTime;

    /** 处置操作人（重投/忽略/重置操作账号；watch 落库不填——非处置动作） */
    private String operateAccount;

    /** 最近处置时间 */
    private LocalDateTime operateTime;

    /** 创建时间（watch 落库时间，Service 显式传值对齐） */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 处置状态枚举（设计 D6 状态机）：0待处理→1已重投（可继续，count&lt;5）/2已忽略（终态）/3已放弃（终态） */
    public enum StatusEnum {
        PENDING(0), RESENT(1), IGNORED(2), ABANDONED(3);

        private final int code;

        StatusEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static StatusEnum of(int code) {
            for (StatusEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知处置状态: " + code);
        }
    }
}
