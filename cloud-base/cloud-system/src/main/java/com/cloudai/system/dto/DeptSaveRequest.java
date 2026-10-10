package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 部门新增/修改入参（契约 2026-10-10-data-permission-api §2.2/§2.3）。
 * 新增必填 parentId/name；修改必填 id（parentId MVP 禁改——传入与库中现值不同即 1002）。
 */
@Data
public class DeptSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 修改必填；新增忽略 */
    private Long id;

    /** 父部门 id（0=根下）；新增必填，修改禁改 */
    private Long parentId;

    /** 部门名称（DDL VARCHAR(30)，同层级唯一） */
    private String name;

    /** 排序号（null 落库默认 0；修改 null 不更新） */
    private Integer sort;

    /** 0 正常 / 1 停用（null 落库默认 0；修改 null 不更新） */
    private Integer status;
}
