package com.cloudai.system.vo;

import com.cloudai.system.dto.PredicateItem;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据权限规则分页行（契约 §6.2 + 谓词轮 §2.1 增量）：subjectName/columns 由服务层补齐
 * （已删主体降级 "(已删除)"）；predicates/predicateSummary 自实体条件组解析（无条件 []/null）。
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

    /** 条件组配置原样回显（无条件 []；组内 AND，与档位行内 AND——契约 §2.1） */
    private List<PredicateItem> predicates = new ArrayList<>();

    /** 条件中文摘要（契约 §1.6，如 请假类型等于1；无条件 null） */
    private String predicateSummary;

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
