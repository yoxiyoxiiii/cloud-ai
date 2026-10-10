package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 规则配置回显入参（契约 §3.2，三项全必填：缺失 1002 / 非法 3034 / 主体无效 3032）。
 */
@Data
public class RuleConfigQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识 */
    private String resource;

    /** 主体类型：0 角色 / 1 用户 */
    private Integer subjectType;

    /** 主体 id（字符串形态，服务层转 Long） */
    private String subjectId;
}
