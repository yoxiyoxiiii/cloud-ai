package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内信用户收件表（契约 2026-11-08-notify-api §3）：写扩散，一条消息 N 行；
 * 收件行 id 是用户侧列表行与已读操作的目标标识。流水表——无业务删除入口、无 deleted 列，
 * 过期由清理任务物理删（沿 mq_consume_dedup 先例），故不继承 BaseEntity、审计字段自带。
 */
@Data
@TableName("sys_notify_user_message")
public class SysNotifyUserMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息ID（sys_notify_message.id） */
    private Long messageId;

    /** 收件用户ID（sys_user.id） */
    private Long userId;

    /** 已读标记：0未读 1已读（未读=未处理，单状态口径） */
    private Integer readFlag;

    /** 已读时间（未读为 NULL） */
    private LocalDateTime readTime;

    /** 创建人（=发送人账号，批量展开时统一填写） */
    private String createBy;

    /** 投递时间（=发送时间） */
    private LocalDateTime createTime;

    /** 更新人（已读操作时=操作用户账号） */
    private String updateBy;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 已读标记枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum ReadFlagEnum {
        UNREAD(0), READ(1);

        private final int code;

        ReadFlagEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static ReadFlagEnum of(int code) {
            for (ReadFlagEnum f : values()) {
                if (f.code == code) {
                    return f;
                }
            }
            throw new IllegalArgumentException("未知已读标记: " + code);
        }
    }
}
