package com.cloudai.system.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 数据权限决策留痕分页筛选（契约 §3.7，筛选全可选，精确匹配）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DataPermLogPageQuery extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 决策对象账号（精确） */
    private String account;

    /** 资源标识（精确） */
    private String resource;
}
