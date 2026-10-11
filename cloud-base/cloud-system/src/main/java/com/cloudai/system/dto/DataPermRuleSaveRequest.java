package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 保存规则入参（契约 §3.3，upsert 全量覆盖语义）：行规则按 uk(resource,subjectType,subjectId)
 * insert 或 update，列规则按主体物理全删后批插——该主体该资源配置整体覆盖。
 */
@Data
public class DataPermRuleSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识（必填，非法 → 3034） */
    private String resource;

    /** 主体类型：0 角色 / 1 用户（必填，否则 1002） */
    private Integer subjectType;

    /** 主体 id（字符串形态；主体不存在/停用 → 3032） */
    private String subjectId;

    /** 行范围档位 0-4（必填，否则 1002） */
    private Integer rowScope;

    /** CUSTOM 档(3)账号集合：该档必填非空（1002）且逐账号存在启用（3033）；其余档必须空（1002） */
    private List<String> customAccounts;

    /**
     * 条件组（谓词轮契约 §2.3，null/[] = 无条件）：组内多条件 AND、与档位行内 AND；
     * 逐条件校验链 3035（列）→ 1002（操作符）→ 3036（矩阵）→ 3037（值）→ 1002（同列重复），
     * 规则级 3037（条件数 ≤5、序列化 ≤2000）。无条件配置行为与 v1 逐字等价（D40 红线）。
     */
    private List<PredicateItem> predicates;

    /** 列规则列表（null/[] = 清空列规则；columnKey 不重复 1002） */
    private List<ColumnRuleItem> columns;

    /** 列规则项（columnKey 须在资源可配列清单内 → 3034；action 0/1 → 1002） */
    @Data
    public static class ColumnRuleItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 列标识（VO 字段名，如 reason） */
        private String columnKey;

        /** 0 隐藏 / 1 脱敏 */
        private Integer action;
    }
}
