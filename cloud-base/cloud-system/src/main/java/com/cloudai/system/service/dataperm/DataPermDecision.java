package com.cloudai.system.service.dataperm;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.dto.PredicateItem;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 求值产物（设计 §5.1）：留痕与 explain 共用——命中规则明细 + 行/列终态 + 中文 narrative。
 * 组件化轮（D19）不出 system：跨服务求值走窄契约 DataPermScopeVo 四字段（本类留 system 内部，
 * 未来 explain 形态演进不牵动跨服务契约）；终态类型 DataScope/ColumnScope 已搬家 api 包（D18）。
 * 谓词轮（2026-10-11 D31）：增 rowFilter 行级终态——<b>行集判定唯一权威</b>；dataScope 语义
 * 收窄记档：= 账号维并集（label 计数/digest/空集判据服务），含条件规则时不再是行集语义。
 * <b>豁免态取值记档（零 diff 红线）</b>：存在 ALL 无条件规则时 dataScope 保持 {@code DataScope.all()}
 * 既有取值不变；「非 ALL 活规则展开并集」仅非豁免态适用。
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

    /** 行级终态（账号维并集摘要语义，见类 javadoc 谓词轮记档） */
    private DataScope dataScope;

    /** 行级终态（谓词轮 D31：分组并集，行集判定唯一权威——list SQL 与 detail 判定一律读此） */
    private RowFilter rowFilter;

    /** 列级终态 */
    private ColumnScope columnScope;

    /** 中文解释句（每命中规则一句 + 每条件一句 + 收敛结论 + 列结论） */
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

        /** 条件组配置原样回显（谓词轮 D36：explain HitRuleVo 出参；无条件空列表） */
        private List<PredicateItem> predicates = new ArrayList<>();
    }
}
