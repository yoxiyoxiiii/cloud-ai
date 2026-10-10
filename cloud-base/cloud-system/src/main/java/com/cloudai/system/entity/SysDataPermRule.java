package com.cloudai.system.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据权限行级规则（设计 §3.2）：不继承 BaseEntity——物理删除无 deleted 列（D9：配置态数据，
 * 墓碑占 uk 会废掉 upsert「删了再配」语义，配置历史观察职责归决策留痕表），但保留审计四列。
 */
@Data
public class SysDataPermRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 资源标识（DataPermResources 注册表管辖，如 leave） */
    private String resource;

    /** 主体类型：0 角色 / 1 用户 */
    private Integer subjectType;

    /** 主体ID（subjectType=0 时 sys_role.id，=1 时 sys_user.id） */
    private Long subjectId;

    /** 行范围档位 0-4 */
    private Integer rowScope;

    /** 自定义账号集合（rowScope=CUSTOM 时生效，JSON 数组字符串；其余档位 NULL） */
    private String customAccounts;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    /** 行范围档位枚举：字段保持 Integer 映射，Java 侧引用枚举常量（禁魔法数） */
    public enum RowScopeEnum {
        SELF(0), DEPT(1), DEPT_AND_CHILD(2), CUSTOM(3), ALL(4);

        private final int code;

        RowScopeEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static RowScopeEnum of(int code) {
            for (RowScopeEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知行范围档位: " + code);
        }

        /** 档位中文名（narratives/摘要用） */
        public String label() {
            return switch (this) {
                case SELF -> "仅自己";
                case DEPT -> "本部门";
                case DEPT_AND_CHILD -> "本部门及以下";
                case CUSTOM -> "自定义集合";
                case ALL -> "全部";
            };
        }
    }

    /** 主体类型枚举：字段保持 Integer 映射 */
    public enum SubjectTypeEnum {
        ROLE(0), USER(1);

        private final int code;

        SubjectTypeEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static SubjectTypeEnum of(int code) {
            for (SubjectTypeEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知主体类型: " + code);
        }
    }
}
