package com.cloudai.system.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 数据权限规则分页筛选（契约 §3.1，组合全可选；resource 非法值宽松语义=返回空集不报错）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DataPermRulePageQuery extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识筛选（精确） */
    private String resource;

    /** 主体类型筛选：0 角色 / 1 用户 */
    private Integer subjectType;

    /** 主体 id 精确筛选（字符串形态，服务层转 Long；非数值宽松返回空集） */
    private String subjectId;
}
