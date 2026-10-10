package com.cloudai.system.service.dataperm;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 求值产物（设计 §5.1）：留痕与 explain 共用——命中规则明细 + 行/列终态 + 中文 narrative。
 */
@Data
public class DataPermDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识 */
    private String resource;

    /** 决策对象账号（求值时的登录人 / explain 的被模拟账号） */
    private String account;

    /** 操作：list / detail / deny（explain 场景为 null） */
    private String operation;

    /** 命中行规则明细（无则空——默认档 SELF） */
    private List<HitRule> hitRules = new ArrayList<>();

    /** 行级终态 */
    private DataScope dataScope;

    /** 列级终态 */
    private ColumnScope columnScope;

    /** 中文解释句（每命中规则一句 + 收敛结论 + 列结论） */
    private List<String> narratives = new ArrayList<>();

    /**
     * 命中规则明细（可排查核心）：每条命中规则展开了多少账号一目了然。
     */
    @Data
    public static class HitRule implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long ruleId;

        /** 0 角色 / 1 用户 */
        private Integer subjectType;

        /** 主体ID（求值内部留痕摘要用；出参 VO 按契约 §6.7 字段集不映射） */
        private Long subjectId;

        /** 主体名称（角色名/用户账号） */
        private String subjectName;

        /** 行范围档位 0-4 */
        private Integer rowScope;

        /** CUSTOM 档配置账号数（其余档 0） */
        private int customCount;

        /** 该规则展开账号数 */
        private int expandedCount;
    }
}
