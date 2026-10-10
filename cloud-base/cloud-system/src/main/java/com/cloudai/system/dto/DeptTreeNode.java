package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点（契约 2026-10-10-data-permission-api §6.1；树表出参载体，沿 MenuTreeNode 先例落 dto 包）。
 * 全量未删除部门（含停用，status tag 区分由前端处理）；叶子节点 children=[]。
 */
@Data
public class DeptTreeNode implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 父部门 id（0=根级） */
    private Long parentId;

    private String name;

    private Integer sort;

    /** 0 正常 / 1 停用 */
    private Integer status;

    /** 内置根部门标记（前端禁删提示）：is_builtin=1 → true */
    private Boolean builtin;

    private LocalDateTime createTime;

    private List<DeptTreeNode> children = new ArrayList<>();
}
