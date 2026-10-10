package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟解释出参（契约 §6.7）：以任意账号身份复算的决策全过程（不执行业务查询、不留痕）。
 */
@Data
public class DataPermExplainVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被模拟账号 */
    private String account;

    /** 资源 */
    private String resource;

    /** 命中规则明细（无则 []） */
    private List<HitRuleVo> hitRules = new ArrayList<>();

    /** 行范围=全部（过滤豁免） */
    private boolean rowAll;

    /** 展开账号集合数（rowAll=true 时 0） */
    private int rowAccountCount;

    /** 集合是否含自己（rowAll=true 恒 true） */
    private boolean selfIncluded;

    /** 列决策（无动作 []） */
    private List<ColumnDecisionVo> columns = new ArrayList<>();

    /** 中文解释句（每命中规则一句 + 收敛结论 + 列结论） */
    private List<String> narratives = new ArrayList<>();

    /** 命中规则明细：ruleId/subjectType/subjectName/rowScope/customCount/expandedCount */
    @Data
    public static class HitRuleVo implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 规则 id（Long→String 序列化） */
        private Long ruleId;

        /** 0 角色 / 1 用户 */
        private Integer subjectType;

        /** 主体名称 */
        private String subjectName;

        /** 行范围档位 */
        private Integer rowScope;

        /** CUSTOM 档配置账号数 */
        private int customCount;

        /** 该规则展开账号数 */
        private int expandedCount;
    }

    /** 列决策项：columnKey / action（0 隐藏 1 脱敏） */
    @Data
    public static class ColumnDecisionVo implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 列标识 */
        private String columnKey;

        /** 0 隐藏 / 1 脱敏 */
        private Integer action;
    }
}
