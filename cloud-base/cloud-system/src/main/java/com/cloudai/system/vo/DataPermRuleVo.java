package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据权限规则分页行（契约 §6.2）：subjectName/columns 由服务层补齐（已删主体降级 "(已删除)"）。
 */
@Data
public class DataPermRuleVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 行规则 id（Long→String 序列化） */
    private Long id;

    /** 资源标识 */
    private String resource;

    /** 0 角色 / 1 用户 */
    private Integer subjectType;

    /** 主体 id（Long→String 序列化） */
    private Long subjectId;

    /** 主体名称（角色名/用户昵称；主体已删时为 "(已删除)" 降级） */
    private String subjectName;

    /** 行范围档位 0-4 */
    private Integer rowScope;

    /** CUSTOM 档账号集合（其余档 []） */
    private List<String> customAccounts;

    /** 列规则（无则 []） */
    private List<ColumnRuleVo> columns;

    private String updateBy;

    private LocalDateTime updateTime;

    /** 列规则项：columnKey / action（0 隐藏 1 脱敏） */
    @Data
    public static class ColumnRuleVo implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 列标识（VO 字段名） */
        private String columnKey;

        /** 0 隐藏 / 1 脱敏 */
        private Integer action;
    }
}
