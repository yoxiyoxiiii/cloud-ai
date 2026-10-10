package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 规则配置回显（契约 §6.3）：configured=false（无行规则）时 rowScope=null、集合空数组，前端弹窗走新增态。
 */
@Data
public class DataPermRuleConfigVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否已有行规则（false=新增态） */
    private boolean configured;

    /** 回显入参：资源标识 */
    private String resource;

    /** 回显入参：0 角色 / 1 用户 */
    private Integer subjectType;

    /** 回显入参：主体 id（Long→String 序列化） */
    private Long subjectId;

    /** 现行档位（configured=false 时 null） */
    private Integer rowScope;

    /** CUSTOM 档账号集合（其余档 []） */
    private List<String> customAccounts;

    /** 该主体该资源全部列规则（无则 []） */
    private List<DataPermRuleVo.ColumnRuleVo> columns;
}
