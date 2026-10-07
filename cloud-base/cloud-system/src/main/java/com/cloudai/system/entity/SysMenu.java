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

    /** 前端路由路径，C 型有效（以/开头），空串=不进导航 */
    private String path;

    /** 图标名（@element-plus/icons-vue 组件名），空串=默认图标 */
    private String icon;

    private Integer sort;

    /** 0正常 1停用 */
    private Integer status;

    /** 内置标记：1=系统内置（禁删禁改含停用），0=用户创建；只读列，无 API 写入口（INSERT/UPDATE 列枚举不含） */
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
