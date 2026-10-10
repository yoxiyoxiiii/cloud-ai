package com.cloudai.system.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据权限列级规则（设计 §3.3）：同 SysDataPermRule 物理删除无 deleted 列（D9）；
 * 列规则按 save 全删全插维护，无独立 update 路径。
 */
@Data
public class SysDataPermColumn implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 资源标识（同 sys_data_perm_rule.resource） */
    private String resource;

    /** 主体类型：0 角色 / 1 用户 */
    private Integer subjectType;

    /** 主体ID */
    private Long subjectId;

    /** 列标识（资源注册表 VO 字段名，如 reason） */
    private String columnKey;

    /** 列动作：0 隐藏 / 1 脱敏 */
    private Integer action;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    /** 列动作枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum ActionEnum {
        HIDDEN(0), MASKED(1);

        private final int code;

        ActionEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static ActionEnum of(int code) {
            for (ActionEnum a : values()) {
                if (a.code == code) {
                    return a;
                }
            }
            throw new IllegalArgumentException("未知列动作: " + code);
        }

        /** 动作中文名（narratives/摘要用） */
        public String label() {
            return this == HIDDEN ? "隐藏" : "脱敏";
        }
    }
}
