package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 站内信模板（契约 2026-11-08-notify-api §1）。管理域配置表：逻辑删除（deleted），
 * 消息内容在发送时解析为最终文本快照进消息表，与模板此后变更解耦。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_notify_template")
public class SysNotifyTemplate extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板编码（业务引用键，小写下划线；库级唯一） */
    private String code;

    /** 模板名称（管理列表展示） */
    private String name;

    /** 标题模板（支持 ${var} 占位符） */
    private String titleTemplate;

    /** 内容模板（支持 ${var} 占位符） */
    private String contentTemplate;

    /** 消息类型：1公告 2提醒（发送时快照到消息表） */
    private Integer type;

    /** 消息级别：0普通 1重要 2紧急（发送时快照；映射前端弹框样式与停留时长） */
    private Integer level;

    /** 变量说明（人工维护文本，仅文档用途；变量集合以模板内容正则解析为准） */
    private String variablesDesc;

    /** 内置标记：1=内置模板（禁删，内容可改）0=用户创建 */
    private Integer isBuiltin;

    /** 消息类型枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum TypeEnum {
        NOTICE(1), REMIND(2);

        private final int code;

        TypeEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static TypeEnum of(int code) {
            for (TypeEnum t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            throw new IllegalArgumentException("未知消息类型: " + code);
        }
    }

    /** 消息级别枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum LevelEnum {
        NORMAL(0), IMPORTANT(1), URGENT(2);

        private final int code;

        LevelEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static LevelEnum of(int code) {
            for (LevelEnum l : values()) {
                if (l.code == code) {
                    return l;
                }
            }
            throw new IllegalArgumentException("未知消息级别: " + code);
        }
    }

    /** 内置标记枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum BuiltinEnum {
        USER_CREATED(0), BUILT_IN(1);

        private final int code;

        BuiltinEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static BuiltinEnum of(int code) {
            for (BuiltinEnum b : values()) {
                if (b.code == code) {
                    return b;
                }
            }
            throw new IllegalArgumentException("未知内置标记: " + code);
        }
    }
}
