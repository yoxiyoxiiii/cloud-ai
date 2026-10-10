package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String account;

    private String nickname;

    /** 部门ID（sys_dept.id；NULL=未挂部门，部门类数据权限档位求值展开为空集——数据权限轮设计 §3.5） */
    private Long deptId;

    /** BCrypt 散列；序列化与 toString 均不出现 */
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 0正常 1停用 */
    private Integer status;

    /** 内置标记：1=系统内置（禁删禁停用，昵称可改），0=用户创建；只读列，无 API 写入口（INSERT/UPDATE 列枚举不含） */
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
