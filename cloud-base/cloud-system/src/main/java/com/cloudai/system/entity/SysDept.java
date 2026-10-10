package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门（数据权限轮 2026-10-10 设计 §3.1）：parent_id=0 为根的邻接表（沿 sys_menu 先例）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父部门ID，0 为根 */
    private Long parentId;

    private String name;

    /** 排序（同级内升序） */
    private Integer sort;

    /** 0正常 1停用 */
    private Integer status;

    /** 内置标记：1=系统内置根部门（禁删），0=用户创建；只读列，无 API 写入口 */
    private Integer isBuiltin;

    /** 状态字典：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum StatusEnum {
        NORMAL(0), DISABLED(1);

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
            throw new IllegalArgumentException("未知状态: " + code);
        }
    }

    /** 内置标记枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum BuiltinEnum {
        DEFAULT(0), BUILT_IN(1);

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
