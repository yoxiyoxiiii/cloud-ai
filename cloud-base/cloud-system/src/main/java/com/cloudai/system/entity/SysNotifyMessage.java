package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内信消息主表（契约 2026-11-08-notify-api §2.1 出参来源）：一次发送一行，
 * 标题/内容为模板解析后快照。发送流水表——不可编辑、无业务删除入口、无 deleted 列，
 * 过期由清理任务物理删（沿 mq_tx_log 流水表先例），故不继承 BaseEntity、审计字段自带。
 * type/level 快照自模板，Java 侧常量引用 SysNotifyTemplate 的 TypeEnum/LevelEnum（本类不重复定义）。
 */
@Data
@TableName("sys_notify_message")
public class SysNotifyMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源模板ID（sys_notify_template.id，发送时快照） */
    private Long templateId;

    /** 消息标题（模板解析后最终文本，快照） */
    private String title;

    /** 消息内容（模板解析后最终文本，快照） */
    private String content;

    /** 消息类型：1公告 2提醒（模板快照，引用 SysNotifyTemplate.TypeEnum） */
    private Integer type;

    /** 消息级别：0普通 1重要 2紧急（模板快照，引用 SysNotifyTemplate.LevelEnum） */
    private Integer level;

    /** 前端跳转相对路由（如 /bpmn/task）；NULL=纯展示无跳转 */
    private String linkUrl;

    /** 接收人维度：1按用户 2按角色 3全员（发送时快照，审计用） */
    private Integer receiverType;

    /** 实际展开收件人数（发送时快照，审计用） */
    private Integer receiverCount;

    /** 发送人（登录账号） */
    private String createBy;

    /** 发送时间 */
    private LocalDateTime createTime;

    /** 更新人（本表不可编辑，恒 NULL，列保留对齐审计基线） */
    private String updateBy;

    /** 更新时间（本表不可编辑，恒 NULL） */
    private LocalDateTime updateTime;

    /** 接收人维度枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum ReceiverTypeEnum {
        USER(1), ROLE(2), ALL(3);

        private final int code;

        ReceiverTypeEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static ReceiverTypeEnum of(int code) {
            for (ReceiverTypeEnum r : values()) {
                if (r.code == code) {
                    return r;
                }
            }
            throw new IllegalArgumentException("未知接收人维度: " + code);
        }
    }
}
