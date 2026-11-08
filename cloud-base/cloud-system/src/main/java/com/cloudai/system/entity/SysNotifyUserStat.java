package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内信未读统计表：summary 30s 轮询的读路径单行化（user_id 主键=每用户一行）。
 * 派生计数——真相源仍是收件表 sys_notify_user_message：发送/已读写路径同事务维护本表，
 * 漂移由清理任务每日对账全量重建自愈。计数表无业务生命周期，故不继承 BaseEntity。
 */
@Data
@TableName("sys_notify_user_stat")
public class SysNotifyUserStat implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID（主键=每用户一行） */
    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 未读数（发送+1、已读按影响行数递减；每日对账重建兜底） */
    private Integer unreadCount;

    /** 该用户最新收件行 id（summary 轮询水位；0=无任何收件） */
    private Long lastReceiptId;

    /** 最近维护时间 */
    private LocalDateTime updateTime;
}
