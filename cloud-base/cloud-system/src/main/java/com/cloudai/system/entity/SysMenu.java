package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String name;

    /** 权限标识，如 system:user:list */
    private String perms;

    /** M目录 C菜单 F按钮 */
    private String type;

    private Integer sort;

    /** 0正常 1停用 */
    private Integer status;

    /** 状态字典：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum Status {
        NORMAL(0), DISABLED(1);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static Status of(int code) {
            for (Status s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知状态: " + code);
        }
    }
}
